"""Current-account, camera-scoped trace reads and bounded replay source download."""
import hashlib
from uuid import UUID
import httpx
from fastapi import HTTPException, Query, Request
from fastapi.responses import Response
from .business_proxy import BusinessProxy


def mount_processing_trace(app, cfg, authenticated, origin_ok):
    proxy = BusinessProxy(app, cfg, authenticated, origin_ok, feature='processing_trace')
    internal = '/internal/processing-traces'

    @app.get('/api/v1/observations/{observation}/trace')
    async def observation_trace(observation: UUID, request: Request, observation_version: int = Query(ge=1)):
        return await proxy.call(request, 'GET', internal+'/observations/'+str(observation), params={'observationVersion': observation_version})

    @app.get('/api/admin/processing-traces/frames/{frame}')
    async def frame_trace(frame: UUID, request: Request):
        return await proxy.call(request, 'GET', internal+'/frames/'+str(frame), admin=True)

    @app.get('/api/admin/processing-traces/frames/{frame}/image')
    async def source_image(frame: UUID, request: Request, sha: str = Query(pattern=r'^[a-f0-9]{64}$')):
        account = proxy.authorize(request, admin=True)
        try:
            async with app.state.http.stream('GET', cfg['core_url'].rstrip('/')+internal+'/frames/'+str(frame)+'/image', params={'sha': sha}, headers=proxy.actor_headers(account), timeout=30) as upstream:
                if upstream.status_code != 200:
                    return Response(status_code=upstream.status_code if upstream.status_code in {400,401,403,404,409,422} else 503, headers={'Cache-Control':'no-store'})
                raw = bytearray()
                async for chunk in upstream.aiter_bytes():
                    if len(raw)+len(chunk)>8*1024*1024:raise ValueError('image exceeded bound')
                    raw.extend(chunk)
                if hashlib.sha256(raw).hexdigest()!=sha or upstream.headers.get('x-content-sha256')!=sha or upstream.headers.get('content-type','').split(';')[0]!='image/jpeg':
                    raise ValueError('source image changed')
                return Response(bytes(raw), media_type='image/jpeg', headers={'Cache-Control':'no-store','X-Content-SHA256':sha})
        except (httpx.HTTPError, ValueError, TypeError):
            raise HTTPException(503, '原图校验失败或服务暂不可用，请重新读取') from None
