"""Admin-only precheck playback. The semantic public frame API stays unchanged."""
import hashlib
import httpx
from fastapi import HTTPException,Query,Request
from fastapi.responses import Response
from uuid import UUID
from .business_proxy import BusinessProxy
from .core_media import stream_core_video


def mount_recording_review(app,cfg,authenticated,origin_ok):
    proxy=BusinessProxy(app,cfg,authenticated,origin_ok,feature='recording_review')
    prefix='/api/admin/recording-review/frames'
    internal='/internal/recording-review/frames'

    @app.get(prefix)
    async def listing(request:Request,camera_id:str|None=Query(default=None,pattern=r'^[A-Za-z0-9_-]{1,100}$'),limit:int=Query(default=20,ge=1,le=50),after:UUID|None=None):
        params={'limit':limit}
        if camera_id:params['camera_id']=camera_id
        if after:params['after']=str(after)
        return await proxy.call(request,'GET',internal,params=params,admin=True)

    @app.get(prefix+'/{frame}')
    async def detail(frame:UUID,request:Request):return await proxy.call(request,'GET',internal+'/'+str(frame),admin=True)

    @app.get(prefix+'/{frame}/image')
    async def image(frame:UUID,request:Request):
        account=proxy.authorize(request,admin=True)
        try:
            async with app.state.http.stream('GET',cfg['core_url'].rstrip('/')+internal+'/'+str(frame)+'/image',headers=proxy.actor_headers(account),timeout=30) as upstream:
                if upstream.status_code!=200:return Response(status_code=upstream.status_code if upstream.status_code in {401,403,404,409,422} else 503,headers={'Cache-Control':'no-store'})
                raw=bytearray()
                async for chunk in upstream.aiter_bytes():
                    if len(raw)+len(chunk)>8*1024*1024:raise ValueError('image exceeded bound')
                    raw.extend(chunk)
                digest=hashlib.sha256(raw).hexdigest()
                if upstream.headers.get('content-type','').split(';')[0]!='image/jpeg' or digest!=upstream.headers.get('x-content-sha256'):raise ValueError('image proof changed')
                return Response(bytes(raw),media_type='image/jpeg',headers={'Cache-Control':'no-store','X-Content-SHA256':digest})
        except (httpx.HTTPError,ValueError,TypeError):raise HTTPException(503,'留存关键帧暂不可用，请重新读取') from None

    @app.get(prefix+'/{frame}/video')
    async def video(frame:UUID,request:Request):
        account=proxy.authorize(request,admin=True)
        return await stream_core_video(request,cfg['core_url'].rstrip('/')+internal+'/'+str(frame)+'/video',proxy.actor_headers(account))
