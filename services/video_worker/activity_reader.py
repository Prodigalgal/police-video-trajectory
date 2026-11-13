"""Motion first -> effective JPEGs -> existing durable target pipeline.

Stationary people/vehicles do not block business-empty decisions. Static audit
ticks are counted separately and never become VLM analysis requests.
"""
import math
import hashlib
import json
import queue
import sqlite3
import time
from datetime import datetime,timezone

from services.scene_analysis.activity_gate import ActivityGate,ActivityPolicy
from services.scene_analysis.activity_regions import ActivityRegions
from services.scene_analysis.motion_guard import SmallMotionGuard
from services.scene_analysis.prefilter import MotionComparison,quality
from services.scene_analysis.foreground_activity import spatial_activity


def run_activity_reader(reader):
    import av
    import cv2
    from .frames import archive_path,simulator_url
    from .scene_pipeline import SceneFrame

    settings=reader.settings
    policy=ActivityPolicy(settings.sample_seconds,settings.scene_active_hold_seconds,settings.scene_fallback_seconds,
                          settings.scene_guard_width,settings.scene_motion_method)
    policy.validate();cv2.setNumThreads(1);av.logging.set_level(av.logging.PANIC)
    from .motion_context import MotionContextRing,motion_witness_usable
    witness_ring=MotionContextRing() if getattr(settings,'scene_motion_context','off')=='observe' else None
    spatial_mode=getattr(settings,'scene_activity_evidence','legacy')
    def reset():
        if witness_ring is not None:witness_ring.clear()
        return ActivityGate(policy),ActivityRegions(policy.quiet_seconds),SmallMotionGuard(include_regions=True),MotionComparison(10,[policy.motion_method],retain_foreground=spatial_mode!='legacy')
    gate,regions,guard,motion=reset()
    first_pts=last_pts=next_probe=None;origin=None;last_submitted_wall=None
    from .audit_samples import AuditSchedule,AuditStore
    audit_schedule=AuditSchedule(settings.scene_audit_seconds,reader.run_id) if settings.scene_audit_enabled else None
    audit_store=None
    started=time.monotonic();bounded_duration_completed=False
    try:
        if reader.source_type=='rtsp':
            source=simulator_url(settings,reader.reference)
            options={'rtsp_transport':'tcp','tls_verify':'1','ca_file':settings.stream_ca,'timeout':'15000000'}
        else:
            source=str(archive_path(settings,reader.reference));options={}
        with av.open(source,options=options,timeout=(15,20)) as container:
            stream=container.streams.video[0];stream.codec_context.thread_count=1
            origin=float(stream.start_time*stream.time_base) if stream.start_time is not None and stream.time_base is not None else None
            if reader.source_type=='archive' and origin is None:raise ValueError('archive PTS origin unavailable')
            if reader.source_type=='archive' and reader.archive_progress is not None:
                duration=float(stream.duration*stream.time_base) if stream.duration is not None else float(container.duration/1000000) if container.duration is not None else None
                if duration is None or not math.isfinite(duration) or reader.emit_after_ms is None or reader.emit_after_ms>round(duration*1000)+1000 or reader.end_media_ms is not None and reader.end_media_ms>round(duration*1000)+50:raise ValueError('managed archive window unavailable')
                if reader.start_media_seconds:container.seek(int((origin+reader.start_media_seconds)/float(stream.time_base)),stream=stream,backward=True,any_frame=False)
            for packet in container.demux(stream):
                if reader.stop.is_set() or reader.window_complete:break
                if reader.duration_seconds and time.monotonic()-started>=reader.duration_seconds:
                    bounded_duration_completed=True;reader.add('bounded_duration_completed');break
                try:frames=packet.decode()
                except av.error.FFmpegError:
                    reader.add('decode_errors');gate,regions,guard,motion=reset();continue
                for frame in frames:
                    if reader.stop.is_set():break
                    reader.add('decoded_frames')
                    if frame.pts is None or frame.time_base is None:
                        reader.add('missing_pts_frames');gate,regions,guard,motion=reset();continue
                    pts=float(frame.pts*frame.time_base)
                    if not math.isfinite(pts):
                        reader.add('invalid_pts_frames');gate,regions,guard,motion=reset();continue
                    offset_ms=max(0,round((pts-origin)*1000)) if reader.source_type=='archive' else None
                    if reader.source_type=='archive' and pts-origin+1e-6<reader.start_media_seconds:continue
                    if reader.end_media_ms is not None and offset_ms>=reader.end_media_ms:
                        reader.window_complete=True;break
                    if last_pts is not None and (pts<=last_pts or pts-last_pts>2):
                        if reader.source_type=='archive' and pts<last_pts:raise ValueError('archive PTS regressed')
                        reader.add('pts_discontinuities');reader.epoch+=1
                        gate,regions,guard,motion=reset();next_probe=None
                    first_pts=pts if first_pts is None else first_pts;last_pts=pts
                    if reader.max_media_seconds is not None and pts-first_pts>=reader.max_media_seconds:
                        reader.add('bounded_media_limit_reached');reader.stop.set();break
                    if frame.is_corrupt:
                        reader.add('corrupt_frames');gate,regions,guard,motion=reset();continue
                    if next_probe is None:
                        # Keep the managed probe grid tied to the original media clock after a seek.
                        next_probe=origin+math.ceil((pts-origin-.001)/.2)*.2 if reader.archive_progress is not None else pts
                    if pts+.001<next_probe:continue
                    if reader.source_type=='archive':reader.archive_offset_ms=offset_ms
                    missed=max(0,math.floor((pts+.001-next_probe)/.2));next_probe+=(missed+1)*.2
                    reader.add('missed_probe_ticks',missed)
                    if missed:gate,regions,guard,motion=reset()
                    image=frame.reformat(width=policy.guard_width,height=max(2,int(frame.height*policy.guard_width/frame.width)//2*2),format='bgr24').to_ndarray()
                    height,width=image.shape[:2];x1,y1,x2,y2=reader.roi
                    crop=image[max(0,int(y1*height)):min(height,int(y2*height)),max(0,int(x1*width)):min(width,int(x2*width))]
                    if min(crop.shape[:2])<16:raise ValueError('activity ROI is too small')
                    g=guard.observe(crop);signals=motion.observe(crop);q=quality(crop)
                    if spatial_mode!='legacy':
                        g,spatial=spatial_activity(g,guard.difference_mask,motion.foregrounds.get(policy.motion_method),
                            mode=spatial_mode,warming_up=signals['warming_up'])
                    decision=gate.observe(pts,g,signals,quality_ok=q['usable_for_empty_confirmation'])
                    activity=regions.observe(pts,g,decision['activity_now'],reader.roi,
                                             observation_complete=decision['observation_complete'])
                    reader.add('scene_probes');reader.add('state_'+decision['state'])
                    if decision['state']!=reader.last_state:reader.add('state_transitions')
                    reader.last_state=decision['state']
                    if decision['audit_only']:reader.add('static_audit_ticks_no_analysis')
                    if not decision['observation_complete']:reader.add('activity_unknown_observations')
                    if not activity['regions_complete']:reader.add('activity_incomplete_region_maps')
                    metrics={'policy':'activity_v1','motion_method':policy.motion_method,'quality':q,'motion':signals,
                             'activity':activity,'activity_now':decision['activity_now'],'semantic_presence_used':False}
                    if spatial_mode!='legacy':metrics['spatial_activity']=spatial
                    if witness_ring is not None:
                        try:
                            witness_ring.observe(image,pts,pts-first_pts,(reader.camera,reader.run_id,reader.epoch),
                                                 usable=motion_witness_usable(q))
                            reader.add('motion_context_probes')
                        except (ValueError,TypeError,OverflowError,cv2.error):
                            witness_ring.clear();reader.add('motion_context_probe_failures')
                    if audit_schedule is not None:
                        due,skipped=audit_schedule.observe(pts,reader.epoch)
                        reader.add('audit_sampling_gaps',skipped)
                        if due:
                            try:
                                if audit_store is None:
                                    audit_store=AuditStore(settings.evidence_dir/'.prefilter-audit',settings.scene_audit_bytes,
                                        settings.scene_audit_entries,settings.scene_audit_retention_seconds)
                                # Independent timer samples every state, before
                                # either candidate or target routing can drop it.
                                audit_image=frame.reformat(width=640,height=max(2,int(frame.height*640/frame.width)//2*2),format='bgr24').to_ndarray()
                                ok,audit_jpeg=cv2.imencode('.jpg',audit_image,[cv2.IMWRITE_JPEG_QUALITY,85])
                                if not ok:raise ValueError('audit JPEG encoding failed')
                                relative_ms=max(0,round((pts-first_pts)*1000))
                                key=hashlib.sha256((reader.camera+'|'+reader.run_id+'|'+str(reader.epoch)+'|'+str(relative_ms)).encode()).hexdigest()
                                policy_config={'policy':'activity_v1','method':policy.motion_method,'quiet_seconds':policy.quiet_seconds,
                                    'effective_frame_seconds':settings.sample_seconds,'probe_seconds':.2,'roi':reader.roi}
                                if spatial_mode!='legacy':policy_config['spatial_activity']=spatial_mode
                                metadata={'audit_version':'independent-media-v1','review_state':'unreviewed','human_confirmed':False,
                                    'camera_id':reader.camera,'source_type':reader.source_type,'source_reference':reader.reference,
                                    'worker_id':settings.worker_id,'scene_run_id':reader.run_id,'media_epoch':reader.epoch,
                                    'relative_media_ms':relative_ms,'media_offset_ms':max(0,round((pts-origin)*1000)) if reader.source_type=='archive' else None,
                                    'source_time':None,'observed_at':datetime.now(timezone.utc).isoformat(),
                                    'image_width':audit_image.shape[1],'image_height':audit_image.shape[0],
                                    'sampling_seconds':settings.scene_audit_seconds,'sampling_phase_seconds':audit_schedule.phase,
                                    'policy_config':policy_config,'policy_sha256':hashlib.sha256(json.dumps(policy_config,sort_keys=True).encode()).hexdigest(),
                                    'scene_state':decision['state'],'candidate_reason':decision['reason'],'metrics':metrics,
                                    'sent_to_model':False,'opencv_version':cv2.__version__}
                                if audit_store.put(key,audit_jpeg.tobytes(),metadata):reader.add('audit_samples_saved')
                            except (OSError,ValueError,sqlite3.Error):
                                reader.add('audit_samples_failed')
                    if reader.scene_observer is not None:
                        reader.scene_observer({'offset_ms':round((pts-origin)*1000) if origin is not None else None,
                            'relative_media_ms':round((pts-first_pts)*1000),'state':decision['state'],
                            'reason':decision['reason'],'audit_only':decision['audit_only'],'metrics':metrics})
                    if reader.emit_after_ms is not None and offset_ms<reader.emit_after_ms:
                        reader.add('resume_warmup_probes');reader.flush();continue
                    if decision['reason'] is None:
                        reader.add('empty_skipped' if decision['state']=='empty_candidate' else 'interval_skipped');reader.flush();continue
                    if (reader.source_type=='rtsp' and last_submitted_wall is not None and
                            time.monotonic()-last_submitted_wall<settings.scene_minimum_submission_seconds):
                        reader.add('submission_budget_deferred');reader.flush();continue
                    # High-resolution materialization happens after measured activity.
                    ratio=1280/frame.width
                    full=frame.reformat(width=1280,height=max(2,int(frame.height*ratio)//2*2),format='bgr24').to_ndarray()
                    ok,jpeg=cv2.imencode('.jpg',full,[cv2.IMWRITE_JPEG_QUALITY,85])
                    if not ok:raise ValueError('activity JPEG encoding failed')
                    reader.add('jpeg_encoded')
                    offset=max(0,round((pts-origin)*1000)) if reader.source_type=='archive' else None
                    context=None
                    if witness_ring is not None:
                        try:
                            source={'camera_id':reader.camera,'scene_run_id':reader.run_id,'media_epoch':reader.epoch,
                                'relative_media_ms':max(0,round((pts-first_pts)*1000)),'media_offset_ms':offset,
                                'source_type':reader.source_type,'source_reference':reader.reference}
                            context=witness_ring.snapshot(hashlib.sha256(jpeg.tobytes()).hexdigest(),full.shape[1],full.shape[0],source)
                            reader.add('motion_context_encoded');reader.add('motion_context_encoded_bytes',len(context))
                        except (ValueError,TypeError,OverflowError,cv2.error):reader.add('motion_context_encoding_failures')
                    selected=SceneFrame(jpeg.tobytes(),offset,decision['state'],decision['reason'],reader.run_id,
                                        reader.epoch,max(0,round((pts-first_pts)*1000)),metrics,context)
                    try:
                        if reader.selected_sink is not None:reader.selected_sink(selected);reader.add('durable_frames')
                        else:reader.outputs.put_nowait(selected)
                        reader.add('selected_'+decision['reason']);reader.add('output_frames');last_submitted_wall=time.monotonic()
                    except queue.Full:reader.add('output_overflow_gaps')
                    except (OSError,ValueError,sqlite3.Error):reader.add('durable_spool_gaps')
                    reader.flush()
            if reader.source_type=='rtsp' and not reader.stop.is_set() and not bounded_duration_completed:reader.error_type='stream_ended'
    except Exception as error:
        reader.error_type=type(error).__name__
    finally:
        reader.flush(final=True);reader.finished.set()
