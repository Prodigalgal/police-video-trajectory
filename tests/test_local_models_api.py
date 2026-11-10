import hashlib
import json
from uuid import uuid4
import httpx
from fastapi.testclient import TestClient
from services.web_gateway.app import create_app
from test_clue_api import settings
from test_simple_accounts import Directory,login,ORIGIN


def test_local_bindings_are_admin_only_and_forward_no_browser_identity():
    cfg=settings();directory=Directory(cfg);calls=[]
    def core(request):
        if '/internal/models/local-catalogs' not in request.url.path:return directory.handle(request)
        calls.append(request);assert request.headers['x-actor-id']=='admin' and request.headers['x-actor-revision']=='1'
        return httpx.Response(200,json={'schema_version':'local-model-bindings-v1','catalogs':[]})
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        url='/api/admin/models/local-catalogs';assert client.get(url).status_code==401
        login(client,'reader');assert client.get(url).status_code==403
        client.delete('/api/session',headers={'Origin':ORIGIN});login(client,'admin')
        assert client.get(url,headers={'X-Actor-Id':'reader'}).status_code==200
        command={'request_id':str(uuid4()),'expected_revision':0,'base_sha256':'a'*64,'bindings':{'license_plate':None,'text_ocr':None}}
        assert client.put(url+'/processing',json=command,headers={'Origin':'https://untrusted.invalid'}).status_code==403
        assert client.put(url+'/processing',json=command,headers={'Origin':ORIGIN}).status_code==200
        assert json.loads(calls[-1].content)['bindings']=={'license_plate':None,'text_ocr':None}
        for changes in ({'expected_revision':True},{'bindings':{'license_plate':'../../model','text_ocr':None}},{'bindings':{'appearance':None}},{'endpoint':'https://untrusted.invalid'}):
            assert client.put(url+'/processing',json={**command,**changes},headers={'Origin':ORIGIN}).status_code==422
        assert len(calls)==2


def test_appearance_binding_requires_matching_local_asset_and_does_not_download_it(tmp_path):
    # Byte/metadata integrity only; this fixture is not an executable ONNX model.
    from services.appearance.catalog import Definition
    payload=b'nonvisual asset integrity contract'
    definition=Definition(profile='visual-contract',model_file='visual-contract.onnx',model_sha256=hashlib.sha256(payload).hexdigest(),dimensions=384,normalization='imagenet',output='cls_token')
    catalog=tmp_path/'catalog.json';catalog.write_text(json.dumps({'schema_version':'appearance-catalog-v1','revision':1,'active':definition.profile,'profiles':[definition.model_dump()]}))
    cfg=settings();cfg.update(appearance_catalog_file=str(catalog),appearance_model_dir=str(tmp_path));directory=Directory(cfg);updates=[]
    metadata={'id':'appearance','available':True,'source_current':True,'revision':0,'base_sha256':'a'*64,'bindings':{'appearance':definition.profile},'profiles':[{'profile':definition.profile,'sha256':definition.sha256,'dimensions':384}]}
    def core(request):
        if '/internal/models/local-catalogs' not in request.url.path:return directory.handle(request)
        if request.method=='PUT':updates.append(request);return httpx.Response(200,json={'saved':True})
        return httpx.Response(200,json={'schema_version':'local-model-bindings-v1','catalogs':[{'id':'processing','available':False},metadata]})
    command={'request_id':str(uuid4()),'expected_revision':0,'base_sha256':'a'*64,'bindings':{'appearance':definition.profile}}
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        login(client,'admin');url='/api/admin/models/local-catalogs'
        assert client.get(url).json()['catalogs'][1]['profiles'][0]['installed'] is False
        assert client.put(url+'/appearance',json=command,headers={'Origin':ORIGIN}).status_code==422
        assert not updates and not (tmp_path/definition.model_file).exists()
        (tmp_path/definition.model_file).write_bytes(payload)
        assert client.get(url).json()['catalogs'][1]['profiles'][0]['installed'] is True
        metadata['profiles'][0]['sha256']='b'*64
        assert client.put(url+'/appearance',json=command,headers={'Origin':ORIGIN}).status_code==422
        assert not updates
        metadata['profiles'][0]['sha256']=definition.sha256
        assert client.put(url+'/appearance',json=command,headers={'Origin':ORIGIN}).status_code==200
        (tmp_path/definition.model_file).write_bytes(payload+b'damaged')
        assert client.put(url+'/appearance',json={**command,'request_id':str(uuid4())},headers={'Origin':ORIGIN}).status_code==422
        assert len(updates)==1
