import asyncio
from dataclasses import replace
from contextlib import aclosing
import base64
import json
import os
import sqlite3
import uuid

from .client import JsonService, ServiceError
from .config import Settings
from .evidence import EvidenceStore
from .evidence_ledger import LedgerEvidenceStore
from .frames import archive_frames, rtsp_frames
from .events import log
from .analysis_consumer import execute_owned, record_failure, supervise, wait_for_poll
from .managed_source import CONTRACT, ManagedSourceError, task_path


LEASE_SECONDS = 600
LEASE_SAFETY_SECONDS = 30
RETRYABLE_STATUS = {408, 429, 500, 502, 503, 504}


async def renew_source(core: JsonService, task: dict, worker: str, deadline: list[float]):
    """Retry transient renewals only while a conservative local lease is valid."""
    loop = asyncio.get_running_loop()
    while True:
        remaining = deadline[0] - loop.time()
        if remaining <= 0:
            raise ServiceError(409)
        await asyncio.sleep(min(120, remaining))
        while True:
            started = loop.time()
            remaining = deadline[0] - started
            if remaining <= 0:
                raise ServiceError(409)
            try:
                await asyncio.wait_for(core.post(task_path(task, 'renew'),
                    {"workerId": worker, "generation": task["generation"], "leaseSeconds": LEASE_SECONDS}), remaining)
            except TimeoutError:
                raise ServiceError(504) from None
            except ServiceError as exc:
                if exc.status not in RETRYABLE_STATUS:
                    raise
                log("Source lease renewal temporarily unavailable; retrying within the lease deadline.")
                await asyncio.sleep(min(5, max(0, deadline[0] - loop.time())))
            else:
                # A server lease is issued after this request started, so this
                # monotonic estimate is conservative without trusting wall clocks.
                deadline[0] = started + LEASE_SECONDS - LEASE_SAFETY_SECONDS
                break


async def finish_source(core: JsonService, task: dict, worker: str, completed: bool, deadline: list[float], error_code='source_interrupted'):
    """Retry a release after the reader stops; stale generations are rejected by the API."""
    loop = asyncio.get_running_loop()
    body = {"workerId": worker, "generation": task["generation"], "completed": completed}
    if task.get('managed'):body['errorCode'] = error_code
    while (remaining := deadline[0] - loop.time()) > 0:
        try:
            await asyncio.wait_for(core.post(task_path(task, 'finish'), body), remaining)
            return True
        except TimeoutError:
            return False
        except ServiceError as exc:
            if exc.status not in RETRYABLE_STATUS:
                return False
            log("Source lease release temporarily unavailable; retrying within the lease deadline.")
            await asyncio.sleep(min(5, max(0, deadline[0] - loop.time())))
    return False


async def enqueue_with_retry(core: JsonService, body: dict):
    while True:
        try:
            return await core.post("/internal/v1/frames/enqueue", body)
        except ServiceError as exc:
            if exc.status not in {409, 429, 502, 503, 504}:
                raise
            log("Frame queue is unavailable or at capacity; retaining the frame for retry.")
            await asyncio.sleep(10)


async def consume_source(settings: Settings, core: JsonService, store: EvidenceStore, task: dict, worker: str):
    async def save(frame_id, image, offset, selection=None):
        reference = await asyncio.to_thread(store.write, frame_id, image) if isinstance(store, LedgerEvidenceStore) else store.write(frame_id, image)
        await enqueue_with_retry(core, {"frameId": str(frame_id), "taskId": task["taskId"], "workerId": worker,
                                        "generation": task["generation"], "evidenceRef": reference,
                                        "mediaOffsetMs": offset, "sourceTime": None})
        if selection is not None:
            log(json.dumps({'event': 'scene_frame_queued', 'frame_id': str(frame_id), 'task_id': task['taskId'],
                            'camera_id': task['sourceRef'].split(':', 1)[1], 'scene_run_id': selection.run_id,
                            'media_epoch': selection.media_epoch, 'relative_media_ms': selection.relative_media_ms,
                            'scene_state': selection.state, 'selection_reason': selection.reason}))

    if task["sourceType"] == "rtsp":
        if settings.scene_gate_mode == 'active':
            from .scene_pipeline import filtered_frames
            async with aclosing(filtered_frames(settings, task['sourceRef'], 'rtsp')) as frames:
                async for selected in frames:
                    await save(uuid.uuid4(), selected.jpeg, None, selected)
            raise RuntimeError('live scene stream ended')
        async for image in rtsp_frames(settings, task["sourceRef"]):
            await save(uuid.uuid4(), image, None)
        raise RuntimeError("live stream ended")
    if task["sourceType"] == "archive":
        if settings.scene_gate_mode == 'active':
            from .scene_pipeline import filtered_frames
            async with aclosing(filtered_frames(settings, task['sourceRef'], 'archive')) as frames:
                async for selected in frames:
                    frame_id = uuid.uuid5(uuid.UUID(task['taskId']), 'offset-ms:' + str(selected.media_offset_ms))
                    await save(frame_id, selected.jpeg, selected.media_offset_ms, selected)
            return
        async for offset, image in archive_frames(settings, task["sourceRef"]):
            frame_id = uuid.uuid5(uuid.UUID(task["taskId"]), "offset-ms:" + str(offset))
            await save(frame_id, image, offset)
        return
    raise ValueError("unsupported source type")


async def source_slot(settings: Settings, core: JsonService, store: EvidenceStore, slot: int, source_type: str, spool=None):
    worker = f"{settings.worker_id}-{slot}"
    while True:
        task = None
        try:
            claim_started = asyncio.get_running_loop().time()
            body = {"workerId": worker, "sourceType": source_type, "leaseSeconds": LEASE_SECONDS}
            managed = getattr(settings, 'ingestion_jobs_enabled', False)
            if managed:body['contracts'] = [CONTRACT]
            result = await core.post('/internal/v2/ingestion/tasks/claim' if managed else '/internal/v1/tasks/claim', body)
            task = result.get("task")
            if not task:
                await asyncio.sleep(5)
                continue
            deadline = [claim_started + LEASE_SECONDS - LEASE_SAFETY_SECONDS]
            if managed and (not task.get('managed') or spool is None):
                await finish_source(core, task, worker, False, deadline, 'source_input_invalid')
                raise ManagedSourceError('source_input_invalid')
            if spool is not None:
                from .dispatch import capture_source
                source = asyncio.create_task(capture_source(settings, spool, task, worker, core=core) if managed else capture_source(settings, spool, task, worker))
            else:
                source = asyncio.create_task(consume_source(settings, core, store, task, worker))
            renew = asyncio.create_task(renew_source(core, task, worker, deadline))
            completed = False
            failure_code = 'source_interrupted'
            try:
                done, _ = await asyncio.wait({source, renew}, return_when=asyncio.FIRST_COMPLETED)
                for item in done:
                    item.result()
                completed = source_type == "archive" and source in done
            except ManagedSourceError as failure:
                failure_code = failure.error_code
                raise
            except (ServiceError, OSError, ValueError, RuntimeError, TimeoutError, KeyError, sqlite3.Error):
                failure_code = 'source_worker_error'
                raise
            finally:
                for item in (source, renew):
                    if not item.done():
                        item.cancel()
                await asyncio.gather(source, renew, return_exceptions=True)
                if not await finish_source(core, task, worker, completed, deadline, failure_code):
                    log("Source lease could not be released; normal lease expiry will control recovery.")
        except (ServiceError, OSError, ValueError, RuntimeError, TimeoutError, KeyError, sqlite3.Error) as failure:
            log(json.dumps({'event':'source_processing_deferred','task_id':task.get('taskId') if task else None,
                'source_type':source_type,'error_code':getattr(failure,'error_code','source_worker_error'),
                'error_type':type(failure).__name__,'http_status':getattr(failure,'status',None)}))
            await asyncio.sleep(5)


async def process_analysis_job(core: JsonService, gateway: JsonService, store: EvidenceStore, worker: str, job: dict, image_transport='json'):
    error = "model_unavailable"
    try:
        try:
            image = await asyncio.to_thread(store.read, job["evidenceRef"])
        except (OSError, ValueError):
            error = "evidence_unavailable"
            raise
        plan = job.get('targetPlan')
        if plan is not None:
            from .target_analysis import analyze_targets
            from .completion import AnalysisFailure
            try:
                return await analyze_targets(core, gateway, image, worker, job,image_transport)
            except AnalysisFailure as failure:
                error=failure.error_code
                raise
            except (ValueError,KeyError,TypeError):
                error='invalid_model_result'
                raise
        metadata={'frame_id':job['frameId'],'media_type':'image/jpeg'}
        if image_transport=='multipart':
            result=await gateway.post_image('/internal/v3/analyze-frame',metadata,image)
        else:
            result=await gateway.post('/internal/v1/analyze-frame',{**metadata,'image_base64':base64.b64encode(image).decode()})
        error = "invalid_model_result"
        objects = result["result"]["objects"]
        if not isinstance(objects, list) or len(objects) > 100:
            raise ValueError("invalid model output")
        converted = [{"objectType": obj["object_type"], "attributes": obj["attributes"], "description": obj.get("description"),
                      "bboxXyxy": obj.get("bbox_xyxy"), "uncertainFields": obj.get("uncertain_fields", [])}
                     for obj in objects]
        error = "result_commit_unavailable"
        from .completion import submit_completion
        await submit_completion(core,"/internal/v1/analysis/complete", {"jobId": job["jobId"], "workerId": worker,
                        "generation": job["generation"], "modelId": result["configured_model"],
                        **({"inferenceReceipt":result["inference_receipt"]} if result.get("inference_receipt") else {}),
                        "promptVersion": result["prompt_version"], "objects": converted},len(converted),job)
        return True
    except (ServiceError, OSError, ValueError, KeyError, TypeError) as failure:
        if error=='model_unavailable' and isinstance(failure,ServiceError):
            error={504:'model_timeout',429:'model_rate_limited'}.get(failure.status,error)
        await record_failure(core,worker,job,error)
        return False


async def analysis_slot(settings: Settings, core: JsonService, gateway: JsonService, store: EvidenceStore, slot: int, stop=None):
    worker = f"{settings.worker_id}-{slot}"
    stop=stop if stop is not None else asyncio.Event()
    while not stop.is_set():
        try:
            claimed_at=asyncio.get_running_loop().time()
            claim = await core.post('/internal/v2/analysis/claim' if settings.target_pipeline else '/internal/v1/analysis/claim',
                                    {"workerId": worker, "leaseSeconds": settings.analysis_lease_seconds})
            job = claim.get("job")
            if not job:
                await wait_for_poll(stop)
                continue
            if stop.is_set():
                await record_failure(core,worker,job,'consumer_interrupted')
                break
            execute=lambda:process_analysis_job(core,gateway,store,worker,job,settings.analysis_image_transport)
            if not await execute_owned(settings,core,worker,job,claimed_at,execute):
                await wait_for_poll(stop)
        except (ServiceError, OSError, ValueError, KeyError, TypeError):
            log("Analysis queue is unavailable; retrying shortly.")
            await wait_for_poll(stop)


def create_evidence_store(settings: Settings):
    if settings.evidence_backend == "ledger":
        return LedgerEvidenceStore(settings.evidence_dir, settings.max_evidence_bytes)
    return EvidenceStore(settings.evidence_dir, settings.max_evidence_bytes)


async def maintain_evidence_ledger(store: LedgerEvidenceStore):
    while True:
        await asyncio.sleep(300)
        try:
            report = await asyncio.to_thread(store.recover)
            if report["released"] or report["completed"] or report["invalid"]:
                log("Evidence ledger recovery: " + ", ".join(f"{key}={report[key]}" for key in ("released", "completed", "invalid")))
        except (sqlite3.Error, OSError, ValueError):
            log("Evidence ledger recovery is unavailable; reserved capacity remains charged.")


async def run(role: str, settings: Settings):
    settings.validate(role)
    store = create_evidence_store(settings) if role not in {'modules','media','specialist','export'} else None
    maintenance = delivery = None
    if role == "ingest" and isinstance(store, LedgerEvidenceStore):
        audit = await asyncio.to_thread(store.audit_files)
        recovery = await asyncio.to_thread(store.recover)
        if audit["missing_ready_files"] or recovery["invalid"]:
            log("Evidence ledger startup found missing or invalid files; investigation is required.")
    core = gateway = None
    try:
        core = JsonService(settings.core_url, settings.core_ca, settings.worker_token,**({'timeout':120,'max_response_bytes':12*1024**2} if role=='export' else {'timeout':120} if role in {'media','specialist'} else {}))
        gateway = JsonService(settings.gateway_url, settings.gateway_ca, settings.gateway_token, timeout=settings.model_timeout+15) if role == "analyze" else None
        if role == "ingest" and isinstance(store, LedgerEvidenceStore):
            maintenance = asyncio.create_task(maintain_evidence_ledger(store))
        if role == "ingest":
            spool = None
            if settings.target_pipeline:
                from .payload_spool import PayloadSpool
                from .dispatch import dispatch_selected
                options={'context_enabled':True} if getattr(settings,'scene_motion_context','off')=='observe' else {}
                spool = await asyncio.to_thread(PayloadSpool, settings.evidence_dir / '.selected-outbox', settings.spool_payload_bytes, settings.spool_entries,**options)
                delivery = asyncio.create_task(dispatch_selected(settings, core, store, spool))
            count = max(1, min(6, int(os.environ.get("INGEST_SLOTS", "6"))))
            kind = os.environ.get("SOURCE_TYPE", "rtsp")
            if kind not in {"rtsp", "archive"}:
                raise ValueError("invalid source type")
            sources = asyncio.gather(*(source_slot(settings, core, store, i + 1, kind, spool) for i in range(count)))
            if delivery is None:
                await sources
            else:
                # A failed dispatcher must not leave apparently healthy decoders.
                supervised={sources,delivery}
                done, _ = await asyncio.wait(supervised, return_when=asyncio.FIRST_COMPLETED)
                try:
                    for item in done:
                        item.result()
                finally:
                    sources.cancel()
                    await asyncio.gather(sources, return_exceptions=True)
        elif role=='export':
            from .export_consumer import export_slot
            await supervise(replace(settings,analysis_slots=1),lambda slot,stop:export_slot(settings,core,slot,stop))
        elif role=='specialist':
            from .specialist_consumer import SpecialistClient,SpecialistSettings,specialist_slot
            from .specialist_runtime import SpecialistRuntimeClient
            configuration=SpecialistSettings.from_env();configuration.validate();client=SpecialistRuntimeClient(configuration) if configuration.accept_routing else SpecialistClient(configuration)
            try:await supervise(replace(settings,analysis_slots=configuration.slots),lambda slot,stop:specialist_slot(settings,configuration,core,client,slot,stop))
            finally:await client.close()
        elif role=='media':
            from .media_consumer import media_slot
            await supervise(replace(settings,analysis_slots=settings.media_slots),lambda slot,stop:media_slot(settings,core,slot,stop))
        else:
            from .module_consumer import module_slot
            consumers=[(f'module-slot-{ordinal}',lambda stop,ordinal=ordinal:module_slot(settings,core,ordinal,stop))
                       for ordinal in range(1,settings.module_slots+1)]
            timing=replace(settings,analysis_slots=0) if role=='modules' else settings
            await supervise(timing,lambda slot,stop:analysis_slot(settings,core,gateway,store,slot,stop),extra_consumers=consumers)
    finally:
        if delivery:
            delivery.cancel()
            await asyncio.gather(delivery, return_exceptions=True)
        if maintenance:
            maintenance.cancel()
            await asyncio.gather(maintenance, return_exceptions=True)
        if core:
            await core.close()
        if gateway:
            await gateway.close()
