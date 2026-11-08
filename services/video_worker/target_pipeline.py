"""Exact-frame detection and conservative camera-local association.

Track IDs describe adjacent geometric candidates, never personal identity or
cross-camera identity. Sparse or discontinuous samples start new candidates.
"""
import time
import uuid
import hashlib


def _iou(a, b):
    intersection = max(0, min(a[2], b[2]) - max(a[0], b[0])) * max(0, min(a[3], b[3]) - max(a[1], b[1]))
    union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - intersection
    return intersection / union if union > 0 else 0


class CandidateTracker:
    def __init__(self, max_gap_seconds=10, minimum_iou=.5):
        self.max_gap_seconds, self.minimum_iou = max_gap_seconds, minimum_iou
        self.previous = {}

    def assign(self, camera, run_id, epoch, media_ms, targets):
        key = (camera, run_id, epoch)
        previous_ms, previous = self.previous.get(key, (None, []))
        if previous_ms is None or not 0 < media_ms - previous_ms <= self.max_gap_seconds * 1000:
            previous = []
        # Only mutual, unambiguous same-class matches are retained. Crowded
        # ambiguous overlaps produce new IDs rather than invented continuity.
        matches = [[j for j, old in enumerate(previous) if old['class_name'] == target['class_name']
                    and _iou(old['bbox_normalized'], target['bbox_normalized']) >= self.minimum_iou]
                   for target in targets]
        for target, candidates in zip(targets, matches):
            match = candidates[0] if len(candidates) == 1 and sum(candidates[0] in row for row in matches) == 1 else None
            target['track_id'] = previous[match]['track_id'] if match is not None else str(uuid.uuid4())
            target['association'] = 'local_geometry_candidate'
        self.previous = {k: v for k, v in self.previous.items() if k[0] != camera}
        self.previous[key] = (media_ms, [dict(v) for v in targets])


class TargetPlanner:
    def __init__(self, detector, threshold=.35,quality_mode='off',*,motion_mode='off',duplicate_mode='off',short_motion_mode='off',specialist_selection_mode='off',processing_catalog_file='',specialist_models_root='',short_motion_strategy='legacy',stationary_filter_mode='off'):
        self.detector, self.threshold = detector, threshold
        self.tracker = CandidateTracker()
        self.specialist_runtime = None
        if specialist_selection_mode != 'off' or processing_catalog_file:
            from .selection_runtime import SelectionRuntime
            self.specialist_runtime = SelectionRuntime(specialist_selection_mode, processing_catalog_file,specialist_models_root)
        if quality_mode not in {'off','observe'}:raise ValueError('invalid target quality mode')
        self.quality_mode=quality_mode
        if motion_mode not in {'off','observe'}:raise ValueError('invalid target motion mode')
        if duplicate_mode not in {'off','observe','mutual_085','mutual_095'}:raise ValueError('invalid vehicle duplicate mode')
        self.motion_mode,self.duplicate_mode=motion_mode,duplicate_mode
        if short_motion_mode not in {'off','observe'}:raise ValueError('invalid short motion mode')
        self.short_motion_mode=short_motion_mode
        if (short_motion_strategy not in {'legacy','grid_batch_v1'} or stationary_filter_mode not in {'off','observe','confirmed'}
                or short_motion_strategy!='legacy' and short_motion_mode!='observe'
                or stationary_filter_mode!='off' and short_motion_strategy!='grid_batch_v1'):
            raise ValueError('invalid stationary motion strategy')
        self.short_motion_strategy=short_motion_strategy;self.stationary_filter_mode=stationary_filter_mode
        if stationary_filter_mode!='off':
            from services.scene_analysis.stationary_frame import StationaryFrameReview
            self.stationary_review=StationaryFrameReview()
        if motion_mode=='observe':
            from services.scene_analysis.target_motion import TargetMotionMemory
            self.motion_memory=TargetMotionMemory()
        if quality_mode=='observe':
            from services.scene_analysis.target_quality import TargetQualityMemory
            self.quality_memory=TargetQualityMemory()

    def plan(self, frame_id, jpeg, metadata,*,motion_context=None,motion_context_issue=None):
        import cv2
        import numpy as np
        image = cv2.imdecode(np.frombuffer(jpeg, dtype=np.uint8), cv2.IMREAD_COLOR)
        if image is None:
            raise ValueError('selected JPEG cannot be decoded')
        started = time.monotonic()
        extra={}
        variant=getattr(self.detector.probe,'variant','raw')
        if variant in {'adaptive_1','adaptive_2'}:
            offset=metadata.get('media_offset_ms')
            if offset is None:offset=metadata['relative_media_ms']
            extra['context']=metadata['camera_id']+'|'+str(offset)
        future=self.detector.executor.submit(self.detector.probe.detect,image,self.threshold,**extra)
        try:
            result = future.result(timeout=30)
            state = 'complete'
        except (ValueError, RuntimeError, TimeoutError, cv2.error):
            # Detection failure never declares an empty scene or drops a frame.
            result = {'image_width': image.shape[1], 'image_height': image.shape[0], 'targets': [],
                      'detector':getattr(self.detector.probe,'kind','unknown')}
            state = 'unknown'
        finally:
            if not future.done():future.cancel()
        for ordinal, target in enumerate(result['targets']):
            target['target_id'] = str(uuid.uuid5(uuid.UUID(frame_id), 'target:' + str(ordinal)))
        duplicate_metrics=None
        if self.duplicate_mode!='off':
            if variant in {'adaptive_1','adaptive_2'} and self.duplicate_mode!='observe':
                raise ValueError('active duplicate suppression is incompatible with protected adaptive detection')
            from services.scene_analysis.target_duplicates import propose_vehicle_dedup
            try:
                duplicate_metrics=propose_vehicle_dedup(result['targets'],.95 if self.duplicate_mode=='mutual_095' else .85)
                applied=self.duplicate_mode!='observe' and state=='complete'
                duplicate_metrics.update(mode=self.duplicate_mode,applied=applied,frame_suppression=False)
                if applied:result['targets']=[result['targets'][n] for n in duplicate_metrics['kept_indices']]
            except (ValueError,RuntimeError,KeyError,TypeError):
                duplicate_metrics={'schema':'vehicle-duplicate-v1','mode':self.duplicate_mode,'state':'unavailable',
                                   'applied':False,'frame_suppression':False}
        self.tracker.assign(metadata['camera_id'], metadata['scene_run_id'], metadata['media_epoch'],
                            metadata['relative_media_ms'], result['targets'])
        detected_targets=list(result['targets'])
        metrics=metadata.get('selection_metrics')
        if duplicate_metrics is not None:metrics={**(metrics or {}),'target_duplicates':duplicate_metrics}
        activity=metrics.get('activity') if isinstance(metrics,dict) else None
        if activity is not None:
            from services.scene_analysis.activity_regions import active_target
            all_targets=result['targets']
            result['targets']=[target for target in all_targets if active_target(target['bbox_normalized'],activity)]
            metrics=dict(metrics)
            metrics['activity_target_routing']={'detected':len(all_targets),'selected':len(result['targets']),
                'stationary_candidates_omitted':len(all_targets)-len(result['targets']),
                'motion_map_complete':activity['regions_complete'],'detection_state':state,
                'route':'local_no_moving_person_vehicle' if state=='complete' and not result['targets'] and activity['regions_complete'] else 'vlm'}
        modules = [{'module': name, 'target_ids': [v['target_id'] for v in result['targets'] if v['object_type'] == kind]}
                   for name, kind in [('person_attributes', 'person'), ('vehicle_attributes', 'vehicle')]]
        modules = [v for v in modules if v['target_ids']]
        modules.append({'module': 'scene_context', 'target_ids': []})
        if variant in {'adaptive_1','adaptive_2'}:
            metrics=dict(metrics or {})
            metrics['target_crops']={**result.get('crop_metrics',{'state':'unavailable','reason':'full_frame_detection_unavailable',
                'frame_suppression':False,'empty_scene_evidence':False}),'jpeg_sha256':hashlib.sha256(jpeg).hexdigest()}
        if self.quality_mode=='observe':
            from services.scene_analysis.target_quality import score_plan
            metrics=dict(metrics or {})
            quality_started=time.monotonic()
            try:metrics['target_quality']=score_plan(image,result['targets'],metadata,frame_id,hashlib.sha256(jpeg).hexdigest(),self.quality_memory)
            except (ValueError,RuntimeError,TypeError,KeyError,OverflowError,cv2.error):
                metrics['target_quality']={'schema':'target-readability-v1','mode':'observe','state':'unavailable',
                                           'frame_suppression':False,'reason':'quality_features_unavailable'}
            metrics['target_quality']['elapsed_ms']=round((time.monotonic()-quality_started)*1000,3)
        if self.motion_mode=='observe':
            metrics=dict(metrics or {});motion_started=time.monotonic()
            try:
                metrics['target_motion']=self.motion_memory.observe(image,result['targets'],metadata,frame_id,
                    hashlib.sha256(jpeg).hexdigest(),detection_complete=state=='complete')
            except (ValueError,RuntimeError,TypeError,KeyError,OverflowError,cv2.error):
                self.motion_memory.clear()
                metrics['target_motion']={'schema':'target-motion-observation-v1','mode':'observe','state':'unavailable',
                    'routing_applied':False,'frame_suppression':False,'reason':'motion_features_unavailable'}
            metrics['target_motion']['elapsed_ms']=round((time.monotonic()-motion_started)*1000,3)
        if self.short_motion_mode=='observe':
            from services.scene_analysis.short_motion import observe_short_motion
            metrics=dict(metrics or {});short_started=time.monotonic()
            try:
                if self.short_motion_strategy=='grid_batch_v1':
                    from services.scene_analysis.occlusion_motion import observe_occlusion_motion
                    metrics['short_target_motion']=observe_occlusion_motion(image,result['targets'],metadata,frame_id,
                        hashlib.sha256(jpeg).hexdigest(),motion_context,issue=motion_context_issue,all_targets=detected_targets)
                else:
                    metrics['short_target_motion']=observe_short_motion(image,result['targets'],metadata,frame_id,
                        hashlib.sha256(jpeg).hexdigest(),motion_context,issue=motion_context_issue)
            except (ValueError,RuntimeError,TypeError,KeyError,OverflowError,cv2.error):
                metrics['short_target_motion']={'schema':'short-target-motion-v1','mode':'observe','state':'unavailable',
                    'routing_applied':False,'frame_suppression':False,'reason':'motion_context_invalid_or_features_unavailable',
                    'states':{'unknown':len(result['targets'])}}
            metrics['short_target_motion']['elapsed_ms']=round((time.monotonic()-short_started)*1000,3)
        if self.stationary_filter_mode!='off':
            metrics=dict(metrics or {})
            try:
                review=self.stationary_review.evaluate(result['targets'],metrics.get('short_target_motion',{}),metadata,frame_id,
                    hashlib.sha256(jpeg).hexdigest(),mode=self.stationary_filter_mode,detection_complete=state=='complete')
                metrics['stationary_frame_review']=review
                if review['applied']:
                    routing=dict(metrics['activity_target_routing']);routing.update(selected=0,
                        stationary_candidates_omitted=routing['detected'],route='local_no_moving_person_vehicle')
                    metrics['stationary_frame_review']['excluded_target_ids']=[target['target_id'] for target in result['targets']]
                    result['targets']=[];modules=[{'module':'scene_context','target_ids':[]}]
                    metrics['activity_target_routing']=routing
            except (ValueError,RuntimeError,TypeError,KeyError,OverflowError):
                self.stationary_review.clear()
                metrics['stationary_frame_review']={'schema':'stationary-frame-review-v1','mode':self.stationary_filter_mode,
                    'applied':False,'frame_suppression':False,'reason':'stationary_evidence_unavailable'}
        if self.specialist_runtime is not None:
            metrics = dict(metrics or {})
            try:
                observations=(metrics.get('short_target_motion') or metrics.get('target_motion') or {}).get('targets',[])
                business_metadata={**metadata,'specialist_motion':{v['target_id']:v.get('state','unknown') for v in observations}}
                metrics['specialist_selection'] = self.specialist_runtime.select(image, result['targets'], business_metadata,
                    frame_id, hashlib.sha256(jpeg).hexdigest(), detection_complete=state == 'complete')
            except (ValueError, RuntimeError, TypeError, KeyError, OSError, OverflowError, cv2.error):
                metrics['specialist_selection'] = {'schema_version': 'specialist-selection-v2',
                    'state': 'unavailable', 'reason': 'selection_stage_unavailable', 'frame_suppression': False,
                    'semantic_modules_changed': False}
        return {'version': 'targets-v1', 'strategy': 'frame_batch', 'detection_state': state,
                'detector': result['detector'], 'model_sha256': self.detector.model_sha256,
                'detector_variant':result.get('detector_variant','raw'),'selection_metrics':metrics,
                'threshold': self.threshold, 'image_width': result['image_width'], 'image_height': result['image_height'],
                'targets': result['targets'], 'modules': modules,
                'detection_latency_ms': round((time.monotonic() - started) * 1000)}

    def close(self):
        if self.specialist_runtime is not None:
            self.specialist_runtime.close()
