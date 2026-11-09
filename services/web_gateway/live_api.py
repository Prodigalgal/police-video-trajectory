"""Authenticated bounded HLS adapter. Browser input never supplies an upstream URL."""
import asyncio
from contextlib import asynccontextmanager
import json
import re
import ssl
import time
from urllib.parse import urlsplit

import httpx
from fastapi import HTTPException, Request, Response
from fastapi.responses import StreamingResponse
from starlette.background import BackgroundTask

ASSET = re.compile(r'[A-Za-z0-9_-]{1,160}\.(?:m3u8|mp4|m4s|ts)\Z')
CAMERA = re.compile(r'[A-Za-z0-9_-]{1,100}\Z')


def rewrite_playlist(payload, camera, kind='hls'):
    if kind not in {'hls','preview'}:raise ValueError('invalid live stream variant')
    text = payload.decode('utf-8')
    if not text.startswith('#EXTM3U') or len(payload) > 131072:
        raise ValueError('bounded HLS manifest required')

    def local(reference):
        if not ASSET.fullmatch(reference):
            raise ValueError('external or traversing HLS reference')
        return f'/api/v1/live/cameras/{camera}/{kind}/{reference}'

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
        self.directory=None
        self.directory_until=0
        self.directory_lock=asyncio.Lock()

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

    def paths(self,preview=False):
        value = json.loads(self.cfg.get('live_preview_paths' if preview else 'live_camera_paths', '{}'))
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
            media=app.state.live_media
            paths = media.paths()
            async with media.directory_lock:
                if media.directory is not None and time.monotonic()<media.directory_until:return media.directory
                response = await app.state.http.get(cfg['core_url'].rstrip('/')+'/api/v1/live/cameras',
                                               headers={'Authorization': 'Bearer '+cfg['core_token']}, timeout=10)
                if response.status_code != 200 or len(response.content) > 65536:
                    raise ValueError('camera authorization unavailable')
                items = response.json()['items']
                if not isinstance(items, list) or len(items) > 128:
                    raise ValueError('bounded cameras required')
                media.directory=[{'camera_id': row['camera_id'], 'display_name': row['display_name'],
                                  **({'preview_available':True} if row['camera_id'] in media.paths(True) else {})}
                                 for row in items if row['camera_id'] in paths]
                media.directory_until=time.monotonic()+2
                return media.directory
        except (httpx.HTTPError, ValueError, KeyError, TypeError):
            raise HTTPException(503, '监控目录暂不可用，请稍后重试') from None

    @app.get('/api/v1/live/cameras')
    async def directory(request: Request):
        return {'items': await cameras(request)}

    @app.get('/api/v1/live/cameras/{camera}/hls/{asset}')
    async def read(camera: str, asset: str, request: Request):
        return await forward(camera,asset,request,False)

    @app.get('/api/v1/live/cameras/{camera}/preview/{asset}')
    async def preview(camera: str, asset: str, request: Request):
        return await forward(camera,asset,request,True)

    async def forward(camera,asset,request,preview):
        allowed = await cameras(request)
        if not CAMERA.fullmatch(camera) or not any(row['camera_id'] == camera for row in allowed):
            raise HTTPException(404, '该监控点位不可用')
        if not ASSET.fullmatch(asset) or request.url.query:
            raise HTTPException(422, '播放片段参数无效')
        media = app.state.live_media
        acquired = False
        context = None
        transferred = False
        closed = False

        async def close():
            nonlocal closed
            if closed:
                return
            closed = True
            try:
                if context is not None:
                    await context.__aexit__(None, None, None)
            finally:
                if acquired:
                    media.slots.release()

        try:
            await asyncio.wait_for(media.slots.acquire(), timeout=1)
            acquired = True
            paths=media.paths(preview)
            if camera not in paths:raise HTTPException(404,'该监控预览尚未配置')
            path = paths[camera]
            bearer = cfg.get('live_media_bearer', '')
            context = media.client.stream('GET', cfg['live_media_url'].rstrip('/')+f'/{path}/{asset}',
                                          headers={'Authorization': 'Bearer '+bearer} if bearer else {},
                                          auth=None if bearer else (cfg.get('live_media_user', ''), cfg.get('live_media_password', '')))
            upstream = await context.__aenter__()
            if upstream.status_code != 200:
                raise HTTPException(503, '监控正在连接，请稍后重试')
            if asset.endswith('.m3u8'):
                body = bytearray()
                async for chunk in upstream.aiter_bytes():
                    body.extend(chunk)
                    if len(body) > 131072:
                        raise ValueError('media response exceeded bound')
                if not body:
                    raise ValueError('empty media response')
                return Response(rewrite_playlist(bytes(body), camera,'preview' if preview else 'hls'), media_type='application/vnd.apple.mpegurl',
                                headers={'Cache-Control': 'no-store'})
            limit = 64*1024*1024
            declared = upstream.headers.get('content-length')
            if declared is not None and (not re.fullmatch(r'[0-9]{1,10}', declared) or not 0 < int(declared) <= limit):
                raise ValueError('bounded media segment required')

            async def chunks():
                received = 0
                try:
                    async for chunk in upstream.aiter_bytes(64*1024):
                        received += len(chunk)
                        if received > limit:
                            raise RuntimeError('live media exceeded its transfer bound')
                        yield chunk
                    if received == 0 or declared is not None and received != int(declared):
                        raise RuntimeError('live media transfer was incomplete')
                finally:
                    await close()

            response_headers = {'Cache-Control': 'no-store'}
            if declared is not None:
                response_headers['Content-Length'] = declared
            response = StreamingResponse(chunks(), media_type='video/mp2t' if asset.endswith('.ts') else 'video/mp4',
                                         headers=response_headers, background=BackgroundTask(close))
            transferred = True
            return response
        except TimeoutError:
            raise HTTPException(429, '观看请求较多，请稍后重试', headers={'Retry-After': '2'}) from None
        except (httpx.HTTPError, ValueError, UnicodeError, KeyError):
            raise HTTPException(503, '监控连接暂时中断，请重新连接') from None
        finally:
            if not transferred:
                await close()
