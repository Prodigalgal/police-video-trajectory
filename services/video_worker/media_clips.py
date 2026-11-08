"""Bounded, immutable recorded-video clips and genuine timestamped samples.

This producer has no model client and never changes original recordings.
"""
import argparse
from contextlib import contextmanager
from fractions import Fraction
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import time
import uuid

MAX_ASSET_BYTES=64*1024*1024
MAX_MANIFEST_BYTES=64*1024
MAX_SOURCE_BYTES=8*1024*1024*1024
MAX_GAP_US=250000


class MediaError(ValueError):
    pass


def canonical(value):
    return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode('utf-8')


def digest_file(path,limit,deadline=None):
    if path.is_symlink():raise MediaError('media_unsafe_path')
    fd=os.open(path,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0));digest=hashlib.sha256();size=0
    with os.fdopen(fd,'rb') as stream:
        before=os.fstat(stream.fileno())
        if not stat.S_ISREG(before.st_mode) or before.st_size>limit:raise MediaError('media_file_too_large')
        while chunk:=stream.read(1024*1024):
            if deadline is not None and time.monotonic()>deadline:raise MediaError('media_timeout')
            size+=len(chunk)
            if size>limit:raise MediaError('media_file_too_large')
            digest.update(chunk)
        after=os.fstat(stream.fileno())
        if (before.st_ino,before.st_size,before.st_mtime_ns)!=(after.st_ino,after.st_size,after.st_mtime_ns) or size!=after.st_size:raise MediaError('media_source_changed')
    return {'bytes':size,'sha256':digest.hexdigest()}


@contextmanager
def writer_lock(root):
    path=root/'.media-writer.lock'
    if path.is_symlink():raise MediaError('media_unsafe_path')
    fd=os.open(path,os.O_RDWR|os.O_CREAT|getattr(os,'O_NOFOLLOW',0),0o600)
    with os.fdopen(fd,'r+b') as stream:
        if not stat.S_ISREG(os.fstat(stream.fileno()).st_mode):raise MediaError('media_unsafe_path')
        if not os.fstat(stream.fileno()).st_size:stream.write(b'\0');stream.flush()
        stream.seek(0)
        try:
            if os.name=='nt':
                import msvcrt
                msvcrt.locking(stream.fileno(),msvcrt.LK_NBLCK,1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(),fcntl.LOCK_EX|fcntl.LOCK_NB)
        except OSError:raise MediaError('media_store_busy') from None
        try:yield
        finally:
            stream.seek(0)
            if os.name=='nt':msvcrt.locking(stream.fileno(),msvcrt.LK_UNLCK,1)
            else:fcntl.flock(stream.fileno(),fcntl.LOCK_UN)


def sync_directory(path):
    if hasattr(os,'O_DIRECTORY'):
        fd=os.open(path,os.O_RDONLY|os.O_DIRECTORY)
        try:os.fsync(fd)
        finally:os.close(fd)


class ClipStore:
    def __init__(self,root,limit=2*1024*1024*1024,entries=64):
        requested=Path(root).absolute()
        if requested.is_symlink():raise MediaError('media_unsafe_path')
        requested.mkdir(parents=True,exist_ok=True,mode=0o700);self.root=requested.resolve()
        if type(limit) is not int or not MAX_ASSET_BYTES<=limit<=16*1024*1024*1024 or type(entries) is not int or not 1<=entries<=4096:raise MediaError('media_invalid_capacity')
        self.limit=limit;self.entries=entries

    def _remove_build(self,path):
        if path.is_symlink() or path.resolve().parent!=self.root or not re.fullmatch(r'\.building-[a-f0-9]{32}',path.name):raise MediaError('media_unsafe_path')
        marker=path/'owner.json'
        if marker.is_symlink() or not marker.is_file() or marker.stat().st_size>200:raise MediaError('media_unknown_staging')
        if json.loads(marker.read_bytes())!={'schema':'media-build-v1','clip_id':path.name.removeprefix('.building-')}:raise MediaError('media_unknown_staging')
        for child in path.iterdir():
            if child.is_symlink() or not child.is_file() or not re.fullmatch(r'(owner|manifest)\.json|clip\.mp4|sample-\d{3}\.jpg',child.name):raise MediaError('media_unknown_staging')
        shutil.rmtree(path)

    @contextmanager
    def building(self,clip_id):
        identity=uuid.UUID(clip_id)
        if str(identity)!=clip_id:raise MediaError('media_invalid_identity')
        with writer_lock(self.root):
            for stale in self.root.glob('.building-*'):self._remove_build(stale)
            folders=[p for p in self.root.iterdir() if re.fullmatch('[a-f0-9]{32}',p.name)]
            total=0
            for folder in folders:
                if folder.is_symlink() or not folder.is_dir():raise MediaError('media_unsafe_path')
                for file in folder.iterdir():
                    if file.is_symlink() or not file.is_file():raise MediaError('media_unsafe_path')
                    total+=file.stat().st_size
            final=self.root/identity.hex
            if not final.exists() and (len(folders)>=self.entries or total+MAX_ASSET_BYTES>self.limit):raise MediaError('media_capacity_reached')
            staging=self.root/('.building-'+identity.hex);staging.mkdir(mode=0o700)
            (staging/'owner.json').write_bytes(canonical({'schema':'media-build-v1','clip_id':identity.hex}))
            try:yield staging,final
            finally:
                if staging.exists():self._remove_build(staging)

    def commit(self,staging,final,manifest):
        raw=canonical(manifest)
        if len(raw)>MAX_MANIFEST_BYTES:raise MediaError('media_manifest_too_large')
        with (staging/'manifest.json').open('xb') as output:output.write(raw);output.flush();os.fsync(output.fileno())
        total=0
        for file in staging.iterdir():
            if file.name=='owner.json':continue
            if file.is_symlink() or not file.is_file():raise MediaError('media_unsafe_path')
            total+=file.stat().st_size
            with file.open('r+b') as source:os.fsync(source.fileno())
        if total>MAX_ASSET_BYTES:raise MediaError('media_file_too_large')
        if final.exists():
            if final.is_symlink() or (final/'manifest.json').is_symlink() or (final/'manifest.json').read_bytes()!=raw:raise MediaError('media_conflicting_replay')
            for file in staging.iterdir():
                if file.name!='owner.json' and digest_file(file,MAX_ASSET_BYTES)!=digest_file(final/file.name,MAX_ASSET_BYTES):raise MediaError('media_conflicting_replay')
            return hashlib.sha256(raw).hexdigest()
        # Keep the ownership marker until rename: an interrupted staging write
        # remains recoverable. Committed assets are never automatically removed.
        sync_directory(staging);os.rename(staging,final);sync_directory(self.root)
        return hashlib.sha256(raw).hexdigest()


def validate_request(source_id,camera,start_ms,end_ms,clip_id):
    for identity in (source_id,clip_id):
        if not isinstance(identity,str) or str(uuid.UUID(identity))!=identity:raise MediaError('media_invalid_identity')
    if not re.fullmatch(r'recording-0[1-4]',camera or '') or type(start_ms) is not int or type(end_ms) is not int or not 0<=start_ms<end_ms or not 2000<=end_ms-start_ms<=30000:raise MediaError('media_invalid_window')


def _decode(source,staging,start_ms,end_ms,deadline):
    import av
    import cv2
    cv2.setNumThreads(1)
    samples=[];positions=[];first=last=None;maximum_gap=0;next_video=next_sample=None;decoded=0
    with av.open(str(source),timeout=(15,20)) as recording:
        if not recording.streams.video:raise MediaError('media_video_missing')
        stream=recording.streams.video[0];stream.codec_context.thread_count=1
        if stream.start_time is None or not stream.time_base:raise MediaError('media_clock_unavailable')
        if not 0<stream.width<=20000 or not 0<stream.height<=20000 or stream.width*stream.height>50000000:raise MediaError('media_invalid_dimensions')
        origin=Fraction(stream.start_time)*stream.time_base
        recording.seek(int((origin+Fraction(start_ms,1000))/stream.time_base),stream=stream,backward=True)
        width=min(1280,stream.width)//2*2;height=max(2,int(stream.height*width/stream.width)//2*2)
        if width<2 or height>20000 or width*height>50000000:raise MediaError('media_invalid_dimensions')
        with av.open(str(staging/'clip.mp4'),'w',options={'movflags':'+faststart'}) as output:
            encoded=output.add_stream('libx264',rate=10);encoded.width=width;encoded.height=height;encoded.pix_fmt='yuv420p'
            encoded.codec_context.thread_count=1;encoded.codec_context.time_base=Fraction(1,1000);encoded.time_base=Fraction(1,1000)
            encoded.options={'preset':'veryfast','crf':'23','bf':'0'}
            for frame in recording.decode(stream):
                if time.monotonic()>deadline:raise MediaError('media_timeout')
                if frame.pts is None or not frame.time_base:raise MediaError('media_clock_unavailable')
                pts=int((Fraction(frame.pts)*frame.time_base-origin)*1000000)
                if pts<start_ms*1000:continue
                if pts>=end_ms*1000:break
                if frame.is_corrupt:raise MediaError('media_corrupt_frame')
                if last is not None:
                    gap=pts-last
                    if not 0<gap<=MAX_GAP_US:raise MediaError('media_clock_discontinuity')
                    maximum_gap=max(maximum_gap,gap)
                first=pts if first is None else first;last=pts;decoded+=1
                if decoded>18000:raise MediaError('media_frame_limit')
                video_due=next_video is None or pts>=next_video;sample_due=next_sample is None or pts>=next_sample
                if not video_due and not sample_due:continue
                resized=frame.reformat(width=width,height=height,format='yuv420p')
                if sample_due:
                    ok,jpeg=cv2.imencode('.jpg',resized.to_ndarray(format='bgr24'),[cv2.IMWRITE_JPEG_QUALITY,90])
                    if not ok or jpeg.nbytes>8*1024*1024 or len(samples)>=31:raise MediaError('media_sample_encoding_failed')
                    index=len(samples);raw=jpeg.tobytes();(staging/f'sample-{index:03}.jpg').write_bytes(raw)
                    samples.append({'index':index,'source_pts_us':pts,'offset_ms':round((pts-first)/1000),'bytes':len(raw),'sha256':hashlib.sha256(raw).hexdigest()})
                    next_sample=first+((pts-first)//1000000+1)*1000000
                if video_due:
                    resized.pts=round((pts-first)/1000);resized.time_base=Fraction(1,1000);positions.append(resized.pts)
                    for packet in encoded.encode(resized):output.mux(packet)
                    next_video=first+((pts-first)//100000+1)*100000
                if sum(p.stat().st_size for p in staging.iterdir() if p.is_file())>MAX_ASSET_BYTES:raise MediaError('media_file_too_large')
            for packet in encoded.encode():output.mux(packet)
    if first is None or decoded<2 or len(samples)<2 or first-start_ms*1000>MAX_GAP_US or end_ms*1000-last>MAX_GAP_US:raise MediaError('media_window_incomplete')
    actual=[]
    with av.open(str(staging/'clip.mp4')) as check:
        for frame in check.decode(video=0):
            if frame.pts is None or not frame.time_base or frame.is_corrupt or frame.width!=width or frame.height!=height:raise MediaError('media_output_invalid')
            actual.append(round(float(frame.pts*frame.time_base)*1000))
    if len(actual)!=len(positions) or any(abs(a-b)>1 for a,b in zip(actual,positions)):raise MediaError('media_output_clock_conflict')
    return {'video':{**digest_file(staging/'clip.mp4',MAX_ASSET_BYTES,deadline),'width':width,'height':height,'codec':'h264','frame_offsets_ms':positions},
        'samples':samples,'actual_start_us':first,'actual_end_us':last,'decoded_frames':decoded,'max_decoded_gap_us':maximum_gap,
        'producer_version':av.__version__}


def committed_result(final,expected,deadline):
    manifest_path=final/'manifest.json'
    if final.is_symlink() or digest_file(manifest_path,MAX_MANIFEST_BYTES,deadline)['bytes']==0:raise MediaError('media_conflicting_replay')
    manifest=json.loads(manifest_path.read_bytes())
    if not isinstance(manifest,dict) or any(manifest.get(key)!=value for key,value in expected.items()) or not isinstance(manifest.get('samples'),list) or not 2<=len(manifest['samples'])<=31:raise MediaError('media_conflicting_replay')
    for index,sample in enumerate(manifest['samples']):
        if sample.get('index')!=index or digest_file(final/f'sample-{index:03}.jpg',8*1024*1024,deadline)!={key:sample[key] for key in ('bytes','sha256')}:raise MediaError('media_conflicting_replay')
    if digest_file(final/'clip.mp4',MAX_ASSET_BYTES,deadline)!={key:manifest['video'][key] for key in ('bytes','sha256')}:raise MediaError('media_conflicting_replay')
    return {'clip_id':manifest['clip_id'],'manifest_sha256':hashlib.sha256(canonical(manifest)).hexdigest(),'manifest':manifest}


def produce(store,recordings,source_id,camera,start_ms,end_ms,clip_id,*,timeout=360,expected_source=None):
    validate_request(source_id,camera,start_ms,end_ms,clip_id)
    requested=Path(recordings).absolute()
    if requested.is_symlink():raise MediaError('media_unsafe_path')
    if type(timeout) not in (int,float) or not 0<timeout<=360:raise MediaError('media_invalid_timeout')
    source=requested.resolve()/(camera+'.mp4');deadline=time.monotonic()+timeout
    with store.building(clip_id) as (staging,final):
        original=digest_file(source,MAX_SOURCE_BYTES,deadline)
        if expected_source is not None and original!=expected_source:raise MediaError('media_source_changed')
        if final.exists():
            return committed_result(final,{'schema_version':'recorded-media-clip-v1','clip_id':clip_id,'source_id':source_id,'camera_id':camera,'requested_start_ms':start_ms,'requested_end_ms':end_ms,'source':original},deadline)
        decoded=_decode(source,staging,start_ms,end_ms,deadline)
        if digest_file(source,MAX_SOURCE_BYTES,deadline)!=original:raise MediaError('media_source_changed')
        manifest={'schema_version':'recorded-media-clip-v1','clip_id':clip_id,'source_id':source_id,'camera_id':camera,
            'source':original,'requested_start_ms':start_ms,'requested_end_ms':end_ms,'sample_period_ms':1000,
            'quality':{'window_complete':True,'source_clock':'recording_relative','absolute_time_verified':False,'identity_association_verified':False,'inference_invoked':False},**decoded}
        sha=store.commit(staging,final,manifest)
    return {'clip_id':clip_id,'manifest_sha256':sha,'manifest':manifest}


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--recordings',type=Path,required=True);parser.add_argument('--store',type=Path,required=True)
    parser.add_argument('--source-id',required=True);parser.add_argument('--camera',required=True);parser.add_argument('--clip-id',required=True)
    parser.add_argument('--start-ms',type=int,required=True);parser.add_argument('--end-ms',type=int,required=True);parser.add_argument('--register',action='store_true');args=parser.parse_args()
    result=produce(ClipStore(args.store),args.recordings,args.source_id,args.camera,args.start_ms,args.end_ms,args.clip_id)
    if args.register:
        import asyncio
        from .config import Settings
        settings=Settings.from_env();settings.validate('modules');asyncio.run(register(settings,result))
    print(json.dumps({'clip_id':result['clip_id'],'manifest_sha256':result['manifest_sha256'],'samples':len(result['manifest']['samples']),'new_model_requests':0}))


async def register(settings,result):
    from .client import JsonService,ServiceError
    import asyncio
    core=JsonService(settings.core_url,settings.core_ca,settings.worker_token,timeout=360)
    deadline=asyncio.get_running_loop().time()+360
    try:
        for attempt in range(3):
            try:
                remaining=deadline-asyncio.get_running_loop().time()
                if remaining<=0:raise MediaError('media_registration_timeout')
                receipt=await asyncio.wait_for(core.post('/internal/v1/media-clips/register',{'clipId':result['clip_id'],'manifestSha256':result['manifest_sha256'],'workerId':settings.worker_id}),remaining)
                if receipt.get('clip_id')!=result['clip_id'] or receipt.get('manifest_sha256')!=result['manifest_sha256'] or receipt.get('inference_invoked') is not False:raise MediaError('media_registration_ack_invalid')
                return receipt
            except ServiceError as error:
                if error.status not in {408,425,429,500,502,503,504} or attempt==2:raise MediaError('media_registration_unavailable') from None
                await asyncio.sleep(min(attempt+1,max(0,deadline-asyncio.get_running_loop().time())))
            except TimeoutError:raise MediaError('media_registration_timeout') from None
    finally:await core.close()


if __name__=='__main__':main()
