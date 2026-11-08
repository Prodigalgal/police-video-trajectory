"""Same demux/decode session, bounded asynchronous H.264 packet remuxing."""
from collections import OrderedDict
from contextlib import contextmanager
from fractions import Fraction
import errno
import hashlib
import queue
import sqlite3
import threading
import time
import uuid

from .live_recording_store import LiveStore,SEGMENT_BYTES,namespace_for
from .media_clips import digest_file,MediaError

PACKET_LIMIT=2*1024*1024
QUEUE_BYTES=8*1024*1024


def micros(value,base):return round(Fraction(value)*base*1000000)


class NativeRecorder:
    def __init__(self,settings,stream,context,count=lambda *args:None):
        import av
        if stream.codec_context.name!='h264' or not stream.time_base or not 0<stream.width<=20000 or not 0<stream.height<=20000 or stream.width*stream.height>50000000:raise MediaError('recording_unsupported_stream')
        for key in ('task_id','scene_run_id'):uuid.UUID(context[key])
        if not isinstance(context.get('worker_id'),str) or type(context.get('task_generation')) is not int or context['task_generation']<1:raise MediaError('recording_invalid_context')
        self.settings,self.stream,self.context,self.count=settings,stream,dict(context),count
        self.base=Fraction(stream.time_base);self.namespace=namespace_for(context['worker_id'])
        self.store=LiveStore(settings.live_recording_dir,self.namespace,writer=True,limit=settings.live_recording_bytes,retention=settings.live_recording_retention_seconds)
        self.queue=queue.Queue(maxsize=1024);self.lock=threading.Lock();self.queued_bytes=0;self.token=0;self.epoch=0
        self.current=None;self.key_pts=None;self.start_dts=None;self.last_dts=None;self.origin=None;self.wait_key=True
        self.decoded_until=None;self.width=stream.width;self.height=stream.height
        self.frames=OrderedDict();self.stopping=threading.Event();self.error=None
        self.thread=threading.Thread(target=self._write,name='recording-'+context['camera_id'],daemon=True);self.thread.start()

    def discontinuity(self,epoch,reason='recording_source_gap'):
        self.token+=1;self.epoch=epoch;self.current=None;self.frames.clear();self.wait_key=True;self.last_dts=None;self.decoded_until=None;self.count(reason)

    def packet(self,packet,epoch):
        import av
        try:self._packet(packet,epoch)
        except (OSError,ValueError,OverflowError,RuntimeError,av.error.FFmpegError):
            self.error='recording_packet_copy_failed';self.discontinuity(epoch,'recording_packet_copy_failed')

    def _packet(self,packet,epoch):
        import av
        if not packet.size:return
        if epoch!=self.epoch:self.discontinuity(epoch)
        if self.error is not None:return
        if packet.is_corrupt or packet.pts is None or packet.dts is None or not packet.time_base or Fraction(packet.time_base)!=self.base or packet.size>PACKET_LIMIT:
            self.discontinuity(epoch,'recording_packet_invalid');return
        pts=micros(packet.pts,self.base);dts=micros(packet.dts,self.base)
        if not -(2**63)<=pts<2**63 or not -(2**63)<=dts<2**63:
            self.discontinuity(epoch,'recording_packet_invalid');return
        if pts in self.frames:self.discontinuity(epoch,'recording_ambiguous_packet_clock');return
        if self.last_dts is not None and not 0<dts-self.last_dts<=2000000:self.discontinuity(epoch,'recording_clock_gap')
        self.last_dts=dts
        if self.wait_key:
            if not packet.is_keyframe:return
            self.current=str(uuid.uuid4());self.key_pts=packet.pts;self.start_dts=dts;self.wait_key=False
        elif packet.is_keyframe and dts-self.start_dts>=self.settings.live_recording_segment_seconds*1000000:
            self.current=str(uuid.uuid4());self.key_pts=packet.pts;self.start_dts=dts
        elif dts-self.start_dts>15000000:
            self.discontinuity(epoch,'recording_keyframe_gap');return
        if packet.pts<self.key_pts:
            self.discontinuity(epoch,'recording_open_gop_unsupported');return
        raw=bytes(packet);sha=hashlib.sha256(raw).hexdigest();copy=av.Packet(raw)
        copy.pts=packet.pts;copy.dts=packet.dts;copy.duration=packet.duration;copy.time_base=packet.time_base;copy.is_keyframe=packet.is_keyframe
        for side in packet.iter_sidedata():copy.set_sidedata(side)
        event=(self.token,self.current,self.key_pts,epoch,copy,pts,dts,sha)
        with self.lock:
            if self.queued_bytes+len(raw)>QUEUE_BYTES or self.queue.full():
                self.discontinuity(epoch,'recording_queue_overflow');return
            self.queue.put_nowait(event);self.queued_bytes+=len(raw)
        self.frames[pts]={'namespace':self.namespace,'segment_id':self.current,'source_pts_us':pts,'packet_sha256':sha,'media_epoch':epoch,'token':self.token}
        self.frames.move_to_end(pts)
        while len(self.frames)>2048:self.frames.popitem(last=False)
        self.count('recording_packets');self.count('recording_packet_bytes',len(raw))

    def observe_frame(self,pts_us,origin_us,epoch,width=None,height=None):
        self.origin=origin_us
        if epoch!=self.epoch:self.discontinuity(epoch)
        if width is not None and (width!=self.width or height!=self.height):self.error='recording_format_changed';self.discontinuity(epoch,'recording_format_changed');return
        self.decoded_until=pts_us

    def binding(self,pts_us,epoch):
        value=self.frames.get(pts_us)
        if self.error is not None or value is None or value['token']!=self.token or value['media_epoch']!=epoch:return None
        return {key:value[key] for key in ('namespace','segment_id','source_pts_us','packet_sha256')}

    def _write(self):
        import av
        current=None;output=None;destination=None;rows=[];key_pts=None;active_token=None;active_epoch=0;blocked=set()
        def abort(reason):
            nonlocal current,output,rows
            if output is not None:
                try:output.close()
                except av.error.FFmpegError:self.count('recording_close_errors')
                output=None
            if current is not None:self.store.abort(current,reason)
            current=None;rows=[];self.count('recording_segments_failed')
        def seal():
            nonlocal current,output,rows
            if output is None:return
            output.close();output=None
            if not rows or self.origin is None:abort('recording_clock_unavailable');return
            until=time.monotonic()+2
            maximum=max(row['pts_us'] for row in rows)
            while active_token==self.token and not self.stopping.is_set() and (self.decoded_until is None or self.decoded_until<maximum) and time.monotonic()<until:time.sleep(.01)
            if active_token!=self.token or self.decoded_until is None or self.decoded_until<maximum:abort('recording_decoder_incomplete');return
            path=self.store.path(current,True)/'source.mp4';actual=[]
            with av.open(str(path)) as written:
                track=written.streams.video[0]
                if track.codec_context.name!='h264' or not track.time_base or track.start_time is None or abs(micros(track.start_time,track.time_base))>2:raise MediaError('recording_output_clock_conflict')
                duration=round(float(track.duration*track.time_base)*1000000) if track.duration is not None else None
                for packet in written.demux(track):
                    if packet.size:
                        if packet.pts is None:raise MediaError('recording_output_clock_conflict')
                        actual.append(micros(packet.pts,packet.time_base))
            base=micros(key_pts,self.base)
            if duration is None or not 0<duration<=20000000 or len(actual)!=len(rows) or any(abs(a-(r['pts_us']-base))>2 for a,r in zip(actual,rows)):raise MediaError('recording_output_clock_conflict')
            manifest={'schema_version':'same-session-recording-v1','segment_id':current,'namespace':self.namespace,**self.context,
                'media_epoch':active_epoch,'origin_pts_us':self.origin,'playback_base_pts_us':base,'time_base_num':self.base.numerator,'time_base_den':self.base.denominator,
                'codec':'h264','width':self.width,'height':self.height,'duration_us':duration,'packet_refs':rows,'video':digest_file(path,SEGMENT_BYTES),
                'quality':{'first_packet_keyframe':True,'packet_sequence_complete':True,'source_clock':'same_reader_session','absolute_time_verified':False,'reencoded':False,'inference_invoked':False}}
            self.store.commit(current,manifest);self.count('recording_segments_ready');current=None;rows=[]
        try:
            while not self.stopping.is_set() or not self.queue.empty():
                if current is not None and active_token!=self.token:abort('recording_source_gap')
                try:event=self.queue.get(timeout=.1)
                except queue.Empty:continue
                token,identity,base,epoch,packet,pts,dts,sha=event
                with self.lock:self.queued_bytes-=packet.size
                if token!=self.token:continue
                if identity in blocked:continue
                try:
                    if current!=identity:
                        if current is not None:seal()
                        current=identity;rows=[];key_pts=base;active_token=token;active_epoch=epoch
                        path=self.store.reserve(identity,{**self.context,'media_epoch':epoch})
                        output=av.open(str(path/'source.mp4'),'w',options={'movflags':'+faststart'});destination=output.add_stream_from_template(self.stream)
                    packet.pts-=key_pts;packet.dts-=key_pts;packet.stream=destination
                    output.mux(packet);rows.append({'pts_us':pts,'dts_us':dts,'sha256':sha})
                    if len(rows)>1800 or (self.store.path(current,True)/'source.mp4').stat().st_size>SEGMENT_BYTES-256*1024:raise MediaError('recording_segment_too_large')
                except (OSError,ValueError,sqlite3.Error,av.error.FFmpegError) as error:
                    reason=str(error) if isinstance(error,MediaError) else 'recording_disk_full' if isinstance(error,OSError) and error.errno in {errno.ENOSPC,errno.EDQUOT} else 'recording_write_failed'
                    blocked.add(identity);abort(reason);self.count(reason)
                    if len(blocked)>512:blocked.clear()
            if current is not None:
                if active_token==self.token:seal()
                else:abort('recording_source_gap')
        except BaseException as error:
            self.error=type(error).__name__;self.count('recording_writer_stopped')
            try:abort('recording_writer_stopped')
            except (OSError,ValueError,sqlite3.Error):self.count('recording_abort_unconfirmed')
        finally:self.store.close()

    def close(self):
        self.stopping.set()
        while self.thread.is_alive():self.thread.join(1)


@contextmanager
def recording_context(reader,container):
    if not getattr(reader.settings,'live_recording_enabled',False):yield None;return
    recorder=None
    try:
        if reader.recording_context is None:raise MediaError('recording_context_missing')
        context={**reader.recording_context,'camera_id':reader.camera,'scene_run_id':reader.run_id}
        recorder=NativeRecorder(reader.settings,container.streams.video[0],context,reader.add)
    except (OSError,ValueError,sqlite3.Error):reader.add('recording_startup_failed')
    try:yield recorder
    finally:
        if recorder is not None:recorder.close()
