"""Admin-only optional producer configuration; saving does not start capture."""
from typing import Literal
from uuid import UUID
from fastapi import Request,HTTPException
from pydantic import BaseModel,ConfigDict,Field,model_validator
from .business_proxy import BusinessProxy


class FeatureUpdate(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    expected_version:int=Field(ge=1,le=9007199254740991,strict=True)
    enabled:bool=Field(strict=True)
    plugin:Literal['bound_video_package_v1']
    parameters:dict[str,int]=Field(max_length=1)

    @model_validator(mode='before')
    @classmethod
    def parameters_valid(cls,value):
        if not isinstance(value,dict):return value
        parameters=value.get('parameters');plugin=value.get('plugin')
        if plugin=='bound_video_package_v1' and parameters=={}:return value
        raise ValueError('录像插件参数无效')


def mount_pipeline_features(app,cfg,authenticated,origin_ok):
    proxy=BusinessProxy(app,cfg,authenticated,origin_ok,feature='account_directory')

    @app.get('/api/admin/pipeline-features')
    async def get(request:Request):return await proxy.call(request,'GET','/internal/pipeline/features',admin=True)

    @app.put('/api/admin/pipeline-features/{feature}')
    async def put(feature:Literal['evidence_export'],body:FeatureUpdate,request:Request):
        if feature!='evidence_export':raise HTTPException(422,'插件与模块不匹配')
        return await proxy.call(request,'PUT','/internal/pipeline/features/'+feature,admin=True,body={
            'requestId':str(body.request_id),'expectedVersion':body.expected_version,'enabled':body.enabled,'plugin':body.plugin,'parameters':body.parameters})
