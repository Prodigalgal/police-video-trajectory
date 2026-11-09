"""Bounded recorded-video transport shared by authenticated Core facades."""
import re
import httpx
from fastapi import HTTPException
from fastapi.responses import Response, StreamingResponse
from starlette.background import BackgroundTask

MAX_VIDEO=64*1024*1024


def valid_video_headers(status,headers):
    try:
        length=int(headers.get('content-length',''))
        if not 0<length<=MAX_VIDEO or headers.get('content-type','').split(';')[0]!='video/mp4':return False
        if status==200:return not headers.get('content-range')
        match=re.fullmatch(r'bytes (\d+)-(\d+)/(\d+)',headers.get('content-range',''))
        return bool(status==206 and match and 0<=int(match[1])<=int(match[2])<int(match[3])<=MAX_VIDEO and length==int(match[2])-int(match[1])+1)
    except (ValueError,TypeError):return False


async def stream_core_video(request,url,headers,*,message='录像片段服务暂不可用'):
    headers={**headers,'Accept-Encoding':'identity'}
    requested=request.headers.get('range')
    if requested is not None:
        if len(requested)>100 or not re.fullmatch(r'bytes=\d*-\d*',requested):raise HTTPException(416,'字节范围无效')
        headers['Range']=requested
    upstream=None;closed=False;transferred=False
    async def close():
        nonlocal closed
        if not closed:
            closed=True
            if upstream is not None:await upstream.aclose()
    try:
        query=request.app.state.http.build_request('GET',url,headers=headers,timeout=120)
        upstream=await request.app.state.http.send(query,stream=True)
        if upstream.status_code not in {200,206}:
            status=upstream.status_code;content_range=upstream.headers.get('content-range','')
            response_headers={'Cache-Control':'no-store'}
            if status==416 and re.fullmatch(r'bytes \*/\d{1,9}',content_range):response_headers['Content-Range']=content_range
            return Response(status_code=status if status in {401,403,404,409,416,422} else 503,headers=response_headers)
        if upstream.headers.get('content-encoding') not in {None,'identity'} or not valid_video_headers(upstream.status_code,upstream.headers):raise ValueError('invalid video response')
        response_headers={key:upstream.headers[key] for key in ('content-length','content-range','accept-ranges') if key in upstream.headers};response_headers['Cache-Control']='no-store'
        async def chunks():
            received=0
            try:
                async for chunk in upstream.aiter_bytes(64*1024):
                    received+=len(chunk)
                    if received>int(upstream.headers['content-length']):raise ValueError('video response exceeds declared size')
                    yield chunk
                if received!=int(upstream.headers['content-length']):raise ValueError('video response is truncated')
            finally:await close()
        response=StreamingResponse(chunks(),status_code=upstream.status_code,media_type='video/mp4',headers=response_headers,background=BackgroundTask(close))
        transferred=True;return response
    except (httpx.HTTPError,ValueError,TypeError):raise HTTPException(503,message) from None
    finally:
        if not transferred:await close()
