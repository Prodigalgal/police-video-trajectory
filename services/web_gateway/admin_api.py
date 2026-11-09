"""Session-protected management proxy. Service credentials never reach browsers."""
import json
import asyncio
import threading
from pathlib import Path
import re
from urllib.parse import urlsplit
from uuid import UUID
from typing import Literal
import httpx
from fastapi import HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel,ConfigDict,Field
from .password_auth import password_hash


class CameraEdit(BaseModel):
    model_config=ConfigDict(extra='forbid')
    display_name: str=Field(min_length=1,max_length=200)
    location_key: str | None=Field(default=None,max_length=100)
    aliases: list[str]=Field(default_factory=list,max_length=18)
    enabled: bool=Field(strict=True)
    expected_revision: str=Field(pattern=r'^[a-f0-9]{64}$')


class RetryJob(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id: UUID
    expected_revision: int=Field(ge=0,strict=True)
    reason: str=Field(min_length=1,max_length=300)

class RetryModule(RetryJob):
    expected_revision: int=Field(ge=1,strict=True)

class UserCreate(BaseModel):
    model_config=ConfigDict(extra='forbid')
    username: str=Field(pattern=r'^[A-Za-z0-9_.@-]{3,80}$')
    display_name: str=Field(min_length=1,max_length=100)
    password: str=Field(min_length=8,max_length=128,repr=False)
    is_admin: bool=Field(default=False,strict=True)

class UserEdit(BaseModel):
    model_config=ConfigDict(extra='forbid')
    display_name: str=Field(min_length=1,max_length=100)
    enabled: bool=Field(strict=True)
    is_admin: bool=Field(strict=True)
    expected_version: int=Field(ge=1,strict=True)

class PasswordReset(BaseModel):
    model_config=ConfigDict(extra='forbid')
    password: str=Field(min_length=8,max_length=128,repr=False)
    expected_version: int=Field(ge=1,strict=True)

class UserDelete(BaseModel):
    model_config=ConfigDict(extra='forbid')
    expected_version: int=Field(ge=1,strict=True)


class ModelDefinition(BaseModel):
    model_config=ConfigDict(extra='forbid')
    display_name:str=Field(min_length=1,max_length=100)
    provider:str=Field(pattern=r'^[a-z][a-z0-9_-]{0,79}$')
    model:str=Field(min_length=1,max_length=150)
    protocol:Literal['chat_completions','responses']
    roles:list[Literal['text','vision']]=Field(min_length=1,max_length=2)
    image_transport:Literal['inline','file']='inline'
    output_mode:Literal['text','tool']='text'
    json_mode:bool=Field(default=False,strict=True)
    reasoning_effort:Literal['','none','minimal','low','medium','high','xhigh','max','auto']=''


class ModelSave(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    expected_version:int=Field(ge=0,le=9999,strict=True)
    definition:ModelDefinition


class ModelSelection(BaseModel):
    model_config=ConfigDict(extra='forbid')
    profile_id:UUID
    version:int=Field(ge=1,le=10000,strict=True)


class ModelBindings(BaseModel):
    model_config=ConfigDict(extra='forbid')
    text:ModelSelection | None
    vision:ModelSelection | None


class ModelBind(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    expected_revision:int=Field(ge=0,strict=True)
    bindings:ModelBindings


def mount_admin(app,cfg,authenticated,origin_ok):
    hash_slots=threading.BoundedSemaphore(2)
    def encode_password(value):
        if not hash_slots.acquire(blocking=False): raise HTTPException(429,'密码处理繁忙，请稍后重试')
        try: return password_hash(value)
        finally: hash_slots.release()

    def actor(request):
        return request.state.account['username'] if cfg.get('account_directory') else cfg.get('username','')

    def authorize(request,write=False):
        if write: origin_ok(request)
        if not authenticated(request): raise HTTPException(401,'请先登录')
        if cfg.get('account_directory') and not request.state.account['is_admin']: raise HTTPException(403,'当前账号没有管理权限')
        if not cfg.get('admin_token') or not re.fullmatch(r'[A-Za-z0-9_.@-]{1,100}',actor(request)):
            raise HTTPException(403,'管理功能尚未配置')

    async def forward(request,method,path,*,body=None,params=None):
        authorize(request,method!='GET')
        try:
            headers={'Authorization':'Bearer '+cfg['admin_token'],'X-Actor-Id':actor(request)}
            if cfg.get('account_directory'):headers['X-Actor-Revision']=str(request.state.account['credential_revision'])
            async with request.app.state.http.stream(method,cfg['core_url'].rstrip('/')+path,
                    headers=headers,json=body,params=params,timeout=35) as response:
                raw=bytearray()
                async for chunk in response.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw)>1024*1024: raise ValueError('management response exceeds bound')
                data=json.loads(raw)
                if not isinstance(data,dict): raise ValueError('invalid management response')
                if response.status_code!=200:
                    codes={'camera_unavailable','camera_revision_conflict','job_unavailable','processing_paused','state_revision_conflict','result_already_present','analysis_queue_full','invalid_admin_request','evidence_unavailable','username_exists','user_unavailable','user_revision_conflict','protected_account','last_admin_required','admin_required','invalid_user_request',
                           'module_job_unavailable','module_queue_full','module_recovery_conflict','invalid_module_source',
                           'model_provider_unavailable','model_providers_unavailable','model_request_conflict','model_profile_conflict',
                           'model_binding_conflict','model_registry_full','model_profile_unavailable','model_activation_forbidden',
                           'invalid_model_request','model_control_unavailable','invalid_business_request','business_unavailable'}
                    code=data.get('code') if data.get('code') in codes else 'admin_unavailable'
                    return JSONResponse({'code':code,'detail':'管理操作未完成'},status_code=response.status_code if response.status_code in {400,403,404,409,429} or response.status_code==422 and code=='invalid_module_source' else 503)
                if path.startswith('/internal/admin/users'):
                    fields={'username','display_name','enabled','is_admin','version','created_at','updated_at'}
                    def public_user(value):
                        if not isinstance(value,dict) or not isinstance(value.get('username'),str): raise ValueError('invalid user response')
                        return {key:value[key] for key in fields if key in value}
                    if method=='GET':
                        data={'items':[public_user(value) for value in data['items']], 'has_more':data['has_more'],'next_cursor':data.get('next_cursor')}
                    elif method=='DELETE': data={'removed':data.get('removed') is True}
                    else: data={'user':public_user(data['user'])}
                return JSONResponse(data,headers={'Cache-Control':'no-store'})
        except (httpx.HTTPError,ValueError,TypeError):
            raise HTTPException(503,'管理服务暂不可用') from None

    @app.get('/api/admin/config')
    async def configuration(request:Request):
        authorize(request)
        return {'username':actor(request),'capabilities':['overview','users.manage','cameras.edit','jobs.read','jobs.retry','processing.pause','models.read','models.manage']}

    @app.get('/api/admin/models/configuration')
    async def model_configuration(request:Request):return await forward(request,'GET','/internal/admin/models/configuration')

    @app.put('/api/admin/models/profiles/{profile_id}')
    async def save_model(profile_id:UUID,body:ModelSave,request:Request):
        return await forward(request,'PUT',f'/internal/admin/models/profiles/{profile_id}',body=body.model_dump(mode='json'))

    @app.post('/api/admin/models/bindings')
    async def bind_models(body:ModelBind,request:Request):
        return await forward(request,'POST','/internal/admin/models/bindings',body=body.model_dump(mode='json'))

    @app.get('/api/admin/users')
    async def users(request:Request,limit:int=50,after:str | None=None):
        if not 1<=limit<=100 or after is not None and not re.fullmatch(r'[a-z0-9_.@-]{3,80}',after): raise HTTPException(422,'用户分页无效')
        return await forward(request,'GET','/internal/admin/users',params={k:v for k,v in {'limit':limit,'after':after}.items() if v is not None})

    @app.post('/api/admin/users')
    async def create_user(body:UserCreate,request:Request):
        authorize(request,True)
        encoded=await asyncio.to_thread(encode_password,body.password)
        return await forward(request,'POST','/internal/admin/users',body={'username':body.username,'displayName':body.display_name,'passwordHash':encoded,'admin':body.is_admin})

    @app.put('/api/admin/users/{username}')
    async def edit_user(username:str,body:UserEdit,request:Request):
        if not re.fullmatch(r'[a-z0-9_.@-]{3,80}',username): raise HTTPException(422,'用户名无效')
        return await forward(request,'PUT','/internal/admin/users/'+username,body={'displayName':body.display_name,'enabled':body.enabled,'admin':body.is_admin,'expectedVersion':body.expected_version})

    @app.post('/api/admin/users/{username}/password')
    async def reset_password(username:str,body:PasswordReset,request:Request):
        authorize(request,True)
        if not re.fullmatch(r'[a-z0-9_.@-]{3,80}',username): raise HTTPException(422,'用户名无效')
        encoded=await asyncio.to_thread(encode_password,body.password)
        return await forward(request,'POST','/internal/admin/users/'+username+'/password',body={'passwordHash':encoded,'expectedVersion':body.expected_version})

    @app.delete('/api/admin/users/{username}')
    async def remove_user(username:str,body:UserDelete,request:Request):
        if not re.fullmatch(r'[a-z0-9_.@-]{3,80}',username): raise HTTPException(422,'用户名无效')
        return await forward(request,'DELETE','/internal/admin/users/'+username,body={'expectedVersion':body.expected_version})

    @app.get('/api/admin/overview')
    async def overview(request:Request): return await forward(request,'GET','/internal/admin/overview')

    @app.get('/api/admin/cameras')
    async def cameras(request:Request): return await forward(request,'GET','/internal/admin/cameras')

    @app.put('/api/admin/cameras/{camera}')
    async def update_camera(camera:str,body:CameraEdit,request:Request):
        if not re.fullmatch(r'[A-Za-z0-9_-]{1,100}',camera): raise HTTPException(422,'点位编号无效')
        return await forward(request,'PUT','/internal/admin/cameras/'+camera,body={'displayName':body.display_name,'locationKey':body.location_key,'aliases':body.aliases,'enabled':body.enabled,'expectedRevision':body.expected_revision})

    @app.get('/api/admin/jobs')
    async def jobs(request:Request,state:str='failed',camera:str | None=None,limit:int=20,after:UUID | None=None):
        if state not in {'all','queued','active','failed','completed'} or not 1<=limit<=50 or camera is not None and not re.fullmatch(r'[A-Za-z0-9_-]{1,100}',camera):
            raise HTTPException(422,'任务筛选无效')
        return await forward(request,'GET','/internal/admin/jobs',params={k:v for k,v in {'state':state,'camera':camera,'limit':limit,'after':str(after) if after else None}.items() if v is not None})

    @app.post('/api/admin/jobs/{job}/retry')
    async def retry(job:UUID,body:RetryJob,request:Request):
        return await forward(request,'POST',f'/internal/admin/jobs/{job}/retry',body={'requestId':str(body.request_id),'expectedRevision':body.expected_revision,'reason':body.reason})

    @app.get('/api/admin/module-jobs')
    async def module_jobs(request:Request,state:str='failed',camera:str | None=None,module_key:str | None=None,limit:int=20,after:UUID | None=None):
        if (state not in {'all','queued','active','failed','completed'} or not 1<=limit<=50 or
                camera is not None and not re.fullmatch(r'[A-Za-z0-9_-]{1,100}',camera) or
                module_key is not None and module_key not in {'person_attributes','vehicle_attributes','scene_context'}):
            raise HTTPException(422,'模块任务筛选无效')
        return await forward(request,'GET','/internal/admin/module-jobs',params={key:value for key,value in
            {'state':state,'camera':camera,'module_key':module_key,'limit':limit,'after':str(after) if after else None}.items() if value is not None})

    @app.post('/api/admin/module-jobs/{job}/retry')
    async def retry_module(job:UUID,body:RetryModule,request:Request):
        return await forward(request,'POST',f'/internal/admin/module-jobs/{job}/retry',body={'requestId':str(body.request_id),'expectedRevision':body.expected_revision,'reason':body.reason})

    @app.post('/api/admin/processing/pause')
    async def pause(request:Request): return await forward(request,'POST','/internal/admin/processing/pause',body={})

    @app.get('/api/admin/models')
    async def models(request:Request):
        authorize(request)
        root=Path(cfg.get('model_catalog_dir','/model-catalog'));saved=[]
        if root.is_dir():
            for path in sorted(root.glob('*.json'))[:50]:
                try:
                    if not path.resolve(strict=True).is_relative_to(root.resolve()): continue
                    with path.open('rb') as stream: raw=stream.read(65537)
                    if len(raw)>65536: continue
                    value=json.loads(raw)
                    if not isinstance(value,dict) or not isinstance(value.get('model'),str): continue
                    entry={key:value[key][:200] for key in ('provider','model','status') if isinstance(value.get(key),str)}
                    entry['enabled']=value.get('enabled') is True
                    profiles=value.get('profiles',[])
                    entry['roles']=sorted({p['role'] for p in profiles if isinstance(p,dict) and p.get('role') in {'text','vision'}})
                    entry['protocols']=sorted({p['protocol'] for p in profiles if isinstance(p,dict) and p.get('protocol') in {'chat_completions','responses'}})
                    parsed=urlsplit(value.get('base_url',''))
                    if parsed.scheme=='https' and parsed.hostname and not any((parsed.username,parsed.password,parsed.query,parsed.fragment)): entry['base_url']=value['base_url'][:300]
                    saved.append(entry)
                except (OSError,ValueError,TypeError,KeyError): continue
        configured=cfg.get('active_models',{})
        if isinstance(configured,str):
            try: configured=json.loads(configured)
            except (ValueError,TypeError): configured={}
        primary={}
        if isinstance(configured,dict):
            for role in ('text','vision'):
                value=configured.get(role,{})
                if isinstance(value,dict): primary[role]={k:value[k][:200] for k in ('provider','model','protocol') if isinstance(value.get(k),str)}
            if isinstance(configured.get('verified_at'),str): primary['verified_at']=configured['verified_at'][:40]
        return JSONResponse({'saved':saved,'primary':primary,'configuration_only':True},headers={'Cache-Control':'no-store'})
