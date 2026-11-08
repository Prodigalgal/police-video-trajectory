"""Independent media worker: fenced queue, bounded child lifetime, no model client."""
import asyncio
from dataclasses import replace
import json
from pathlib import Path
import re
import sys
from .analysis_consumer import execute_owned,job_event,record_failure,wait_for_poll
from .client import ServiceError
from .media_producer import validate_job

ROUTE='/internal/v1/media-jobs'
FAILURES={'media_source_changed','media_source_unavailable','media_input_invalid','media_capacity_reached','media_store_busy','media_worker_error','media_timeout'}


async def encode(settings,job):
    validate_job(job)
    source_root=str(Path(__file__).resolve().parents[2])
    entry=f"import sys,runpy;sys.path.insert(0,{source_root!r});runpy.run_module('services.video_worker.media_producer',run_name='__main__')"
    try:
        process=await asyncio.create_subprocess_exec(sys.executable,'-c',entry,
            '--recordings',str(settings.archive_dir),'--store',str(settings.media_clip_dir),'--bytes',str(settings.media_clip_bytes),
            '--entries',str(settings.media_clip_entries),'--live-recordings',str(getattr(settings,'live_recording_dir','/recordings-live')),
            stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.DEVNULL)
    except OSError:return {'error_code':'media_worker_error'}
    async def collect():
        process.stdin.write(json.dumps(job).encode());await process.stdin.drain();process.stdin.close()
        output=bytearray()
        while chunk:=await process.stdout.read(4096):
            output.extend(chunk)
            if len(output)>65536:raise ValueError('media child output exceeded bound')
        await process.wait();return json.loads(output)
    try:
        result=await asyncio.wait_for(collect(),timeout=360)
        if not isinstance(result,dict):raise ValueError('invalid media child output')
        if process.returncode:
            return {'error_code':result.get('error_code') if result.get('error_code') in FAILURES else 'media_worker_error'}
        if result.get('clipId')!=job['jobId'] or result.get('inputSha256')!=job['inputSha256'] or not isinstance(result.get('manifestSha256'),str) or not re.fullmatch('[a-f0-9]{64}',result['manifestSha256']):raise ValueError('media child receipt mismatch')
        return result
    except TimeoutError:return {'error_code':'media_timeout'}
    except (ValueError,OSError):return {'error_code':'media_worker_error'}
    finally:
        if process.returncode is None:
            try:process.terminate()
            except ProcessLookupError:pass
            try:await asyncio.wait_for(process.wait(),5)
            except TimeoutError:
                try:process.kill()
                except ProcessLookupError:pass
                await asyncio.wait_for(process.wait(),5)


async def produce_job(settings,core,worker,job):
    result=await encode(settings,job)
    if result.get('error_code'):
        await record_failure(core,worker,job,result['error_code'],route=ROUTE);return False
    body={'jobId':job['jobId'],'workerId':worker,'generation':job['generation'],'inputSha256':job['inputSha256'],'manifestSha256':result['manifestSha256']}
    for attempt in range(3):
        try:
            reply=await core.post(ROUTE+'/complete',body)
            if reply.get('completed') is True and all(reply.get(key)==value for key,value in result.items()) and reply.get('jobId')==job['jobId']:return True
        except ServiceError as error:
            if error.status==409:return False
            if error.status in {400,404,422}:break
        if attempt<2:await asyncio.sleep(attempt+1)
    await record_failure(core,worker,job,'result_commit_unavailable',route=ROUTE);return False


async def media_slot(settings,core,slot,stop):
    worker=settings.worker_id[:60]+f'-media-{slot}';timing=replace(settings,analysis_job_timeout_seconds=settings.media_job_timeout_seconds)
    while not stop.is_set():
        try:
            claimed_at=asyncio.get_running_loop().time();response=await core.post(ROUTE+'/claim',{'workerId':worker,'leaseSeconds':settings.analysis_lease_seconds,'inputKinds':['archive','session']})
            job=response.get('job')
            if not job:await wait_for_poll(stop);continue
            validate_job(job)
            if stop.is_set():await record_failure(core,worker,job,'consumer_interrupted',route=ROUTE);break
            if not await execute_owned(timing,core,worker,job,claimed_at,lambda:produce_job(settings,core,worker,job),route=ROUTE):await wait_for_poll(stop)
        except (ServiceError,OSError,ValueError,KeyError,TypeError) as error:
            job_event('media_queue_unavailable',worker,{},http_status=getattr(error,'status',None));await wait_for_poll(stop)
