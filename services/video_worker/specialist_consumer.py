"""Dedicated OCR protocol consumer. No VLM, model download or provider fallback."""
import asyncio
import codecs
from dataclasses import dataclass,replace
import hashlib
import json
import os
from pathlib import Path
import re
import ssl
from urllib.parse import quote,urlencode,urlparse
from uuid import UUID
import httpx

from .analysis_consumer import execute_owned,job_event,record_failure,wait_for_poll
from .client import ServiceError

ROUTE='/internal/v1/specialist-jobs'
OPERATIONS={'text_ocr','license_plate'}
LIMITATIONS={'low_resolution','blur','occlusion','unsupported_language','uncalibrated_score','uncertain_characters','partial_text','inconsistent_readings'}


def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()


def validate_job(job):
    if not isinstance(job,dict) or type(job.get('generation')) is not int or job['generation']<1 or str(UUID(job['jobId']))!=job['jobId']:raise ValueError('invalid specialist job')
    value=job.get('descriptor')
    if not isinstance(value,dict) or value.keys()!={'schema_version','input_id','input_manifest_sha256','frame_id','camera_id','evidence_sha256','image_width','image_height','operation','profile'}:raise ValueError('invalid specialist input')
    if value['schema_version']!='specialist-input-v1' or value['operation'] not in OPERATIONS or not re.fullmatch(r'[A-Za-z0-9_.-]{1,80}',value['profile']) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}',value['camera_id']):raise ValueError('invalid specialist profile')
    for key in ('input_id','frame_id'):
        if str(UUID(value[key]))!=value[key]:raise ValueError('invalid specialist identity')
    for key in ('input_manifest_sha256','evidence_sha256'):
        if not isinstance(value[key],str) or not re.fullmatch('[a-f0-9]{64}',value[key]):raise ValueError('invalid specialist digest')
    if any(type(value[key]) is not int or not 1<=value[key]<=20000 for key in ('image_width','image_height')) or value['image_width']*value['image_height']>50000000:raise ValueError('invalid specialist dimensions')
    if hashlib.sha256(canonical(value)).hexdigest()!=job.get('inputSha256'):raise ValueError('specialist input digest mismatch')
    return value


def validate_result(job,result):
    value=validate_job(job)
    if not isinstance(result,dict) or result.keys()!={'schema_version','job_id','input_sha256','frame_id','evidence_sha256','operation','profile','image_width','image_height','engine','outcome','regions','limitations'}:raise ValueError('invalid specialist result')
    if result['schema_version']!='specialist-result-v1' or result['job_id']!=job['jobId'] or result['input_sha256']!=job['inputSha256']:raise ValueError('specialist result belongs to another job')
    for key in ('frame_id','evidence_sha256','operation','profile','image_width','image_height'):
        if type(result[key]) is not type(value[key]) or result[key]!=value[key]:raise ValueError('specialist result source mismatch')
    engine=result['engine']
    if not isinstance(engine,dict) or engine.keys()!={'name','version'} or any(not isinstance(text,str) or not text.strip() or len(text)>100 or any(ord(c)<32 and c not in '\n\t' for c in text) for text in engine.values()):raise ValueError('invalid specialist engine declaration')
    regions=result['regions']
    if result['outcome'] not in {'recognized','no_text','unreadable'} or not isinstance(regions,list) or len(regions)>100 or (result['outcome']=='recognized')!=(len(regions)>0):raise ValueError('invalid specialist outcome')
    for index,region in enumerate(regions):
        if not isinstance(region,dict) or region.keys()!={'index','text','bbox_xyxy','score'} or type(region['index']) is not int or region['index']!=index:raise ValueError('invalid specialist region')
        text=region['text'];limit=64 if value['operation']=='license_plate' else 512
        if not isinstance(text,str) or not text.strip() or len(text)>limit or any(ord(c)<32 and c not in '\n\t' for c in text):raise ValueError('invalid specialist text')
        box=region['bbox_xyxy']
        if not isinstance(box,list) or len(box)!=4 or any(type(n) is not int for n in box) or not 0<=box[0]<box[2]<=value['image_width'] or not 0<=box[1]<box[3]<=value['image_height']:raise ValueError('invalid specialist box')
        score=region['score']
        if score is not None and (type(score) not in (int,float) or not 0<=score<=1):raise ValueError('invalid specialist score')
    limitations=result['limitations']
    if not isinstance(limitations,list) or len(limitations)>8 or any(not isinstance(v,str) or v not in LIMITATIONS for v in limitations) or len(set(limitations))!=len(limitations) or len(canonical(result))>128*1024:raise ValueError('invalid specialist limitations')
    if value['operation']=='license_plate' and result['outcome']=='recognized' and set(limitations)&{'uncertain_characters','partial_text','inconsistent_readings'}:raise ValueError('uncertain plate cannot claim recognition success')
    return result


@dataclass(frozen=True)
class SpecialistSettings:
    enabled:bool=False
    url:str=''
    ca_file:str=''
    token:str=''
    profile:str=''
    operations:tuple=('text_ocr','license_plate')
    slots:int=1
    job_timeout:int=480
    accept_routing:bool=False
    secrets_root:str=''
    expected_model_id:str=''

    @classmethod
    def from_env(cls):
        get=lambda key,default='':os.getenv(key,default).strip()
        return cls(get('SPECIALIST_ENABLED','false')=='true',get('SPECIALIST_URL'),get('SPECIALIST_CA_FILE'),get('SPECIALIST_TOKEN'),get('SPECIALIST_PROFILE'),tuple(get('SPECIALIST_OPERATIONS','text_ocr,license_plate').split(',')),int(get('SPECIALIST_SLOTS','1')),int(get('SPECIALIST_JOB_TIMEOUT_SECONDS','480')),get('SPECIALIST_ACCEPT_ROUTING','false')=='true',get('SPECIALIST_SECRETS_ROOT'),get('SPECIALIST_MODEL_ID'))

    def validate(self):
        if self.expected_model_id and (len(self.expected_model_id)>100 or any(ord(c)<32 or ord(c)==127 for c in self.expected_model_id)):raise ValueError('invalid frozen specialist model')
        if self.accept_routing:
            if not self.enabled or not self.secrets_root or not Path(self.secrets_root).is_dir():raise ValueError('specialist routing credentials not configured')
            if type(self.slots) is not int or not 1<=self.slots<=2 or type(self.job_timeout) is not int or not 420<=self.job_timeout<=1800 or not self.operations or len(self.operations)>2 or not set(self.operations)<=OPERATIONS:raise ValueError('invalid specialist consumer policy')
            if not self.url and not self.profile:return
        address=urlparse(self.url)
        if not self.enabled or address.scheme!='https' or not address.hostname or address.username or address.password or address.query or address.fragment or not Path(self.ca_file).is_file() or not self.token or len(self.token)>4096 or not re.fullmatch(r'[A-Za-z0-9_.-]{1,80}',self.profile):raise ValueError('specialist service not configured')
        if type(self.slots) is not int or not 1<=self.slots<=2 or type(self.job_timeout) is not int or not 420<=self.job_timeout<=1800 or not self.operations or len(self.operations)>2 or not set(self.operations)<=OPERATIONS:raise ValueError('invalid specialist consumer policy')


class SpecialistClient:
    def __init__(self,settings,transport=None,*,tls_context=None):
        self.settings=settings
        self.client=httpx.AsyncClient(verify=(tls_context or ssl.create_default_context(cafile=settings.ca_file)) if transport is None else True,transport=transport,trust_env=False,follow_redirects=False,timeout=360)

    async def close(self):await self.client.aclose()

    async def recognize(self,job,image):
        descriptor=validate_job(job)
        if descriptor['profile']!=self.settings.profile or descriptor['operation'] not in self.settings.operations or not 0<len(image)<=8*1024*1024 or hashlib.sha256(image).hexdigest()!=descriptor['evidence_sha256']:raise ValueError('specialist input changed')
        metadata={**descriptor,'schema_version':'specialist-request-v1','job_id':job['jobId'],'input_sha256':job['inputSha256']}
        headers={'Authorization':'Bearer '+self.settings.token,'Accept':'text/event-stream','Idempotency-Key':job['jobId']}
        if self.settings.expected_model_id:headers['X-Specialist-Model']=quote(self.settings.expected_model_id,safe='')
        async def read():
            async with self.client.stream('POST',self.settings.url.rstrip('/')+'/v1/recognize',headers=headers,
                data={'metadata':json.dumps(metadata)},files={'file':(descriptor['frame_id']+'.jpg',image,'image/jpeg')}) as response:
                if response.status_code!=200:raise ServiceError(response.status_code)
                if response.headers.get('content-type','').split(';')[0].strip()!='text/event-stream':raise ValueError('specialist service did not stream')
                decoder=codecs.getincrementaldecoder('utf-8')('strict');buffer='';terminal=None;total=0
                async for raw in response.aiter_bytes():
                    total+=len(raw)
                    if total>1024*1024:raise ValueError('specialist stream exceeded bound')
                    buffer=(buffer+decoder.decode(raw)).replace('\r\n','\n')
                    if len(buffer.encode())>256*1024:raise ValueError('specialist event exceeded bound')
                    while '\n\n' in buffer:
                        event,buffer=buffer.split('\n\n',1);parts=[line[5:].lstrip(' ') for line in event.split('\n') if line.startswith('data:')]
                        if not parts:continue
                        message=json.loads('\n'.join(parts))
                        if not isinstance(message,dict) or terminal is not None:raise ValueError('invalid specialist terminal event')
                        if message.get('type')=='progress' and message.keys()=={'type'}:continue
                        if message.get('type')=='error' and message.keys()=={'type','code'}:
                            if message['code']=='specialist_timeout':raise TimeoutError('specialist service deadline')
                            if message['code']=='specialist_unavailable':raise ServiceError(503)
                            raise ValueError('unknown specialist failure')
                        if message.get('type')!='result' or message.keys()!={'type','result'}:raise ValueError('specialist stream failed')
                        terminal=validate_result(job,message['result'])
                buffer+=decoder.decode(b'',final=True)
                if buffer.strip() or terminal is None:raise ValueError('specialist stream interrupted')
                return terminal
        try:return await asyncio.wait_for(read(),360)
        except httpx.TimeoutException:raise TimeoutError('specialist service deadline') from None
        except (httpx.HTTPError,UnicodeError):raise ServiceError(502) from None


async def image_bytes(core,worker,job):
    value=validate_job(job);params=urlencode({'workerId':worker,'generation':job['generation']})
    async with core.client.stream('GET',core.base_url+ROUTE+'/'+job['jobId']+'/image?'+params,headers={'Authorization':'Bearer '+core.token},timeout=120) as response:
        if response.status_code!=200:raise ServiceError(response.status_code)
        if response.headers.get('content-type','').split(';')[0]!='image/jpeg':raise ValueError('invalid specialist image response')
        raw=bytearray()
        async for chunk in response.aiter_bytes():
            raw.extend(chunk)
            if len(raw)>8*1024*1024:raise ValueError('specialist image exceeded bound')
        if not raw or hashlib.sha256(raw).hexdigest()!=value['evidence_sha256'] or response.headers.get('x-content-sha256')!=value['evidence_sha256']:raise ValueError('specialist image changed')
        return bytes(raw)


async def process_job(core,client,worker,job):
    try:image=await image_bytes(core,worker,job)
    except ServiceError as error:
        await record_failure(core,worker,job,'specialist_input_changed' if error.status in {400,404,409,422} else 'specialist_unavailable',route=ROUTE);return False
    except (ValueError,TypeError,KeyError):
        await record_failure(core,worker,job,'specialist_input_changed',route=ROUTE);return False
    except (httpx.HTTPError,OSError):
        await record_failure(core,worker,job,'specialist_unavailable',route=ROUTE);return False
    try:result=await client.recognize(job,image)
    except asyncio.TimeoutError:
        await record_failure(core,worker,job,'specialist_timeout',route=ROUTE);return False
    except ServiceError:
        await record_failure(core,worker,job,'specialist_unavailable',route=ROUTE);return False
    except (ValueError,TypeError,KeyError):
        await record_failure(core,worker,job,'invalid_specialist_result',route=ROUTE);return False
    body={'jobId':job['jobId'],'workerId':worker,'generation':job['generation'],'inputSha256':job['inputSha256'],'result':result}
    for attempt in range(3):
        try:
            receipt=await core.post(ROUTE+'/complete',body)
            if receipt.get('completed') is True and receipt.get('jobId')==job['jobId'] and receipt.get('inputSha256')==job['inputSha256'] and re.fullmatch('[a-f0-9]{64}',receipt.get('resultSha256','')):return True
        except ServiceError as error:
            if error.status==409:return False
            if error.status in {400,404,422}:break
        if attempt<2:await asyncio.sleep(attempt+1)
    await record_failure(core,worker,job,'result_commit_unavailable',route=ROUTE);return False


async def specialist_slot(settings,configuration,core,client,slot,stop):
    worker=settings.worker_id[:50]+f'-specialist-{slot}';timing=replace(settings,analysis_job_timeout_seconds=configuration.job_timeout)
    while not stop.is_set():
        try:
            body={'workerId':worker,'leaseSeconds':settings.analysis_lease_seconds,'profile':configuration.profile or None,'operations':list(configuration.operations)}
            if configuration.accept_routing:body['adapters']=['specialist_sse_v1']
            started=asyncio.get_running_loop().time();claim=await core.post(ROUTE+'/claim',body)
            job=claim.get('job')
            if not job:await wait_for_poll(stop);continue
            validate_job(job)
            if stop.is_set():await record_failure(core,worker,job,'consumer_interrupted',route=ROUTE);break
            if not await execute_owned(timing,core,worker,job,started,lambda:process_job(core,client,worker,job),route=ROUTE):await wait_for_poll(stop)
        except (ServiceError,httpx.HTTPError,OSError,ValueError,TypeError,KeyError):
            job_event('specialist_queue_unavailable',worker,{});await wait_for_poll(stop)
