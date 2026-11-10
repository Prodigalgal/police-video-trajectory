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
from .recording_windows import (RecordingWindows,LEGACY_SCHEMA,CONTEXT_SCHEMA,
    LEGACY_SEGMENT_BYTES,MANIFEST_BYTES,MAX_DURATION_US,MAX_PACKETS)

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
        self.context_seconds=getattr(settings,'live_recording_context_seconds',0)
        self.windows=RecordingWindows(self.context_seconds,settings.live_recording_segment_seconds)
        self.segment_bytes=SEGMENT_BYTES if self.context_seconds else LEGACY_SEGMENT_BYTES
        self.store=LiveStore(settings.live_recording_dir,self.namespace,writer=True,limit=settings.live_recording_bytes,retention=settings.live_recording_retention_seconds,segment_bytes=self.segment_bytes)
        self.queue=queue.Queue(maxsize=1024);self.lock=threading.Lock();self.queued_bytes=0;self.token=0;self.epoch=0
        self.last_dts=None;self.last_key_dts=None;self.origin=None
        self.decoded_until=None;self.width=stream.width;self.height=stream.height
        self.frames=OrderedDict();self.stopping=threading.Event();self.error=None
        self.thread=threading.Thread(target=self._write,name='recording-'+context['camera_id'],daemon=True);self.thread.start()

    def discontinuity(self,epoch,reason='recording_source_gap'):
        self.token+=1;self.epoch=epoch;self.windows.clear();self.frames.clear();self.last_dts=None;self.last_key_dts=None;self.decoded_until=None;self.count(reason)

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
        if packet.is_keyframe:self.last_key_dts=dts
        elif self.last_key_dts is not None and dts-self.last_key_dts>15000000:
            self.discontinuity(epoch,'recording_keyframe_gap');return
        windows=self.windows.advance(packet.is_keyframe,packet.pts,pts,dts)
        if not windows:return
        if packet.pts<windows[-1].key_pts:
            self.discontinuity(epoch,'recording_open_gop_unsupported');return
        raw=bytes(packet);sha=hashlib.sha256(raw).hexdigest();copy=av.Packet(raw)
        copy.pts=packet.pts;copy.dts=packet.dts;copy.duration=packet.duration;copy.time_base=packet.time_base;copy.is_keyframe=packet.is_keyframe
        for side in packet.iter_sidedata():copy.set_sidedata(side)
        event=(self.token,windows,epoch,copy,pts,dts,sha)
        with self.lock:
            if self.queued_bytes+len(raw)>QUEUE_BYTES or self.queue.full():
                self.discontinuity(epoch,'recording_queue_overflow');return
            self.queue.put_nowait(event);self.queued_bytes+=len(raw)
        binding=self.windows.binding_window(pts)
        self.frames[pts]={'namespace':self.namespace,'segment_id':binding.identity,'source_pts_us':pts,'packet_sha256':sha,'media_epoch':epoch,'token':self.token}
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

    def finish_decoding(self):
        import av
        # Drain only packets already read. These tail frames certify the saved
        # segment; they do not restart capture or enqueue analysis after a stop.
        try:
            # An empty packet flushes FFmpeg and lets PyAV propagate the verified
            # input time_base; decode(None) leaves tail-frame time_base unset.
            flush=av.Packet();flush.time_base=self.base
            for frame in self.stream.codec_context.decode(flush):
                if frame.pts is None or frame.time_base is None or frame.is_corrupt or self.origin is None:
                    reason='recording_tail_clock_missing' if frame.pts is None or frame.time_base is None else 'recording_tail_corrupt' if frame.is_corrupt else 'recording_clock_unavailable'
                    self.discontinuity(self.epoch,reason);return
                pts=micros(frame.pts,frame.time_base)
                if self.decoded_until is not None and pts<=self.decoded_until:
                    self.discontinuity(self.epoch,'recording_frame_clock_gap');return
                self.observe_frame(pts,self.origin,self.epoch,frame.width,frame.height)
                self.count('recording_decoder_drained_frames')
        except av.error.EOFError:
            # Demux may already have delivered its normal end-of-stream flush.
            self.count('recording_decoder_already_drained')
        except (ValueError,OverflowError,av.error.FFmpegError):
            self.discontinuity(self.epoch,'recording_decoder_drain_failed')

    def _write(self):
        import av
        outputs={};blocked=set();active_token=None
        def abort_all(reason):
            for writer in outputs.values():writer.abort(reason)
            outputs.clear()
        def finish(identity):
            writer=outputs.pop(identity)
            try:writer.seal()
            except (OSError,ValueError,sqlite3.Error,av.error.FFmpegError) as error:
                reason=self._write_error(error);writer.abort(reason);self.count(reason)
        try:
            while not self.stopping.is_set() or not self.queue.empty():
                if outputs and active_token!=self.token:abort_all('recording_source_gap')
                try:event=self.queue.get(timeout=.1)
                except queue.Empty:continue
                token,windows,epoch,packet,pts,dts,sha=event
                with self.lock:self.queued_bytes-=packet.size
                if token!=self.token:continue
                active_token=token
                identities={window.identity for window in windows}
                for identity in tuple(outputs):
                    if identity not in identities:finish(identity)
                for window in windows:
                    if window.identity in blocked:continue
                    try:
                        if window.identity not in outputs:
                            outputs[window.identity]=PacketSegment(self,window,token,epoch)
                        outputs[window.identity].append(packet,pts,dts,sha)
                    except (OSError,ValueError,sqlite3.Error,av.error.FFmpegError) as error:
                        reason=self._write_error(error);blocked.add(window.identity)
                        writer=outputs.pop(window.identity,None)
                        if writer is not None:writer.abort(reason)
                        else:self.store.abort(window.identity,reason)
                        self.count(reason)
                blocked.intersection_update(identities)
            if active_token==self.token:
                for identity in tuple(outputs):finish(identity)
            else:abort_all('recording_source_gap')
        except BaseException as error:
            self.error=type(error).__name__;self.count('recording_writer_stopped')
            try:abort_all('recording_writer_stopped')
            except (OSError,ValueError,sqlite3.Error):self.count('recording_abort_unconfirmed')
        finally:self.store.close()

    @staticmethod
    def _write_error(error):
        if isinstance(error,MediaError):return str(error)
        if isinstance(error,OSError) and error.errno in {errno.ENOSPC,errno.EDQUOT}:return 'recording_disk_full'
        return 'recording_write_failed'

    def close(self):
        self.stopping.set()
        while self.thread.is_alive():self.thread.join(1)


class PacketSegment:
    def __init__(self,recorder,window,token,epoch):
        import av
        self.recorder,self.window,self.token,self.epoch=recorder,window,token,epoch
        self.output=None;self.rows=[]
        self.path=recorder.store.reserve(window.identity,{**recorder.context,'media_epoch':epoch})/'source.mp4'
        try:
            self.output=av.open(str(self.path),'w',options={'movflags':'+faststart'})
            self.destination=self.output.add_stream_from_template(recorder.stream)
        except BaseException:
            self.abort('recording_write_failed');raise

    def append(self,original,pts,dts,sha):
        import av
        packet=av.Packet(bytes(original));packet.pts=original.pts-self.window.key_pts;packet.dts=original.dts-self.window.key_pts
        packet.duration=original.duration;packet.time_base=original.time_base;packet.is_keyframe=original.is_keyframe
        for side in original.iter_sidedata():packet.set_sidedata(side)
        packet.stream=self.destination;self.output.mux(packet)
        self.rows.append({'pts_us':pts,'dts_us':dts,'sha256':sha})
        packet_limit=MAX_PACKETS if self.recorder.context_seconds else 1800
        metadata_limit=MANIFEST_BYTES if self.recorder.context_seconds else 256*1024
        if len(self.rows)>packet_limit or self.path.stat().st_size>self.recorder.segment_bytes-metadata_limit:raise MediaError('recording_segment_too_large')

    def abort(self,reason):
        import av
        if self.output is not None:
            try:self.output.close()
            except av.error.FFmpegError:self.recorder.count('recording_close_errors')
            self.output=None
        self.recorder.store.abort(self.window.identity,reason);self.recorder.count('recording_segments_failed')

    def seal(self):
        import av
        self.output.close();self.output=None;recorder=self.recorder
        if not self.rows or recorder.origin is None:raise MediaError('recording_clock_unavailable')
        until=time.monotonic()+2;maximum=max(row['pts_us'] for row in self.rows)
        while self.token==recorder.token and not recorder.stopping.is_set() and (recorder.decoded_until is None or recorder.decoded_until<maximum) and time.monotonic()<until:time.sleep(.01)
        if self.token!=recorder.token or recorder.decoded_until is None or recorder.decoded_until<maximum:raise MediaError('recording_decoder_incomplete')
        actual=[]
        with av.open(str(self.path)) as written:
            track=written.streams.video[0]
            if track.codec_context.name!='h264' or not track.time_base or track.start_time is None or abs(micros(track.start_time,track.time_base))>2:raise MediaError('recording_output_clock_conflict')
            duration=micros(track.duration,track.time_base) if track.duration is not None else None
            for packet in written.demux(track):
                if packet.size:
                    if packet.pts is None:raise MediaError('recording_output_clock_conflict')
                    actual.append(micros(packet.pts,packet.time_base))
        maximum_duration=MAX_DURATION_US if recorder.context_seconds else 20_000_000
        base=self.window.base_pts_us
        if duration is None or not 0<duration<=maximum_duration or len(actual)!=len(self.rows) or any(abs(a-(r['pts_us']-base))>2 for a,r in zip(actual,self.rows)):raise MediaError('recording_output_clock_conflict')
        manifest={'schema_version':CONTEXT_SCHEMA if recorder.context_seconds else LEGACY_SCHEMA,'segment_id':self.window.identity,'namespace':recorder.namespace,**recorder.context,
            'media_epoch':self.epoch,'origin_pts_us':recorder.origin,'playback_base_pts_us':base,'time_base_num':recorder.base.numerator,'time_base_den':recorder.base.denominator,
            'codec':'h264','width':recorder.width,'height':recorder.height,'duration_us':duration,'packet_refs':self.rows,'video':digest_file(self.path,recorder.segment_bytes),
            'quality':{'first_packet_keyframe':True,'packet_sequence_complete':True,'source_clock':'same_reader_session','absolute_time_verified':False,'reencoded':False,'inference_invoked':False}}
        if recorder.context_seconds:manifest['context_window']={'policy':'overlap-keyframe-v1','target_seconds':recorder.context_seconds}
        recorder.store.commit(self.window.identity,manifest);recorder.count('recording_segments_ready')


@contextmanager
def recording_context(reader,container):
    control=getattr(reader.settings,'recording_control',None)
    if control is not None:
        from .recording_control import RecordingSlot
        slot=RecordingSlot(reader,container,control)
        try:yield slot
        finally:slot.close()
        return
    if not getattr(reader.settings,'live_recording_enabled',False):yield None;return
    recorder=None
    try:
        if reader.recording_context is None:raise MediaError('recording_context_missing')
        context={**reader.recording_context,'camera_id':reader.camera,'scene_run_id':reader.run_id}
        recorder=NativeRecorder(reader.settings,container.streams.video[0],context,reader.add)
    except (OSError,ValueError,sqlite3.Error):reader.add('recording_startup_failed')
    try:yield recorder
    finally:
        if recorder is not None:
            try:recorder.finish_decoding()
            finally:recorder.close()
