import hashlib
from uuid import uuid4
import httpx
from fastapi.testclient import TestClient
from services.web_gateway.app import create_app
from test_clue_api import settings
from test_simple_accounts import Directory,login,ORIGIN


def test_trace_uses_current_account_revision_and_keeps_frame_sources_admin_only():
    cfg=settings();directory=Directory(cfg);calls=[]
    def core(request):
        if '/internal/processing-traces/' not in request.url.path:return directory.handle(request)
        calls.append(request)
        assert request.headers['x-actor-id'] in {'reader','admin'}
        assert request.headers['x-actor-revision']=='1'
        return httpx.Response(409,json={'code':'trace_source_changed','private':'must not leak'})
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        url='/api/v1/observations/'+str(uuid4())+'/trace?observation_version=1'
        assert client.get(url).status_code==401
        login(client,'reader');assert client.get(url).status_code==404
        cfg['processing_trace']=True
        result=client.get(url,headers={'X-Actor-Id':'admin'})
        assert result.status_code==409 and result.json()=={'code':'trace_source_changed'} and result.headers['cache-control']=='no-store'
        assert calls[0].headers['x-actor-id']=='reader' and calls[0].url.params['observationVersion']=='1'
        assert 'verifyAssets' not in calls[0].url.params
        assert client.get(url.replace('=1','=0')).status_code==422
        assert client.get('/api/admin/processing-traces/frames/'+str(uuid4())).status_code==403
        assert len(calls)==1


def test_replay_source_download_is_bounded_and_pinned_to_requested_image_hash():
    cfg=settings();cfg['processing_trace']=True;directory=Directory(cfg);raw=b'nonvisual transport contract'
    digest=hashlib.sha256(raw).hexdigest();bad=False
    def core(request):
        if '/internal/processing-traces/' not in request.url.path:return directory.handle(request)
        assert request.headers['x-actor-id']=='admin' and request.url.params['sha']==digest
        return httpx.Response(200,content=raw,headers={'Content-Type':'image/jpeg','X-Content-SHA256':'0'*64 if bad else digest})
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        login(client,'admin');url='/api/admin/processing-traces/frames/'+str(uuid4())+'/image'
        assert client.get(url+'?sha=broken').status_code==422
        assert client.get(url+'?sha='+digest).content==raw
        bad=True;assert client.get(url+'?sha='+digest).status_code==503


def test_explicit_resource_verification_keeps_observation_and_admin_authorization():
    cfg=settings();cfg['processing_trace']=True;directory=Directory(cfg);calls=[]
    def core(request):
        if '/internal/processing-traces/' not in request.url.path:return directory.handle(request)
        calls.append(request)
        assert request.url.params['verifyAssets']=='true'
        return httpx.Response(200,json={'schema_version':'processing-trace-v1'})
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        observation='/api/v1/observations/'+str(uuid4())+'/trace?observation_version=2&verify_assets=true'
        frame='/api/admin/processing-traces/frames/'+str(uuid4())+'?verify_assets=true'
        assert client.get(observation).status_code==401
        login(client,'reader');assert client.get(observation).status_code==200
        assert calls[-1].headers['x-actor-id']=='reader' and calls[-1].url.params['observationVersion']=='2'
        assert client.get(frame).status_code==403 and len(calls)==1
        login(client,'admin');assert client.get(frame).status_code==200
        assert calls[-1].headers['x-actor-id']=='admin'
        assert client.get(frame.replace('true','unexpected')).status_code==422 and len(calls)==2
