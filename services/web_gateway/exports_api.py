"""Session-bound export management and bounded native ZIP streaming."""
from typing import Literal
from uuid import UUID
import asyncio
import re

import httpx
from fastapi import HTTPException, Query, Request
from fastapi.responses import StreamingResponse
from pydantic import BaseModel, ConfigDict, Field
from starlette.background import BackgroundTask

from .business_proxy import BusinessProxy

MAX_ARTIFACT=516*1024**2


class CreateExport(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    collection_id:UUID
    collection_revision:int=Field(ge=1,strict=True)
    part:int=Field(ge=1,le=100,strict=True)
    native_video:bool=Field(default=True,strict=True)
    normalized_video:bool=Field(default=True,strict=True)


class ControlExport(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    expected_revision:int=Field(ge=1,strict=True)
    action:Literal['cancel','retry']
    reason:str=Field(min_length=1,max_length=300)


def valid_zip_headers(status,headers):
    if headers.get('content-type','').split(';')[0]!='application/zip' or headers.get('content-encoding') not in {None,'identity'}:return False
    length=headers.get('content-length','')
    if not re.fullmatch(r'[0-9]{1,10}',length) or not 0<int(length)<=MAX_ARTIFACT or not re.fullmatch('[a-f0-9]{64}',headers.get('x-content-sha256','')):return False
    if status==200:return 'content-range' not in headers
    match=re.fullmatch(r'bytes ([0-9]+)-([0-9]+)/([0-9]+)',headers.get('content-range',''))
    return bool(match and 0<=int(match[1])<=int(match[2])<int(match[3])<=MAX_ARTIFACT and int(length)==int(match[2])-int(match[1])+1)


def mount_exports(app,cfg,authenticated,origin_ok):
    proxy=BusinessProxy(app,cfg,authenticated,origin_ok,feature='exports')
    status_proxy=BusinessProxy(app,cfg,authenticated,origin_ok,feature='account_directory')

    @app.get('/api/v1/exports/status')
    async def status(request:Request):return await status_proxy.call(request,'GET','/internal/exports/status')

    @app.get('/api/v1/exports/info')
    async def info(request:Request,collection_id:UUID,revision:int=Query(ge=1)):
        return await proxy.call(request,'GET','/internal/exports/info',params={'collection_id':str(collection_id),'revision':revision})

    @app.post('/api/v1/exports')
    async def create(body:CreateExport,request:Request):
        if not body.native_video and not body.normalized_video:raise HTTPException(422,'请选择至少一种录像附件')
        return await proxy.call(request,'POST','/internal/exports',timeout=180,body={'requestId':str(body.request_id),'collectionId':str(body.collection_id),'collectionRevision':body.collection_revision,'part':body.part,'nativeVideo':body.native_video,'normalizedVideo':body.normalized_video})

    @app.get('/api/v1/exports')
    async def listing(request:Request,collection_id:UUID,limit:int=Query(default=10,ge=1,le=30),after:UUID|None=None):
        return await proxy.call(request,'GET','/internal/exports',params={'collection_id':str(collection_id),'limit':limit,**({'after':str(after)} if after else {})})

    @app.get('/api/v1/exports/{identity}')
    async def read(identity:UUID,request:Request):return await proxy.call(request,'GET',f'/internal/exports/{identity}')

    @app.post('/api/v1/exports/{identity}/control')
    async def control(identity:UUID,body:ControlExport,request:Request):
        return await proxy.call(request,'POST',f'/internal/exports/{identity}/control',body={'requestId':str(body.request_id),'expectedRevision':body.expected_revision,'action':body.action,'reason':body.reason})

    @app.get('/api/v1/exports/{identity}/transfers')
    async def transfers(identity:UUID,request:Request,limit:int=Query(default=10,ge=1,le=30),after:UUID|None=None):
        return await proxy.call(request,'GET',f'/internal/exports/{identity}/transfers',params={'limit':limit,**({'after':str(after)} if after else {})})

    @app.get('/api/v1/exports/{identity}/download')
    async def download(identity:UUID,request:Request):
        account=proxy.authorize(request);headers={'Authorization':'Bearer '+cfg['admin_token'],'X-Actor-Id':account['username'],'X-Actor-Revision':str(account['credential_revision'])}
        requested=request.headers.get('range')
        if requested is not None:
            if len(requested)>100 or not re.fullmatch(r'bytes=\d*-\d*',requested):raise HTTPException(416,'下载范围无效')
            headers['Range']=requested
        upstream=None
        try:
            query=app.state.http.build_request('GET',cfg['core_url'].rstrip('/')+f'/internal/exports/{identity}/download',headers=headers,timeout=180)
            upstream=await app.state.http.send(query,stream=True)
            if upstream.status_code not in {200,206}:
                status=upstream.status_code;await upstream.aclose()
                raise HTTPException(status if status in {400,401,403,404,409,410,416,422,429} else 503,'证据包暂不可下载，请刷新任务状态')
            if not valid_zip_headers(upstream.status_code,upstream.headers):raise ValueError('Invalid export headers')
            transfer=str(UUID(upstream.headers.get('x-transfer-id','')))
            response_headers={key:upstream.headers[key] for key in ('content-length','content-range','accept-ranges','x-content-sha256') if key in upstream.headers}
            response_headers.update({'Cache-Control':'no-store','X-Transfer-Id':transfer,'Content-Disposition':f'attachment; filename="evidence-{identity}.zip"','X-Content-Type-Options':'nosniff'})
            async def chunks():
                size=0
                try:
                    async with asyncio.timeout(900):
                        async for chunk in upstream.aiter_bytes(64*1024):
                            size+=len(chunk)
                            if size>int(upstream.headers['content-length']):raise ValueError('Export exceeds declared size')
                            yield chunk
                        if size!=int(upstream.headers['content-length']):raise ValueError('Export truncated')
                finally:await upstream.aclose()
            return StreamingResponse(chunks(),status_code=upstream.status_code,media_type='application/zip',headers=response_headers,background=BackgroundTask(upstream.aclose))
        except (httpx.HTTPError,ValueError,TypeError):
            if upstream is not None:await upstream.aclose()
            raise HTTPException(503,'证据包传输暂不可用，请稍后重试') from None
