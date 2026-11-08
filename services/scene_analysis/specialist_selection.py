"""Select occasional specialist candidates without suppressing semantic analysis.

Local geometric tracks are scheduling hints, never vehicle/person identity.
Person boxes do not establish face visibility; no face identity is processed.
"""
from copy import deepcopy
import hashlib
import json
import math
import re
import threading
from uuid import UUID, uuid5, NAMESPACE_URL
import numpy as np
import cv2

from .target_quality import target_quality


SCHEMA = 'specialist-selection-v2'
POLICY = {
    'schema': SCHEMA, 'plate_classes': ['car', 'motorcycle', 'bus', 'truck'],
    'minimum_plate_width_px': 64, 'minimum_plate_height_px': 16,
    'minimum_readability_rank': .5, 'improvement_delta': .08,
    'minimum_reselect_ms': 1000,
    'maximum_recommendations_per_event': 3, 'maximum_selected_targets': 1,
    'context_gap_ms': 10000, 'history_limit': 256, 'camera_limit': 6,
    'face_scope': 'disabled_extension_only_not_implemented',
}
POLICY_SHA256 = hashlib.sha256(json.dumps(POLICY, sort_keys=True, separators=(',', ':')).encode()).hexdigest()


def _uuid(value):
    if not isinstance(value, str) or str(UUID(value)) != value:
        raise ValueError('noncanonical selection identity')
    return value


class SpecialistSelector:
    def __init__(self, mode='observe', plate_probe=None):
        if mode not in {'observe', 'select'}:
            raise ValueError('invalid specialist selection mode')
        self.mode = mode
        self.plate_probe = plate_probe
        self.entries = {}
        self.clocks = {}
        self.replays = {}
        self.motion_history = {}
        self.lock = threading.RLock()

    def close(self):
        with self.lock:
            self.entries.clear()
            self.clocks.clear()
            self.replays.clear()
            self.motion_history.clear()
            if self.plate_probe is not None:self.plate_probe.close()

    def _context(self, metadata, frame_id, digest):
        camera = metadata['camera_id']
        run, epoch, now = metadata['scene_run_id'], metadata['media_epoch'], metadata['relative_media_ms']
        if (not isinstance(camera, str) or not re.fullmatch(r'[A-Za-z0-9_-]{1,100}', camera)
                or type(epoch) is not int or epoch < 0 or type(now) is not int or now < 0
                or not isinstance(digest, str) or not re.fullmatch('[a-f0-9]{64}', digest)):
            raise ValueError('invalid specialist selection source')
        _uuid(run)
        _uuid(frame_id)
        context = (camera, run, epoch)
        clock = self.clocks.get(camera)
        reset = clock is not None and (clock[:3] != context or now <= clock[3]
                                      or now - clock[3] > POLICY['context_gap_ms'])
        if reset:
            self.entries = {key: value for key, value in self.entries.items() if key[0] != camera}
            self.motion_history = {key:value for key,value in self.motion_history.items() if key[0]!=camera}
        self.clocks.pop(camera, None)
        self.clocks[camera] = (*context, now)
        if len(self.clocks) > POLICY['camera_limit']:
            evicted = next(iter(self.clocks))
            self.clocks.pop(evicted)
            self.replays.pop(evicted, None)
            self.entries = {key: value for key, value in self.entries.items() if key[0] != evicted}
            self.motion_history = {key:value for key,value in self.motion_history.items() if key[0]!=evicted}
        self.entries = {key: value for key, value in self.entries.items()
                        if key[:3] != context or 0 <= now - value['last_ms'] <= POLICY['context_gap_ms']}
        self.motion_history={key:value for key,value in self.motion_history.items()
                             if key[:3]!=context or 0<=now-value<=POLICY['context_gap_ms']}
        return context, now, reset

    def _candidate(self, image, target, targets, context, now, frame_id, digest, metadata, probe):
        identity = _uuid(target['target_id'])
        base = {'target_id': identity, 'decision': 'deferred', 'business_state':'waiting_evidence', 'reasons': [], 'representative': None}
        if target.get('object_type') != 'vehicle':
            return {**base, 'decision': 'skipped', 'business_state':'not_applicable', 'reasons': ['not_a_vehicle_candidate']}, None
        if target.get('class_name') not in POLICY['plate_classes']:
            return {**base, 'decision': 'skipped', 'business_state':'not_applicable', 'reasons': ['plate_profile_class_unsupported']}, None
        if target.get('association') != 'local_geometry_candidate':
            return {**base, 'reasons': ['association_unavailable']}, None
        try:
            track = _uuid(target.get('track_id'))
        except (ValueError, TypeError, AttributeError):
            return {**base, 'reasons': ['association_unavailable']}, None
        event = str(uuid5(NAMESPACE_URL,'plate-event:'+':'.join(map(str,(*context,track)))))
        key = (*context, track, 'license_plate')
        previous = self.entries.get(key)
        intent = metadata.get('specialist_intent','collect_vehicle_passages')
        motion = metadata.get('specialist_motion',{}).get(identity,'unknown')
        base = {**base,'track_id':track,'event_id':event,'intent':intent}
        if motion=='moving':
            if len(self.motion_history)>=POLICY['history_limit'] and key not in self.motion_history:
                self.motion_history.pop(min(self.motion_history,key=self.motion_history.get))
            self.motion_history[key]=now
        if motion == 'stationary' and intent == 'collect_vehicle_passages' and key not in self.motion_history:
            return {**base,'decision':'skipped','business_state':'not_applicable','reasons':['stationary_without_passage_event']},None
        if not probe or probe.get('state')!='complete':
            return {**base,'reasons':['plate_probe_unavailable']},None
        def contains(box,plate):
            area=(plate[2]-plate[0])*(plate[3]-plate[1])
            intersection=max(0,min(box[2],plate[2])-max(box[0],plate[0]))*max(0,min(box[3],plate[3])-max(box[1],plate[1]))
            return area>0 and intersection/area>=.8
        regions=[value for value in probe['regions'] if contains(target['bbox_xyxy'],value['bbox_xyxy'])]
        if len(regions)!=1:
            return {**base,'reasons':['plate_not_visible' if not regions else 'plate_association_ambiguous']},None
        region=regions[0];box=region['bbox_xyxy']
        if sum(contains(t['bbox_xyxy'],box) for t in targets if t.get('object_type')=='vehicle')!=1:
            return {**base,'reasons':['plate_association_ambiguous']},None
        quality = target_quality(image, {'bbox_xyxy':box})
        if quality['state'] != 'scored':
            return {**base, 'reasons': ['quality_unavailable']}, None
        geometry = quality['geometry']
        rank = quality['ranks'].get('target_balanced')
        evidence = {'native_width_px': geometry['width_px'],
                    'native_height_px': geometry['height_px'], 'readability_rank': rank,
                    'quality_scope': 'localized_plate_not_character_correctness',
                    'plate_evidence':{'schema_version':'plate-evidence-v1','frame_id':frame_id,'jpeg_sha256':digest,
                        'bbox_xyxy':box,'model_sha256':probe['model_sha256'],'adapter':probe['adapter'],
                        'association':'unique_spatial_containment','localization_score':region['localization_score']}}
        base = {**base, **evidence}
        if (geometry['width_px'] < POLICY['minimum_plate_width_px']
                or geometry['height_px'] < POLICY['minimum_plate_height_px']):
            return {**base, 'reasons': ['wait_for_larger_plate_image']}, None
        if type(rank) not in {int, float} or not math.isfinite(rank) or rank < POLICY['minimum_readability_rank']:
            return {**base, 'reasons': ['wait_for_better_plate_image']}, None
        if motion=='unknown' and intent=='collect_vehicle_passages' and key not in self.motion_history:
            return {**base,'reasons':['passage_evidence_required']},None
        base['passage_evidence'] = ('moving_observed' if motion == 'moving' else 'recent_moving_observed'
                                    if key in self.motion_history else 'explicit_request')
        if previous is not None:
            base['representative'] = deepcopy(previous['representative'])
            improved = rank >= previous['representative']['rank'] + POLICY['improvement_delta']
            gap = now - previous['selected_ms']
            if previous['count']>=POLICY['maximum_recommendations_per_event']:
                return {**base,'decision':'deferred','business_state':'waiting_result','reasons':['event_recommendation_limit']},(key,previous,False)
            if not improved:
                return {**base, 'decision': 'skipped', 'business_state':'waiting_result', 'reasons': ['recent_recommendation_not_improved']}, (key, previous, False)
            if gap < POLICY['minimum_reselect_ms']:
                return {**base, 'decision': 'skipped', 'business_state':'waiting_result', 'reasons': ['recommendation_cooldown']}, (key, previous, False)
            reason = 'better_readability_candidate'
            count = previous['count'] + 1
        else:
            reason, count = 'first_usable_candidate', 1
        representative = {'frame_id': frame_id, 'jpeg_sha256': digest, 'relative_media_ms': now, 'rank': rank}
        saved = {'representative': representative, 'count': count, 'selected_ms': now, 'last_ms': now}
        return {**base, 'decision': 'selected', 'business_state':'need_read', 'reasons': [reason], 'representative': representative}, (key, saved, True)

    def select(self, image, targets, metadata, frame_id, digest, *, detection_complete):
        with self.lock:
            return self._select(image, targets, metadata, frame_id, digest, detection_complete=detection_complete)

    def _select(self, image, targets, metadata, frame_id, digest, *, detection_complete):
        if (not isinstance(targets, list) or len(targets) > 100 or type(detection_complete) is not bool
                or not isinstance(image, np.ndarray) or image.dtype != np.uint8
                or image.ndim != 3 or image.shape[2] != 3 or min(image.shape[:2]) < 1):
            raise ValueError('invalid specialist selection targets')
        camera = metadata['camera_id']
        replay_key = (metadata['scene_run_id'], metadata['media_epoch'], metadata['relative_media_ms'], frame_id, digest)
        replay = self.replays.get(camera)
        if metadata.get('specialist_intent','collect_vehicle_passages') not in {'collect_vehicle_passages','plate_query','verify_vehicle'}:
            raise ValueError('invalid plate business intent')
        target_fingerprint = hashlib.sha256(json.dumps({'targets': targets, 'complete': detection_complete,'intent':metadata.get('specialist_intent'),'motion':metadata.get('specialist_motion'),'scene_state':metadata.get('scene_state')},
            sort_keys=True, separators=(',', ':'), allow_nan=False).encode()).hexdigest()
        # Stable capture replay must not consume another recommendation.
        if replay is not None and replay[0] == replay_key:
            if target_fingerprint != replay[2]:
                raise ValueError('selection capture replay changed')
            return deepcopy(replay[1])
        context, now, reset = self._context(metadata, frame_id, digest)
        decisions, mutations = [], []
        probe=None
        if detection_complete and self.plate_probe is not None and any(t.get('object_type')=='vehicle' for t in targets):
            try:probe=self.plate_probe.locate(image)
            except (OSError,ValueError,RuntimeError,cv2.error):probe=None
        if detection_complete:
            for target in targets:
                decision, mutation = self._candidate(image, target, targets, context, now, frame_id, digest, metadata, probe)
                decisions.append(decision)
                mutations.append(mutation)
        else:
            decisions = [{ 'target_id': _uuid(t['target_id']), 'decision': 'deferred', 'business_state':'waiting_evidence',
                           'reasons': ['detection_incomplete'], 'representative': None} for t in targets]
            mutations = [None] * len(decisions)
        selected = sorted((index for index, value in enumerate(decisions) if value['decision'] == 'selected'),
                          key=lambda index: (-decisions[index]['readability_rank'], decisions[index]['target_id']))
        allowed = set(selected[:POLICY['maximum_selected_targets']])
        for index, mutation in enumerate(mutations):
            if mutation is None:
                continue
            key, value, changed = mutation
            if changed and index not in allowed:
                previous = self.entries.get(key, {})
                decisions[index].update(decision='deferred', business_state='waiting_evidence', reasons=['frame_target_budget'],
                                        representative=deepcopy(previous.get('representative')))
                continue
            if len(self.entries) >= POLICY['history_limit'] and key not in self.entries:
                self.entries.pop(min(self.entries, key=lambda item: self.entries[item]['last_ms']))
            self.entries[key] = {**value, 'last_ms': now}
        selected_ids = [value['target_id'] for value in decisions if value['decision'] == 'selected']
        if selected_ids:
            plate_state, plate_reasons = 'selected', ['readable_plate_candidate']
        elif not detection_complete:
            plate_state, plate_reasons = 'deferred', ['detection_incomplete']
        elif not any(t.get('object_type') == 'vehicle' for t in targets):
            plate_state, plate_reasons = 'skipped', ['no_detected_vehicle_candidate']
        elif any(value['decision'] == 'deferred' for value in decisions):
            plate_state, plate_reasons = 'deferred', ['wait_for_usable_vehicle_candidate']
        else:
            plate_state, plate_reasons = 'skipped', ['no_new_specialist_candidate']
        business_state = ('need_read' if selected_ids else 'waiting_evidence' if plate_state == 'deferred'
                          else 'waiting_result' if any(value.get('business_state') == 'waiting_result' for value in decisions)
                          else 'not_applicable')
        result = {
            'schema_version': SCHEMA, 'policy_sha256': POLICY_SHA256, 'mode': self.mode,
            'frame_id': frame_id, 'jpeg_sha256': digest, 'camera_id': context[0],
            'scene_run_id': context[1], 'media_epoch': context[2], 'relative_media_ms': now,
            'image_width': image.shape[1], 'image_height': image.shape[0],
            'modules': [
                {'module': 'license_plate', 'decision': plate_state, 'reasons': plate_reasons,
                  'business_state':business_state,
                 'selected_target_ids': selected_ids, 'targets': decisions},
                {'module': 'face_quality', 'decision': 'deferred',
                 'reasons': ['face_module_not_implemented'],
                 'selected_target_ids': [],
                 'operation_available': False, 'identity_matching': False},
            ],
            'context_reset': reset, 'history_entries': len(self.entries),
            'association_semantics': 'local_geometry_candidate_not_identity',
            'recommendation_is_inference_completion': False,
            'original_targets_changed': False, 'semantic_modules_changed': False,
            'frame_suppression': False,
        }
        self.replays[camera] = (replay_key, deepcopy(result), target_fingerprint)
        return result
