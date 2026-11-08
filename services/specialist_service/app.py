"""Private native specialist endpoint using the existing multipart/SSE contract."""
import asyncio
from contextlib import asynccontextmanager
from dataclasses import dataclass
import hmac
import json
import logging
import os
from pathlib import Path
import re

from fastapi import FastAPI,HTTPException,Request
from fastapi.responses import JSONResponse,StreamingResponse

from services.ai_gateway.binary_input import BinaryInputMiddleware,read_image_form,remaining_budget
from services.video_worker.specialist_consumer import validate_result
from . import __version__
from .catalog import ModelCatalog,ModelUnavailable,artifact_bytes
from .contracts import RecognitionRequest,validate_image
from .runner import NativeRunner,NativeFailure,NativeTimeout,owned_result


@dataclass(frozen=True)
class Settings:
    token:str=''
    catalog_file:str='/model-catalog/specialist.json'
    models_root:str='/models'
    slots:int=1
    allowed_cameras:tuple=('recording-01','recording-02','recording-03','recording-04')

    def validate(self):
        if type(self.slots) is not int or not 1<=self.slots<=2 or not self.allowed_cameras or len(self.allowed_cameras)>50:
            raise ValueError('invalid native service capacity')
        if any(not re.fullmatch(r'[A-Za-z0-9_-]{1,100}',camera) for camera in self.allowed_cameras):raise ValueError('invalid camera scope')
        if self.token and (not self.token.isascii() or any(ord(char)<=32 or ord(char)==127 for char in self.token) or len(self.token)>4096):
            raise ValueError('invalid native service credential')

    @classmethod
    def from_env(cls):
        return cls(os.getenv('NATIVE_SPECIALIST_TOKEN',''),os.getenv('NATIVE_SPECIALIST_CATALOG','/model-catalog/specialist.json'),
                   os.getenv('NATIVE_SPECIALIST_MODELS_ROOT','/models'),int(os.getenv('NATIVE_SPECIALIST_SLOTS','1')),
                   tuple(os.getenv('NATIVE_SPECIALIST_CAMERAS','recording-01,recording-02,recording-03,recording-04').split(',')))


def event(value):return 'data: '+json.dumps(value,ensure_ascii=False,separators=(',',':'),allow_nan=False)+'\n\n'


def create_app(settings=None,runner=None):
    settings=settings or Settings.from_env();settings.validate();catalog=ModelCatalog(settings.catalog_file)
    @asynccontextmanager
    async def lifespan(application):
        application.state.runner=runner or (NativeRunner(settings.models_root) if Path(settings.models_root).is_dir() else None)
        try:yield
        finally:
            if application.state.runner is not None:await application.state.runner.close()
    app=FastAPI(lifespan=lifespan,docs_url=None,redoc_url=None,openapi_url=None)
    app.add_middleware(BinaryInputMiddleware,shared_token=settings.token,max_inflight=settings.slots,paths={'/v1/recognize'})

    @app.middleware('http')
    async def no_cache(request,call_next):
        response=await call_next(request);response.headers.setdefault('Cache-Control','no-store')
        response.headers['X-Content-Type-Options']='nosniff';return response

    def authorize(request):
        values=request.headers.getlist('authorization')
        if not settings.token:raise HTTPException(503,'专用服务尚未配置')
        if len(values)!=1 or not hmac.compare_digest(values[0].encode(),('Bearer '+settings.token).encode()):raise HTTPException(401,'未授权访问')

    @app.get('/healthz')
    def health():return {'status':'ok','version':__version__}

    @app.get('/readyz')
    async def ready():
        def check():
            if not settings.token or app.state.runner is None:raise ModelUnavailable('service not configured')
            document=catalog.snapshot();active=[model for model in document.models if model.active]
            if not active:raise ModelUnavailable('no active model')
            verified=set()
            for model in active:
                for artifact in (model.detector,model.recognizer):
                    if (artifact.file,artifact.sha256) not in verified:artifact_bytes(settings.models_root,artifact);verified.add((artifact.file,artifact.sha256))
            return {'status':'ready','version':__version__,'catalog_revision':document.revision}
        try:return await asyncio.to_thread(check)
        except (OSError,ValueError):return JSONResponse({'status':'unavailable'},status_code=503)

    @app.get('/v1/capabilities')
    async def capabilities(request:Request):
        authorize(request)
        try:document=await asyncio.to_thread(catalog.snapshot)
        except ModelUnavailable:raise HTTPException(503,'专用模型配置不可用') from None
        return {'version':__version__,'operations':['license_plate'],'streaming':True,'timeout_seconds':360,'max_inflight':settings.slots,
                'catalog_revision':document.revision,'models':[{'profile':model.profile,'model_id':model.model_id,'active':model.active} for model in document.models]}

    @app.post('/v1/recognize')
    async def recognize(request:Request):
        try:
            async with asyncio.timeout(min(30,remaining_budget(request,360))):metadata,image=await read_image_form(request,RecognitionRequest,memory_only=True)
        except TimeoutError:raise HTTPException(408,'图像上传已超时') from None
        if metadata.camera_id not in settings.allowed_cameras:raise HTTPException(403,'点位不在允许范围')
        if request.headers.getlist('idempotency-key')!=[metadata.job_id]:raise HTTPException(422,'请求编号与任务不符')
        values=request.headers.getlist('x-specialist-model');model_id=values[0] if len(values)==1 else None
        if len(values)>1 or values and not re.fullmatch('plate-[a-f0-9]{64}',model_id):raise HTTPException(422,'模型版本无效')
        try:
            validate_image(metadata,image)
            definition=await asyncio.to_thread(catalog.resolve,metadata.profile,model_id)
        except ModelUnavailable:raise HTTPException(503,'指定专用模型暂不可用') from None
        except ValueError:raise HTTPException(422,'图像与任务来源不一致') from None
        if app.state.runner is None:raise HTTPException(503,'原生推理尚未配置')
        budget=remaining_budget(request,360)
        if budget<=2:raise HTTPException(504,'专用识别预算已耗尽')

        async def stream():
            task=asyncio.create_task(app.state.runner.recognize(definition,metadata,image,budget))
            try:
                yield event({'type':'progress'})
                while not task.done():
                    done,_=await asyncio.wait({task},timeout=15)
                    if not done:yield event({'type':'progress'})
                output=task.result();result=validate_result(metadata.job(),output['result'])
                logging.getLogger(__name__).info('Native specialist complete: outcome=%s regions=%d',result['outcome'],len(result['regions']))
                yield event({'type':'result','result':result})
            except NativeTimeout:yield event({'type':'error','code':'specialist_timeout'})
            except (NativeFailure,OSError,ValueError,KeyError,TypeError):
                logging.getLogger(__name__).warning('Native specialist failed without a committed result')
                yield event({'type':'error','code':'specialist_unavailable'})
            finally:
                if not task.done():task.cancel()
                _,cancelled=await owned_result(asyncio.gather(task,return_exceptions=True))
                if cancelled:raise asyncio.CancelledError
        return StreamingResponse(stream(),media_type='text/event-stream',headers={'X-Accel-Buffering':'no','Cache-Control':'no-store, no-transform'})
    return app


app=create_app()
