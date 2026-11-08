"""Authenticated bounded HLS adapter. Browser input never supplies an upstream URL."""
import asyncio
from contextlib import asynccontextmanager
import json
import re
import ssl
from urllib.parse import urlsplit

import httpx
from fastapi import HTTPException, Request, Response

ASSET = re.compile(r'[A-Za-z0-9_-]{1,160}\.(?:m3u8|mp4|m4s|ts)\Z')
CAMERA = re.compile(r'[A-Za-z0-9_-]{1,100}\Z')


def rewrite_playlist(payload, camera):
    text = payload.decode('utf-8')
    if not text.startswith('#EXTM3U') or len(payload) > 131072:
        raise ValueError('bounded HLS manifest required')

    def local(reference):
        if not ASSET.fullmatch(reference):
            raise ValueError('external or traversing HLS reference')
        return f'/api/v1/live/cameras/{camera}/hls/{reference}'

    lines = []
    for line in text.splitlines():
        if line.startswith('#'):
            # URI attributes include init segments and variant playlists.
            line = re.sub(r'URI="([^"]*)"', lambda match: 'URI="'+local(match[1])+'"', line)
            if re.search(r'URI=(?!")', line):
                raise ValueError('quoted HLS URI required')
        elif line.strip():
            line = local(line.strip())
        lines.append(line)
    return ('\n'.join(lines)+'\n').encode()


class LiveMedia:
    def __init__(self, cfg, transport=None):
        self.cfg, self.transport = cfg, transport
        self.slots = asyncio.Semaphore(8)
        self.client = None

    @asynccontextmanager
    async def lifespan(self):
        address = self.cfg.get('live_media_url', '')
        parsed = urlsplit(address)
        if address and (parsed.scheme != 'https' or not parsed.hostname or parsed.path not in {'', '/'} or
                        any((parsed.username, parsed.password, parsed.query, parsed.fragment))):
            raise ValueError('private HTTPS media origin required')
        verify = ssl.create_default_context(cafile=self.cfg['live_media_ca']) if address and self.transport is None else True
        async with httpx.AsyncClient(transport=self.transport, verify=verify, trust_env=False,
                                    follow_redirects=False, timeout=httpx.Timeout(20, connect=5),
                                    limits=httpx.Limits(max_connections=8, max_keepalive_connections=4)) as client:
            self.client = client
            try:
                yield
            finally:
                self.client = None

    def paths(self):
        value = json.loads(self.cfg.get('live_camera_paths', '{}'))
        if (not isinstance(value, dict) or len(value) > 128 or
                any(not isinstance(key, str) or not CAMERA.fullmatch(key) or
                    not isinstance(path, str) or not CAMERA.fullmatch(path) for key, path in value.items())):
            raise ValueError('bounded camera paths required')
        return value


def mount_live(app, cfg, authenticated):
    async def cameras(request):
        if not authenticated(request):
            raise HTTPException(401, '请先登录')
        if not cfg.get('live_view'):
            raise HTTPException(404, '实时监控尚未启用')
        try:
            paths = app.state.live_media.paths()
            response = await app.state.http.get(cfg['core_url'].rstrip('/')+'/api/v1/live/cameras',
                                               headers={'Authorization': 'Bearer '+cfg['core_token']}, timeout=10)
            if response.status_code != 200 or len(response.content) > 65536:
                raise ValueError('camera authorization unavailable')
            items = response.json()['items']
            if not isinstance(items, list) or len(items) > 128:
                raise ValueError('bounded cameras required')
            return [{'camera_id': row['camera_id'], 'display_name': row['display_name']}
                    for row in items if row['camera_id'] in paths]
        except (httpx.HTTPError, ValueError, KeyError, TypeError):
            raise HTTPException(503, '监控目录暂不可用，请稍后重试') from None

    @app.get('/api/v1/live/cameras')
    async def directory(request: Request):
        return {'items': await cameras(request)}

    @app.get('/api/v1/live/cameras/{camera}/hls/{asset}')
    async def read(camera: str, asset: str, request: Request):
        allowed = await cameras(request)
        if not CAMERA.fullmatch(camera) or not any(row['camera_id'] == camera for row in allowed):
            raise HTTPException(404, '该监控点位不可用')
        if not ASSET.fullmatch(asset) or request.url.query:
            raise HTTPException(422, '播放片段参数无效')
        media = app.state.live_media
        acquired = False
        try:
            await asyncio.wait_for(media.slots.acquire(), timeout=1)
            acquired = True
            path = media.paths()[camera]
            async with media.client.stream('GET', cfg['live_media_url'].rstrip('/')+f'/{path}/{asset}',
                                           auth=(cfg.get('live_media_user', ''), cfg.get('live_media_password', ''))) as upstream:
                if upstream.status_code != 200:
                    raise HTTPException(503, '监控正在连接，请稍后重试')
                limit = 131072 if asset.endswith('.m3u8') else 12*1024*1024
                body = bytearray()
                async for chunk in upstream.aiter_bytes():
                    body.extend(chunk)
                    if len(body) > limit:
                        raise ValueError('media response exceeded bound')
                if not body:
                    raise ValueError('empty media response')
                if asset.endswith('.m3u8'):
                    body = rewrite_playlist(bytes(body), camera)
                    content_type = 'application/vnd.apple.mpegurl'
                else:
                    content_type = 'video/mp2t' if asset.endswith('.ts') else 'video/mp4'
                return Response(bytes(body), media_type=content_type, headers={'Cache-Control': 'no-store'})
        except TimeoutError:
            raise HTTPException(429, '观看请求较多，请稍后重试', headers={'Retry-After': '2'}) from None
        except (httpx.HTTPError, ValueError, UnicodeError, KeyError):
            raise HTTPException(503, '监控连接暂时中断，请重新连接') from None
        finally:
            if acquired:
                media.slots.release()
