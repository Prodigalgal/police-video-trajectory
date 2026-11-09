"""Bounded authenticated multipart intake, shared by the binary vision routes."""
import hmac
import time
from contextlib import asynccontextmanager

from fastapi import HTTPException, Request
from pydantic import ValidationError
from starlette.datastructures import UploadFile
from starlette.exceptions import HTTPException as StarletteHTTPException
from starlette.formparsers import MultiPartException,MultiPartParser
from starlette.responses import JSONResponse

MAX_IMAGE_BYTES=8 * 1024 * 1024
MAX_METADATA_BYTES=128 * 1024
BINARY_PATHS={'/internal/v3/analyze-frame','/internal/v3/analyze-targets','/internal/v1/describe-appearance'}


class BinaryInputMiddleware:
    """Limit bodies before form parsing and hold a process-local memory permit."""
    def __init__(self,app,shared_token,max_inflight,paths=None):
        self.app=app
        self.token=shared_token.encode()
        self.limit=max(1,max_inflight)
        self.active=0
        self.paths=frozenset(BINARY_PATHS if paths is None else paths)

    async def __call__(self,scope,receive,send):
        if scope['type']!='http' or scope.get('path') not in self.paths:
            return await self.app(scope,receive,send)
        headers=dict(scope['headers'])
        if not self.token:
            return await JSONResponse({'detail':'网关访问令牌尚未配置'},status_code=503)(scope,receive,send)
        if (sum(k==b'authorization' for k,_ in scope['headers'])!=1
                or not hmac.compare_digest(headers.get(b'authorization',b''),b'Bearer '+self.token)):
            return await JSONResponse({'detail':'未授权访问'},status_code=401,headers={'WWW-Authenticate':'Bearer'})(scope,receive,send)
        if self.active>=self.limit:
            return await JSONResponse({'detail':'图像接收繁忙，请稍后重试'},status_code=503)(scope,receive,send)
        try:
            length=int(headers.get(b'content-length',b'0'))
            if length<0:raise ValueError()
        except ValueError:
            return await JSONResponse({'detail':'请求大小无效'},status_code=422)(scope,receive,send)
        maximum=MAX_IMAGE_BYTES+MAX_METADATA_BYTES
        if length>maximum:
            return await JSONResponse({'detail':'图像请求超过大小限制'},status_code=413)(scope,receive,send)
        if not headers.get(b'content-type',b'').lower().startswith(b'multipart/form-data;'):
            return await JSONResponse({'detail':'需要 multipart 图像请求'},status_code=415)(scope,receive,send)
        self.active+=1
        scope['binary_request_started']=time.monotonic()
        received=0
        async def bounded_receive():
            nonlocal received
            message=await receive()
            if message['type']=='http.request':
                received+=len(message.get('body',b''))
                if received>maximum:
                    scope['binary_body_too_large']=True
                    # Starlette closes spooled files when this parser exception
                    # interrupts a multipart body, including chunked requests.
                    raise MultiPartException('image request exceeds its limit')
            return message
        try:
            return await self.app(scope,bounded_receive,send)
        finally:
            self.active-=1


@asynccontextmanager
async def image_form(request,memory_only):
    if not memory_only:
        async with request.form(max_files=1,max_fields=1,max_part_size=MAX_METADATA_BYTES) as form:yield form
        return
    # The intake middleware bounds the complete body before this parser runs.
    # Buffering it also makes parsing independent of disconnects while a form
    # owns files. Every permitted file fits its in-memory spool, never disk.
    try:body=await request.body()
    except MultiPartException:
        raise HTTPException(413 if request.scope.get('binary_body_too_large') else 400,'图像请求超过大小限制或格式无效') from None
    async def chunks():yield body
    parser=MultiPartParser(request.headers,chunks(),max_files=1,max_fields=1,max_part_size=MAX_METADATA_BYTES)
    parser.spool_max_size=MAX_IMAGE_BYTES+MAX_METADATA_BYTES+1
    try:form=await parser.parse()
    except MultiPartException:raise HTTPException(400,'图像表单无效') from None
    try:yield form
    finally:await form.close()


async def read_image_form(request:Request,metadata_model,*,memory_only=False):
    try:
        async with image_form(request,memory_only) as form:
            if set(form)!={'file','metadata'} or len(form.getlist('file'))!=1 or len(form.getlist('metadata'))!=1:
                raise HTTPException(422,'需要一份图像和元数据')
            upload=form['file'];raw=form['metadata']
            if not isinstance(upload,UploadFile) or not isinstance(raw,str) or len(raw.encode())>MAX_METADATA_BYTES:
                raise HTTPException(422,'图像或元数据无效')
            try:metadata=metadata_model.model_validate_json(raw)
            except ValidationError:
                raise HTTPException(422,'图像元数据无效') from None
            if upload.content_type not in {None,'application/octet-stream',metadata.media_type}:
                raise HTTPException(422,'图像类型与元数据不符')
            image=await upload.read(MAX_IMAGE_BYTES+1)
            if len(image)>MAX_IMAGE_BYTES:raise HTTPException(413,'图像超过大小限制')
            return metadata,image
    except StarletteHTTPException:
        if request.scope.get('binary_body_too_large'):
            raise HTTPException(413,'图像请求超过大小限制') from None
        raise


def remaining_budget(request,seconds):
    started=request.scope.get('binary_request_started',time.monotonic())
    remaining=seconds-(time.monotonic()-started)
    if remaining<=0:raise HTTPException(504,'图像请求已超过总超时')
    return remaining
