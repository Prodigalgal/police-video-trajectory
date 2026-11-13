"""Local scene filtering before high-resolution JPEG creation and VLM queuing.

Each source decodes once. A bounded local detector runs independently of the
reader; absence requires repeated fresh negatives, while failures stay unknown.
"""
import asyncio
from collections import Counter
from concurrent.futures import ThreadPoolExecutor
from dataclasses import dataclass
from datetime import datetime, timezone
from functools import lru_cache
import hashlib
import json
import math
import os
from pathlib import Path
import queue
import sqlite3
import threading
import time
import uuid

from .scene_gate import SceneGate, SceneGatePolicy
from .events import log


MODEL_SHA256 = '4b82da9944b88577175ee23a459dce2e26e6e4be573def65b1055dc2d9720186'
MODEL_HASHES = {'nanodet':MODEL_SHA256,'yolox':'c5c2d13e59ae883e6af3b45daea64af4833a4951c92d116ec270d9ddbe998063'}


class LocalDetector:
    def __init__(self, model,kind='nanodet',variant='raw',filtered_presence=False):
        import av
        import cv2
        from services.scene_analysis.detectors import SceneProbe
        if kind not in MODEL_HASHES or hashlib.sha256(Path(model).read_bytes()).hexdigest() != MODEL_HASHES[kind]:
            raise ValueError('scene detector model digest differs')
        # Raw libav messages can include the authenticated source URL.
        av.logging.set_level(av.logging.PANIC)
        cv2.setNumThreads(1)
        self.probe = SceneProbe(kind, model)
        if variant!='raw' or filtered_presence:
            from services.scene_analysis.prefilter import PolicyProbe
            self.probe=PolicyProbe(self.probe,variant,filtered_presence)
        self.model_sha256 = MODEL_HASHES[kind]
        # OpenCV Net is stateful: never interleave setInput/forward from cameras.
        self.executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix='local-scene-detector')

    def submit(self, image):
        return self.executor.submit(self.probe.max_target_score, image)


@lru_cache(maxsize=4)
def _cached_local_detector(model,kind='nanodet',variant='raw',filtered_presence=False):
    return LocalDetector(model,kind,variant,filtered_presence)


_detector_initialization_lock=threading.Lock()


def local_detector(model,kind='nanodet',variant='raw',filtered_presence=False):
    # lru_cache protects its dictionary, but concurrent misses can still run
    # the constructor repeatedly. Serialize cache lookup plus cold creation.
    with _detector_initialization_lock:
        return _cached_local_detector(model,kind,variant,filtered_presence)


def _clear_local_detectors():
    with _detector_initialization_lock:
        _cached_local_detector.cache_clear()


local_detector.cache_clear=_clear_local_detectors
local_detector.cache_info=_cached_local_detector.cache_info


@dataclass(frozen=True)
class SceneFrame:
    jpeg: bytes
    media_offset_ms: int | None
    state: str
    reason: str
    run_id: str
    media_epoch: int
    relative_media_ms: int
    selection_metrics: dict | None = None
    motion_context: bytes | None = None


class SceneReader:
    def __init__(self, settings, reference, source_type, *, detector=None, duration_seconds=None, selected_sink=None,max_media_seconds=None,start_media_seconds=0,scene_observer=None,archive_progress=None,emit_after_ms=None,end_media_ms=None):
        if source_type not in {'rtsp', 'archive'}:
            raise ValueError('unknown scene source type')
        prefix = 'simulator:' if source_type == 'rtsp' else 'archive:'
        camera = reference.removeprefix(prefix)
        if reference != prefix + camera or camera not in {f'recording-0{i}' for i in range(1, 7)}:
            raise ValueError('unknown scene camera')
        self.settings, self.reference, self.source_type = settings, reference, source_type
        self.camera = camera
        self.roi = settings.scene_rois().get(camera, [0, 0, 1, 1])
        self.detector = (detector if settings.scene_policy=='activity_v1' else detector or
            local_detector(str(settings.scene_model),settings.scene_detector_kind,settings.scene_detector_variant,settings.scene_policy in {'road_v1','road_v2'}))
        self.outputs = queue.Queue(maxsize=2)
        self.stop = threading.Event()
        self.finished = threading.Event()
        self.lock = threading.Lock()
        self.counts = Counter()
        self.last_state = 'unknown'
        self.error_type = None
        self.epoch = 0
        self.run_id = str(uuid.uuid4())
        self.started_at = datetime.now(timezone.utc).isoformat()
        self.duration_seconds = duration_seconds
        self.selected_sink = selected_sink
        if scene_observer is not None and not callable(scene_observer):
            raise ValueError('scene observer must be callable')
        self.scene_observer = scene_observer
        if max_media_seconds is not None and (not math.isfinite(max_media_seconds) or not 1<=max_media_seconds<=600):
            raise ValueError('invalid bounded media duration')
        self.max_media_seconds=max_media_seconds
        if not math.isfinite(start_media_seconds) or not 0<=start_media_seconds<=604800 or start_media_seconds and (source_type!='archive' or settings.scene_policy not in {'road_v2','activity_v1'}):
            raise ValueError('invalid bounded archive start')
        self.start_media_seconds=start_media_seconds
        if (archive_progress is not None and (source_type!='archive' or settings.scene_policy!='activity_v1')
            or emit_after_ms is not None and (type(emit_after_ms) is not int or not 0<=emit_after_ms<=604800000)
            or end_media_ms is not None and (type(end_media_ms) is not int or not 0<end_media_ms<=604800000)):
            raise ValueError('invalid managed archive window')
        self.archive_progress=archive_progress;self.archive_offset_ms=None;self.emit_after_ms=emit_after_ms;self.end_media_ms=end_media_ms;self.window_complete=False
        self.last_flush = 0.0
        self.thread = threading.Thread(target=self._run, name='scene-' + camera, daemon=True)

    def add(self, key, amount=1):
        with self.lock:
            self.counts[key] += amount

    def snapshot(self):
        with self.lock:
            counts = dict(self.counts)
        return {'camera_id': self.camera, 'run_id': self.run_id, 'started_at': self.started_at,
                'observed_at': datetime.now(timezone.utc).isoformat(), 'source_type': self.source_type,
                'gate_mode': 'active', 'sample_seconds': self.settings.sample_seconds,
                'probe_seconds': self.settings.scene_probe_seconds, 'fallback_seconds': self.settings.scene_fallback_seconds,
                'minimum_submission_seconds': self.settings.scene_minimum_submission_seconds,
                'scene_policy':self.settings.scene_policy,'motion_method':self.settings.scene_motion_method,
                'frame_selection':self.settings.frame_selection,'scene_detector':self.settings.scene_detector_kind,
                'empty_verifier':self.settings.scene_empty_verifier,
                'roi': self.roi, 'state': self.last_state, 'media_epoch': self.epoch,
                'error_type': self.error_type, **counts}

    def flush(self, final=False):
        if self.archive_progress is not None:self.archive_progress(self,final)
        now = time.monotonic()
        if not final and now - self.last_flush < 10:
            return
        self.last_flush = now
        data = self.snapshot()
        try:
            root = self.settings.evidence_dir / '.scene-state' / self.source_type
            root.mkdir(mode=0o700, parents=True, exist_ok=True)
            target = root / (self.camera + '.json')
            temporary = root / ('.' + self.camera + '-' + self.run_id + '.tmp')
            with temporary.open('w', encoding='utf-8') as output:
                json.dump(data, output)
                output.flush()
                os.fsync(output.fileno())
            os.replace(temporary, target)
        except OSError:
            self.add('status_write_errors')
        log(json.dumps({'event': 'scene_pipeline_stopped' if final else 'scene_pipeline_status', **data}))

    def _run(self):
        if self.settings.scene_policy=='activity_v1':
            from .activity_reader import run_activity_reader
            return run_activity_reader(self)
        if self.settings.scene_policy=='road_v2':
            from .temporal_reader import run_temporal_reader
            return run_temporal_reader(self)
        import av
        import cv2
        import numpy as np
        from .frames import archive_path, simulator_url
        settings = self.settings
        road=settings.scene_policy=='road_v1'
        def new_gate():
            return SceneGate(SceneGatePolicy(sample_seconds=settings.sample_seconds,fallback_seconds=settings.scene_fallback_seconds,
                                             empty_confirmations=1 if road else 3))
        gate = new_gate()
        if road:
            from services.scene_analysis.prefilter import MotionComparison,quality,scene_state
            motion_probe=MotionComparison(max(3,round(10/settings.scene_probe_seconds)),[settings.scene_motion_method])
        negative_updates=0;best_candidate=None;last_success_digest=None;last_success_pts=None
        initial_pts = last_pts = next_probe = previous = None
        score = score_pts = future = future_pts = future_epoch = next_detection = None
        uncertain_until = -1.0
        started = time.monotonic()
        last_submitted_wall = None
        try:
            if self.source_type == 'rtsp':
                source = simulator_url(settings, self.reference)
                options = {'rtsp_transport': 'tcp', 'tls_verify': '1', 'ca_file': settings.stream_ca, 'timeout': '15000000'}
            else:
                source, options = str(archive_path(settings, self.reference)), {}
            with av.open(source, mode='r', options=options, timeout=(15, 15)) as container:
                stream = container.streams.video[0]
                stream.codec_context.thread_count = 1
                archive_origin = float(stream.start_time * stream.time_base) if stream.start_time is not None and stream.time_base is not None else None
                if self.source_type == 'archive' and archive_origin is None:
                    raise ValueError('archive media-time origin unavailable')
                for packet in container.demux(stream):
                    if self.stop.is_set() or (self.duration_seconds and time.monotonic() - started >= self.duration_seconds):
                        break
                    try:
                        frames = packet.decode()
                    except av.error.FFmpegError:
                        self.add('decode_errors')
                        score = score_pts = None
                        uncertain_until = (last_pts or 0) + 15
                        continue
                    for frame in frames:
                        if self.stop.is_set():
                            break
                        self.add('decoded_frames')
                        if frame.pts is None or frame.time_base is None:
                            self.add('missing_pts_frames')
                            score = score_pts = None
                            uncertain_until = (last_pts or 0) + 15
                            continue
                        pts = float(frame.pts * frame.time_base)
                        if not math.isfinite(pts):
                            self.add('invalid_pts_frames')
                            continue
                        if last_pts is not None and (pts < last_pts or pts - last_pts > 2):
                            self.add('pts_discontinuities')
                            self.epoch += 1
                            gate = new_gate()
                            negative_updates=0;best_candidate=None;last_success_digest=last_success_pts=None
                            if road:motion_probe=MotionComparison(max(3,round(10/settings.scene_probe_seconds)),[settings.scene_motion_method])
                            initial_pts = next_probe = previous = score = score_pts = next_detection = None
                            if future is not None and future.cancel():
                                future = None
                            uncertain_until = pts + 15
                        initial_pts = pts if initial_pts is None else initial_pts
                        last_pts = pts
                        if self.max_media_seconds is not None and pts-initial_pts>=self.max_media_seconds:
                            self.add('bounded_media_limit_reached');self.stop.set();break
                        if frame.is_corrupt:
                            self.add('corrupt_frames')
                            score = score_pts = None
                            uncertain_until = pts + 15
                            # Corruption is not empty and must never become a VLM image.
                            continue
                        if next_probe is not None and pts + .001 < next_probe:
                            continue
                        if next_probe is None:
                            next_probe = pts
                        missed = max(0, math.floor((pts + .001 - next_probe) / settings.scene_probe_seconds))
                        self.add('missed_probe_ticks', missed)
                        next_probe += (missed + 1) * settings.scene_probe_seconds
                        self.add('scene_probes')
                        scale = min(1.0, 320 / frame.width)
                        small = frame.reformat(width=max(2, int(frame.width * scale) // 2 * 2),
                                               height=max(2, int(frame.height * scale) // 2 * 2), format='bgr24').to_ndarray()
                        height, width = small.shape[:2]
                        x1, y1, x2, y2 = self.roi
                        crop = small[int(y1 * height):max(int(y1 * height) + 1, int(y2 * height)),
                                     int(x1 * width):max(int(x1 * width) + 1, int(x2 * width))]
                        gray = cv2.cvtColor(crop, cv2.COLOR_BGR2GRAY)
                        reduced = cv2.resize(gray, (80, 60), interpolation=cv2.INTER_AREA).astype(np.int16)
                        brightness = float(reduced.mean())
                        motion = None if previous is None else float(np.abs(reduced - previous).mean())
                        previous = reduced
                        q=quality(crop) if road else None
                        signals=motion_probe.observe(crop) if road else None
                        update = False
                        if future is not None and future.done():
                            try:
                                score = float(future.result())
                                if not math.isfinite(score) or not 0 <= score <= 1:
                                    raise ValueError('invalid scene score')
                                score_pts = future_pts
                                if future_epoch != self.epoch:
                                    score = score_pts = None
                                    self.add('detector_old_epoch_ignored')
                                self.add('detector_results')
                            except Exception:
                                score = score_pts = None
                                self.add('detector_errors')
                            future = None
                            update = True
                        if future is None and (next_detection is None or pts >= next_detection):
                            future = self.detector.submit(crop.copy())
                            future_pts = pts
                            future_epoch = self.epoch
                            next_detection = pts + settings.scene_detector_seconds
                            self.add('detector_submitted')
                            if self.source_type == 'archive':
                                # Historical decoding can run faster than wall time. Wait
                                # for this observation instead of reusing a future result.
                                try:
                                    score = float(future.result(timeout=10))
                                    if not math.isfinite(score) or not 0 <= score <= 1:
                                        raise ValueError('invalid scene score')
                                    score_pts = pts
                                    self.add('detector_results')
                                except Exception:
                                    score = score_pts = None
                                    self.add('detector_errors')
                                if future.done() or future.cancel():
                                    future = None
                                update = True
                        current_score = score if score_pts is not None and 0 <= pts - score_pts <= max(12, 2 * settings.scene_detector_seconds) else None
                        quality_ok = 8 <= brightness <= 247 and pts >= uncertain_until
                        road_category=None
                        if road:
                            if update:
                                negative_updates=negative_updates+1 if current_score is not None and current_score<.1 and q['usable_for_empty_confirmation'] else 0
                            fraction=signals['mad']/500 if settings.scene_motion_method=='mad' and signals['mad'] is not None else signals.get(
                                {'robust':'robust_diff_fraction','mog2':'mog2_fraction','knn':'knn_fraction'}.get(settings.scene_motion_method,''))
                            road_category=scene_state(fraction,current_score,q['usable_for_empty_confirmation'],negative_updates,
                                                      warming_up=signals['warming_up'],shake=signals['shake'])
                            if pts<uncertain_until:road_category='unknown'
                            motion=None if fraction is None else (10. if fraction>=.01 else 0.)
                            quality_ok=quality_ok and road_category!='unknown'
                            self.add('road_'+road_category)
                        prior_selection = (gate.last_selected, gate.next_sample)
                        state, reason = gate.observe(pts - initial_pts, motion, current_score,
                                                      detector_update=update, quality_ok=quality_ok)
                        if road and state=='active' and road_category=='occupied_quiet':state='occupied_quiet'
                        metrics={'policy':'road_v1','motion_method':settings.scene_motion_method,'quality':q,'motion':signals,
                                 'scene_category':road_category,'detector_age_ms':round((pts-score_pts)*1000) if score_pts is not None else None} if road else None
                        candidate={'frame':frame,'pts':pts,'state':state,'metrics':metrics}
                        if road and settings.frame_selection=='best':
                            priority={'active':3,'unknown':2,'occupied_quiet':1,'empty_candidate':0}
                            if (best_candidate is None or
                                (priority[state],q['quality_rank'])>(priority[best_candidate['state']],best_candidate['metrics']['quality']['quality_rank'])):
                                best_candidate=candidate;self.add('best_candidate_updated')
                        if state != self.last_state:
                            self.add('state_transitions')
                        self.last_state = state
                        self.add('state_' + state)
                        if not reason:
                            self.add('empty_skipped' if state == 'empty_candidate' else 'interval_skipped')
                        else:
                            if (self.source_type == 'rtsp' and last_submitted_wall is not None and
                                    time.monotonic() - last_submitted_wall < settings.scene_minimum_submission_seconds):
                                # PTS resets and unknown/active transitions cannot increase
                                # the explicitly configured trial submission frequency.
                                gate.last_selected, gate.next_sample = prior_selection
                                best_candidate=None
                                self.add('submission_budget_deferred')
                                self.flush()
                                continue
                            # Expensive full-scene JPEG creation happens only after this decision.
                            chosen=candidate
                            if (road and settings.frame_selection=='best' and reason in {'active_interval','unknown_interval'} and
                                    best_candidate is not None and 0<=pts-best_candidate['pts']<=settings.sample_seconds):
                                chosen=best_candidate
                            best_candidate=None
                            chosen_frame=chosen['frame'];chosen_pts=chosen['pts'];chosen_state=chosen['state'];chosen_metrics=chosen['metrics']
                            if chosen_metrics is not None:
                                chosen_metrics={**chosen_metrics,'frame_selection':settings.frame_selection,'selection_delay_ms':round((pts-chosen_pts)*1000)}
                            ratio = 1280 / chosen_frame.width
                            full = chosen_frame.reformat(width=1280, height=max(2, int(chosen_frame.height * ratio) // 2 * 2), format='bgr24').to_ndarray()
                            encoded, image = cv2.imencode('.jpg', full, [cv2.IMWRITE_JPEG_QUALITY, 85])
                            if not encoded:
                                raise ValueError('scene JPEG encoding failed')
                            self.add('selected_' + reason)
                            self.add('jpeg_encoded')
                            jpeg=image.tobytes();digest=hashlib.sha256(jpeg).hexdigest()
                            if (road and settings.quiet_dedup_seconds>0 and chosen_state=='occupied_quiet' and
                                    last_success_digest==digest and last_success_pts is not None and
                                    0<=chosen_pts-last_success_pts<settings.quiet_dedup_seconds):
                                self.add('quiet_exact_duplicate_skipped');self.flush();continue
                            offset = max(0, round((chosen_pts - archive_origin) * 1000)) if self.source_type == 'archive' else None
                            selected = SceneFrame(jpeg, offset, chosen_state, reason,
                                                  self.run_id, self.epoch, max(0, round((chosen_pts - initial_pts) * 1000)),chosen_metrics)
                            try:
                                if self.selected_sink is not None:
                                    self.selected_sink(selected)
                                    self.add('durable_frames')
                                else:
                                    self.outputs.put_nowait(selected)
                                last_submitted_wall = time.monotonic()
                                last_success_digest=digest;last_success_pts=chosen_pts
                                self.add('output_frames')
                            except queue.Full:
                                # A bounded in-memory handoff is not a durable queue. Report
                                # the gap instead of silently claiming a covered interval.
                                self.add('output_overflow_gaps')
                            except (OSError, ValueError, sqlite3.Error):
                                # A finite spool cannot mask persistent overload. Record
                                # the gap and keep the configured selection frequency.
                                last_submitted_wall = time.monotonic()
                                self.add('durable_spool_gaps')
                        self.flush()
            if not self.stop.is_set() and self.source_type == 'rtsp' and self.duration_seconds is None:
                self.error_type = 'stream_ended'
        except Exception as error:
            self.error_type = type(error).__name__
        finally:
            if future is not None:
                future.cancel()
            self.flush(final=True)
            self.finished.set()

    async def frames(self):
        self.thread.start()
        try:
            while True:
                try:
                    item = await asyncio.to_thread(self.outputs.get, True, 1)
                except queue.Empty:
                    if self.finished.is_set():
                        if self.error_type:
                            raise RuntimeError('scene stream processing failed')
                        return
                    continue
                yield item
        finally:
            self.stop.set()
            # Container.close runs in its owner thread, never concurrently with decode.
            while self.thread.is_alive():
                await asyncio.to_thread(self.thread.join, 1)


async def filtered_frames(settings, reference, source_type):
    reader = SceneReader(settings, reference, source_type)
    stream = reader.frames()
    try:
        async for item in stream:
            yield item
    finally:
        await stream.aclose()
