"""Bounded original-JPEG packages; preserves full collection metadata and version checks."""
import asyncio
import hashlib
import io
import json
import re
from contextlib import asynccontextmanager
from datetime import datetime
from uuid import UUID
import zipfile
import httpx
from fastapi import Request,Query,HTTPException
from fastapi.responses import Response
from .business_proxy import BusinessProxy

MAX_PACKAGE=64*1024*1024
PART_FRAMES=16
MAX_COLLECTION_ITEMS=200
MAX_IMAGE_BYTES=8*1024*1024
SOURCE_SCHEMA='evidence-package-source-v1'
MESSAGES={'collection_revision_conflict':'清单已有更新，请刷新后重新打包','package_source_changed':'来源线索已变化，请刷新清单','package_evidence_unavailable':'原始画面未通过核验，暂不能打包','collection_unavailable':'当前线索集已不可用','investigation_disabled':'证据打包功能尚未启用','unauthorized':'登录状态已失效，请重新登录'}


def source_snapshot(value):
    stable={key:entry for key,entry in value.items() if key not in {'prepared_at','prepared_by'}}
    payload=json.dumps({'schema_version':SOURCE_SCHEMA,'manifest':stable},ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()
    return hashlib.sha256(payload).hexdigest()


def package_time(value,optional=False):
    if optional and value is None:return
    if not isinstance(value,str) or not 0<len(value)<=80:raise ValueError('invalid package time')
    if datetime.fromisoformat(value.replace('Z','+00:00')).tzinfo is None:raise ValueError('package time requires timezone')


def preview_manifest(value,identity,revision,part):
    """Safe per-part metadata; keeps all business reviews for each deduplicated frame."""
    parts,frame_count=value['parts'],value['frame_count']
    if type(parts) is not int or type(frame_count) is not int or not 0<=frame_count<=MAX_COLLECTION_ITEMS or parts!=max(1,(frame_count+PART_FRAMES-1)//PART_FRAMES) or not 1<=part<=parts:raise ValueError('invalid package partition')
    nodes=value['collection']['nodes'];observations=value['observations'];images=value['images']
    if not isinstance(nodes,list) or len(nodes)>MAX_COLLECTION_ITEMS or not isinstance(observations,list) or len(observations)!=len(images):raise ValueError('invalid package rows')
    groups={};identities=set()
    for node in nodes:
        observation=str(UUID(node['observation_id']))
        if observation in identities or type(node.get('available')) is not bool:raise ValueError('invalid package observation')
        identities.add(observation)
        if not node['available']:continue
        frame=str(UUID(node['frame_id']))
        if type(node.get('record_version')) is not int or not 1<=node['record_version']<=9007199254740991 or node.get('target_type') not in {'person','vehicle'} or node.get('review_state') not in {'pending','retained','excluded'} or type(node.get('review_stale')) is not bool:raise ValueError('invalid package review')
        groups.setdefault(frame,[]).append(node)
    selected=list(groups)[(part-1)*PART_FRAMES:part*PART_FRAMES]
    if len(groups)!=frame_count or len(selected)!=len(images):raise ValueError('package frame count differs')
    frames=[];total=0
    package_time(value['prepared_at'])
    for index,(image,detail) in enumerate(zip(images,observations)):
        if not isinstance(image,dict) or not isinstance(detail,dict):raise ValueError('invalid package image record')
        frame=str(UUID(image['frame_id']));associated=groups.get(frame,[])
        if not associated or frame!=selected[index] or detail.get('frame_id')!=frame or detail.get('observation_id')!=associated[0]['observation_id'] or detail.get('record_version')!=associated[0]['record_version']:raise ValueError('package source differs')
        if image.get('filename')!='frames/'+frame+'.jpg' or not isinstance(image.get('sha256'),str) or not re.fullmatch('[a-f0-9]{64}',image['sha256']) or type(image.get('bytes')) is not int or not 0<image['bytes']<=MAX_IMAGE_BYTES:raise ValueError('invalid package image')
        evidence=detail.get('evidence',{})
        if not isinstance(evidence,dict) or evidence.get('available') is not True or evidence.get('capture_integrity') not in {'matched','legacy_without_digest'} or evidence.get('sha256')!=image['sha256'] or evidence.get('bytes')!=image['bytes']:raise ValueError('package evidence differs')
        camera=detail['camera_id'];name=detail['camera_name']
        if not isinstance(camera,str) or not re.fullmatch('[A-Za-z0-9_.-]{1,100}',camera) or not isinstance(name,str) or not 0<len(name)<=160 or any(node.get('camera_id')!=camera for node in associated):raise ValueError('package camera differs')
        for key in ('source_time','received_at'):package_time(detail.get(key),optional=True)
        origin=detail.get('source_time_origin')
        if origin is not None and (not isinstance(origin,str) or len(origin)>80):raise ValueError('invalid package clock origin')
        records=[{key:node[key] for key in ('observation_id','record_version','target_type','review_state','review_stale')} for node in associated]
        frames.append({'frame_id':frame,'camera_id':camera,'camera_name':name,'sha256':image['sha256'],'bytes':image['bytes'],'capture_integrity':evidence['capture_integrity'],
            'source_time':detail.get('source_time'),'source_time_origin':detail.get('source_time_origin'),'received_at':detail.get('received_at'),'observations':records})
        total+=image['bytes']
    reason='empty_package' if not frames else 'package_size_limit' if total>MAX_PACKAGE else None
    return {'schema_version':'evidence-package-preview-v1','collection_id':str(identity),'collection_revision':revision,'part':part,'parts':parts,
        'frame_count':frame_count,'frames_per_part':PART_FRAMES,'image_bytes':total,'source_sha256':source_snapshot(value),'prepared_at':value['prepared_at'],
        'download_allowed':reason is None,'download_reason':reason,'frames':frames}


def mount_evidence_packages(app,cfg,authenticated,origin_ok):
    proxy=BusinessProxy(app,cfg,authenticated,origin_ok)
    package_lock=asyncio.Lock()

    @asynccontextmanager
    async def preparation_slot():
        if package_lock.locked():raise HTTPException(429,'已有证据包正在核验或准备，请稍后重试')
        async with package_lock,asyncio.timeout(120):yield

    async def manifest(request,identity,revision,part):
        response=await proxy.call(request,'GET',f'/internal/investigation/collections/{identity}/package-manifest',params={'revision':revision,'part':part})
        if response.status_code!=200:raise HTTPException(response.status_code,MESSAGES.get(json.loads(response.body).get('code'),'证据包暂不可用，请稍后重试'))
        wrapper=json.loads(response.body);payload=wrapper.get('manifest_payload','')
        if not isinstance(payload,str) or len(payload.encode())>2*1024*1024 or hashlib.sha256(payload.encode()).hexdigest()!=wrapper.get('manifest_sha256'):raise ValueError('package manifest digest differs')
        value=json.loads(payload)
        if not isinstance(value,dict) or value.get('schema_version')!='evidence-package-v1' or value['collection']['collection']['collection_id']!=str(identity) or value['collection']['collection']['revision']!=revision or value['part']!=part or not isinstance(value.get('images'),list) or len(value['images'])>16:raise ValueError('package scope differs')
        return wrapper,value

    @app.get('/api/v1/collections/{identity}/package-preview')
    async def preview(identity:UUID,request:Request,revision:int=Query(ge=1,le=9007199254740991),part:int=Query(default=1,ge=1,le=13)):
        proxy.authorize(request)
        try:
            async with preparation_slot():_,value=await manifest(request,identity,revision,part)
            from fastapi.responses import JSONResponse
            return JSONResponse(preview_manifest(value,identity,revision,part),headers={'Cache-Control':'no-store'})
        except (ValueError,KeyError,TypeError,TimeoutError):raise HTTPException(503,'无法完整核验本卷来源，请刷新线索集') from None

    @app.get('/api/v1/collections/{identity}/package-info')
    async def info(identity:UUID,request:Request,revision:int=Query(ge=1)):
        proxy.authorize(request)
        try:
            async with preparation_slot():_,value=await manifest(request,identity,revision,1)
            from fastapi.responses import JSONResponse
            return JSONResponse({key:value[key] for key in ('parts','frame_count')},headers={'Cache-Control':'no-store'})
        except (ValueError,KeyError,TypeError,TimeoutError):raise HTTPException(503,'无法核验打包来源') from None

    @app.get('/api/v1/collections/{identity}/package')
    async def package(identity:UUID,request:Request,revision:int=Query(ge=1),part:int=Query(default=1,ge=1,le=13),expected_source_sha256:str|None=Query(default=None,pattern='^[a-f0-9]{64}$')):
        proxy.authorize(request)
        try:
            async with preparation_slot():
                wrapper,value=await manifest(request,identity,revision,part)
                source_sha=source_snapshot(value)
                if expected_source_sha256 is not None and expected_source_sha256!=source_sha:raise HTTPException(409,'预览后的线索或复核已变化，请重新核验本卷')
                stream=io.BytesIO();total=0
                with zipfile.ZipFile(stream,'w',compression=zipfile.ZIP_STORED) as bundle:
                    bundle.writestr('manifest.json',wrapper['manifest_payload']);bundle.writestr('manifest.sha256',wrapper['manifest_sha256']+'  manifest.json\n')
                    source_receipt={'schema_version':SOURCE_SCHEMA,'sha256':source_sha,'excluded_manifest_fields':['prepared_at','prepared_by'],
                        'canonicalization':{'encoding':'UTF-8','ensure_ascii':False,'sort_keys':True,'separators':[',',':'],'envelope_field':'manifest'}}
                    bundle.writestr('source-snapshot.json',json.dumps(source_receipt,ensure_ascii=False,sort_keys=True,separators=(',',':')))
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
                    if source_snapshot(current)!=source_sha:raise HTTPException(409,'打包期间来源或复核发生变化，请重新核验本卷')
                raw=stream.getvalue()
                if len(raw)>MAX_PACKAGE+3*1024*1024:raise ValueError('package exceeds total bound')
                return Response(raw,media_type='application/zip',headers={'Content-Disposition':f'attachment; filename="evidence-{identity}-r{revision}-part{part}.zip"','X-Content-SHA256':hashlib.sha256(raw).hexdigest(),'Cache-Control':'no-store'})
        except (httpx.HTTPError,ValueError,KeyError,TypeError,TimeoutError):raise HTTPException(503,'证据包未能完整核验，请重新尝试') from None
