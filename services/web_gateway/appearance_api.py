"""Session-protected local appearance encoding, then authoritative Core retrieval."""
import asyncio
import hashlib
import json
import time
from typing import Literal
from uuid import UUID
from fastapi import HTTPException,Query,Request
from fastapi.responses import JSONResponse,StreamingResponse
from pydantic import BaseModel,ConfigDict,Field
from starlette.formparsers import MultiPartException
from services.ai_gateway.binary_input import MAX_IMAGE_BYTES,MAX_METADATA_BYTES,read_image_form
from .business_proxy import BusinessProxy


class ImageQuery(BaseModel):
    model_config=ConfigDict(extra='forbid')
    request_id:UUID
    media_type:Literal['image/jpeg','image/png']
    bbox_xyxy:list[float]=Field(min_length=4,max_length=4)
    target_type:Literal['person','vehicle']
    profile:str=Field(pattern=r'^[A-Za-z0-9_-]{1,80}$')
    profile_sha256:str=Field(pattern=r'^[a-f0-9]{64}$')
    cameras:list[str]=Field(default_factory=list,max_length=50)
    start:str|None=Field(default=None,max_length=40)
    end:str|None=Field(default=None,max_length=40)
    semantic_assistance:bool=Field(default=False,strict=True)


class Produce(BaseModel):
    model_config=ConfigDict(extra='forbid')
    limit:int=Field(default=100,ge=1,le=200,strict=True)


class Control(BaseModel):
    model_config=ConfigDict(extra='forbid')
    control:Literal['appearance_claims','appearance_production']
    enabled:bool=Field(strict=True)


class AppearanceUploadLimit:
    """Bound memory before parsing. The route checks the session before reading bytes."""
    def __init__(self,app):self.app=app;self.active=0
    async def __call__(self,scope,receive,send):
        if scope['type']!='http' or scope.get('path')!='/api/v1/appearance/queries' or scope.get('method')!='POST':return await self.app(scope,receive,send)
        maximum=MAX_IMAGE_BYTES+MAX_METADATA_BYTES;headers=dict(scope['headers'])
        try:length=int(headers.get(b'content-length',b'0'))
        except ValueError:length=-1
        if length<0 or length>maximum:return await JSONResponse({'detail':'图像请求大小无效或超过限制'},status_code=413)(scope,receive,send)
        if self.active>=1:return await JSONResponse({'detail':'以图搜图正在处理其他请求，请稍后重试'},status_code=429)(scope,receive,send)
        self.active+=1;received=0;scope['binary_request_started']=time.monotonic()
        async def bounded():
            nonlocal received
            message=await receive()
            if message['type']=='http.request':
                received+=len(message.get('body',b''))
                if received>maximum:scope['binary_body_too_large']=True;raise MultiPartException('image upload exceeds bound')
            return message
        started=False;finished=False
        async def tracked_send(message):
            nonlocal started,finished
            if message['type']=='http.response.start':started=True
            if message['type']=='http.response.body' and not message.get('more_body',False):finished=True
            await send(message)
        try:
            async with asyncio.timeout(360):await self.app(scope,bounded,tracked_send)
        except TimeoutError:
            if not started:await JSONResponse({'detail':'图像请求已超过总超时'},status_code=504)(scope,receive,send)
            elif not finished:await send({'type':'http.response.body','body':b'event: error\ndata: {"code":"appearance_timeout"}\n\n','more_body':False})
        finally:self.active-=1


def mount_appearance(app,cfg,authenticated,origin_ok):
    proxy=BusinessProxy(app,cfg,authenticated,origin_ok,feature='appearance')
    app.state.appearance_runner=None
    app.add_middleware(AppearanceUploadLimit)

    @app.get('/api/v1/appearance/capabilities')
    async def capabilities(request:Request):return await proxy.call(request,'GET','/internal/appearance/capabilities')

    @app.post('/api/v1/appearance/queries')
    async def create(request:Request):
        proxy.authorize(request,write=True)
        if not request.headers.get('content-type','').lower().startswith('multipart/form-data;'):raise HTTPException(415,'请上传 JPEG 或 PNG 图像')
        metadata,image=await read_image_form(request,ImageQuery,memory_only=True)
        if not image or not (image.startswith(b'\xff\xd8\xff') if metadata.media_type=='image/jpeg' else image.startswith(b'\x89PNG\r\n\x1a\n')):raise HTTPException(422,'图像内容与格式不符')
        from services.appearance.catalog import Catalog
        from services.appearance.process import EncodeRequest
        from services.specialist_service.runner import NativeRunner,NativeFailure,NativeTimeout
        try:
            definition=Catalog(cfg.get('appearance_catalog_file','')).definition(metadata.profile)
            if definition is None or definition.sha256!=metadata.profile_sha256:raise HTTPException(409,'外观模型配置已更新，请刷新后重试')
            if app.state.appearance_runner is None:app.state.appearance_runner=NativeRunner(cfg.get('appearance_model_dir',''),module='services.appearance.process')
        except (OSError,ValueError):raise HTTPException(503,'本地外观模型尚未完整配置') from None
        started=request.scope.get('binary_request_started',time.monotonic())
        async def execute():
            remaining=360-(time.monotonic()-started)
            encoded=await app.state.appearance_runner.recognize(definition,EncodeRequest(bbox_xyxy=metadata.bbox_xyxy,image_sha256=hashlib.sha256(image).hexdigest()),image,remaining)
            result=encoded['result']
            if result['profile_sha256']!=definition.sha256 or result['image_sha256']!=hashlib.sha256(image).hexdigest():raise ValueError('appearance encoding binding differs')
            from .appearance_semantics import describe_target
            remaining=360-(time.monotonic()-started)
            semantic=await describe_target(cfg,metadata,image,max(1,min(285,remaining-70)))
            semantic={'state':semantic['state'],'attributes':semantic['attributes'],'uncertainFields':semantic['uncertain_fields'],'modelId':semantic['model_id'],'promptVersion':semantic['prompt_version']}
            body=dict(requestId=str(metadata.request_id),profile=definition.profile,profileSha256=definition.sha256,imageSha256=result['image_sha256'],bbox=metadata.bbox_xyxy,imageWidth=result['image_width'],imageHeight=result['image_height'],targetType=metadata.target_type,cameras=metadata.cameras,start=metadata.start,end=metadata.end,vector=result['vector'],semantic=semantic)
            response=await proxy.call(request,'POST','/internal/appearance/queries',body=body,timeout=max(1,min(65,360-(time.monotonic()-started))))
            return response
        async def events():
            task=asyncio.create_task(execute())
            def event(value):
                kind=value['type'];payload=value['result'] if kind=='result' else {k:v for k,v in value.items() if k!='type'}
                return 'event: '+kind+'\ndata: '+json.dumps(payload,ensure_ascii=False,separators=(',',':'))+'\n\n'
            try:
                yield event({'type':'status','message':'正在提取目标外观与可见属性，检索已入库画面…' if metadata.semantic_assistance else '正在提取目标外观并检索已入库画面…'})
                while not task.done():
                    await asyncio.wait({task},timeout=10)
                    if not task.done():yield ': 处理中\n\n'
                response=await task;value=json.loads(response.body)
                if response.status_code==200:yield event({'type':'result','result':value})
                else:yield event({'type':'error','code':value.get('code','appearance_unavailable'),'status':response.status_code})
            except NativeTimeout:yield event({'type':'error','code':'appearance_timeout'})
            except (NativeFailure,ValueError,KeyError):yield event({'type':'error','code':'appearance_unavailable'})
            except HTTPException as error:yield event({'type':'error','code':'unauthorized' if error.status_code==401 else 'appearance_unavailable','status':error.status_code})
            finally:
                if not task.done():task.cancel()
                await asyncio.gather(task,return_exceptions=True)
        return StreamingResponse(events(),media_type='text/event-stream',headers={'Cache-Control':'no-store','X-Accel-Buffering':'no'})

    @app.get('/api/v1/appearance/queries/{identity}')
    async def page(identity:UUID,request:Request,offset:int=Query(default=0,ge=0,le=1000),limit:int=Query(default=10,ge=1,le=20)):
        return await proxy.call(request,'GET',f'/internal/appearance/queries/{identity}',params={'offset':offset,'limit':limit},timeout=65)

    @app.delete('/api/v1/appearance/queries/{identity}')
    async def close(identity:UUID,request:Request):return await proxy.call(request,'DELETE',f'/internal/appearance/queries/{identity}')

    @app.post('/api/admin/appearance/produce')
    async def produce(body:Produce,request:Request):return await proxy.call(request,'POST','/internal/appearance/produce',body=body.model_dump(),admin=True)

    @app.post('/api/admin/appearance/controls')
    async def control(body:Control,request:Request):return await proxy.call(request,'POST','/internal/appearance/controls',body=body.model_dump(),admin=True)

    @app.get('/api/admin/appearance/jobs')
    async def jobs(request:Request,limit:int=Query(default=20,ge=1,le=50),after:UUID|None=None):return await proxy.call(request,'GET','/internal/appearance/jobs',params={'limit':limit,**({'after':str(after)} if after else {})},admin=True)

    @app.post('/api/admin/appearance/jobs/{identity}/retry')
    async def retry(identity:UUID,request:Request):return await proxy.call(request,'POST',f'/internal/appearance/jobs/{identity}/retry',admin=True)
