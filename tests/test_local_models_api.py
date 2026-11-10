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
