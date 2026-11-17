import asyncio
import re
from typing import Literal
from uuid import UUID
from fastapi import Request,Query
from pydantic import BaseModel,ConfigDict,Field,model_validator
from .business_proxy import BusinessProxy


class LocationUpdate(BaseModel):
    model_config=ConfigDict(extra='forbid',allow_inf_nan=False)
    request_id:UUID
    expected_revision:int=Field(ge=0,strict=True)
    longitude:float|None=Field(default=None,ge=-180,le=180,strict=True)
    latitude:float|None=Field(default=None,ge=-90,le=90,strict=True)
    coordinate_system:Literal['GCJ-02']='GCJ-02'
    coordinate_source:Literal['configured','display_layout']|None=None
    address:str=Field(default='',max_length=500)
    reason:str=Field(min_length=1,max_length=300)

    @model_validator(mode='after')
    def coordinate_pair(self):
        if (self.longitude is None)!=(self.latitude is None):raise ValueError('coordinate pair required')
        return self


class MtmcQuery(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    observation_id:UUID
    expected_version:int=Field(ge=1,strict=True)
    strategy:str=Field(default='evidence-fusion-v1',pattern=r'^[A-Za-z0-9_-]{1,80}$')
    profiles:list[str]=Field(default_factory=list,max_length=3)
    camera_ids:list[str]=Field(default_factory=list,max_length=50)
    seconds:int=Field(default=600,ge=1,le=3600,strict=True)
    limit:int=Field(default=20,ge=1,le=50,strict=True)

    @model_validator(mode='after')
    def bounded_identifiers(self):
        for values,pattern in ((self.profiles,r'[A-Za-z0-9_-]{1,80}'),(self.camera_ids,r'[A-Za-z0-9_.-]{1,100}')):
            if len(values)!=len(set(values)) or any(not re.fullmatch(pattern,value) for value in values):
                raise ValueError('invalid or duplicate identifier')
        return self


def mount_investigation(app,cfg,authenticated,origin_ok):
    proxy=BusinessProxy(app,cfg,authenticated,origin_ok)
    prefix='/internal/investigation'

    @app.get('/api/v1/investigation/mtmc/capabilities')
    async def mtmc_capabilities(request:Request):
        return await proxy.call(request,'GET',prefix+'/mtmc/capabilities')

    @app.post('/api/v1/investigation/mtmc/queries')
    async def mtmc_create(body:MtmcQuery,request:Request):
        # httpx's read timeout is per chunk; bound the complete operation as well.
        try:
            async with asyncio.timeout(360):
                return await proxy.call(request,'POST',prefix+'/mtmc/queries',timeout=360,body={
                    'requestId':str(body.request_id),'observationId':str(body.observation_id),
                    'expectedVersion':body.expected_version,'strategy':body.strategy,
                    'profiles':body.profiles,'cameraIds':body.camera_ids,'seconds':body.seconds,'limit':body.limit})
        except TimeoutError:
            from fastapi import HTTPException
            raise HTTPException(503,'跨镜关联超时，请保留原条件重试') from None

    @app.get('/api/v1/investigation/mtmc/queries/{identity}')
    async def mtmc_read(identity:UUID,request:Request,offset:int=Query(default=0,ge=0,le=1000),limit:int=Query(default=20,ge=1,le=50),run:str|None=Query(default=None,max_length=161,pattern=r'^[A-Za-z0-9_-]{1,80}:[A-Za-z0-9_-]{1,80}$')):
        return await proxy.call(request,'GET',prefix+f'/mtmc/queries/{identity}',params={
            'offset':offset,'limit':limit,**({'run':run} if run is not None else {})})

    @app.delete('/api/v1/investigation/mtmc/queries/{identity}')
    async def mtmc_close(identity:UUID,request:Request):
        return await proxy.call(request,'DELETE',prefix+f'/mtmc/queries/{identity}')

    @app.get('/api/v1/investigation/capabilities')
    async def capabilities(request:Request):return await proxy.call(request,'GET',prefix+'/capabilities')

    @app.get('/api/v1/investigation/observations/{identity}/timeline')
    async def timeline(identity:UUID,request:Request,expected_version:int|None=Query(default=None,ge=1),before_ms:int=Query(default=60000,ge=0,le=120000),after_ms:int=Query(default=60000,ge=0,le=120000),max_gap_ms:int=Query(default=10000,ge=1000,le=30000)):
        return await proxy.call(request,'GET',prefix+f'/observations/{identity}/timeline',params={'before_ms':before_ms,'after_ms':after_ms,'max_gap_ms':max_gap_ms,**({'expected_version':expected_version} if expected_version else {})})

    @app.get('/api/v1/investigation/observations/{identity}/candidates')
    async def candidates(identity:UUID,request:Request,expected_version:int|None=Query(default=None,ge=1),seconds:int=Query(default=600,ge=1,le=3600),limit:int=Query(default=20,ge=1,le=50)):
        return await proxy.call(request,'GET',prefix+f'/observations/{identity}/candidates',params={'seconds':seconds,'limit':limit,**({'expected_version':expected_version} if expected_version else {})})

    @app.get('/api/v1/investigation/cameras')
    async def cameras(request:Request):return await proxy.call(request,'GET',prefix+'/cameras')

    @app.put('/api/admin/cameras/{camera}/location')
    async def update(camera:str,body:LocationUpdate,request:Request):
        if len(camera)>100 or not camera or any(character not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_-.' for character in camera):
            from fastapi import HTTPException
            raise HTTPException(422,'点位编号无效')
        return await proxy.call(request,'PUT',prefix+f'/cameras/{camera}/location',admin=True,body={'requestId':str(body.request_id),'expectedRevision':body.expected_revision,'longitude':body.longitude,'latitude':body.latitude,'coordinateSystem':body.coordinate_system,'coordinateSource':body.coordinate_source,'address':body.address,'reason':body.reason})

    @app.get('/api/v1/investigation/collections/{identity}/map')
    async def collection_map(identity:UUID,request:Request):return await proxy.call(request,'GET',prefix+f'/collections/{identity}/map')
