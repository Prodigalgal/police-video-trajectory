"""Nonvisual actor, range and transport contracts; no model accuracy fixtures."""
import json
from uuid import uuid4
import httpx
from fastapi.testclient import TestClient
from services.web_gateway.app import create_app
from test_clue_api import settings
from test_simple_accounts import Directory,login,ORIGIN


def setup():
    cfg=settings();cfg['investigation']=True
    directory=Directory(cfg);calls=[]
    def core(request):
        if not request.url.path.startswith('/internal/investigation/mtmc'):
            return directory.handle(request)
        calls.append(request)
        return httpx.Response(200,json={'status':'ok','items':[]})
    return cfg,calls,core


def command():
    return {'request_id':str(uuid4()),'observation_id':str(uuid4()),'expected_version':1,
        'strategy':'evidence-fusion-v1','profiles':['visual'],'camera_ids':['recording-02'],'seconds':600,'limit':20}


def test_mtmc_requires_login_origin_and_server_actor_with_360_second_budget():
    cfg,calls,core=setup();body=command()
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        assert client.get('/api/v1/investigation/mtmc/capabilities').status_code==401
        login(client,'reader')
        assert client.post('/api/v1/investigation/mtmc/queries',json=body).status_code==403
        assert client.post('/api/v1/investigation/mtmc/queries',json=body,headers={'Origin':ORIGIN,'X-Actor-Id':'admin','X-Actor-Revision':'999'}).status_code==200
        request=calls[-1]
        assert request.headers['x-actor-id']=='reader' and request.headers['x-actor-revision']=='1'
        assert request.extensions['timeout']['read']==360
        forwarded=json.loads(request.content)
        assert forwarded['requestId']==body['request_id'] and forwarded['observationId']==body['observation_id']
        assert forwarded['expectedVersion']==1 and forwarded['cameraIds']==['recording-02']
        assert set(forwarded)=={'requestId','observationId','expectedVersion','strategy','profiles','cameraIds','seconds','limit'}
        assert client.post('/api/v1/investigation/mtmc/queries',json={**command(),'strategy':'camera-aware-v2'},headers={'Origin':ORIGIN}).status_code==200
        assert json.loads(calls[-1].content)['strategy']=='camera-aware-v2'


def test_mtmc_rejects_invalid_scope_and_unsupported_payload_without_reaching_core():
    cfg,calls,core=setup()
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        login(client,'reader')
        for patch in ({'profiles':['visual']*2},{'profiles':['a','b','c','d']},{'profiles':['../file']},{'camera_ids':[f'camera-{i}' for i in range(51)]},
            {'camera_ids':['recording-02']*2},{'camera_ids':['https://camera.invalid']},{'seconds':3601},
            {'seconds':True},{'expected_version':0},{'strategy':'../arbitrary-code'},{'endpoint':'https://upstream.invalid'}):
            assert client.post('/api/v1/investigation/mtmc/queries',json={**command(),**patch},headers={'Origin':ORIGIN}).status_code==422
        assert not calls


def test_mtmc_frozen_run_pagination_and_delete_keep_auth_and_origin_checks():
    cfg,calls,core=setup();identity=str(uuid4())
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        login(client,'reader')
        path='/api/v1/investigation/mtmc/queries/'+identity
        assert client.get(path,params={'offset':20,'limit':20,'run':'visual:appearance-v1'}).status_code==200
        assert calls[-1].url.params['run']=='visual:appearance-v1' and calls[-1].url.params['offset']=='20'
        for params in ({'offset':1001},{'run':'../../arbitrary'},{'run':'visual:../unsupported'}):
            assert client.get(path,params=params).status_code==422
        assert client.delete(path).status_code==403
        assert client.delete(path,headers={'Origin':ORIGIN}).status_code==200
        assert calls[-1].method=='DELETE' and calls[-1].headers['x-actor-id']=='reader'
