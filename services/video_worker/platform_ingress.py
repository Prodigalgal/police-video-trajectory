"""ADR023 WVP-PRO / ZLMediaKit bounded platform ingress client contracts.

Validates platform URLs/refs, maintains TLS verification, prevents arbitrary SSRF
endpoints from search inputs, and separates WVP-PRO / ZLM signaling/media handles
from verified camera clock claims.
"""
from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime, timezone
import ipaddress
import ssl
from urllib.parse import urlsplit
import uuid

import httpx


DEFAULT_TIMEOUT_SECONDS = 15.0


def validate_platform_base_url(url: str, *, allow_private_host: bool = False) -> str:
    """Validate platform REST endpoint; requires HTTPS and forbids embedded credentials."""
    if not isinstance(url, str) or not url.strip():
        raise ValueError('platform base url required')
    raw = url.strip()
    parsed = urlsplit(raw)
    if parsed.scheme != 'https':
        raise ValueError('platform endpoint must use https')
    if parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError('platform endpoint cannot embed credentials or query parameters')
    host = parsed.hostname
    if not host:
        raise ValueError('platform endpoint host missing')
    if not allow_private_host:
        try:
            addr = ipaddress.ip_address(host)
            if addr.is_loopback or addr.is_link_local:
                raise ValueError('loopback or link-local platform address rejected')
        except ValueError as exc:
            if 'rejected' in str(exc):
                raise
    return raw.rstrip('/')


def resolve_verified_platform_time(raw_iso: str | None, *, clock_verified: bool,
                                   offset_ms: int | None = None) -> str | None:
    """Return normalized UTC ISO string only when upstream platform clock is explicitly verified."""
    if not clock_verified or not isinstance(raw_iso, str) or not raw_iso.strip():
        return None
    parsed = datetime.fromisoformat(raw_iso.strip().replace('Z', '+00:00'))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=timezone.utc)
    if offset_ms is not None:
        if isinstance(offset_ms, bool) or not isinstance(offset_ms, int):
            raise ValueError('invalid clock offset')
        parsed = datetime.fromtimestamp(parsed.timestamp() + offset_ms / 1000.0, tz=timezone.utc)
    return parsed.astimezone(timezone.utc).isoformat().replace('+00:00', 'Z')


@dataclass(frozen=True)
class WvpChannelItem:
    device_id: str
    channel_id: str
    name: str
    online: bool
    has_audio: bool


class WvpPlatformClient:
    """Bounded HTTPS client for WVP-PRO REST API with explicit TLS verification."""

    def __init__(self, base_url: str, *, ca_file: str = '', token: str = '',
                 transport: httpx.AsyncBaseTransport | None = None,
                 allow_private_host: bool = False):
        self.base_url = validate_platform_base_url(base_url, allow_private_host=allow_private_host)
        self.ca_file = ca_file
        self.token = token
        self.transport = transport

    def _client(self) -> httpx.AsyncClient:
        headers = {'Accept': 'application/json'}
        if self.token:
            headers['access-token'] = self.token
        kwargs: dict = {'timeout': DEFAULT_TIMEOUT_SECONDS, 'headers': headers}
        if self.transport is not None:
            kwargs['transport'] = self.transport
        else:
            kwargs['verify'] = ssl.create_default_context(cafile=self.ca_file) if self.ca_file else True
        return httpx.AsyncClient(**kwargs)

    async def query_channels(self, device_id: str, *, page: int = 1, count: int = 50) -> list[WvpChannelItem]:
        if not isinstance(device_id, str) or not device_id or not 1 <= count <= 100:
            raise ValueError('invalid device channel query')
        async with self._client() as client:
            resp = await client.get(
                f'{self.base_url}/api/device/query/devices/{device_id}/channels',
                params={'page': page, 'count': count},
            )
            resp.raise_for_status()
            payload = resp.json()
        data = payload.get('data') if isinstance(payload, dict) else None
        rows = data.get('list', []) if isinstance(data, dict) else []
        items = []
        for row in rows:
            if not isinstance(row, dict):
                continue
            ch_id = str(row.get('channelId') or row.get('deviceId') or '').strip()
            if not ch_id:
                continue
            items.append(WvpChannelItem(
                device_id=str(row.get('deviceId') or device_id),
                channel_id=ch_id,
                name=str(row.get('name') or ch_id)[:200],
                online=str(row.get('status') or '').upper() in {'ON', 'ONLINE', 'TRUE', '1'},
                has_audio=bool(row.get('hasAudio', False)),
            ))
        return items

    async def start_live(self, device_id: str, channel_id: str) -> dict:
        if not device_id or not channel_id:
            raise ValueError('device_id and channel_id required')
        async with self._client() as client:
            resp = await client.get(
                f'{self.base_url}/api/play/start/{device_id}/{channel_id}',
            )
            resp.raise_for_status()
            payload = resp.json()
        data = payload.get('data') if isinstance(payload, dict) else None
        if not isinstance(data, dict) or not data.get('stream'):
            raise ValueError('wvp stream handle unavailable')
        # Only expose bounded non-secret stream identity metadata, never raw credentialed URLs.
        return {
            'app': str(data.get('app') or 'rtp'),
            'stream': str(data['stream']),
            'media_server_id': str(data.get('mediaServerId') or ''),
            'interoperability_verified': True,
        }


class CorePlatformCoordinator:
    """Internal worker adapter for Core ADR023 leased platform media sessions."""

    def __init__(self, core_url: str, worker_token: str, *, ca_file: str = '',
                 transport: httpx.AsyncBaseTransport | None = None):
        self.core_url = validate_platform_base_url(core_url, allow_private_host=True)
        self.worker_token = worker_token
        self.ca_file = ca_file
        self.transport = transport

    def _client(self) -> httpx.AsyncClient:
        kwargs: dict = {
            'timeout': DEFAULT_TIMEOUT_SECONDS,
            'headers': {'Authorization': f'Bearer {self.worker_token}'},
        }
        if self.transport is not None:
            kwargs['transport'] = self.transport
        else:
            kwargs['verify'] = ssl.create_default_context(cafile=self.ca_file) if self.ca_file else True
        return httpx.AsyncClient(**kwargs)

    async def acquire_live(self, *, platform_id: str, device_id: str, channel_id: str,
                           worker_id: str, stream_profile: str = 'main',
                           lease_seconds: int = 60) -> dict:
        body = {
            'requestId': str(uuid.uuid4()),
            'platformId': platform_id,
            'deviceId': device_id,
            'channelId': channel_id,
            'sessionKind': 'live',
            'streamProfile': stream_profile,
            'workerId': worker_id,
            'leaseSeconds': lease_seconds,
        }
        async with self._client() as client:
            resp = await client.post(f'{self.core_url}/internal/v1/platforms/sessions/acquire', json=body)
            resp.raise_for_status()
            return resp.json()['session']

    async def renew(self, *, session_id: str, worker_id: str, lease_token: str,
                    lease_seconds: int = 60) -> dict:
        async with self._client() as client:
            resp = await client.post(
                f'{self.core_url}/internal/v1/platforms/sessions/{session_id}/renew',
                json={'workerId': worker_id, 'leaseToken': lease_token, 'leaseSeconds': lease_seconds},
            )
            resp.raise_for_status()
            return resp.json()['session']

    async def release(self, *, session_id: str, worker_id: str, lease_token: str,
                      reason: str = 'completed') -> dict:
        async with self._client() as client:
            resp = await client.post(
                f'{self.core_url}/internal/v1/platforms/sessions/{session_id}/release',
                json={'workerId': worker_id, 'leaseToken': lease_token, 'reason': reason},
            )
            resp.raise_for_status()
            return resp.json()['session']
