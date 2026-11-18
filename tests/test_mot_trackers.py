"""Deterministic contract & regression tests for Kalman ByteTrack and OC-SORT MOT."""
from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
import hashlib
import itertools
from types import SimpleNamespace
import uuid

import cv2
import numpy as np
import pytest

from services.scene_analysis.mot_trackers import (
    MotTrackerManager,
    linear_sum_assignment,
)
from services.scene_analysis.target_quality import TargetQualityMemory
from services.video_worker.config import Settings
from services.video_worker.target_pipeline import CandidateTracker, TargetPlanner


def _box_det(cx, cy, w=0.08, h=0.16, conf=0.85, cls='person', otype='person', img_w=1280, img_h=720):
    x1, y1 = max(0.0, cx - w / 2), max(0.0, cy - h / 2)
    x2, y2 = min(1.0, cx + w / 2), min(1.0, cy + h / 2)
    return {
        'target_id': str(uuid.uuid4()),
        'object_type': otype,
        'class_name': cls,
        'confidence': conf,
        'bbox_xyxy': [round(x1 * img_w, 2), round(y1 * img_h, 2), round(x2 * img_w, 2), round(y2 * img_h, 2)],
        'bbox_normalized': [round(x1, 6), round(y1, 6), round(x2, 6), round(y2, 6)],
    }


def test_linear_sum_assignment_matches_bruteforce_exact():
    assert tuple(map(len, linear_sum_assignment(np.empty((0, 3))))) == (0, 0)
    rng = np.random.default_rng(7)
    for rows, cols in [(3, 5), (5, 3), (5, 5)]:
        cost = rng.uniform(0.0, 10.0, size=(rows, cols))
        r_ind, c_ind = linear_sum_assignment(cost)
        assert len(r_ind) == min(rows, cols)
        got = float(cost[r_ind, c_ind].sum())
        if rows <= cols:
            best = min(sum(cost[i, perm[i]] for i in range(rows)) for perm in itertools.permutations(range(cols), rows))
        else:
            best = min(sum(cost[perm[j], j] for j in range(cols)) for perm in itertools.permutations(range(rows), cols))
        assert got == pytest.approx(best, abs=1e-9)


@pytest.mark.parametrize('mode,association', [
    ('bytetrack', 'bytetrack_kalman_v1'),
    ('ocsort', 'ocsort_momentum_v1'),
])
def test_kalman_mot_recovers_moving_target_across_missed_detection_gap(mode, association):
    legacy = CandidateTracker()
    tracker = MotTrackerManager(mode=mode, max_gap_ms=5500)
    run = 'run-1'
    # Smoothly moving pedestrian shifts further than IoU>=0.5 threshold when missed for 2 steps
    positions = [(0, 0.20), (500, 0.24), (1000, 0.28), (1500, None), (2000, None), (2500, 0.40), (3000, 0.44)]
    legacy_ids = []
    mot_ids = []
    for ms, cx in positions:
        l_dets = [] if cx is None else [_box_det(cx, 0.5)]
        m_dets = [] if cx is None else [_box_det(cx, 0.5)]
        legacy.assign('cam-1', run, 0, ms, l_dets)
        info = tracker.assign('cam-1', run, 0, ms, m_dets)
        if m_dets:
            legacy_ids.append(l_dets[0]['track_id'])
            mot_ids.append(m_dets[0]['track_id'])
            assert m_dets[0]['association'] == association
            if ms == 2500:
                assert info['recovered_tracks'] == 1
    assert len(set(legacy_ids)) >= 2  # legacy fragments without motion prediction or lost-buffer
    assert len(set(mot_ids)) == 1     # ByteTrack and OC-SORT recover unified track ID


def test_bytetrack_two_stage_uses_low_confidence_detections_to_bridge_blur():
    tracker = MotTrackerManager(mode='bytetrack', high_thresh=0.45, low_thresh=0.10)
    run = 'run-blur'
    ids = []
    for step, ms in enumerate([0, 400, 800, 1200, 1600]):
        cx = 0.20 + step * 0.045
        if step == 2:
            # Confidence dips below high_thresh (0.22); high list is empty while low_targets carries detection
            info = tracker.assign('cam-1', run, 0, ms, [], low_targets=[_box_det(cx, 0.45, conf=0.22)])
            assert info['active_tracks'] == 1
            assert info['lost_tracks'] == 0
        else:
            dets = [_box_det(cx, 0.45, conf=0.85)]
            tracker.assign('cam-1', run, 0, ms, dets)
            ids.append(dets[0]['track_id'])
    assert len(set(ids)) == 1


def test_ocsort_handles_crossing_and_nonlinear_stop_turn_recovery():
    tracker = MotTrackerManager(mode='ocsort', high_thresh=0.35, ocsort_iou_thresh=0.20, ocsort_inertia=0.25)
    run = 'run-cross'
    # Track A moves right along y=0.4; Track B moves left across A and reappears after brief occlusion
    a_ids, b_ids = [], []
    seq = [
        (0, 0.20, 0.40, 0.76, 0.40),
        (400, 0.25, 0.40, 0.71, 0.40),
        (800, 0.30, 0.40, 0.66, 0.40),
        (1200, 0.35, 0.40, 0.61, 0.40),
        (1600, 0.40, 0.40, None, None),  # B temporarily occluded before crossing
        (2000, 0.45, 0.40, 0.51, 0.40),  # B reappears continuing leftwards
        (2400, 0.50, 0.40, 0.46, 0.40),
    ]
    for ms, ax, ay, bx, by in seq:
        dets = [_box_det(ax, ay, w=0.09, h=0.16, conf=0.88)]
        if bx is not None:
            dets.append(_box_det(bx, by, w=0.09, h=0.16, conf=0.88))
        tracker.assign('cam-1', run, 0, ms, dets)
        a_ids.append(dets[0]['track_id'])
        if bx is not None:
            b_ids.append(dets[1]['track_id'])
    assert len(set(a_ids)) == 1
    assert len(set(b_ids)) == 1
    assert a_ids[0] != b_ids[0]


@pytest.mark.parametrize('mode', ['bytetrack', 'ocsort'])
def test_mot_never_crosses_class_camera_epoch_or_gap_boundaries(mode):
    tracker = MotTrackerManager(mode=mode, max_gap_ms=5500)
    a = [_box_det(0.5, 0.5, cls='person', otype='person')]
    b = [_box_det(0.5, 0.5, cls='car', otype='vehicle')]
    tracker.assign('cam-1', 'run-a', 0, 1000, a)
    tracker.assign('cam-1', 'run-a', 0, 1500, b)  # same box, different class
    assert a[0]['track_id'] != b[0]['track_id']

    c = [_box_det(0.5, 0.5, cls='person', otype='person')]
    tracker.assign('cam-1', 'run-a', 1, 2000, c)  # media_epoch bumped -> reset!
    assert c[0]['track_id'] != a[0]['track_id']

    d = [_box_det(0.5, 0.5, cls='person', otype='person')]
    info_gap = tracker.assign('cam-1', 'run-a', 1, 8000, d)  # > 5500ms gap -> reset!
    assert info_gap['context_reset'] is True
    assert d[0]['track_id'] != c[0]['track_id']

    e = [_box_det(0.5, 0.5, cls='person', otype='person')]
    info_cam2 = tracker.assign('cam-2', 'run-a', 1, 8000, e)  # different camera -> independent
    assert e[0]['track_id'] != d[0]['track_id']
    assert info_cam2['context_reset'] is False


def test_target_planner_and_config_support_mot_modes_with_downstream_compatibility():
    img = np.full((180, 320, 3), 120, dtype=np.uint8)
    cv2.rectangle(img, (130, 60), (190, 120), (40, 180, 220), -1)
    cv2.line(img, (135, 65), (185, 115), (255, 255, 255), 2)
    ok, enc = cv2.imencode('.jpg', img)
    assert ok
    jpeg = enc.tobytes()
    sha = hashlib.sha256(jpeg).hexdigest()
    frame_id = str(uuid.uuid4())
    run_id = str(uuid.uuid4())

    class StubProbe:
        kind = 'nanodet'
        def detect(self, image, threshold):
            return {
                'detector': 'nanodet',
                'image_width': 320,
                'image_height': 180,
                'targets': [_box_det(0.5, 0.5, w=0.18, h=0.33, img_w=320, img_h=180)],
            }

    detector = SimpleNamespace(probe=StubProbe(), executor=ThreadPoolExecutor(max_workers=1), model_sha256='0' * 64)
    for mode, expected_assoc in [('bytetrack', 'bytetrack_kalman_v1'), ('ocsort', 'ocsort_momentum_v1')]:
        planner = TargetPlanner(detector, threshold=0.35, quality_mode='observe', tracker_mode=mode)
        meta = {'camera_id': 'recording-01', 'scene_run_id': run_id, 'media_epoch': 0, 'relative_media_ms': 1000}
        plan = planner.plan(frame_id, jpeg, meta)
        assert plan['targets'][0]['association'] == expected_assoc
        assert plan['selection_metrics']['target_tracking']['tracker_mode'] == mode
        assert plan['selection_metrics']['target_quality']['state'] == 'scored'
        rec = plan['selection_metrics']['target_quality']['temporal']['recommendations'][0]
        assert rec['reason'] == 'first_geometric_candidate'
        assert rec['semantic_change'] is None

    cfg = Settings(core_url='https://core.local', core_ca=__file__, worker_token='tok',
                   gateway_url='https://gw.local', gateway_ca=__file__, gateway_token='gtok',
                   target_pipeline=True, scene_gate_mode='active', target_tracker_mode='ocsort',
                   sample_seconds=60)
    with pytest.raises(ValueError, match='invalid target tracker mode'):
        Settings(core_url='https://core.local', core_ca=__file__, worker_token='tok',
                 target_tracker_mode='deepsort_unapproved').validate('ingest')
    cfg.validate('analyze')
    assert cfg.target_tracker_mode == 'ocsort'
