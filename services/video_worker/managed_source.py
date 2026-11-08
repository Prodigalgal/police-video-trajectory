"""Managed archive boundaries: original-file verification, durable resume and read barriers."""
import asyncio
import hashlib
import re
import threading
import uuid
from urllib.parse import urlencode

from .client import ServiceError
from .frames import archive_path
from .processing_catalog import canonical

CONTRACT='managed-ingestion-v1'
GAP_KEYS=('decode_errors','corrupt_frames','missing_pts_frames','invalid_pts_frames','pts_discontinuities',
          'durable_spool_gaps','output_overflow_gaps','missed_probe_ticks')


class ManagedSourceError(RuntimeError):
    def __init__(self,code):self.error_code=code;super().__init__(code)


def task_path(task,suffix):
    prefix='/internal/v2/ingestion/tasks/' if task.get('managed') else '/internal/v1/tasks/'
    return prefix+task['taskId']+'/'+suffix


def verify_input(settings,task):
    value=task.get('managed');source=value.get('source') if isinstance(value,dict) else None
    if (not isinstance(value,dict) or value.get('contract')!=CONTRACT or not isinstance(source,dict)
        or not re.fullmatch('[a-f0-9]{64}',value.get('input_sha256','')) or hashlib.sha256(canonical(source)).hexdigest()!=value['input_sha256']):raise ManagedSourceError('source_input_invalid')
    for key,field in [('source_id','sourceId'),('camera_id','cameraId'),('source_type','sourceType'),('source_ref','sourceRef'),('source_time_origin','sourceTimeOrigin')]:
        if source.get(key)!=task.get(field):raise ManagedSourceError('source_input_invalid')
    if source.get('window')!={'start_ms':value.get('start_ms'),'end_ms':value.get('end_ms')}:raise ManagedSourceError('source_input_invalid')
    if not settings.target_pipeline or settings.scene_policy!='activity_v1' or settings.scene_gate_mode!='active':raise ManagedSourceError('source_input_invalid')
    if task['sourceType']=='archive':
        expected=source.get('file',{});path=archive_path(settings,task['sourceRef']);before=path.stat()
        if type(expected.get('bytes')) is not int or not 0<expected['bytes']<=8*1024**3 or before.st_size!=expected['bytes']:raise ManagedSourceError('source_file_changed')
        digest=hashlib.sha256();size=0
        with path.open('rb') as stream:
            while block:=stream.read(min(1024**2,expected['bytes']-size+1)):
                size+=len(block)
                if size>expected['bytes']:raise ManagedSourceError('source_file_changed')
                digest.update(block)
        after=path.stat()
        if (size!=expected['bytes'] or digest.hexdigest()!=expected.get('sha256')
            or (before.st_ino,before.st_size,before.st_mtime_ns)!=(after.st_ino,after.st_size,after.st_mtime_ns)):raise ManagedSourceError('source_file_changed')
        value={**value,'local_file_marker':(path,after.st_ino,after.st_size,after.st_mtime_ns)}
    return value


async def known_captures(core,task,worker,frame_ids=None):
    query=urlencode({'worker':worker,'generation':task['generation'],'input_sha256':task['managed']['input_sha256']})
    if frame_ids is None:value=await core.get(task_path(task,'captures')+'?'+query)
    else:value=await core.post(task_path(task,'receipts'),{'workerId':worker,'generation':task['generation'],'inputSha256':task['managed']['input_sha256'],'frameIds':frame_ids})
    rows=value.get('items')
    if not isinstance(rows,list) or len(rows)>512:raise ManagedSourceError('source_input_invalid')
    seen=set();identities=set()
    for row in rows:
        try:
            uuid.UUID(row['frame_id']);uuid.UUID(row['receipt'])
            if (row['frame_id'] in identities or frame_ids is not None and row['frame_id'] not in frame_ids
                or not re.fullmatch('[a-f0-9]{64}',row['sha256'])):raise ValueError()
            if task['sourceType']=='archive':
                if (type(row['offset_ms']) is not int or row['offset_ms']<0 or row['offset_ms'] in seen
                    or str(uuid.uuid5(uuid.UUID(task['taskId']),'offset-ms:'+str(row['offset_ms'])))!=row['frame_id']):raise ValueError()
            elif row['offset_ms'] is not None:raise ValueError()
            seen.add(row['offset_ms'])
            identities.add(row['frame_id'])
        except (ValueError,KeyError,TypeError):raise ManagedSourceError('source_input_invalid') from None
    return rows


async def reconcile_spool(core,spool,task,worker):
    value=task['managed'];after=None
    while True:
        pending=await asyncio.to_thread(spool.managed_pending,task['taskId'],after)
        if not pending:break
        known=await known_captures(core,task,worker,pending)
        try:await asyncio.to_thread(spool.resume_managed,task['taskId'],value['input_sha256'],worker,task['generation'],known,pending)
        except (ValueError,KeyError,TypeError):raise ManagedSourceError('source_input_invalid') from None
        after=pending[-1]
    while await asyncio.to_thread(spool.unregistered,task['taskId'],task['generation']):await asyncio.sleep(.25)
    return {row['offset_ms']:row for row in await known_captures(core,task,worker)} if task['sourceType']=='archive' else {}


class ArchiveCheckpoint:
    """Reader waits at a safe point; the async caller drains registrations and persists its cursor."""
    def __init__(self,task,worker,known,file_marker=None):
        self.task=task;self.worker=worker;self.known=known
        self.input=task['managed'];self.safe=self.input['safe_through_ms'];self.observed=self.input.get('observed_through_ms',self.safe)
        self.cut=max(self.input['start_ms'],self.safe+1);self.last_barrier=max(self.safe,self.cut-1)
        self.last_frame=None;self.pending=None;self.error=None;self.lock=threading.Lock()
        self.file_marker=file_marker

    def reused(self,offset):return offset<=self.safe or offset in self.known

    def captured(self,frame_id):
        with self.lock:self.last_frame=frame_id

    def flush(self,reader,final):
        if reader.stop.is_set():return
        snapshot=reader.snapshot();gaps={key:int(snapshot.get(key,0)) for key in GAP_KEYS}
        if snapshot.get('error_type'):gaps['reader_error']=1
        if any(gaps.values()):self.error='source_checkpoint_gap';return
        offset=reader.archive_offset_ms
        if offset is None:return
        if offset<self.cut:
            if not final or self.safe<self.input['start_ms']:return
            offset=self.safe
        if not final and offset-self.last_barrier<10000:return
        if self.file_marker is not None:
            path,*expected=self.file_marker
            try:current=path.stat()
            except OSError:self.error='source_file_changed';return
            if [current.st_ino,current.st_size,current.st_mtime_ns]!=expected:self.error='source_file_changed';return
        barrier={'checkpointId':str(uuid.uuid4()),'workerId':self.worker,'generation':self.task['generation'],
                 'inputSha256':self.input['input_sha256'],'observedThroughMs':max(self.observed,offset),
                 'safeThroughMs':offset,'lastFrameId':self.last_frame,'readerFinished':final,'gaps':gaps}
        release=threading.Event()
        with self.lock:self.pending=(barrier,release)
        while not release.wait(.2):
            if reader.stop.is_set():return

    def waiting(self):
        with self.lock:return self.pending

    def acknowledged(self,body):
        with self.lock:
            self.safe=body['safeThroughMs'];self.observed=body['observedThroughMs'];self.last_barrier=self.safe
            pending=self.pending;self.pending=None
            if pending:pending[1].set()


async def commit_checkpoint(core,spool,task,checkpoint,body):
    while await asyncio.to_thread(spool.unregistered,task['taskId'],task['generation']):await asyncio.sleep(.25)
    # Retain the exact checkpoint ID/body across an unknown response.
    for attempt in range(5):
        try:
            result=await core.post(task_path(task,'progress'),body)
            if (result.get('task_id')!=task['taskId'] or result.get('checkpoint_id')!=body['checkpointId']
                or result.get('safe_through_ms')!=body['safeThroughMs'] or result.get('reader_finished')!=body['readerFinished']):raise ManagedSourceError('source_checkpoint_unavailable')
            checkpoint.acknowledged(body);return
        except ServiceError as failure:
            if failure.status not in {408,429,500,502,503,504}:raise
            if attempt==4:raise ManagedSourceError('source_checkpoint_unavailable') from None
            await asyncio.sleep(min(10,2**attempt))
