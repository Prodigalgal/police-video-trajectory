"""Durable selected-frame dispatch, independent of the live decoder."""
import asyncio
from datetime import datetime, timezone
import json
import sqlite3
import time
import uuid

from .client import ServiceError
from .events import log
from .scene_pipeline import SceneReader, local_detector
from .target_pipeline import TargetPlanner

DELIVERY_RETRY_BASE_SECONDS = 1.
DELIVERY_RETRY_MAX_SECONDS = 15.
SERVICE_BACKPRESSURE_STATUS = {401, 403, 408, 429, 500, 502, 503, 504}


async def capture_source(settings, spool, task, worker, core=None):
    checkpoint=None;options={}
    if task.get('managed'):
        from .managed_source import verify_input,reconcile_spool,ArchiveCheckpoint,ManagedSourceError
        if core is None:raise ManagedSourceError('source_input_invalid')
        # Verification/reconciliation happen before a new decoder can emit another capture.
        value=await asyncio.to_thread(verify_input,settings,task)
        known=await reconcile_spool(core,spool,task,worker)
        if task['sourceType']=='archive':
            checkpoint=ArchiveCheckpoint(task,worker,known,value.get('local_file_marker'))
            options.update(start_media_seconds=max(0,checkpoint.cut-5000)/1000,emit_after_ms=checkpoint.cut,end_media_ms=value['end_ms'],archive_progress=checkpoint.flush)
    def selected_sink(selected):
        offset = selected.media_offset_ms if task['sourceType'] == 'archive' else None
        frame_id = str(uuid.uuid5(uuid.UUID(task['taskId']), 'offset-ms:' + str(offset))) if offset is not None else str(uuid.uuid4())
        if checkpoint is not None and checkpoint.reused(offset):return
        metadata = {'task_id': task['taskId'], 'worker_id': worker, 'generation': task['generation'],
                    'camera_id': task['cameraId'], 'source_type': task['sourceType'], 'media_offset_ms': offset,
                    'source_time': None, 'scene_run_id': selected.run_id, 'media_epoch': selected.media_epoch,
                    'relative_media_ms': selected.relative_media_ms, 'scene_state': selected.state,
                    'selection_reason': selected.reason, 'selected_at': datetime.now(timezone.utc).isoformat()}
        if task.get('managed'):metadata['source_input_sha256']=task['managed']['input_sha256']
        if selected.selection_metrics is not None:metadata['selection_metrics']=selected.selection_metrics
        # The decoder only waits for its local commit; no network or VLM wait.
        if getattr(settings,'scene_motion_context','off')=='observe':
            from .motion_context import bind_context
            import hashlib
            metadata['source_reference']=task['sourceRef']
            context=getattr(selected,'motion_context',None)
            if context is None:metadata['motion_context_capture_issue']='not_encoded'
            if context is not None:
                try:context=bind_context(context,frame_id,hashlib.sha256(selected.jpeg).hexdigest(),metadata)
                except (ValueError,TypeError,KeyError,OverflowError):
                    context=None;metadata['motion_context_capture_issue']='binding_rejected'
            spool.put(frame_id,selected.jpeg,metadata,motion_context=context)
        else:spool.put(frame_id, selected.jpeg, metadata)
        if checkpoint is not None:checkpoint.captured(frame_id)

    reader = SceneReader(settings, task['sourceRef'], task['sourceType'], selected_sink=selected_sink,**options)
    reader.thread.start()
    try:
        while not reader.finished.is_set():
            if checkpoint is not None:
                from .managed_source import commit_checkpoint,ManagedSourceError
                if checkpoint.error:raise ManagedSourceError(checkpoint.error)
                pending=checkpoint.waiting()
                if pending is not None:await commit_checkpoint(core,spool,task,checkpoint,pending[0])
            await asyncio.sleep(.25)
        if checkpoint is not None and checkpoint.error:raise ManagedSourceError(checkpoint.error)
        if task.get('managed') and reader.error_type:raise ManagedSourceError('source_decode_error')
        if reader.error_type or task['sourceType'] == 'rtsp':
            raise RuntimeError('durable scene reader ended')
        # Keep the archive lease alive until the final capture is registered.
        # Queue activation may happen later using its immutable receipt.
        while await asyncio.to_thread(spool.unregistered, task['taskId'], task['generation']):
            await asyncio.sleep(2)
    finally:
        reader.stop.set()
        while reader.thread.is_alive():
            await asyncio.to_thread(reader.thread.join, 1)


async def prepare_selected(settings, spool):
    """Single ordered planner; Core latency cannot hold this local stage."""
    planner_options={}
    if getattr(settings,'target_motion_mode','off')!='off':planner_options['motion_mode']=settings.target_motion_mode
    if getattr(settings,'target_duplicate_mode','off')!='off':planner_options['duplicate_mode']=settings.target_duplicate_mode
    if getattr(settings,'scene_motion_context','off')=='observe':planner_options['short_motion_mode']='observe'
    if getattr(settings,'target_short_motion_strategy','legacy')!='legacy':planner_options['short_motion_strategy']=settings.target_short_motion_strategy
    if getattr(settings,'target_stationary_filter','off')!='off':planner_options['stationary_filter_mode']=settings.target_stationary_filter
    if getattr(settings,'specialist_selection_mode','off')!='off':planner_options['specialist_selection_mode']=settings.specialist_selection_mode
    if getattr(settings,'processing_catalog_file',''):planner_options['processing_catalog_file']=settings.processing_catalog_file
    if getattr(settings,'specialist_models_root',''):planner_options['specialist_models_root']=settings.specialist_models_root
    planner = TargetPlanner(local_detector(str(settings.target_model or settings.scene_model),settings.target_detector_kind,
        settings.target_detector_variant,settings.scene_policy=='road_v1'), settings.target_threshold,settings.target_quality_mode,
        **planner_options)
    last_report = time.monotonic()
    try:
        while True:
            # Clear before querying durable state so a concurrent commit wakes us.
            spool.clear_pending_notification('prepare')
            # Failed records retain the existing whole-second 2-3 second backoff.
            pending = await asyncio.to_thread(spool.pending, retry_before=max(0, int(time.time()) - 3), stage='prepare')
            for frame_id in pending:
                failed = False
                try:
                    record = await asyncio.to_thread(spool.get, frame_id)
                    if record is None:
                        continue
                    if record['plan'] is None and record['receipt'] is None:
                        options={}
                        if getattr(settings,'scene_motion_context','off')=='observe':
                            options={'motion_context':record.get('motion_context'),'motion_context_issue':record.get('motion_context_issue')}
                        plan = await asyncio.to_thread(planner.plan, frame_id, record['payload'], record['metadata'],**options)
                        await asyncio.to_thread(spool.attach, frame_id, record['sha256'], 'plan', plan)
                except (OSError, ValueError, KeyError, TypeError, sqlite3.Error):
                    failed = True
                    log(json.dumps({'event': 'capture_preparation_deferred', 'frame_id': frame_id, 'status': 'retained'}))
                finally:
                    if failed:
                        await asyncio.to_thread(spool.attempted, frame_id)
            if time.monotonic() - last_report >= 30:
                log(json.dumps({'event': 'selected_frame_spool', **await asyncio.to_thread(spool.status)}))
                last_report = time.monotonic()
            if not pending:
                await asyncio.to_thread(spool.wait_for_pending_notification, .5, 'prepare')
    finally:
        planner.close()


async def deliver_selected(core, store, spool, *, analysis_handoff=True):
    """Retry persisted plans/receipts without rerunning target inference."""
    retry_delay, retry_at = DELIVERY_RETRY_BASE_SECONDS, 0.
    while True:
        remaining = retry_at-time.monotonic()
        if remaining > 0:
            # A service-wide outage/full queue affects all frames. Per-record
            # retry alone scales request/I/O pressure with the entire backlog.
            # Local preparation continues independently during this cooldown.
            await asyncio.sleep(min(remaining, .5))
            continue
        spool.clear_pending_notification('deliver')
        pending = await asyncio.to_thread(spool.pending, retry_before=max(0, int(time.time()) - 3), stage='deliver')
        for frame_id in pending:
            service_backpressure = False
            try:
                record = await asyncio.to_thread(spool.get, frame_id)
                if record is None:
                    continue
                meta, digest, plan = record['metadata'], record['sha256'], record['plan']
                if plan is None:
                    continue
                reference = await asyncio.to_thread(store.write, uuid.UUID(frame_id), record['payload'])
                receipt = record['receipt']
                if receipt is None:
                    registration_path='/internal/v2/captures/register'+('' if analysis_handoff else '?analysis_requested=false')
                    response = await core.post(registration_path, {
                        'frameId': frame_id, 'taskId': meta['task_id'], 'workerId': meta['worker_id'], 'generation': meta['generation'],
                        'evidenceRef': reference, 'payloadSha256': digest, 'mediaOffsetMs': meta['media_offset_ms'],
                        'sourceTime': meta['source_time'], 'sceneRunId': meta['scene_run_id'], 'mediaEpoch': meta['media_epoch'],
                        'relativeMediaMs': meta['relative_media_ms'], 'plan': plan})
                    receipt = response['capture']
                    if receipt['frameId'] != frame_id or receipt['payloadSha256'] != digest:
                        raise ValueError('capture receipt mismatch')
                    uuid.UUID(receipt['receipt'])
                    await asyncio.to_thread(spool.attach, frame_id, digest, 'receipt', receipt)
                    log(json.dumps({'event': 'capture_registered', 'frame_id': frame_id, 'camera_id': meta['camera_id'],
                                    'targets': len(plan['targets']), 'detection_state': plan['detection_state']}))
                if analysis_handoff:
                    response = await core.post('/internal/v2/captures/enqueue', receipt)
                    if response['frameId'] != frame_id or response['payloadSha256'] != digest:
                        raise ValueError('queue acknowledgement mismatch')
                    if response.get('queued') is not True:
                        raise ServiceError(429 if response.get('queued') is False else 502)
                    uuid.UUID(response['jobId'])
                await asyncio.to_thread(spool.acknowledge, frame_id, digest)
                retry_delay = DELIVERY_RETRY_BASE_SECONDS
                log(json.dumps({'event': 'target_frame_queued' if analysis_handoff else 'capture_registered_without_analysis', 'frame_id': frame_id, 'camera_id': meta['camera_id'],
                                'targets': len(plan['targets']), 'strategy': 'frame_batch'}))
            except (ServiceError, OSError, ValueError, KeyError, TypeError, sqlite3.Error) as error:
                # No raw responses, credentials, payloads or receipts are logged.
                log(json.dumps({'event': 'capture_delivery_deferred', 'frame_id': frame_id,
                                'status': 'retained', 'http_status': getattr(error, 'status', None)}))
                if isinstance(error, ServiceError) and error.status in SERVICE_BACKPRESSURE_STATUS:
                    service_backpressure = True
                    retry_at = time.monotonic()+retry_delay
                    retry_delay = min(DELIVERY_RETRY_MAX_SECONDS, retry_delay*2)
            finally:
                await asyncio.to_thread(spool.attempted, frame_id)
            if service_backpressure:
                break
        if not pending:
            # Thread-local commits wake immediately. The bounded fallback also
            # discovers writes made through another spool instance/process.
            await asyncio.to_thread(spool.wait_for_pending_notification, .5, 'deliver')


async def dispatch_selected(settings, core, store, spool):
    # Both stages share durable records, not an unbounded in-memory frame queue.
    # Unexpected exit in either stage stops the supervisor so the runner cannot
    # leave a healthy-looking capture process with a dead preparation/delivery.
    stages = [asyncio.create_task(prepare_selected(settings, spool)),
              asyncio.create_task(deliver_selected(core, store, spool, analysis_handoff=getattr(settings,'capture_analysis_handoff_enabled',True)))]
    try:
        done, _ = await asyncio.wait(stages, return_when=asyncio.FIRST_COMPLETED)
        for task in done:
            task.result()
        raise RuntimeError('selected-frame stage stopped unexpectedly')
    finally:
        for task in stages:
            task.cancel()
        await asyncio.gather(*stages, return_exceptions=True)
