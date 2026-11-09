"""User-scoped dedicated jobs. No provider credentials or caller-supplied URLs."""
import json
from typing import Literal
from uuid import UUID
import httpx
from fastapi import HTTPException,Query,Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel,ConfigDict,Field,model_validator


class Create(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    input_manifest_sha256:str=Field(pattern=r'^[a-f0-9]{64}$')
    operation:Literal['text_ocr','license_plate']
    requested_intent:Literal['verify_vehicle','plate_query'] | None=None

    @model_validator(mode='after')
    def plate_intent_only(self):
        if self.requested_intent is not None and self.operation!='license_plate':raise ValueError('主动核验仅支持车牌识别')
        return self


class Retry(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    revision:int=Field(ge=1,strict=True)


class PlateReview(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    result_sha256:str=Field(pattern=r'^[a-f0-9]{64}$')
    region_index:int=Field(ge=0,le=99,strict=True)
    decision:Literal['confirm','revoke']
    expected_revision:int=Field(ge=0,strict=True)
    original_checked:bool=Field(strict=True)


class CandidateRecovery(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    expected_revision:int=Field(ge=1,strict=True)
    reason:str=Field(min_length=1,max_length=300)


class SpecialistControl(BaseModel):
    model_config=ConfigDict(extra='forbid')
    enabled:bool=Field(strict=True)
    expected_revision:int=Field(ge=1,strict=True)


def mount_specialists(app,cfg,authenticated,origin_ok):
    allowed={'specialist_disabled','specialist_not_configured','specialist_single_frame_required','specialist_queue_full','specialist_request_conflict','specialist_input_changed','specialist_job_unavailable','specialist_revision_conflict','specialist_result_unavailable','specialist_result_changed','invalid_specialist_request','invalid_specialist_result','input_unavailable','input_source_changed','input_manifest_changed','input_evidence_changed','input_evidence_unavailable','observation_unavailable','observation_revision_conflict'}
    allowed.update({'specialist_not_selected','specialist_selection_unavailable','specialist_selection_budget',
                    'specialist_module_disabled','processing_catalog_unavailable','processing_profile_conflict',
                    'specialist_result_reusable','invalid_plate_review','plate_review_conflict','plate_review_unavailable','plate_review_target_mismatch'})
    allowed.update({'candidate_unavailable','candidate_recovery_conflict','candidate_revision_conflict',
                    'candidate_expired','candidate_queue_full','invalid_candidate_request','admin_required'})
    allowed.add('invalid_specialist_cursor')
    allowed.update({'specialist_control_conflict','invalid_business_request','business_unavailable'})
    async def forward(request,method,suffix,body=None,params=None,*,admin=False):
        if method!='GET':origin_ok(request)
        if not authenticated(request):raise HTTPException(401,'请先登录')
        if not all(cfg.get(key) for key in ('specialist_jobs','analysis_inputs','account_directory','admin_token')):raise HTTPException(404,'专用识别尚未启用')
        account=request.state.account
        if admin and not account.get('is_admin'):raise HTTPException(403,'需要管理员权限')
        try:
            async with request.app.state.http.stream(method,cfg['core_url'].rstrip('/')+'/internal/specialist-jobs'+suffix,json=body,params=params,
                headers={'Authorization':'Bearer '+cfg['admin_token'],'X-Actor-Id':account['username'],'X-Actor-Revision':str(account['credential_revision'])},timeout=120) as response:
                raw=bytearray()
                async for chunk in response.aiter_bytes():
                    if len(raw)+len(chunk)>1024*1024:raise ValueError('specialist response exceeds bound')
                    raw.extend(chunk)
                value=json.loads(raw)
                if not isinstance(value,dict):raise ValueError('invalid specialist response')
                status=response.status_code
                if status!=200:value={'code':value.get('code') if value.get('code') in allowed else 'specialist_unavailable'}
                return JSONResponse(value,status_code=status if status in {200,400,401,403,404,409,422,429,503} else 503,headers={'Cache-Control':'no-store'})
        except (httpx.HTTPError,ValueError,TypeError):raise HTTPException(503,'专用识别服务暂不可用') from None
    @app.get('/api/v1/specialist-jobs/capabilities')
    async def capabilities(request:Request):return await forward(request,'GET','/capabilities')
    @app.get('/api/v1/observations/{identity}/specialist-jobs')
    async def observation_jobs(identity:UUID,request:Request,observation_version:int=Query(ge=1,le=9007199254740991),
                               limit:int=Query(default=10,ge=1,le=20),after:UUID|None=None):
        return await forward(request,'GET','/observations/'+str(identity),params={'observationVersion':observation_version,'limit':limit,**({'after':str(after)} if after else {})})
    @app.get('/api/admin/specialist-candidates')
    async def candidates(request:Request,camera_id:str|None=Query(default=None,min_length=1,max_length=100),
                         state:Literal['ready','active','waiting_source','waiting_configuration','waiting_budget','waiting_result','waiting_review','submitted','reused','skipped','invalid','expired','failed']|None=None,
                         limit:int=Query(default=20,ge=1,le=50),after:UUID|None=None):
        params={'limit':limit,**({'camera_id':camera_id} if camera_id else {}),**({'state':state} if state else {}),**({'after':str(after)} if after else {})}
        return await forward(request,'GET','/candidates',params=params,admin=True)
    @app.post('/api/admin/specialist-candidates/{identity}/retry')
    async def recover_candidate(identity:UUID,body:CandidateRecovery,request:Request):
        return await forward(request,'POST',f'/candidates/{identity}/retry',{'requestId':str(body.request_id),'expectedRevision':body.expected_revision,'reason':body.reason},admin=True)
    @app.post('/api/v1/analysis-inputs/{identity}/specialist-jobs')
    async def create(identity:UUID,body:Create,request:Request):return await forward(request,'POST','/inputs/'+str(identity),{'requestId':str(body.request_id),'inputManifestSha256':body.input_manifest_sha256,'operation':body.operation,**({'requestedIntent':body.requested_intent} if body.requested_intent else {})})
    @app.get('/api/v1/analysis-inputs/{identity}/specialist-jobs')
    async def listing(identity:UUID,request:Request,limit:int=Query(default=10,ge=1,le=50),after:UUID|None=None):return await forward(request,'GET','/inputs/'+str(identity),params={'limit':limit,**({'after':str(after)} if after else {})})
    @app.get('/api/v1/analysis-inputs/{identity}/specialist-selection')
    async def selection(identity:UUID,request:Request,intent:Literal['verify_vehicle','plate_query'] | None=None):return await forward(request,'GET','/inputs/'+str(identity)+'/selection',params={'intent':intent} if intent else None)
    @app.get('/api/admin/specialist-control')
    async def control_status(request:Request):return await forward(request,'GET','/control',admin=True)
    @app.post('/api/admin/specialist-control')
    async def control(body:SpecialistControl,request:Request):return await forward(request,'POST','/control',body.model_dump(mode='json'),admin=True)
    @app.get('/api/v1/specialist-jobs/{identity}/result')
    async def result(identity:UUID,request:Request):return await forward(request,'GET',f'/{identity}/result')
    @app.get('/api/v1/specialist-jobs/{identity}')
    async def read(identity:UUID,request:Request):return await forward(request,'GET',f'/{identity}')
    @app.post('/api/v1/specialist-jobs/{identity}/plate-review')
    async def review(identity:UUID,body:PlateReview,request:Request):return await forward(request,'POST',f'/{identity}/plate-review',{'requestId':str(body.request_id),'resultSha256':body.result_sha256,'regionIndex':body.region_index,'decision':body.decision,'expectedRevision':body.expected_revision,'originalChecked':body.original_checked})
    @app.post('/api/v1/specialist-jobs/{identity}/retry')
    async def retry(identity:UUID,body:Retry,request:Request):return await forward(request,'POST',f'/{identity}/retry',{'requestId':str(body.request_id),'revision':body.revision})
