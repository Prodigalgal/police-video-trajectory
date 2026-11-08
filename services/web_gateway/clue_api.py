"""Business clue collections for signed-in users, independently of training tools."""
import hashlib
import json
import re
from typing import Literal
from uuid import UUID
import httpx
from fastapi import HTTPException,Request
from fastapi.responses import JSONResponse,Response
from pydantic import BaseModel,ConfigDict,Field


class Create(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    title:str=Field(min_length=1,max_length=160)
    note:str=Field(default='',max_length=2000)


class Edit(BaseModel):
    model_config=ConfigDict(extra='forbid')
    title:str=Field(min_length=1,max_length=160)
    note:str=Field(default='',max_length=2000)
    state:Literal['open','closed']
    expected_revision:int=Field(ge=1,strict=True)


class Add(BaseModel):
    model_config=ConfigDict(extra='forbid')
    expected_revision:int=Field(ge=1,strict=True)
    observation_version:int=Field(ge=1,strict=True)


class Review(Add):
    state:Literal['pending','retained','excluded']
    note:str=Field(default='',max_length=2000)
    evidence_sha256:str|None=Field(default=None,pattern=r'^[a-f0-9]{64}$')


class Link(BaseModel):
    model_config=ConfigDict(extra='forbid')
    expected_revision:int=Field(ge=1,strict=True)
    reason:str=Field(min_length=1,max_length=1000)
    state:Literal['pending','accepted','rejected']


def mount_clues(app,cfg,authenticated,origin_ok):
    def enabled():return bool(cfg.get('clue_collections') and cfg.get('account_directory') and cfg.get('admin_token'))
    def authorize(request,write=False):
        if write:origin_ok(request)
        if not authenticated(request):raise HTTPException(401,'请先登录')
        if not enabled():raise HTTPException(404,'线索整理功能尚未启用')

    async def forward(request,method,suffix='',body=None,params=None,export=None):
        authorize(request,method!='GET')
        account=request.state.account
        try:
            async with request.app.state.http.stream(method,cfg['core_url'].rstrip('/')+'/internal/clues/collections'+suffix,
                json=body,params=params,headers={'Authorization':'Bearer '+cfg['admin_token'],'X-Actor-Id':account['username'],
                    'X-Actor-Revision':str(account['credential_revision'])},timeout=40) as upstream:
                raw=bytearray()
                async for chunk in upstream.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw)>2*1024*1024:raise ValueError('clue response exceeds bound')
                if upstream.status_code!=200:
                    value=json.loads(raw);code=value.get('code')
                    allowed={'collection_unavailable','collection_revision_conflict','collection_request_conflict','collection_closed','collection_full',
                        'observation_unavailable','observation_revision_conflict','item_unavailable','review_evidence_changed','collection_export_too_large','invalid_clue_request'}
                    return JSONResponse({'code':code if code in allowed else 'clues_unavailable'},status_code=upstream.status_code if upstream.status_code in {400,401,403,404,409,422,429} else 503)
                if export:
                    collection,revision,format=export;digest=hashlib.sha256(raw).hexdigest()
                    if upstream.headers.get('x-content-sha256')!=digest:raise ValueError('export digest differs')
                    return Response(bytes(raw),media_type='text/csv; charset=utf-8' if format=='csv' else 'application/json',
                        headers={'Content-Disposition':f'attachment; filename="clues-{collection}-r{revision}.{format}"','X-Content-SHA256':digest,'Cache-Control':'no-store'})
                value=json.loads(raw)
                if not isinstance(value,dict):raise ValueError('invalid clue response')
                return JSONResponse(value,headers={'Cache-Control':'no-store'})
        except (httpx.HTTPError,ValueError,TypeError):raise HTTPException(503,'线索整理服务暂不可用') from None

    @app.get('/api/v1/collections')
    async def list_collections(request:Request,state:Literal['all','open','closed']='open',limit:int=20,after:UUID|None=None):
        if not 1<=limit<=50:raise HTTPException(422,'分页参数无效')
        return await forward(request,'GET',params={'state':state,'limit':limit,**({'after':str(after)} if after else {})})

    @app.post('/api/v1/collections')
    async def create(body:Create,request:Request):
        return await forward(request,'POST',body={'requestId':str(body.request_id),'title':body.title,'note':body.note})

    @app.get('/api/v1/collections/{collection}')
    async def read(collection:UUID,request:Request):return await forward(request,'GET','/'+str(collection))

    @app.put('/api/v1/collections/{collection}')
    async def edit(collection:UUID,body:Edit,request:Request):
        return await forward(request,'PUT','/'+str(collection),body={'title':body.title,'note':body.note,'state':body.state,'expectedRevision':body.expected_revision})

    @app.put('/api/v1/collections/{collection}/items/{observation}')
    async def add(collection:UUID,observation:UUID,body:Add,request:Request):
        return await forward(request,'PUT',f'/{collection}/items/{observation}',body={'expectedRevision':body.expected_revision,'observationVersion':body.observation_version})

    @app.put('/api/v1/collections/{collection}/items/{observation}/review')
    async def review(collection:UUID,observation:UUID,body:Review,request:Request):
        if body.state!='pending' and body.evidence_sha256 is None:raise HTTPException(422,'请先读取原始关键帧')
        return await forward(request,'PUT',f'/{collection}/items/{observation}/review',body={'expectedRevision':body.expected_revision,'observationVersion':body.observation_version,
            'evidenceSha256':body.evidence_sha256,'state':body.state,'note':body.note})

    @app.put('/api/v1/collections/{collection}/links/{source}/{target}')
    async def link(collection:UUID,source:UUID,target:UUID,body:Link,request:Request):
        if source==target:raise HTTPException(422,'请选择两条不同线索')
        return await forward(request,'PUT',f'/{collection}/links/{source}/{target}',body={'expectedRevision':body.expected_revision,'reason':body.reason,'state':body.state})

    @app.get('/api/v1/collections/{collection}/export')
    async def export(collection:UUID,request:Request,revision:int,format:Literal['json','csv']='json'):
        if revision<1:raise HTTPException(422,'清单版本无效')
        return await forward(request,'GET',f'/{collection}/export',params={'revision':revision,'format':format},export=(collection,revision,format))
