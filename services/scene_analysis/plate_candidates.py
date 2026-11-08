"""Bounded same-image plate rechecks, with spatial evidence and explicit rejection.

This prepares dedicated recognition; it neither suppresses video frames nor
establishes vehicle identity. Detection scores are not character probabilities.
"""
from dataclasses import asdict, dataclass
import hashlib
import json
import math
from typing import Callable
import unicodedata

import numpy as np

from .adaptive_crops import iou
from .target_quality import target_quality


POLICY = {
    'schema': 'plate-preparation-v1', 'max_extra_regions': 4,
    'max_candidates_per_pass': 100, 'max_groups': 100,
    'roi_width_multiplier': 2.5, 'roi_height_multiplier': 4.0,
    'minimum_roi_width': 256, 'minimum_roi_height': 128,
    'duplicate_iou': .5, 'region_duplicate_iou': .9, 'edge_margin_px': 2,
    'exploration_fraction': .6,
    'readable_formats': 'common 7/8 character mainland plates; not authenticity validation',
    'quality_role': 'observable ranking only; never a correctness probability',
}
POLICY_SHA256 = hashlib.sha256(json.dumps(POLICY, sort_keys=True).encode()).hexdigest()
PROVINCES = frozenset('京沪津渝冀晋蒙辽吉黑苏浙皖闽赣鲁豫鄂湘粤桂琼川贵云藏陕甘青宁新')
LETTERS = frozenset('ABCDEFGHJKLMNPQRSTUVWXYZ')
ALPHANUMERIC = LETTERS | frozenset('0123456789')
SPECIAL_SUFFIXES = frozenset('学警港澳挂')


def normalized_reading(text: str) -> str:
    return ''.join(c for c in unicodedata.normalize('NFKC', text).upper()
                   if not c.isspace() and c not in '.·•')


def reading_rejection(text: str) -> str | None:
    """Conservative supported-format guard; never repair or infer characters."""
    text = normalized_reading(text)
    if len(text) < 7:
        return 'partial_text'
    if (len(text) not in {7, 8} or text[0] not in PROVINCES or text[1] not in LETTERS
            or any(c not in ALPHANUMERIC for c in text[2:-1])
            or text[-1] not in ALPHANUMERIC | SPECIAL_SUFFIXES):
        return 'unsupported_plate_format'
    return None


@dataclass(frozen=True)
class PlateCandidate:
    source: str
    window: tuple[int, int, int, int]
    bbox_xyxy: tuple[int, int, int, int]
    landmarks: tuple[tuple[float, float], ...]
    raw_text: str
    text: str
    detection_score: float
    readability_rank: float | None
    rejection: str | None


def _number(value):
    if type(value) not in {int, float} or not math.isfinite(value):
        raise ValueError('invalid plate geometry number')
    return value


def _mapped_candidate(image, value, window, source):
    if not isinstance(value, dict):
        raise ValueError('invalid plate candidate')
    box, points = value.get('axis'), value.get('landmarks')
    text, score = value.get('plate'), value.get('score')
    if (not isinstance(box, (tuple, list)) or len(box) != 4
            or not isinstance(points, (tuple, list)) or len(points) != 4
            or not isinstance(text, str) or len(text) > 64
            or any(ord(c) < 32 or 0xD800 <= ord(c) <= 0xDFFF for c in text)
            or not 0 <= _number(score) <= 1):
        raise ValueError('invalid plate candidate fields')
    left, top, right, bottom = window
    width, height = right - left, bottom - top
    x1, y1, x2, y2 = map(_number, box)
    if not 0 <= x1 < x2 <= width or not 0 <= y1 < y2 <= height:
        raise ValueError('plate box outside source window')
    mapped_points = []
    for point in points:
        if not isinstance(point, (tuple, list)) or len(point) != 2:
            raise ValueError('invalid plate landmark')
        x, y = map(_number, point)
        if not 0 <= x <= width or not 0 <= y <= height:
            raise ValueError('plate landmark outside source window')
        mapped_points.append((x + left, y + top))
    if len(set(mapped_points)) != 4:
        raise ValueError('degenerate plate landmarks')
    turns = []
    for index in range(4):
        a, b, c = (mapped_points[(index + offset) % 4] for offset in range(3))
        turns.append((b[0] - a[0]) * (c[1] - b[1]) - (b[1] - a[1]) * (c[0] - b[0]))
    if not (all(turn > 0 for turn in turns) or all(turn < 0 for turn in turns)):
        raise ValueError('nonconvex or collinear plate landmarks')
    mapped_box = (math.floor(x1 + left), math.floor(y1 + top),
                  math.ceil(x2 + left), math.ceil(y2 + top))
    margin = POLICY['edge_margin_px']
    image_height, image_width = image.shape[:2]
    edge = ((left > 0 and x1 <= margin) or (top > 0 and y1 <= margin)
            or (right < image_width and x2 >= width - margin)
            or (bottom < image_height and y2 >= height - margin))
    quality = target_quality(image, {'bbox_xyxy': mapped_box})
    rejection = 'crop_edge_fragment' if edge else None
    return PlateCandidate(source, window, mapped_box, tuple(mapped_points), text,
                          normalized_reading(text), float(score),
                          quality['ranks'].get('target_balanced'), rejection)


def _centered_window(box, width, height):
    x1, y1, x2, y2 = box
    crop_width = min(width, max(POLICY['minimum_roi_width'],
                               math.ceil((x2 - x1) * POLICY['roi_width_multiplier'])))
    crop_height = min(height, max(POLICY['minimum_roi_height'],
                                 math.ceil((y2 - y1) * POLICY['roi_height_multiplier'])))
    left = max(0, min(width - crop_width, round((x1 + x2 - crop_width) / 2)))
    top = max(0, min(height - crop_height, round((y1 + y2 - crop_height) / 2)))
    return left, top, left + crop_width, top + crop_height


def _proposals(candidates, width, height, budget):
    full = (0, 0, width, height)
    windows = []
    ranked = sorted(candidates, key=lambda c: (
        reading_rejection(c.text) is None,
        min(c.bbox_xyxy[2] - c.bbox_xyxy[0], c.bbox_xyxy[3] - c.bbox_xyxy[1]),
        -c.detection_score, c.bbox_xyxy))
    proposals = [('plate_roi', _centered_window(c.bbox_xyxy, width, height)) for c in ranked]
    crop_width = max(1, round(width * POLICY['exploration_fraction']))
    crop_height = max(1, round(height * POLICY['exploration_fraction']))
    proposals += [('exploration', (x, y, x + crop_width, y + crop_height))
                  for x, y in [(0, 0), (width - crop_width, height - crop_height),
                               (width - crop_width, 0), (0, height - crop_height)]]
    for kind, window in proposals:
        if window == full or any(iou(window, old['bbox_xyxy']) >= POLICY['region_duplicate_iou'] for old in windows):
            continue
        windows.append({'kind': kind, 'bbox_xyxy': window})
        if len(windows) == budget:
            break
    return windows if budget else []


def _group_candidates(candidates):
    groups, rejected, ambiguous = [], [], set()
    for index, candidate in enumerate(candidates):
        if candidate.rejection:
            rejected.append({'candidate_index': index, 'reason': candidate.rejection})
            continue
        # Complete-link matching avoids a bridging box merging two nearby plates.
        touches = [index for index, group in enumerate(groups)
                   if any(iou(candidate.bbox_xyxy, candidates[old].bbox_xyxy)
                          >= POLICY['duplicate_iou'] for old in group)]
        matches = [index for index in touches
                   if all(iou(candidate.bbox_xyxy, candidates[old].bbox_xyxy)
                          >= POLICY['duplicate_iou'] for old in groups[index])]
        if len(touches) > 1 or touches and not matches:
            ambiguous.update(touches)
            rejected.append({'candidate_index': index, 'reason': 'ambiguous_spatial_group'})
        elif matches:
            groups[matches[0]].append(index)
        elif len(groups) < POLICY['max_groups']:
            groups.append([index])
        else:
            rejected.append({'candidate_index': index, 'reason': 'group_budget_exceeded'})
    result = []
    for group_index, group in enumerate(groups):
        readings = sorted({candidates[index].text for index in group})
        limitations = sorted({reason for text in readings if (reason := reading_rejection(text))})
        if len(readings) > 1:
            limitations.append('inconsistent_readings')
        if group_index in ambiguous:
            limitations.append('ambiguous_spatial_group')
        # The score only selects geometry among the same spatial group.
        selected = max(group, key=lambda index: (candidates[index].detection_score,
                       candidates[index].readability_rank or 0, -index))
        result.append({'candidate_indices': group, 'bbox_xyxy': candidates[selected].bbox_xyxy,
                       'readings': readings, 'accepted': not limitations,
                       'text': readings[0] if not limitations else None,
                       'limitations': limitations, 'recognition_score': None})
    return result, rejected


def prepare_plate_candidates(image: np.ndarray, infer: Callable, *, extra_regions: int = 0):
    """Run mandatory full image then at most four native-pixel regional rechecks."""
    if (not isinstance(image, np.ndarray) or image.dtype != np.uint8 or image.ndim != 3
            or image.shape[2] != 3 or min(image.shape[:2]) < 1
            or max(image.shape[:2]) > 20000 or image.shape[0] * image.shape[1] > 50000000
            or type(extra_regions) is not int or not 0 <= extra_regions <= POLICY['max_extra_regions']):
        raise ValueError('invalid plate preparation input')
    height, width = image.shape[:2]
    candidates, invalid = [], []

    def append_outputs(outputs, window, source):
        if not isinstance(outputs, list) or len(outputs) > POLICY['max_candidates_per_pass']:
            raise ValueError('plate output exceeded contract')
        for index, value in enumerate(outputs):
            try:
                candidate = _mapped_candidate(image, value, window, source)
            except ValueError:
                invalid.append({'source': source, 'index': index, 'reason': 'invalid_candidate_geometry_or_fields'})
                continue
            candidates.append(candidate)

    # A caller-supplied model must not mutate the frozen original or future crops.
    append_outputs(infer(image.copy()), (0, 0, width, height), 'full')
    baseline_count = len(candidates)
    windows = _proposals(candidates, width, height, extra_regions)
    failures = []
    for index, proposal in enumerate(windows):
        window = proposal['bbox_xyxy']
        left, top, right, bottom = window
        try:
            outputs = infer(image[top:bottom, left:right].copy())
            append_outputs(outputs, window, f'region_{index}')
        except (ValueError, RuntimeError, OSError, TimeoutError):
            failures.append({'region_index': index, 'reason': 'regional_inference_failed'})
    groups, rejected = _group_candidates(candidates)
    return {
        'schema_version': POLICY['schema'], 'policy_sha256': POLICY_SHA256,
        'state': 'partial_baseline_retained' if failures or invalid else 'complete',
        'image_width': width, 'image_height': height, 'input_role': 'frozen_original_jpeg',
        'full_inference_performed': True, 'baseline_count': baseline_count,
        'valid_baseline_candidates_retained': True, 'extra_pass_budget': extra_regions,
        'extra_passes': len(windows), 'windows': windows, 'regional_failures': failures,
        'invalid_candidates': invalid, 'candidates': [asdict(c) for c in candidates],
        'groups': groups, 'rejected_candidates': rejected,
        'accepted_groups': sum(group['accepted'] for group in groups),
        'character_confidence_calibrated': False, 'vehicle_identity_established': False,
        'high_resolution_coverage_complete': False, 'empty_scene_evidence': False,
        'frame_suppression': False,
    }
