"""Nonvisual HTTP contracts; no recognition requests or business media."""
from uuid import uuid4
import httpx
from fastapi.testclient import TestClient
from services.web_gateway.app import create_app
from test_clue_api import settings
from test_simple_accounts import Directory,login,ORIGIN


def test_plate_readiness_requires_login_enabled_modules_and_current_account_scope():
    cfg=settings();directory=Directory(cfg);calls=[]
    def core(request):
        if not request.url.path.endswith('/plate-search'):return directory.handle(request)
        calls.append(request)
        assert request.method=='GET' and request.headers['x-actor-id']=='reader'
        assert request.headers['x-actor-revision']=='1' and request.url.params['observationVersion']=='2'
        return httpx.Response(200,json={'schema_version':'reviewed-plate-search-status-v1','state':'pending','query':None})
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        url='/api/v1/observations/'+str(uuid4())+'/plate-search-status?observation_version=2'
        assert client.get(url).status_code==401
        login(client,'reader');assert client.get(url).status_code==404
        cfg.update(specialist_jobs=True,analysis_inputs=True)
        result=client.get(url,headers={'X-Actor-Id':'admin','X-Actor-Revision':'9'})
        assert result.status_code==200 and result.json()['state']=='pending'
        assert result.headers['cache-control']=='no-store' and len(calls)==1
        for revision in ('0','9007199254740992','1.5'):
            assert client.get(url.replace('=2','='+revision)).status_code==422
        directory.accounts['reader']['enabled']=False
        assert client.get(url).status_code==401 and len(calls)==1


def test_plate_readiness_errors_hide_private_details_and_preserve_stale_binding_code():
    cfg=settings();cfg.update(specialist_jobs=True,analysis_inputs=True);directory=Directory(cfg);unknown=False
    def core(request):
        if not request.url.path.endswith('/plate-search'):return directory.handle(request)
        return httpx.Response(409,json={'code':'private_provider_path' if unknown else 'plate_search_changed','private':'must never leak'})
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        login(client,'reader');url='/api/v1/observations/'+str(uuid4())+'/plate-search-status?observation_version=1'
        response=client.get(url)
        assert response.status_code==409 and response.json()=={'code':'plate_search_changed'}
        unknown=True;assert client.get(url).json()=={'code':'specialist_unavailable'}
