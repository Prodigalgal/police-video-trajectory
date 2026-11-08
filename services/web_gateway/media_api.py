"""Authenticated, opt-in reads of registered recorded-media assets."""
import hashlib
import json
import re
from uuid import UUID

import httpx
from fastapi import HTTPException,Query,Request
from fastapi.responses import JSONResponse,Response,StreamingResponse

MAX_VIDEO=64*1024*1024


def valid_video_headers(status,headers):
    try:
        length=int(headers.get('content-length',''))
        if not 0<length<=MAX_VIDEO or headers.get('content-type','').split(';')[0]!='video/mp4':return False
        if status==200:return not headers.get('content-range')
        match=re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)',headers.get('content-range',''))
        return bool(status==206 and match and 0<=int(match[1])<=int(match[2])<int(match[3])<=MAX_VIDEO and length==int(match[2])-int(match[1])+1)
    except (ValueError,TypeError):return False


def mount_media(app,cfg,authenticated):
    def authorize(request):
        if not authenticated(request):raise HTTPException(401,'请先登录')
        if not cfg.get('media_clips'):raise HTTPException(404,'录像片段功能尚未启用')

    def url(suffix):return cfg['core_url'].rstrip('/')+'/api/v1/media-clips'+suffix
    allowed={'media_unavailable','media_source_unavailable','media_source_changed','media_manifest_changed',
             'media_evidence_changed','media_evidence_unavailable','media_sample_unavailable','invalid_media_request','media_session_changed','media_session_unavailable'}

    async def forward(request,suffix='',params=None,kind='json'):
        authorize(request)
        try:
            async with request.app.state.http.stream('GET',url(suffix),params=params,
                headers={'Authorization':'Bearer '+cfg['core_token']},timeout=120) as upstream:
                raw=bytearray();limit=8*1024*1024 if kind=='image' else 1024*1024
                async for chunk in upstream.aiter_bytes():
                    if len(raw)+len(chunk)>limit:raise ValueError('media response exceeds limit')
                    raw.extend(chunk)
                if upstream.status_code!=200:
                    try:value=json.loads(raw);code=value.get('code') if isinstance(value,dict) else None
                    except ValueError:code=None
                    return JSONResponse({'code':code if code in allowed else 'media_unavailable'},status_code=upstream.status_code if upstream.status_code in {400,401,403,404,409,422} else 503,headers={'Cache-Control':'no-store'})
                if kind!='json':
                    digest=hashlib.sha256(raw).hexdigest()
                    if digest!=upstream.headers.get('x-content-sha256'):raise ValueError('media digest differs')
                    return Response(bytes(raw),media_type='image/jpeg' if kind=='image' else 'application/json',headers={'Cache-Control':'no-store','X-Content-SHA256':digest})
                value=json.loads(raw)
                if not isinstance(value,dict):raise ValueError('invalid media metadata')
                return JSONResponse(value,headers={'Cache-Control':'no-store'})
        except (httpx.HTTPError,ValueError,TypeError):raise HTTPException(503,'录像片段服务暂不可用') from None

    @app.get('/api/v1/media-clips')
    async def clips(request:Request,camera_id:str=Query(min_length=1,max_length=100),limit:int=Query(default=20,ge=1,le=50),after:UUID|None=None):
        return await forward(request,params={'camera_id':camera_id,'limit':limit,**({'after':str(after)} if after else {})})

    @app.get('/api/v1/media-clips/{identity}/manifest')
    async def manifest(identity:UUID,request:Request):return await forward(request,'/'+str(identity)+'/manifest',kind='manifest')

    @app.get('/api/v1/media-clips/{identity}/samples/{index}')
    async def sample(identity:UUID,index:int,request:Request):
        if not 0<=index<=30:raise HTTPException(422,'样本序号无效')
        return await forward(request,f'/{identity}/samples/{index}',kind='image')

    @app.get('/api/v1/media-clips/{identity}/video')
    async def video(identity:UUID,request:Request):
        authorize(request);headers={'Authorization':'Bearer '+cfg['core_token']};requested=request.headers.get('range')
        if requested is not None:
            if len(requested)>100 or not re.fullmatch(r'bytes=\d*-\d*',requested):raise HTTPException(416,'字节范围无效')
            headers['Range']=requested
        upstream=None
        try:
            query=request.app.state.http.build_request('GET',url('/'+str(identity)+'/video'),headers=headers,timeout=120)
            upstream=await request.app.state.http.send(query,stream=True)
            if upstream.status_code not in {200,206}:
                status=upstream.status_code;content_range=upstream.headers.get('content-range','');await upstream.aclose()
                response_headers={'Cache-Control':'no-store'}
                if status==416 and re.fullmatch(r'bytes \*/\d{1,9}',content_range):response_headers['Content-Range']=content_range
                return Response(status_code=status if status in {401,403,404,409,416,422} else 503,headers=response_headers)
            if upstream.headers.get('content-encoding') not in {None,'identity'} or not valid_video_headers(upstream.status_code,upstream.headers):raise ValueError('invalid video response')
            response_headers={key:upstream.headers[key] for key in ['content-length','content-range','accept-ranges'] if key in upstream.headers};response_headers['Cache-Control']='no-store'
            async def chunks():
                size=0
                try:
                    async for chunk in upstream.aiter_bytes():
                        size+=len(chunk)
                        if size>int(upstream.headers['content-length']):raise ValueError('video response exceeds declared size')
                        yield chunk
                    if size!=int(upstream.headers['content-length']):raise ValueError('video response is truncated')
                finally:await upstream.aclose()
            return StreamingResponse(chunks(),status_code=upstream.status_code,media_type='video/mp4',headers=response_headers)
        except (httpx.HTTPError,ValueError,TypeError):
            if upstream is not None:await upstream.aclose()
            raise HTTPException(503,'录像片段服务暂不可用') from None
