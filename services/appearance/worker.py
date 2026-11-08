"""Independent gallery consumer. Reads only already registered effective-frame jobs."""
import argparse
import asyncio
import hashlib
import logging
import math
import os
import signal
from urllib.parse import urlsplit
import httpx
from services.video_worker.client import JsonService,ServiceError
from services.specialist_service.runner import NativeRunner,NativeFailure,NativeTimeout
from .catalog import Catalog,Definition,InstalledModels,canonical
from .process import EncodeRequest


LOG=logging.getLogger('appearance-worker')


def validate_job(job):
    definition=Definition.model_validate(job['definition']);descriptor=job['descriptor']
    if definition.sha256!=job['profile_sha256'] or hashlib.sha256(canonical(descriptor)).hexdigest()!=job['source_sha256']:raise ValueError('appearance source binding differs')
    if type(job['lease_generation']) is not int or job['lease_generation']<1:raise ValueError('invalid lease generation')
    return definition


class Consumer:
    def __init__(self,core,root,catalog,worker_id):
        self.core=core;self.catalog=catalog;self.worker=worker_id;self.models=InstalledModels(root);self.runner=NativeRunner(root,module='services.appearance.process')

    async def _image(self,job):
        path='/internal/v1/appearance/'+job['job_id']+'/image';params={'workerId':self.worker,'generation':job['lease_generation']}
        try:
            async with self.core.client.stream('GET',self.core.base_url+path,params=params,headers={'Authorization':'Bearer '+self.core.token}) as response:
                if response.status_code!=200:raise ServiceError(response.status_code)
                raw=bytearray()
                async for block in response.aiter_bytes():
                    if len(raw)+len(block)>8*1024*1024:raise ValueError('image exceeds bound')
                    raw.extend(block)
                sha=hashlib.sha256(raw).hexdigest()
                if sha!=response.headers.get('x-content-sha256') or sha!=job['descriptor']['image_sha256']:raise ValueError('image hash differs')
                return bytes(raw)
        except httpx.HTTPError:raise ServiceError(503) from None

    async def _heartbeat(self,job):
        while True:
            await asyncio.sleep(20)
            await self.core.post('/internal/v1/appearance/'+job['job_id']+'/renew',{'workerId':self.worker,'generation':job['lease_generation'],'leaseSeconds':120})

    async def once(self):
        declared=self.catalog.current().profiles;definitions=self.models.available(declared)
        if not definitions:LOG.warning('appearance_no_installed_profiles');return False
        claim=await self.core.post('/internal/v1/appearance/claim',{'workerId':self.worker,'leaseSeconds':120,'profiles':[p.sha256 for p in definitions]})
        if not claim.get('job_available'):return False
        job=claim['job'];heartbeat=asyncio.create_task(self._heartbeat(job));encoding=None;failure='appearance_unavailable';committed=False
        try:
            definition=validate_job(job)
            if not any(p.sha256==definition.sha256 for p in definitions):raise ValueError('worker profile is unavailable')
            image=await self._image(job)
            encoding=asyncio.create_task(self.runner.recognize(definition,EncodeRequest(bbox_xyxy=job['descriptor']['bbox_xyxy'],image_sha256=job['descriptor']['image_sha256']),image,360))
            done,_=await asyncio.wait({encoding,heartbeat},return_when=asyncio.FIRST_COMPLETED)
            if heartbeat in done:await heartbeat
            encoded=await encoding;result=encoded['result'];vector=result['vector']
            if result['profile_sha256']!=definition.sha256 or result['image_sha256']!=job['descriptor']['image_sha256'] or len(vector)!=definition.dimensions or not all(math.isfinite(v) for v in vector) or abs(sum(v*v for v in vector)-1)>.002:
                failure='appearance_vector_invalid';raise ValueError('invalid encoder result')
            body=dict(jobId=job['job_id'],workerId=self.worker,generation=job['lease_generation'],sourceSha256=job['source_sha256'],profileSha256=job['profile_sha256'],vector=vector)
            for attempt in range(2):
                try:await self.core.post('/internal/v1/appearance/complete',body);break
                except ServiceError as error:
                    if attempt or error.status in {400,401,403,404,409,422}:raise
            committed=True;LOG.info('appearance_completed job=%s profile=%s',job['job_id'],definition.profile)
            return True
        except asyncio.CancelledError:
            failure='consumer_interrupted';raise
        except NativeTimeout:
            failure='appearance_timeout';LOG.warning('appearance_timeout job=%s',job['job_id'])
        except ServiceError as error:
            if error.status in {404,409}:failure='appearance_source_changed'
            LOG.warning('appearance_service_rejected job=%s status=%s',job['job_id'],error.status)
        except (NativeFailure,ValueError,KeyError,TypeError):LOG.warning('appearance_failed job=%s code=%s',job['job_id'],failure)
        finally:
            if encoding is not None and not encoding.done():encoding.cancel()
            heartbeat.cancel();await asyncio.gather(*(task for task in (encoding,heartbeat) if task is not None),return_exceptions=True)
            # A completed replay rejects fail with 409; a lost lease is never overwritten.
            if not committed:
                try:await self.core.post('/internal/v1/appearance/'+job['job_id']+'/fail',{'workerId':self.worker,'generation':job['lease_generation'],'errorCode':failure})
                except ServiceError as error:
                    if error.status!=409:LOG.warning('appearance_failure_receipt_unavailable job=%s status=%s',job['job_id'],error.status)
        return True

    async def close(self):await self.runner.close()


async def run(once=False):
    endpoint=os.environ['CORE_URL'];parsed=urlsplit(endpoint)
    if parsed.scheme!='https' or not parsed.hostname or any((parsed.username,parsed.password,parsed.query,parsed.fragment)):raise ValueError('trusted HTTPS Core URL required')
    root=os.environ['APPEARANCE_MODEL_DIR'];catalog=Catalog(os.environ['APPEARANCE_CATALOG_FILE']);catalog.current()
    core=JsonService(endpoint,os.environ['CORE_CA_FILE'],os.environ['WORKER_TOKEN'],timeout=40)
    consumer=Consumer(core,root,catalog,os.getenv('APPEARANCE_WORKER_ID','appearance-1'));stop=asyncio.Event();loop=asyncio.get_running_loop()
    for sig in (signal.SIGINT,signal.SIGTERM):
        try:loop.add_signal_handler(sig,stop.set)
        except (NotImplementedError,RuntimeError):pass
    waiting=asyncio.create_task(stop.wait());active=None
    try:
        while not stop.is_set():
            try:
                active=asyncio.create_task(consumer.once());done,_=await asyncio.wait({waiting,active},return_when=asyncio.FIRST_COMPLETED)
                if waiting in done:
                    active.cancel();await asyncio.gather(active,return_exceptions=True);break
                worked=await active
            except (ServiceError,OSError,ValueError):LOG.warning('appearance_consumer_deferred');worked=False
            if once:break
            if not worked:
                try:await asyncio.wait_for(stop.wait(),timeout=3)
                except TimeoutError:pass
    finally:
        waiting.cancel()
        if active is not None and not active.done():active.cancel()
        await asyncio.gather(*(task for task in (waiting,active) if task is not None),return_exceptions=True)
        await consumer.close();await core.close()


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--once',action='store_true');arguments=parser.parse_args()
    logging.basicConfig(level=logging.INFO,format='%(asctime)s %(levelname)s %(message)s');asyncio.run(run(arguments.once))
