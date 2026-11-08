"""Opt-in road_v2 reader: fresh evidence, small motion, bounded selected frames.

Shares the same SceneFrame handoff/durable sink as the existing Worker. No
network model or core API calls are made by this reader.
"""
import hashlib
import math
import queue
import sqlite3
import time

from services.scene_analysis.motion_guard import SmallMotionGuard,guard_activity,guard_uncertain
from services.scene_analysis.prefilter import MotionComparison,quality,scene_state
from services.scene_analysis.temporal_gate import FreshEvidenceGate
from services.scene_analysis.temporal_selection import TemporalSelector
from services.scene_analysis.empty_verification import SelectiveEmptyVerifier
from .scene_gate import SceneGatePolicy


def run_temporal_reader(reader):
    import av
    import cv2
    import numpy as np
    from .frames import archive_path,simulator_url
    from .scene_pipeline import SceneFrame

    settings=reader.settings;started=time.monotonic();future=None;future_pts=None;future_epoch=None;future_quality=False
    future_crop=score_crop=None;score_quality=False;future_quiet=score_quiet=False
    verify_future=verify_request=verify_epoch=None
    verify_enabled=settings.scene_empty_verifier=='selective_yolox'
    if verify_enabled:
        from .scene_pipeline import local_detector
        verify_detector=local_detector(str(settings.scene_verifier_model),'yolox','raw',True)
    initial=None;last_pts=None;next_probe=None;next_base=None;next_detector=None
    score=None;score_pts=None;negative=0;uncertain_until=-1.;q=None;signals=None;brightness=None
    last_wall=None;last_digest=None;last_success_pts=None;archive_origin=None

    def new_state():
        return (FreshEvidenceGate(SceneGatePolicy(sample_seconds=settings.sample_seconds,
            fallback_seconds=settings.scene_fallback_seconds,active_hold_seconds=settings.scene_active_hold_seconds,empty_confirmations=1)),
            TemporalSelector(merge=settings.scene_candidate_merge,width=settings.scene_guard_width),SmallMotionGuard(),MotionComparison(10,['robust']),
            SelectiveEmptyVerifier(settings.scene_verifier_confirmations) if verify_enabled else None)
    gate,selector,guard,motion_probe,verifier=new_state()

    def emit(items,now):
        nonlocal last_wall,last_digest,last_success_pts
        for item in items:
            frame,pts,epoch,relative,metrics=item.payload
            if (reader.source_type=='rtsp' and last_wall is not None and time.monotonic()-last_wall<settings.scene_minimum_submission_seconds):
                reader.add('submission_budget_deferred');reader.add('temporal_budget_gaps');continue
            metrics={**metrics,'frame_selection':'temporal','selection_delay_ms':max(0,round((now-pts)*1000)),
                'decision_available_ms':max(0,round((now-initial)*1000)),
                'peak_representative':item.peak,'burst_id':item.burst_id,'candidate_merge':settings.scene_candidate_merge}
            ratio=1280/frame.width
            full=frame.reformat(width=1280,height=max(2,int(frame.height*ratio)//2*2),format='bgr24').to_ndarray()
            ok,jpeg=cv2.imencode('.jpg',full,[cv2.IMWRITE_JPEG_QUALITY,85])
            if not ok:raise ValueError('temporal JPEG encoding failed')
            reader.add('jpeg_encoded');raw=jpeg.tobytes();digest=hashlib.sha256(raw).hexdigest()
            if (item.state=='occupied_quiet' and settings.quiet_dedup_seconds>0 and digest==last_digest and last_success_pts is not None
                    and 0<=pts-last_success_pts<settings.quiet_dedup_seconds):reader.add('quiet_exact_duplicate_skipped');continue
            offset=max(0,round((pts-archive_origin)*1000)) if reader.source_type=='archive' else None
            selected=SceneFrame(raw,offset,item.state,item.reason,reader.run_id,epoch,relative,metrics)
            try:
                if reader.selected_sink is not None:reader.selected_sink(selected);reader.add('durable_frames')
                else:reader.outputs.put_nowait(selected)
                reader.add('selected_'+item.reason);reader.add('output_frames')
                last_wall=time.monotonic();last_digest=digest;last_success_pts=pts
            except queue.Full:reader.add('output_overflow_gaps')
            except (OSError,ValueError,sqlite3.Error):reader.add('durable_spool_gaps');last_wall=time.monotonic()

    def selector_counts():
        for name,value in selector.snapshot().items():
            with reader.lock:reader.counts[name]=value
        if verifier:
            for name,value in verifier.snapshot().items():
                with reader.lock:reader.counts[name]=value

    try:
        if reader.source_type=='rtsp':
            source=simulator_url(settings,reader.reference)
            options={'rtsp_transport':'tcp','tls_verify':'1','ca_file':settings.stream_ca,'timeout':'15000000'}
        else:source=str(archive_path(settings,reader.reference));options={}
        with av.open(source,options=options,timeout=(15,20)) as container:
            stream=container.streams.video[0];stream.codec_context.thread_count=1
            archive_origin=float(stream.start_time*stream.time_base) if stream.start_time is not None and stream.time_base is not None else None
            if reader.source_type=='archive' and archive_origin is None:raise ValueError('archive PTS origin unavailable')
            if reader.start_media_seconds:
                container.seek(int((archive_origin+reader.start_media_seconds)/float(stream.time_base)),stream=stream,backward=True,any_frame=False)
            for packet in container.demux(stream):
                if reader.stop.is_set() or reader.duration_seconds and time.monotonic()-started>=reader.duration_seconds:break
                try:frames=packet.decode()
                except av.error.FFmpegError:
                    reader.add('decode_errors');score=score_pts=None;negative=0;uncertain_until=(last_pts or 0)+15;continue
                for frame in frames:
                    if reader.stop.is_set():break
                    reader.add('decoded_frames')
                    if frame.pts is None or frame.time_base is None:
                        reader.add('missing_pts_frames');score=score_pts=None;negative=0;uncertain_until=(last_pts or 0)+15;continue
                    pts=float(frame.pts*frame.time_base)
                    if not math.isfinite(pts):reader.add('invalid_pts_frames');continue
                    if reader.source_type=='archive' and pts-archive_origin+1e-6<reader.start_media_seconds:continue
                    if last_pts is not None and (pts<last_pts or pts-last_pts>2):
                        if reader.source_type=='archive' and pts<last_pts:raise ValueError('archive PTS regressed')
                        emit(selector.finish(),last_pts);selector_counts();reader.add('pts_discontinuities');reader.epoch+=1
                        gate,selector,guard,motion_probe,verifier=new_state();initial=next_probe=next_base=next_detector=None
                        q=signals=brightness=None;score=score_pts=None;negative=0;uncertain_until=pts+15
                        last_digest=last_success_pts=None
                    initial=pts if initial is None else initial;last_pts=pts
                    if reader.max_media_seconds is not None and pts-initial>=reader.max_media_seconds:
                        reader.add('bounded_media_limit_reached');reader.stop.set();break
                    if frame.is_corrupt:
                        reader.add('corrupt_frames');score=score_pts=None;negative=0;uncertain_until=pts+15;continue
                    if next_probe is not None and pts+.001<next_probe:continue
                    if next_probe is None:next_probe=pts
                    missed=max(0,math.floor((pts+.001-next_probe)/.2));reader.add('missed_probe_ticks',missed)
                    next_probe+=(missed+1)*.2;reader.add('scene_probes')
                    image=frame.reformat(width=320,height=max(2,int(frame.height*320/frame.width)//2*2),format='bgr24').to_ndarray()
                    height,width=image.shape[:2];x1,y1,x2,y2=reader.roi
                    crop=image[int(y1*height):max(int(y1*height)+1,int(y2*height)),int(x1*width):max(int(x1*width)+1,int(x2*width))]
                    base_update=next_base is None or pts+.001>=next_base
                    if base_update:
                        if next_base is None:next_base=pts
                        next_base+=(max(0,math.floor(pts+.001-next_base))+1)
                        q=quality(crop);signals=motion_probe.observe(crop)
                        brightness=float(cv2.resize(cv2.cvtColor(crop,cv2.COLOR_BGR2GRAY),(80,60),interpolation=cv2.INTER_AREA).mean())
                        reader.add('base_scene_probes')
                    guard_crop=crop
                    if settings.scene_guard_width==640:
                        larger=frame.reformat(width=640,height=max(2,int(frame.height*640/frame.width)//2*2),format='bgr24').to_ndarray()
                        h,w=larger.shape[:2];guard_crop=larger[int(y1*h):max(int(y1*h)+1,int(y2*h)),int(x1*w):max(int(x1*w)+1,int(x2*w))]
                    small_guard=guard.observe(guard_crop);reader.add('small_motion_probes')
                    source_fraction=signals['robust_diff_fraction'] if base_update else 0.
                    source_quiet=(q['usable_for_empty_confirmation'] and not signals['warming_up'] and not signals['shake']
                        and not guard_uncertain(small_guard) and not guard_activity(small_guard,settings.scene_guard_width)
                        and source_fraction is not None and source_fraction<.01 and pts>=uncertain_until)
                    if future is not None and not source_quiet:future_quiet=False
                    update=False;result_quality=False
                    if future is not None and future.done():
                        try:
                            value=float(future.result())
                            if not math.isfinite(value) or not 0<=value<=1:raise ValueError('invalid presence score')
                            if future_epoch!=reader.epoch:reader.add('detector_old_epoch_ignored');score=score_pts=None
                            else:
                                score=value;score_pts=future_pts;result_quality=future_quality;update=True;reader.add('detector_results')
                                score_crop=future_crop;score_quality=future_quality;score_quiet=future_quiet
                        except Exception:score=score_pts=None;negative=0;update=True;reader.add('detector_errors')
                        future=None
                    if future is None and (next_detector is None or pts+.001>=next_detector):
                        future_crop=crop.copy();future=reader.detector.submit(future_crop);future_pts=pts;future_epoch=reader.epoch;future_quality=q['usable_for_empty_confirmation']
                        future_quiet=source_quiet
                        next_detector=pts+settings.scene_detector_seconds;reader.add('detector_submitted')
                        if reader.source_type=='archive':
                            try:
                                value=float(future.result(timeout=10))
                                if not math.isfinite(value) or not 0<=value<=1:raise ValueError('invalid presence score')
                                score=value;score_pts=pts;result_quality=future_quality;reader.add('detector_results')
                                score_crop=future_crop;score_quality=future_quality;score_quiet=future_quiet
                            except Exception:score=score_pts=None;negative=0;reader.add('detector_errors')
                            if future.done() or future.cancel():future=None
                            update=True
                    current=score if score_pts is not None and 0<=pts-score_pts<=max(2,settings.scene_detector_seconds+1) else None
                    fraction=signals['robust_diff_fraction'] if base_update else 0.
                    if guard_activity(small_guard,settings.scene_guard_width):fraction=max(fraction or 0.,.02)
                    uncertain=guard_uncertain(small_guard) or pts<uncertain_until
                    if verifier:
                        request=verifier.observe(pts-initial,current,score_pts-initial if score_pts is not None else None,
                            primary_fresh=update,quality_ok=score_quality and score_quiet and q['usable_for_empty_confirmation'] and not uncertain
                                and not signals['warming_up'] and not signals['shake'],
                            activity=fraction is not None and fraction>=.01)
                        if verify_future is not None and verify_future.done():
                            if verify_epoch==reader.epoch:
                                try:verified=float(verify_future.result())
                                except Exception:verified=None
                                verifier.complete(verify_request,pts-initial,verified)
                            else:reader.add('verifier_old_epoch_ignored')
                            verify_future=verify_request=None
                        if request is not None:
                            if verify_future is not None or score_crop is None:
                                verifier.complete(request,pts-initial,None);reader.add('verifier_busy_skipped')
                            else:
                                verify_future=verify_detector.submit(score_crop.copy());verify_request=request;verify_epoch=reader.epoch
                                if reader.source_type=='archive':
                                    try:verified=float(verify_future.result(timeout=10))
                                    except Exception:verified=None
                                    verifier.complete(verify_request,pts-initial,verified)
                                    if verify_future.done() or verify_future.cancel():verify_future=verify_request=None
                        current,update=verifier.decision()
                        if update and verifier.completed_now:result_quality=score_quality and q['usable_for_empty_confirmation']
                    if update:negative=negative+1 if current is not None and current<.1 and result_quality and q['usable_for_empty_confirmation'] else 0
                    if current is None:negative=0
                    category=scene_state(fraction,current,q['usable_for_empty_confirmation'] and not uncertain,
                        3 if negative>=settings.scene_negative_confirmations else 0,warming_up=signals['warming_up'],shake=signals['shake'] or uncertain)
                    state,reason=gate.observe(pts-initial,None if fraction is None else 10. if fraction>=.01 else 0.,current,
                        detector_update=update,quality_ok=8<=brightness<=247 and category!='unknown' and not uncertain)
                    if state=='active' and category=='occupied_quiet':state='occupied_quiet'
                    reader.last_state=state;reader.add('state_'+state);reader.add('road_'+category)
                    metrics={'policy':'road_v2','scene_category':category,'quality':q,'motion':signals,'small_motion':small_guard,
                        'guard_width':settings.scene_guard_width,'negative_updates':negative,
                        'detector_age_ms':round((pts-score_pts)*1000) if score_pts is not None else None}
                    if verifier:metrics['empty_verifier']=verifier.snapshot()
                    if reader.scene_observer is not None:
                        reader.scene_observer({'offset_ms':max(0,round((pts-archive_origin)*1000)) if reader.source_type=='archive' else None,
                            'relative_media_ms':max(0,round((pts-initial)*1000)), 'state':state,'reason':reason,'scene_category':category})
                    payload=(frame,pts,reader.epoch,max(0,round((pts-initial)*1000)),metrics)
                    size=sum(p.buffer_size for p in frame.planes)
                    ready=selector.offer(max(0,round((pts-initial)*1000)),state,reason,q,small_guard,payload,size,
                        warming_up=signals['warming_up'],shake=signals['shake'] or uncertain)
                    emit(ready,pts);selector_counts();reader.flush()
            if last_pts is not None:emit(selector.finish(),last_pts);selector_counts()
            if not reader.stop.is_set() and reader.source_type=='rtsp' and reader.duration_seconds is None:reader.error_type='stream_ended'
    except Exception as error:reader.error_type=type(error).__name__
    finally:
        if future is not None:future.cancel()
        if verify_future is not None:verify_future.cancel()
        reader.flush(final=True);reader.finished.set()
