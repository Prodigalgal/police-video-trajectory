"""Materialize one saved module result; this consumer never uses an AI client."""
import asyncio
from dataclasses import replace
from datetime import datetime,timezone
import re
import uuid

from .analysis_consumer import execute_owned,job_event,record_failure,wait_for_poll
from .client import ServiceError

ROUTE='/internal/v1/modules'
KINDS={'person_attributes','vehicle_attributes','scene_context'}
RETRYABLE={408,425,429,500,502,503,504}


def validate_job(job):
    if not isinstance(job,dict) or job.get('moduleKey') not in KINDS or type(job.get('generation')) is not int or job['generation']<1:
        raise ValueError('invalid module claim')
    for key in ('jobId','frameId','sourceId'):
        if not isinstance(job.get(key),str) or str(uuid.UUID(job[key]))!=job[key]:raise ValueError('invalid module identity')
    if not isinstance(job.get('sourceSha256'),str) or not re.fullmatch('[0-9a-f]{64}',job['sourceSha256']):raise ValueError('invalid module source digest')


def acknowledged(receipt,job):
    return (isinstance(receipt,dict) and receipt.get('completed') is True and
        all(receipt.get(key)==job[key] for key in ('jobId','moduleKey','sourceId','sourceSha256')) and
        isinstance(receipt.get('resultSha256'),str) and re.fullmatch('[0-9a-f]{64}',receipt['resultSha256']) is not None)


async def materialize(core,worker,job,*,retry_delays=(.25,.5)):
    validate_job(job)
    body={key:job[key] for key in ('jobId','sourceId','sourceSha256','generation')};body['workerId']=worker
    failure='result_commit_unavailable'
    for attempt in range(len(retry_delays)+1):
        expires=job.get('expiresAt')
        if expires is not None:
            try:
                until=datetime.fromisoformat(expires.replace('Z','+00:00'))
                if until.tzinfo is None or until<=datetime.now(timezone.utc):break
            except (TypeError,ValueError,AttributeError):break
        try:
            receipt=await core.post(ROUTE+'/complete',body)
            if acknowledged(receipt,job):return True
        except ServiceError as error:
            if error.status==409:
                job_event('module_completion_rejected',worker,job,http_status=409);return False
            if error.status in {400,422}:
                failure='invalid_module_source';break
            if error.status not in RETRYABLE:break
        if attempt<len(retry_delays):await asyncio.sleep(retry_delays[attempt])
    await record_failure(core,worker,job,failure,route=ROUTE)
    return False


async def module_slot(settings,core,slot,stop):
    worker=f'{settings.worker_id}-module-{slot}'
    timing=replace(settings,analysis_job_timeout_seconds=settings.module_job_timeout_seconds)
    while not stop.is_set():
        try:
            claimed_at=asyncio.get_running_loop().time()
            response=await core.post(ROUTE+'/claim',{'workerId':worker,'leaseSeconds':settings.analysis_lease_seconds,'moduleKey':None})
            job=response.get('job')
            if not job:await wait_for_poll(stop);continue
            validate_job(job)
            if stop.is_set():
                await record_failure(core,worker,job,'consumer_interrupted',route=ROUTE);break
            execute=lambda:materialize(core,worker,job)
            if not await execute_owned(timing,core,worker,job,claimed_at,execute,route=ROUTE):await wait_for_poll(stop)
        except (ServiceError,OSError,ValueError,KeyError,TypeError) as error:
            job_event('module_queue_unavailable',worker,{},http_status=getattr(error,'status',None))
            await wait_for_poll(stop)
