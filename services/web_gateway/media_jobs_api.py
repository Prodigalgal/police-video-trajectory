"""Optional authenticated archive production tasks; no inference submission."""
import json
from uuid import UUID
import httpx
from fastapi import HTTPException,Query,Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel,ConfigDict,Field


class Create(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    source_id:UUID
    start_ms:int=Field(ge=0,le=9007199254710991,strict=True)
    end_ms:int=Field(ge=2000,le=9007199254740991,strict=True)


class Retry(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    revision:int=Field(ge=1,strict=True)


class SessionCreate(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    recording_manifest_sha256:str=Field(pattern=r'^[a-f0-9]{64}$')


def mount_media_jobs(app,cfg,authenticated,origin_ok):
    allowed={'media_jobs_disabled','media_jobs_full','media_request_conflict','media_source_unavailable','media_source_changed',
        'media_job_unavailable','media_revision_conflict','invalid_media_job_request','media_jobs_unavailable',
        'media_session_changed','media_session_unavailable','media_frame_limit','recording_file_unavailable','recording_pixels_changed','recording_manifest_changed'}
    async def forward(request,method,suffix='',body=None,params=None,frame=None):
        if method!='GET':origin_ok(request)
        if not authenticated(request):raise HTTPException(401,'请先登录')
        if not cfg.get('media_jobs'):raise HTTPException(404,'录像制作任务尚未启用')
        try:
            path=f'/api/v1/frames/{frame}/media-jobs' if frame else '/api/v1/media-jobs'+suffix
            async with request.app.state.http.stream(method,cfg['core_url'].rstrip('/')+path,
                json=body,params=params,headers={'Authorization':'Bearer '+cfg['core_token']},timeout=120) as response:
                raw=bytearray()
                async for chunk in response.aiter_bytes():
                    if len(raw)+len(chunk)>1024*1024:raise ValueError('media task response exceeded bound')
                    raw.extend(chunk)
                value=json.loads(raw)
                if not isinstance(value,dict):raise ValueError('invalid media task response')
                if response.status_code!=200:
                    value={'code':value.get('code') if value.get('code') in allowed else 'media_jobs_unavailable'}
                return JSONResponse(value,status_code=response.status_code if response.status_code in {200,400,401,403,404,409,422,429} else 503,headers={'Cache-Control':'no-store'})
        except (httpx.HTTPError,ValueError,TypeError):raise HTTPException(503,'录像制作任务服务暂不可用') from None
    @app.post('/api/v1/media-jobs')
    async def create(body:Create,request:Request):
        if not 2000<=body.end_ms-body.start_ms<=30000:raise HTTPException(422,'制作窗口必须为 2–30 秒')
        return await forward(request,'POST',body={'requestId':str(body.request_id),'sourceId':str(body.source_id),'startMs':body.start_ms,'endMs':body.end_ms})
    @app.post('/api/v1/frames/{frame_id}/media-jobs')
    async def create_session(frame_id:UUID,body:SessionCreate,request:Request):
        return await forward(request,'POST',frame=frame_id,body={'requestId':str(body.request_id),'recordingManifestSha256':body.recording_manifest_sha256})
    @app.get('/api/v1/frames/{frame_id}/media-jobs')
    async def frame_jobs(frame_id:UUID,request:Request,limit:int=Query(default=10,ge=1,le=50),after:UUID|None=None):
        return await forward(request,'GET',frame=frame_id,params={'limit':limit,**({'after':str(after)} if after else {})})
    @app.get('/api/v1/media-jobs')
    async def listing(request:Request,camera_id:str=Query(min_length=1,max_length=100),limit:int=Query(default=20,ge=1,le=50),after:UUID|None=None):
        return await forward(request,'GET',params={'camera_id':camera_id,'limit':limit,**({'after':str(after)} if after else {})})
    @app.get('/api/v1/media-jobs/{identity}')
    async def read(identity:UUID,request:Request):return await forward(request,'GET','/'+str(identity))
    @app.post('/api/v1/media-jobs/{identity}/retry')
    async def retry(identity:UUID,body:Retry,request:Request):return await forward(request,'POST',f'/{identity}/retry',body={'requestId':str(body.request_id),'revision':body.revision})
