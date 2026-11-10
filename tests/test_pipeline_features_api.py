import json
from uuid import uuid4
import httpx
from fastapi.testclient import TestClient
from services.web_gateway.app import create_app
from test_clue_api import settings
from test_simple_accounts import Directory,login,ORIGIN


def test_optional_feature_configuration_keeps_admin_identity_and_origin_boundary():
    cfg=settings();directory=Directory(cfg);calls=[]
    def core(request):
        if not request.url.path.startswith('/internal/pipeline/features'):return directory.handle(request)
        calls.append(request)
        assert request.headers['x-actor-id']=='admin' and request.headers['x-actor-revision']=='1'
        return httpx.Response(200,json={'schema_version':'pipeline-features-v1','features':[]})
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        prefix='/api/admin/pipeline-features'
        assert client.get(prefix).status_code==401
        login(client,'reader');assert client.get(prefix).status_code==403
        client.delete('/api/session',headers={'Origin':ORIGIN});login(client,'admin')
        assert client.get(prefix,headers={'X-Actor-Id':'reader'}).status_code==200
        command={'request_id':str(uuid4()),'expected_version':1,'enabled':False,'plugin':'context_window_v1','parameters':{'seconds':5}}
        url=prefix+'/frame_recording'
        assert client.put(url,json=command,headers={'Origin':'https://untrusted.invalid'}).status_code==403
        assert client.put(url,json=command,headers={'Origin':ORIGIN}).status_code==200
        forwarded=json.loads(calls[-1].content)
        assert forwarded['enabled'] is False and forwarded['parameters']=={'seconds':5} and forwarded['expectedVersion']==1
        for parameters in ({'seconds':True},{'seconds':5.0},{'seconds':60},{'seconds':5,'extra':1}):
            assert client.put(url,json={**command,'parameters':parameters},headers={'Origin':ORIGIN}).status_code==422
        assert client.put(url,json={**command,'enabled':'true'},headers={'Origin':ORIGIN}).status_code==422
        assert len(calls)==2
