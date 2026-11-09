import asyncio
import hashlib
import threading
from types import SimpleNamespace
from uuid import uuid4
import pytest
from services.video_worker.dispatch import deliver_selected
from services.video_worker.config import Settings


class Spool:
    def __init__(self,registered=False):
        self.frame=str(uuid4());self.payload=b'capture transport contract';self.sha=hashlib.sha256(self.payload).hexdigest()
        self.receipt={'frameId':self.frame,'payloadSha256':self.sha,'receipt':str(uuid4())}
        self.record={'payload':self.payload,'sha256':self.sha,'metadata':{'task_id':str(uuid4()),'worker_id':'worker','generation':1,'camera_id':'camera-1','media_offset_ms':None,'source_time':None,'scene_run_id':str(uuid4()),'media_epoch':0,'relative_media_ms':1},'plan':{'targets':[]},'receipt':self.receipt if registered else None}
        self.ack=threading.Event()
    def clear_pending_notification(self,*args):pass
    def pending(self,**kwargs):return [] if self.ack.is_set() else [self.frame]
    def get(self,frame):return self.record
    def attach(self,frame,digest,kind,value):self.record[kind]=value
    def acknowledge(self,frame,digest):
        assert self.record['receipt']==self.receipt;self.ack.set()
    def attempted(self,*args):pass
    def wait_for_pending_notification(self,*args):self.ack.wait(.01)


@pytest.mark.asyncio
@pytest.mark.parametrize('handoff,registered',[(True,False),(False,False),(False,True)])
async def test_durable_capture_ack_is_independent_of_optional_analysis_handoff(handoff,registered):
    spool=Spool(registered);calls=[]
    class Core:
        async def post(self,path,body):
            calls.append(path)
            return {'capture':spool.receipt} if path.split('?')[0].endswith('/register') else {'frameId':spool.frame,'payloadSha256':spool.sha,'queued':True,'jobId':str(uuid4())}
    store=SimpleNamespace(write=lambda frame,payload:'evidence:'+str(frame).replace('-',''))
    task=asyncio.create_task(deliver_selected(Core(),store,spool,analysis_handoff=handoff))
    try:assert await asyncio.wait_for(asyncio.to_thread(spool.ack.wait,2),3)
    finally:task.cancel();await asyncio.gather(task,return_exceptions=True)
    registration_path='/internal/v2/captures/register'+('' if handoff else '?analysis_requested=false')
    assert calls.count(registration_path)==(0 if registered else 1)
    assert calls.count('/internal/v2/captures/enqueue')==(1 if handoff else 0)


def test_handoff_flag_rejects_ambiguous_configuration(monkeypatch):
    monkeypatch.setenv('CAPTURE_ANALYSIS_HANDOFF_ENABLED','disabled')
    with pytest.raises(ValueError,match='handoff'):Settings.from_env()


@pytest.mark.asyncio
@pytest.mark.parametrize('namespace',[None,'b'*32])
async def test_recording_recovery_keeps_its_namespace_scope_between_passes(monkeypatch,namespace):
    from services.video_worker import live_recording_delivery as delivery
    scanned=[];published=[];passes=0
    frame,segment=str(uuid4()),str(uuid4());digest='a'*64
    def pending(settings,selected):
        scanned.append(selected)
        return [('b'*32,(frame,segment,digest,1000,digest,digest))]
    async def sleep(seconds):
        nonlocal passes
        passes+=1
        if passes==2:raise asyncio.CancelledError()
    class Core:
        async def post(self,path,body):
            published.append(body['namespace'])
            return {'recorded':True,'frame_id':frame,'segment_id':segment,'manifest_sha256':digest}
    monkeypatch.setattr(delivery,'pending',pending)
    monkeypatch.setattr(delivery,'LiveStore',lambda root,scope:SimpleNamespace(attempted=lambda *args,**kwargs:None))
    monkeypatch.setattr(delivery.asyncio,'sleep',sleep)
    with pytest.raises(asyncio.CancelledError):
        await delivery.publish_recordings(SimpleNamespace(live_recording_dir='unused'),Core(),namespace=namespace)
    assert scanned==[namespace,namespace]
    assert published==['b'*32,'b'*32]
