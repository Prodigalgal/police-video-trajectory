import hashlib
import json
from uuid import uuid4
import httpx
from fastapi.testclient import TestClient
from services.web_gateway.app import create_app
from services.web_gateway.password_auth import password_hash
from test_simple_accounts import Directory,login,ORIGIN,PASSWORD


def settings(enabled=True):
    return dict(auth_mode='password',username='admin',password_hash=password_hash(PASSWORD),pow_bits=16,session_secret='test-session-secret',
        core_token='test-core-secret',admin_token='test-admin-secret',account_directory=True,clue_collections=enabled,
        core_url='https://core.invalid',core_ca='unused',origin=ORIGIN,static_dir='missing')


def test_ordinary_user_can_write_collections_but_browser_cannot_spoof_actor():
    config=settings();directory=Directory(config);calls=[]
    def upstream(request):
        if not request.url.path.startswith('/internal/clues/'):return directory.handle(request)
        calls.append(request);return httpx.Response(200,json={'collection':{'revision':2},'nodes':[]})
    with TestClient(create_app(config,httpx.MockTransport(upstream)),base_url=ORIGIN) as client:
        assert client.get('/api/v1/collections').status_code==401
        assert login(client,'reader').status_code==200
        assert client.get('/api/v1/features').json()=={'clue_collections':True,'analysis_inputs':False}
        body={'request_id':str(uuid4()),'title':'实际点位复核','note':'独立业务意见'}
        assert client.post('/api/v1/collections',json=body).status_code==403
        response=client.post('/api/v1/collections',headers={'Origin':ORIGIN,'X-Actor-Id':'admin','X-Actor-Revision':'999'},json=body)
        assert response.status_code==200 and 'test-admin-secret' not in response.text
        request=calls[-1];assert request.headers['x-actor-id']=='reader' and request.headers['x-actor-revision']=='1'
        assert json.loads(request.content)['requestId']==body['request_id']


def test_feature_off_never_forwards_to_new_backend_or_requires_a_new_deployment():
    config=settings(False);directory=Directory(config)
    with TestClient(create_app(config,httpx.MockTransport(directory.handle)),base_url=ORIGIN) as client:
        login(client,'reader')
        assert client.get('/api/v1/features').json()=={'clue_collections':False,'analysis_inputs':False}
        assert client.get('/api/v1/collections').status_code==404
        assert not any('/internal/clues' in call[1] for call in directory.calls)


def test_review_requires_current_evidence_and_bounded_state_before_forwarding():
    config=settings();directory=Directory(config);identity=str(uuid4());observation=str(uuid4())
    with TestClient(create_app(config,httpx.MockTransport(directory.handle)),base_url=ORIGIN) as client:
        login(client,'reader')
        response=client.put(f'/api/v1/collections/{identity}/items/{observation}/review',headers={'Origin':ORIGIN},json={
            'expected_revision':1,'observation_version':1,'state':'retained','note':'核验记录'})
        assert response.status_code==422
        assert not any('/internal/clues' in call[1] for call in directory.calls)
        assert client.get('/api/v1/collections?limit=1000').status_code==422


def test_export_checksum_limits_and_server_filename_are_enforced():
    config=settings();directory=Directory(config);identity=str(uuid4());payload='观测编号,复核结果\r\n'.encode();bad=False
    def upstream(request):
        if not request.url.path.endswith('/export'):return directory.handle(request)
        return httpx.Response(200,content=payload,headers={'X-Content-SHA256':'bad' if bad else hashlib.sha256(payload).hexdigest(),
            'Content-Disposition':'attachment; filename="untrusted.exe"'})
    with TestClient(create_app(config,httpx.MockTransport(upstream)),base_url=ORIGIN) as client:
        login(client,'reader')
        response=client.get(f'/api/v1/collections/{identity}/export?revision=3&format=csv')
        assert response.status_code==200 and response.content==payload and 'untrusted' not in response.headers['content-disposition']
        assert f'clues-{identity}-r3.csv' in response.headers['content-disposition']
        bad=True
        assert client.get(f'/api/v1/collections/{identity}/export?revision=3&format=csv').status_code==503


def test_public_errors_are_sanitized_and_preserve_edit_conflicts():
    config=settings();directory=Directory(config)
    def upstream(request):
        if not request.url.path.startswith('/internal/clues/'):return directory.handle(request)
        return httpx.Response(409,json={'code':'collection_revision_conflict','password_hash':'never-copy','source_ref':'never-copy'})
    with TestClient(create_app(config,httpx.MockTransport(upstream)),base_url=ORIGIN) as client:
        login(client,'reader')
        response=client.put('/api/v1/collections/'+str(uuid4()),headers={'Origin':ORIGIN},json={'title':'清单','state':'open','expected_revision':1})
        assert response.status_code==409 and response.json()=={'code':'collection_revision_conflict'}
