"""Contract tests for ADR023 WVP-PRO / ZLMediaKit platform ingress gateway and client boundaries."""
from __future__ import annotations

import asyncio
import json
from pathlib import Path
from types import SimpleNamespace
import uuid

from fastapi.testclient import TestClient
import httpx
import pytest

from services.video_worker.platform_ingress import (
    CorePlatformCoordinator,
    WvpPlatformClient,
    resolve_verified_platform_time,
    validate_platform_base_url,
)
from services.web_gateway.app import create_app


class _StubCoreTransport(httpx.AsyncBaseTransport):
    def __init__(self):
        self.requests = []
        self.platforms = {}
        self.channels = {}

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        path = request.url.path
        body = json.loads(request.content.decode()) if request.content else None
        self.requests.append((request.method, path, body))
        if path == '/internal/admin/users/admin/status':
            return httpx.Response(200, json={'valid': True})
        if path == '/internal/admin/platforms' and request.method == 'GET':
            return httpx.Response(200, json={'schema_version': 'platform-ingress-v1', 'adapters': [], 'items': list(self.platforms.values())})
        if path.startswith('/internal/admin/platforms/') and path.endswith('/channels') and request.method == 'PUT':
            pid = path.split('/')[4]
            if pid not in self.platforms:
                return httpx.Response(404, json={'code': 'platform_unavailable'})
            item = {'mapping_id': str(uuid.uuid4()), 'platform_id': pid, 'device_id': body['deviceId'],
                    'channel_id': body['channelId'], 'camera_id': body['cameraId'], 'active': body['active'], 'revision': 1}
            self.channels[(pid, body['deviceId'], body['channelId'])] = item
            return httpx.Response(200, json={'channel': item, 'request_id': body['requestId']})
        if path.startswith('/internal/admin/platforms/') and request.method == 'PUT':
            pid = path.split('/')[4]
            if not body['clockState'] == 'verified' and body.get('clockOffsetMs') is not None:
                return httpx.Response(400, json={'code': 'platform_clock_unverified'})
            item = {'platform_id': pid, 'adapter_kind': body['adapterKind'], 'display_name': body['displayName'],
                    'endpoint_ref': body['endpointRef'], 'clock_state': body['clockState'],
                    'interoperability_verified': False, 'enabled': body['enabled'], 'revision': 1}
            self.platforms[pid] = item
            return httpx.Response(200, json={'platform': item, 'request_id': body['requestId']})
        if path == '/internal/admin/platforms/sessions':
            return httpx.Response(200, json={'items': []})
        return httpx.Response(404, json={'code': 'not_found'})


def test_admin_platform_gateway_routes_and_ssrf_secret_guards(tmp_path):
    ca = tmp_path / 'ca.pem'
    ca.write_text('dummy', encoding='utf-8')
    stub = _StubCoreTransport()
    cfg = {
        'auth_mode': 'token',
        'access_token': 'user-token',
        'username': 'admin',
        'session_secret': 's' * 32,
        'core_token': 'core-tok',
        'admin_token': 'admin-tok',
        'core_url': 'https://core.local',
        'core_ca': str(ca),
        'origin': 'https://localhost:8445',
        'static_dir': str(tmp_path / 'static'),
    }
    (tmp_path / 'static').mkdir()
    app = create_app(cfg, transport=stub)
    headers = {'Origin': 'https://localhost:8445'}
    with TestClient(app, base_url='https://localhost:8445') as client:
        resp = client.post('/api/session', json={'token': 'user-token'}, headers=headers)
        assert resp.status_code == 200

        r = client.get('/api/admin/platforms')
        assert r.status_code == 200
        assert r.json()['schema_version'] == 'platform-ingress-v1'

        # Reject raw rtsp:// / embedded credentials in endpoint_ref at gateway Pydantic boundary
        bad = client.put('/api/admin/platforms/wvp-01', headers=headers, json={
            'request_id': str(uuid.uuid4()),
            'expected_revision': 0,
            'adapter_kind': 'gb28181_cascade',
            'display_name': 'WVP 平台',
            'endpoint_ref': 'rtsp://admin:pass@10.0.0.1/cam1',
            'enabled': True,
        })
        assert bad.status_code == 422

        ok = client.put('/api/admin/platforms/wvp-01', headers=headers, json={
            'request_id': str(uuid.uuid4()),
            'expected_revision': 0,
            'adapter_kind': 'gb28181_cascade',
            'display_name': '南京 WVP-PRO 级联',
            'endpoint_ref': 'k8s-secret:wvp-01',
            'media_node': 'zlm-edge-01',
            'clock_state': 'unverified',
            'enabled': True,
        })
        assert ok.status_code == 200
        assert ok.json()['platform']['interoperability_verified'] is False

        ch = client.put('/api/admin/platforms/wvp-01/channels', headers=headers, json={
            'request_id': str(uuid.uuid4()),
            'expected_revision': 0,
            'device_id': '34020000001320000001',
            'channel_id': '34020000001310000002',
            'camera_id': 'recording-02',
            'channel_name': '二号卡口',
            'stream_profile': 'main',
            'clock_verified': False,
            'active': True,
        })
        assert ch.status_code == 200
        assert ch.json()['channel']['camera_id'] == 'recording-02'
        assert stub.requests[-1][1] == '/internal/admin/platforms/wvp-01/channels'


def test_wvp_platform_client_url_clock_and_redaction_contracts():
    with pytest.raises(ValueError, match='https'):
        validate_platform_base_url('http://10.0.0.5:18080')
    with pytest.raises(ValueError, match='credentials'):
        validate_platform_base_url('https://admin:123@wvp.internal')
    with pytest.raises(ValueError, match='loopback'):
        validate_platform_base_url('https://127.0.0.1:8443')

    assert resolve_verified_platform_time('2026-11-18T03:00:00Z', clock_verified=False, offset_ms=500) is None
    assert resolve_verified_platform_time('2026-11-18T03:00:00Z', clock_verified=True, offset_ms=500) == '2026-11-18T03:00:00.500000Z'

    class MockWvp(httpx.AsyncBaseTransport):
        async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
            if request.url.path.endswith('/channels'):
                return httpx.Response(200, json={'code': 0, 'data': {'list': [
                    {'deviceId': '3402001', 'channelId': '3402002', 'name': '北门高清', 'status': 'ON', 'hasAudio': False}
                ]}})
            if '/api/play/start/' in request.url.path:
                return httpx.Response(200, json={'code': 0, 'data': {
                    'app': 'rtp', 'stream': '3402001_3402002', 'mediaServerId': 'zlm-1',
                    'rtsp': 'rtsp://secret-host/rtp/3402001_3402002',
                }})
            return httpx.Response(404)

    async def exercise():
        wvp = WvpPlatformClient('https://wvp.internal', token='secret-api-tok', transport=MockWvp())
        channels = await wvp.query_channels('3402001')
        assert len(channels) == 1 and channels[0].online is True and channels[0].channel_id == '3402002'
        live = await wvp.start_live('3402001', '3402002')
        assert live['stream'] == '3402001_3402002'
        assert 'rtsp' not in live

    asyncio.run(exercise())


def test_core_platform_coordinator_lease_flow():
    seen = []

    class MockCore(httpx.AsyncBaseTransport):
        async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
            seen.append(request.url.path)
            body = json.loads(request.content.decode())
            if request.url.path.endswith('/acquire'):
                return httpx.Response(200, json={'session': {'session_id': 's1', 'lease_token': 't1', 'media_epoch': 0, 'state': 'active'}})
            if request.url.path.endswith('/renew'):
                return httpx.Response(200, json={'session': {'session_id': 's1', 'state': 'active'}})
            if request.url.path.endswith('/release'):
                return httpx.Response(200, json={'session': {'session_id': 's1', 'state': 'released'}})
            return httpx.Response(404)

    async def exercise():
        coord = CorePlatformCoordinator('https://core.local', 'worker-tok', transport=MockCore())
        s = await coord.acquire_live(platform_id='wvp-01', device_id='d1', channel_id='c1', worker_id='w1')
        assert s['media_epoch'] == 0
        await coord.renew(session_id='s1', worker_id='w1', lease_token='t1')
        rel = await coord.release(session_id='s1', worker_id='w1', lease_token='t1')
        assert rel['state'] == 'released'

    asyncio.run(exercise())
    assert len(seen) == 3
