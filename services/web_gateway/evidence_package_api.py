"""Bounded original-JPEG packages; preserves full collection metadata and version checks."""
import asyncio
import hashlib
import io
import json
import re
from uuid import UUID
import zipfile
import httpx
from fastapi import Request,Query,HTTPException
from fastapi.responses import Response
from .business_proxy import BusinessProxy

MAX_PACKAGE=64*1024*1024
MESSAGES={'collection_revision_conflict':'清单已有更新，请刷新后重新打包','package_source_changed':'来源线索已变化，请刷新清单','package_evidence_unavailable':'原始画面未通过核验，暂不能打包','collection_unavailable':'当前线索集已不可用','investigation_disabled':'证据打包功能尚未启用','unauthorized':'登录状态已失效，请重新登录'}


def mount_evidence_packages(app,cfg,authenticated,origin_ok):
    proxy=BusinessProxy(app,cfg,authenticated,origin_ok)
    package_lock=asyncio.Lock()

    async def manifest(request,identity,revision,part):
        response=await proxy.call(request,'GET',f'/internal/investigation/collections/{identity}/package-manifest',params={'revision':revision,'part':part})
        if response.status_code!=200:raise HTTPException(response.status_code,MESSAGES.get(json.loads(response.body).get('code'),'证据包暂不可用，请稍后重试'))
        wrapper=json.loads(response.body);payload=wrapper.get('manifest_payload','')
        if not isinstance(payload,str) or len(payload.encode())>2*1024*1024 or hashlib.sha256(payload.encode()).hexdigest()!=wrapper.get('manifest_sha256'):raise ValueError('package manifest digest differs')
        value=json.loads(payload)
        if value.get('schema_version')!='evidence-package-v1' or value['collection']['collection']['collection_id']!=str(identity) or value['collection']['collection']['revision']!=revision or value['part']!=part or not isinstance(value.get('images'),list) or len(value['images'])>16:raise ValueError('package scope differs')
        return wrapper,value

    @app.get('/api/v1/collections/{identity}/package-info')
    async def info(identity:UUID,request:Request,revision:int=Query(ge=1)):
        try:
            _,value=await manifest(request,identity,revision,1)
            from fastapi.responses import JSONResponse
            return JSONResponse({key:value[key] for key in ('parts','frame_count')},headers={'Cache-Control':'no-store'})
        except (ValueError,KeyError,TypeError):raise HTTPException(503,'无法核验打包来源') from None

    @app.get('/api/v1/collections/{identity}/package')
    async def package(identity:UUID,request:Request,revision:int=Query(ge=1),part:int=Query(default=1,ge=1,le=13)):
        proxy.authorize(request)
        if package_lock.locked():raise HTTPException(429,'已有证据包正在准备，请稍后重试')
        try:
            async with package_lock,asyncio.timeout(120):
                wrapper,value=await manifest(request,identity,revision,part)
                stream=io.BytesIO();total=0
                with zipfile.ZipFile(stream,'w',compression=zipfile.ZIP_STORED) as bundle:
                    bundle.writestr('manifest.json',wrapper['manifest_payload']);bundle.writestr('manifest.sha256',wrapper['manifest_sha256']+'  manifest.json\n')
                    for image in value['images']:
                        frame=str(UUID(image['frame_id']));filename='frames/'+frame+'.jpg'
                        if image.get('filename')!=filename or not re.fullmatch('[a-f0-9]{64}',image.get('sha256','')) or type(image.get('bytes')) is not int or not 0<image['bytes']<=8*1024*1024:raise ValueError('invalid package image')
                        async with app.state.http.stream('GET',cfg['core_url'].rstrip('/')+f'/api/v1/frames/{frame}/image',headers={'Authorization':'Bearer '+cfg['core_token']},timeout=30) as upstream:
                            if upstream.status_code!=200:raise HTTPException(409,'原始画面不可用，请重新读取线索')
                            raw=bytearray()
                            async for chunk in upstream.aiter_bytes():
                                raw.extend(chunk)
                                if len(raw)>image['bytes']:raise ValueError('package image exceeds expected size')
                        if len(raw)!=image['bytes'] or hashlib.sha256(raw).hexdigest()!=image['sha256']:raise ValueError('package image digest differs')
                        total+=len(raw)
                        if total>MAX_PACKAGE:raise HTTPException(409,'当前分卷超过容量，请减少线索后重新打包')
                        bundle.writestr(filename,raw)
                    _,current=await manifest(request,identity,revision,part)
                    if current['images']!=value['images']:raise HTTPException(409,'打包期间来源发生变化，请重新读取')
                raw=stream.getvalue()
                if len(raw)>MAX_PACKAGE+3*1024*1024:raise ValueError('package exceeds total bound')
                return Response(raw,media_type='application/zip',headers={'Content-Disposition':f'attachment; filename="evidence-{identity}-r{revision}-part{part}.zip"','X-Content-SHA256':hashlib.sha256(raw).hexdigest(),'Cache-Control':'no-store'})
        except (httpx.HTTPError,ValueError,KeyError,TypeError,TimeoutError):raise HTTPException(503,'证据包未能完整核验，请重新尝试') from None
