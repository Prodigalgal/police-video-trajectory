"""Select registered profiles without accepting endpoints or model files."""
import re
from typing import Literal
from uuid import UUID
from fastapi import HTTPException,Request
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

    @app.get(public)
    async def get(request:Request):return await proxy.call(request,'GET',internal,admin=True)

    @app.put(public+'/{kind}')
    async def put(kind:Literal['processing','appearance'],body:LocalBindings,request:Request):
        expected={'license_plate','text_ocr'} if kind=='processing' else {'appearance'}
        if body.bindings.keys()!=expected or kind=='appearance' and body.bindings['appearance'] is None:raise HTTPException(422,'模型用途绑定无效')
        return await proxy.call(request,'PUT',internal+'/'+kind,admin=True,body={'requestId':str(body.request_id),
            'expectedRevision':body.expected_revision,'baseSha256':body.base_sha256,'bindings':body.bindings})
