"""Artifact queue consumer. No model client, source decoding, or inference queue changes."""
import asyncio
import hashlib
import json
import os
import re
import time
import tempfile
from pathlib import Path
from urllib.parse import urlencode
import uuid
import zipfile

import httpx

from .client import ServiceError
from .events import log
from .export_store import ExportStore,ExportError,MAX_ARTIFACT
from .analysis_consumer import wait_for_poll
from . import __version__

RETRYABLE={408,429,500,502,503,504}
CONTRACTS=['evidence-export-v1','evidence-native-128-v1']
HEALTH_FILE=Path(tempfile.gettempdir())/'police-video-export-ready'


async def local_io(action,*args):
    pending=asyncio.create_task(asyncio.to_thread(action,*args))
    try:return await asyncio.shield(pending)
    except asyncio.CancelledError:
        await pending
        raise


def validate_plan(job):
    raw=job.get('manifest_payload')
    if not isinstance(raw,str) or len(raw.encode())>4*1024**2 or hashlib.sha256(raw.encode()).hexdigest()!=job.get('manifest_sha256'):raise ExportError('export_artifact_changed')
    plan=json.loads(raw);assets=plan.get('assets');sources=plan.get('media_sources')
    if plan.get('schema_version')!='evidence-export-v1' or not isinstance(assets,list) or not 1<=len(assets)<=12 or not isinstance(sources,list) or len(sources)>8:raise ExportError('export_artifact_changed')
    names={'manifest.json','manifest.sha256'};keys=set();total=0
    for asset in assets:
        identity=str(uuid.UUID(asset['identity']));kind=asset['kind'];key=kind+'-'+identity
        expected='frames/'+identity+'.jpg' if kind=='image' else 'media/'+kind+'/'+identity+'.mp4'
        limit={'image':8,'native':128,'normalized':64}.get(kind,0)*1024**2
        if (asset.get('filename')!=expected or asset.get('asset_key')!=key or key in keys or expected in names
            or type(asset.get('bytes')) is not int or not 0<asset['bytes']<=limit or not re.fullmatch('[a-f0-9]{64}',asset.get('sha256',''))):raise ExportError('export_artifact_changed')
        keys.add(key);names.add(expected);total+=asset['bytes']
    if total!=plan.get('asset_bytes') or total>512*1024**2:raise ExportError('export_artifact_changed')
    for source in sources:
        name='sources/'+source['asset_key']+'.json';payload=source['manifest_payload'].encode()
        if (source['asset_key'] not in keys or not source['asset_key'].startswith(('native-','normalized-')) or name!=source.get('filename') or name in names or len(payload)>256*1024 or hashlib.sha256(payload).hexdigest()!=source.get('sha256')):raise ExportError('export_artifact_changed')
        names.add(name);total+=len(payload)
    total+=len(raw.encode())+512+len(names)*256
    if total>MAX_ARTIFACT:raise ExportError('export_storage_full')
    return plan,total


async def write_asset(core,job,worker,asset,bundle):
    query=urlencode({'worker':worker,'generation':job['generation']})
    path=f"/internal/v2/exports/{job['export_id']}/assets/{asset['asset_key']}?{query}"
    try:
        async with core.client.stream('GET',core.base_url+path,headers={'Authorization':'Bearer '+core.token},timeout=120) as upstream:
            if upstream.status_code!=200:raise ExportError('export_source_changed' if upstream.status_code in {400,401,403,404,409,410,422} else 'export_asset_unavailable')
            if (upstream.headers.get('content-encoding') not in {None,'identity'} or upstream.headers.get('x-content-sha256')!=asset['sha256']
                or upstream.headers.get('content-length')!=str(asset['bytes'])):raise ExportError('export_artifact_changed')
            info=zipfile.ZipInfo(asset['filename']);info.compress_type=zipfile.ZIP_STORED;info.file_size=asset['bytes'];digest=hashlib.sha256();size=0
            with bundle.open(info,'w',force_zip64=True) as output:
                async for chunk in upstream.aiter_bytes(64*1024):
                    size+=len(chunk)
                    if size>asset['bytes']:raise ExportError('export_artifact_changed')
                    digest.update(chunk);await local_io(output.write,chunk)
            if size!=asset['bytes'] or digest.hexdigest()!=asset['sha256']:raise ExportError('export_artifact_changed')
    except httpx.HTTPError:raise ExportError('export_asset_unavailable') from None


async def build(settings,core,job,worker,store):
    plan,reservation=validate_plan(job);deadline=time.monotonic()+settings.export_job_timeout_seconds
    with store.exclusive():
        renewal=await core.post('/internal/v2/exports/'+job['export_id']+'/renew',{'workerId':worker,'generation':job['generation']})
        if renewal.get('renewed') is not True:raise ExportError('export_worker_error')
        with store.building(job,worker,reservation) as (partial,final):
            if not final.exists():
                await write_package(core,job,worker,plan,partial,deadline)
                if partial.stat().st_size>MAX_ARTIFACT:raise ExportError('export_storage_full')
                await local_io(store.publish,partial,final)
            result=await local_io(store.describe,final,deadline)
    body={'workerId':worker,'generation':job['generation'],'artifactSha256':result['sha256'],'artifactBytes':result['bytes']}
    for attempt in range(5):
        try:
            response=await core.post('/internal/v2/exports/'+job['export_id']+'/complete',body)
            if response.get('completed') is not True:raise ExportError('export_worker_error')
            return
        except ServiceError as failure:
            if failure.status not in RETRYABLE or attempt==4:raise
            await asyncio.sleep(min(10,2**attempt))


async def write_package(core,job,worker,plan,partial,deadline):
    with partial.open('xb') as stream:
        with zipfile.ZipFile(stream,'w',compression=zipfile.ZIP_STORED,allowZip64=True) as bundle:
            await local_io(bundle.writestr,'manifest.json',job['manifest_payload'])
            await local_io(bundle.writestr,'manifest.sha256',job['manifest_sha256']+'  manifest.json\n')
            for source in plan['media_sources']:await local_io(bundle.writestr,source['filename'],source['manifest_payload'])
            for asset in plan['assets']:
                if time.monotonic()>deadline:raise ExportError('export_timeout')
                await write_asset(core,job,worker,asset,bundle)
        await local_io(stream.flush);await local_io(os.fsync,stream.fileno())


async def reclaim_one(core,store,worker):
    record=None
    try:
        reply=await core.post('/internal/v2/exports/reclaims/claim',{'workerId':worker});record=reply.get('reclaim')
        if not record:return
        body={'workerId':worker,'token':str(uuid.UUID(record['token']))}
        path=f"/internal/v2/exports/{str(uuid.UUID(record['export_id']))}/reclaims/{record['generation']}"
        with store.exclusive():
            verified=await core.post(path+'/verify',body)
            if verified.get('reclaim_allowed') is not True:raise ExportError('export_worker_error')
            await local_io(store.reclaim,record)
            for attempt in range(3):
                try:
                    reply=await core.post(path+'/complete',body)
                    if reply.get('reclaimed') is not True:raise ExportError('export_worker_error')
                    log(json.dumps({'event':'evidence_export_reclaimed','export_id':record['export_id'],'generation':record['generation']}));return
                except ServiceError as failure:
                    if failure.status not in RETRYABLE or attempt==2:raise
                    await asyncio.sleep(2**attempt)
    except (ServiceError,ExportError,OSError,ValueError,KeyError,TypeError,TimeoutError) as error:
        log(json.dumps({'event':'evidence_export_reclaim_deferred','export_id':record.get('export_id') if record else None,'code':getattr(error,'code','export_worker_error'),'http_status':getattr(error,'status',None)}))


async def heartbeat(core,job,worker,deadline):
    loop=asyncio.get_running_loop()
    while True:
        await asyncio.sleep(25)
        while True:
            started=loop.time();remaining=deadline[0]-started
            if remaining<=0:raise ServiceError(409)
            try:await asyncio.wait_for(core.post('/internal/v2/exports/'+job['export_id']+'/renew',{'workerId':worker,'generation':job['generation']}),remaining)
            except ServiceError as error:
                if error.status not in RETRYABLE:raise
                await asyncio.sleep(min(5,max(0,deadline[0]-loop.time())))
            else:deadline[0]=started+110;break


async def consumer_presence(settings,core,worker,stop):
    while not stop.is_set():
        try:
            response=await asyncio.wait_for(core.post('/internal/v2/exports/heartbeat',{'workerId':worker,'contracts':CONTRACTS,'quotaBytes':settings.export_bytes,'version':__version__}),10)
            if response.get('accepted') is not True:raise ValueError('export heartbeat differs')
            HEALTH_FILE.write_text(str(time.monotonic()),encoding='ascii')
        except (ServiceError,TimeoutError,ValueError,OSError) as error:
            log(json.dumps({'event':'evidence_export_consumer_unavailable','http_status':getattr(error,'status',None),'reason':type(error).__name__}))
        try:await asyncio.wait_for(stop.wait(),15)
        except TimeoutError:pass


async def export_slot(settings,core,slot,stop):
    worker=f'{settings.worker_id}-export-{slot}'
    presence=asyncio.create_task(consumer_presence(settings,core,worker,stop))
    try:await export_loop(settings,core,slot,stop)
    finally:
        presence.cancel();await asyncio.gather(presence,return_exceptions=True)
        try:HEALTH_FILE.unlink(missing_ok=True)
        except OSError:log(json.dumps({'event':'evidence_export_readiness_cleanup_deferred'}))


async def export_loop(settings,core,slot,stop):
    worker=f'{settings.worker_id}-export-{slot}';store=ExportStore(settings.export_dir,settings.export_bytes)
    while not stop.is_set():
        job=None;work=renew=shutdown=None;completed=False;code='export_interrupted'
        try:
            await asyncio.wait_for(reclaim_one(core,store,worker),45)
            started=asyncio.get_running_loop().time()
            reply=await core.post('/internal/v2/exports/claim',{'workerId':worker,'contracts':CONTRACTS});job=reply.get('job')
            if not job:
                if reply.get('module_enabled') is False:
                    try:await asyncio.wait_for(stop.wait(),10)
                    except TimeoutError:pass
                else:await wait_for_poll(stop)
                continue
            deadline=[started+110]
            work=asyncio.create_task(asyncio.wait_for(build(settings,core,job,worker,store),settings.export_job_timeout_seconds))
            renew=asyncio.create_task(heartbeat(core,job,worker,deadline));shutdown=asyncio.create_task(stop.wait())
            done,_=await asyncio.wait({work,renew,shutdown},return_when=asyncio.FIRST_COMPLETED)
            if work in done:work.result();completed=True
            elif renew in done:renew.result()
        except (ServiceError,ExportError,OSError,ValueError,KeyError,TypeError,TimeoutError) as error:
            code=error.code if isinstance(error,ExportError) else 'export_timeout' if isinstance(error,TimeoutError) else 'export_worker_error'
            log(json.dumps({'event':'evidence_export_deferred','export_id':job.get('export_id') if job else None,'code':code,'http_status':getattr(error,'status',None)}))
        finally:
            tasks=[task for task in (work,renew,shutdown) if task is not None]
            for task in tasks:
                if not task.done():task.cancel()
            if tasks:await asyncio.gather(*tasks,return_exceptions=True)
            if job and not completed:
                try:await asyncio.wait_for(core.post('/internal/v2/exports/'+job['export_id']+'/fail',{'workerId':worker,'generation':job['generation'],'errorCode':code}),10)
                except (ServiceError,TimeoutError):log(json.dumps({'event':'evidence_export_release_unknown','export_id':job['export_id']}))
        if not completed:await wait_for_poll(stop)
