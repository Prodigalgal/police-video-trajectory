"""Leased operator policy. The decoder never performs a configuration request."""
import asyncio
from dataclasses import dataclass,replace
import json
import threading
import time

from .client import ServiceError
from .events import log


@dataclass(frozen=True)
class RecordingPolicy:
    version:int=0
    enabled:bool=False
    plugin:str='short_segment_v1'
    seconds:int=0


class RecordingControl:
    def __init__(self,clock=time.monotonic):
        self.clock=clock;self.lock=threading.Lock();self.policy=RecordingPolicy();self.valid_until=0

    def accept(self,value):
        if not isinstance(value,dict) or value.get('schema_version')!='pipeline-features-v1' or type(value.get('lease_seconds')) is not int or not 1<=value['lease_seconds']<=15:
            raise ValueError('invalid optional feature lease')
        features=value.get('features')
        if not isinstance(features,list) or len(features)>32:raise ValueError('invalid optional feature list')
        recording=[feature for feature in features if isinstance(feature,dict) and feature.get('id')=='frame_recording']
        if len(recording)!=1:raise ValueError('recording feature required')
        feature=recording[0];parameters=feature.get('parameters');plugin=feature.get('plugin');version=feature.get('version')
        if type(feature.get('enabled')) is not bool or type(version) is not int or not 1<=version<=9007199254740991 or not isinstance(parameters,dict):raise ValueError('invalid recording policy')
        if plugin=='short_segment_v1' and not parameters:seconds=0
        elif plugin=='context_window_v1' and parameters.keys()=={'seconds'} and type(parameters['seconds']) is int and parameters['seconds'] in (3,5,10):seconds=parameters['seconds']
        else:raise ValueError('recording plugin unavailable')
        policy=RecordingPolicy(version,feature['enabled'],plugin,seconds)
        with self.lock:
            if version<self.policy.version or version==self.policy.version and policy!=self.policy:raise ValueError('recording policy revision conflict')
            changed=policy!=self.policy;self.policy=policy;self.valid_until=self.clock()+value['lease_seconds']
        return changed

    def invalidate(self):
        with self.lock:self.valid_until=0

    def current(self):
        with self.lock:return self.policy if self.clock()<self.valid_until else replace(self.policy,enabled=False)


async def synchronize_recording(control,core):
    while True:
        try:
            value=await asyncio.wait_for(core.get('/internal/v2/pipeline/features'),timeout=5)
            if control.accept(value):
                policy=control.current()
                log(json.dumps({'event':'recording_policy_loaded','version':policy.version,'enabled':policy.enabled,'plugin':policy.plugin},ensure_ascii=False))
        except (ServiceError,asyncio.TimeoutError):
            log('可选录像配置暂不可用，配置租约到期后停止新增录制。')
        except (ValueError,KeyError,TypeError):
            control.invalidate();log('可选录像配置无效，已关闭新增录制。')
        await asyncio.sleep(5)


class RecordingSlot:
    """Owns one optional recorder; removing it never flushes the live decoder."""
    def __init__(self,reader,container,control):
        self.reader,self.stream,self.control=reader,container.streams.video[0],control
        self.recorder=None;self.applied=None;self.failed=None;self.retry_at=0

    def detach(self):
        if self.recorder is not None:
            self.recorder.discontinuity(self.reader.epoch,'recording_module_detached')
            self.recorder.close();self.recorder=None

    def synchronize(self):
        policy=self.control.current()
        if policy!=self.applied:
            self.detach();self.applied=policy;self.failed=None
        if self.recorder is not None and getattr(self.recorder,'error',None) is not None:
            self.detach();self.failed=policy;self.retry_at=self.control.clock()+30
            self.reader.add('recording_module_failed')
        if not policy.enabled or self.recorder is not None or self.failed==policy and self.control.clock()<self.retry_at:return
        from .live_recording import NativeRecorder
        import sqlite3
        try:
            if self.stream.codec_context.name!='h264' or not self.stream.time_base or self.reader.recording_context is None:raise ValueError('recording adapter unavailable')
            settings=replace(self.reader.settings,live_recording_enabled=True,live_recording_context_seconds=policy.seconds)
            context={**self.reader.recording_context,'camera_id':self.reader.camera,'scene_run_id':self.reader.run_id}
            self.recorder=NativeRecorder(settings,self.stream,context,self.reader.add)
        except (OSError,ValueError,sqlite3.Error):
            self.failed=policy;self.retry_at=self.control.clock()+30;self.reader.add('recording_startup_failed')

    def packet(self,packet,epoch):
        self.synchronize()
        if self.recorder is not None:self.recorder.packet(packet,epoch)

    def observe_frame(self,*args):
        if self.recorder is not None:self.recorder.observe_frame(*args)

    def binding(self,*args):
        return self.recorder.binding(*args) if self.recorder is not None else None

    def discontinuity(self,*args):
        if self.recorder is not None:self.recorder.discontinuity(*args)

    def close(self):
        if not self.control.current().enabled:self.detach()
        if self.recorder is not None:
            try:self.recorder.finish_decoding()
            finally:self.recorder.close();self.recorder=None
