"""Select registered profiles without accepting endpoints or model files."""
import asyncio
import json
import logging
import re
from typing import Literal
from uuid import UUID
from fastapi import HTTPException,Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel,ConfigDict,Field,StrictStr,field_validator
from .business_proxy import BusinessProxy


class LocalBindings(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    expected_revision:int=Field(ge=0,lt=9007199254740991,strict=True)
    base_sha256:str=Field(pattern=r'^[a-f0-9]{64}$')
    bindings:dict[Literal['license_plate','text_ocr','appearance'],StrictStr|None]=Field(min_length=1,max_length=2)

    @field_validator('bindings')
    @classmethod
    def registered_ids(cls,value):
        if any(profile is not None and not re.fullmatch(r'[A-Za-z0-9_.-]{1,80}',profile) for profile in value.values()):raise ValueError('模型配置标识无效')
        return value


def mount_local_models(app,cfg,authenticated,origin_ok):
    proxy=BusinessProxy(app,cfg,authenticated,origin_ok,feature='account_directory')
    public='/api/admin/models/local-catalogs';internal='/internal/models/local-catalogs'
    assets=None;verification_lock=asyncio.Lock()

    async def installed_profiles():
        nonlocal assets
        from services.appearance.catalog import Catalog,InstalledModels
        async with verification_lock:
            def verify():
                nonlocal assets
                definitions=Catalog(cfg.get('appearance_catalog_file','')).current().profiles
                if assets is None:assets=InstalledModels(cfg.get('appearance_model_dir',''))
                return {definition.profile:definition.sha256 for definition in assets.available(definitions)}
            try:return await asyncio.to_thread(verify)
            except (OSError,ValueError,TypeError):
                logging.getLogger(__name__).warning('外观模型文件核验暂不可用')
                return {}

    @app.get(public)
    async def get(request:Request):
        response=await proxy.call(request,'GET',internal,admin=True)
        if response.status_code!=200:return response
        value=json.loads(response.body)
        for catalog in value.get('catalogs',[]):
            if catalog.get('id')=='appearance' and catalog.get('available'):
                installed=await installed_profiles()
                for profile in catalog['profiles']:profile['installed']=installed.get(profile['profile'])==profile['sha256']
        return JSONResponse(value,headers={'Cache-Control':'no-store'})

    @app.put(public+'/{kind}')
    async def put(kind:Literal['processing','appearance'],body:LocalBindings,request:Request):
        proxy.authorize(request,write=True,admin=True)
        expected={'license_plate','text_ocr'} if kind=='processing' else {'appearance'}
        if body.bindings.keys()!=expected or kind=='appearance' and body.bindings['appearance'] is None:raise HTTPException(422,'模型用途绑定无效')
        if kind=='appearance':
            response=await proxy.call(request,'GET',internal,admin=True)
            if response.status_code!=200:return response
            catalog=next((entry for entry in json.loads(response.body).get('catalogs',[]) if entry.get('id')=='appearance'),{})
            profile=next((entry for entry in catalog.get('profiles',[]) if entry.get('profile')==body.bindings['appearance']),None)
            installed=await installed_profiles()
            if not catalog.get('available') or profile is None or installed.get(profile['profile'])!=profile['sha256']:
                return JSONResponse({'code':'appearance_model_not_installed'},status_code=422,headers={'Cache-Control':'no-store'})
        return await proxy.call(request,'PUT',internal+'/'+kind,admin=True,body={'requestId':str(body.request_id),
            'expectedRevision':body.expected_revision,'baseSha256':body.base_sha256,'bindings':body.bindings})
