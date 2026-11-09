import json
import uuid

import httpx
from fastapi.testclient import TestClient

from services.web_gateway.app import create_app


ORIGIN = "https://localhost:8445"
SETTINGS = {"access_token": "browser-token", "session_secret": "session-secret", "core_token": "core-secret",
            "core_url": "https://core.invalid", "core_ca": "unused", "origin": ORIGIN, "static_dir": "missing"}


def test_changed_plate_snapshot_preserves_conflict_without_forwarding_private_fields():
    def core(request):
        assert request.url.path == '/api/v1/search'
        return httpx.Response(409, json={'code': 'plate_search_changed', 'private': 'do-not-forward'})
    with TestClient(create_app(SETTINGS, httpx.MockTransport(core)), base_url=ORIGIN) as client:
        client.post('/api/session', headers={'Origin': ORIGIN}, json={'token': 'browser-token'})
        response = client.post('/api/v1/search', headers={'Origin': ORIGIN}, json={'query': '完整车牌', 'cursor': 'opaque-signed-cursor'})
        assert response.status_code == 409 and response.json()['code'] == 'plate_search_changed'
        assert 'do-not-forward' not in response.text


def test_pagination_preserves_signed_cursor_errors_and_authenticated_cleanup():
    calls=[]
    def core(req):
        calls.append(req)
        assert req.headers['authorization']=='Bearer core-secret'
        body=json.loads(req.content)
        if req.url.path.endswith('/close'):
            assert body=={'query':'白色 车','cursor':'opaque-signed-cursor'}
            return httpx.Response(200,json={'closed':True})
        assert body=={'query':'白色 车','pageSize':20,'cursor':'opaque-signed-cursor'}
        return httpx.Response(410,json={'code':'search_cursor_expired','private':'do-not-forward'})
    with TestClient(create_app(SETTINGS,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        payload={'query':'白色 车','cursor':'opaque-signed-cursor'}
        assert client.post('/api/v1/search/close',headers={'Origin':ORIGIN},json=payload).status_code==401
        client.post('/api/session',headers={'Origin':ORIGIN},json={'token':'browser-token'})
        response=client.post('/api/v1/search',headers={'Origin':ORIGIN},json=payload)
        assert response.status_code==410 and response.json()['code']=='search_cursor_expired' and 'do-not-forward' not in response.text
        assert client.post('/api/v1/search/close',headers={'Origin':'https://other.invalid'},json=payload).status_code==403
        assert client.post('/api/v1/search/close',headers={'Origin':ORIGIN},json=payload).json()=={'closed':True}
        before=len(calls)
        for invalid in [0,51,True,'20']:
            assert client.post('/api/v1/search',headers={'Origin':ORIGIN},json={**payload,'page_size':invalid}).status_code==422
        assert len(calls)==before


def test_session_csrf_and_search_proxy_keep_secrets_server_side():
    calls = []
    def core(req):
        calls.append(req)
        assert req.headers["authorization"] == "Bearer core-secret"
        assert json.loads(req.content) == {"query": "黑衣背包的人", "pageSize": 20, "cursor": None}
        return httpx.Response(200, json={"status": "ok", "results": [], "coverage": {"status": "unknown"}})
    with TestClient(create_app(SETTINGS, httpx.MockTransport(core)), base_url=ORIGIN) as client:
        assert client.get("/api/session").json() == {"authenticated": False}
        assert client.post("/api/v1/search", headers={"Origin": ORIGIN}, json={"query": "黑衣背包的人"}).status_code == 401
        assert client.post("/api/session", headers={"Origin": "https://other.invalid"}, json={"token": "browser-token"}).status_code == 403
        invalid = client.post("/api/session", headers={"Origin": ORIGIN}, json={"token": "wrong"})
        assert invalid.status_code == 401 and "browser-token" not in invalid.text
        login = client.post("/api/session", headers={"Origin": ORIGIN}, json={"token": "browser-token"})
        assert login.status_code == 200
        assert "HttpOnly" in login.headers["set-cookie"] and "Secure" in login.headers["set-cookie"] and "SameSite=strict" in login.headers["set-cookie"]
        assert "core-secret" not in login.text and "browser-token" not in login.text
        assert client.get("/api/session").json() == {"authenticated": True}
        result = client.post("/api/v1/search", headers={"Origin": ORIGIN}, json={"query": "黑衣背包的人"})
        assert result.status_code == 200 and result.json()["coverage"]["status"] == "unknown" and len(calls) == 1
        assert client.post("/api/v1/search", headers={"Origin": "https://other.invalid"}, json={"query": "黑衣背包的人"}).status_code == 403
        assert client.delete("/api/session", headers={"Origin": ORIGIN}).status_code == 200
        assert client.get("/api/session").json() == {"authenticated": False}


def test_login_limits_guesses_and_validation_does_not_echo_token():
    with TestClient(create_app(SETTINGS, httpx.MockTransport(lambda req: httpx.Response(200, json={}))), base_url=ORIGIN) as client:
        oversized = "private" * 100
        bad = client.post("/api/session", headers={"Origin": ORIGIN}, json={"token": oversized})
        assert bad.status_code == 422 and oversized not in bad.text
        for _ in range(5):
            assert client.post("/api/session", headers={"Origin": ORIGIN}, json={"token": "incorrect"}).status_code == 401
        assert client.post("/api/session", headers={"Origin": ORIGIN}, json={"token": "browser-token"}).status_code == 429


def test_upstream_errors_are_sanitized_and_configuration_fails_closed():
    with TestClient(create_app(SETTINGS, httpx.MockTransport(lambda req: httpx.Response(500, json={"error": "core-secret"}))), base_url=ORIGIN) as client:
        client.post("/api/session", headers={"Origin": ORIGIN}, json={"token": "browser-token"})
        result = client.post("/api/v1/search", headers={"Origin": ORIGIN}, json={"query": "测试"})
        assert result.status_code == 502 and "core-secret" not in result.text
    with TestClient(create_app({**SETTINGS, "session_secret": ""}, httpx.MockTransport(lambda req: httpx.Response(200, json={}))), base_url=ORIGIN) as client:
        assert client.get("/readyz").status_code == 503
        assert client.post("/api/session", headers={"Origin": ORIGIN}, json={"token": "browser-token"}).status_code == 503


def test_evidence_requires_session_and_never_exposes_upstream_credentials():
    frame_id = uuid.uuid4()
    image = b"\xff\xd8\xffsynthetic\xff\xd9"
    def core(req):
        assert req.headers["authorization"] == "Bearer core-secret"
        assert req.url.path == f"/api/v1/frames/{frame_id}/image"
        return httpx.Response(200, content=image, headers={"content-type": "image/jpeg"})
    with TestClient(create_app(SETTINGS, httpx.MockTransport(core)), base_url=ORIGIN) as client:
        path = f"/api/v1/frames/{frame_id}/image"
        assert client.get(path).status_code == 401
        client.post("/api/session", headers={"Origin": ORIGIN}, json={"token": "browser-token"})
        result = client.get(path)
        assert result.status_code == 200 and result.content == image and result.headers["content-type"] == "image/jpeg"
        assert result.headers["cache-control"] == "no-store"


def test_archive_proxy_preserves_single_range_and_requires_session():
    frame_id = uuid.uuid4()
    def core(req):
        assert req.headers["authorization"] == "Bearer core-secret" and req.headers["range"] == "bytes=10-14"
        return httpx.Response(206, content=b"12345", headers={"content-range": "bytes 10-14/100", "content-length": "5"})
    with TestClient(create_app(SETTINGS, httpx.MockTransport(core)), base_url=ORIGIN) as client:
        path = f"/api/v1/frames/{frame_id}/archive"
        assert client.get(path).status_code == 401
        client.post("/api/session", headers={"Origin": ORIGIN}, json={"token": "browser-token"})
        result = client.get(path, headers={"Range": "bytes=10-14"})
        assert result.status_code == 206 and result.content == b"12345" and result.headers["content-range"] == "bytes 10-14/100"
        assert client.get(path, headers={"Range": "bytes=1-2,4-5"}).status_code == 416


def test_observation_detail_proxy_binds_identity_and_keeps_credentials_private():
    observation_id=uuid.uuid4()
    calls=[]
    def core(request):
        calls.append(request)
        assert request.headers['authorization']=='Bearer core-secret'
        return httpx.Response(200,json={'observation_id':str(observation_id),'evidence':{'available':False}})
    with TestClient(create_app(SETTINGS,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        path=f'/api/v1/observations/{observation_id}'
        assert client.get(path).status_code==401 and not calls
        client.post('/api/session',headers={'Origin':ORIGIN},json={'token':'browser-token'})
        response=client.get(path)
        assert response.status_code==200 and response.json()['observation_id']==str(observation_id)
        assert response.headers['cache-control']=='no-store' and 'core-secret' not in response.text
    with TestClient(create_app(SETTINGS,httpx.MockTransport(lambda req:httpx.Response(200,json={'observation_id':str(uuid.uuid4())}))),base_url=ORIGIN) as client:
        client.post('/api/session',headers={'Origin':ORIGIN},json={'token':'browser-token'})
        assert client.get(f'/api/v1/observations/{observation_id}').status_code==503
