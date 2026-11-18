"""Single-camera Multi-Object Tracking (MOT): Kalman ByteTrack and OC-SORT.

Track IDs produced here represent camera-local candidate tracklets within an
unbroken `(camera_id, scene_run_id, media_epoch)` stream segment. They are not
verified identities and never cross cameras or clock discontinuities.
"""
from __future__ import annotations

from collections import deque
import math
import time
import uuid

import numpy as np

from .activity_regions import valid_box


VALID_LOCAL_ASSOCIATIONS = frozenset({
    'local_geometry_candidate',
    'bytetrack_kalman_v1',
    'ocsort_momentum_v1',
})

TRACKER_ASSOCIATIONS = {
    'legacy': 'local_geometry_candidate',
    'bytetrack': 'bytetrack_kalman_v1',
    'ocsort': 'ocsort_momentum_v1',
}


def linear_sum_assignment(cost_matrix) -> tuple[np.ndarray, np.ndarray]:
    """Exact minimum-weight bipartite matching (Jonker-Volgenant / Hungarian)."""
    cost = np.asarray(cost_matrix, dtype=np.float64)
    if cost.ndim != 2:
        raise ValueError('cost matrix must be 2-dimensional')
    rows, cols = cost.shape
    if rows == 0 or cols == 0:
        return np.empty(0, dtype=np.int64), np.empty(0, dtype=np.int64)
    if not np.isfinite(cost).all():
        raise ValueError('cost matrix contains non-finite values')
    transposed = False
    if rows > cols:
        cost = cost.T
        rows, cols = cols, rows
        transposed = True
    u = np.zeros(rows + 1, dtype=np.float64)
    v = np.zeros(cols + 1, dtype=np.float64)
    p = np.zeros(cols + 1, dtype=np.int64)
    way = np.zeros(cols + 1, dtype=np.int64)
    inf = 1e18
    for i in range(1, rows + 1):
        p[0] = i
        j0 = 0
        minv = np.full(cols + 1, inf, dtype=np.float64)
        used = np.zeros(cols + 1, dtype=bool)
        while True:
            used[j0] = True
            i0 = int(p[j0])
            delta = inf
            j1 = 0
            row_cost = cost[i0 - 1]
            cur = row_cost - u[i0] - v[1:]
            unused_indices = np.flatnonzero(~used[1:]) + 1
            if unused_indices.size:
                cur_unused = cur[unused_indices - 1]
                improved = cur_unused < minv[unused_indices]
                if np.any(improved):
                    upd_idx = unused_indices[improved]
                    minv[upd_idx] = cur_unused[improved]
                    way[upd_idx] = j0
                best_pos = int(np.argmin(minv[unused_indices]))
                j1 = int(unused_indices[best_pos])
                delta = float(minv[j1])
            used_mask = used
            if np.any(used_mask):
                used_cols = np.flatnonzero(used_mask)
                u[p[used_cols]] += delta
                v[used_cols] -= delta
            unused_cols = np.flatnonzero(~used_mask)
            if unused_cols.size:
                minv[unused_cols] -= delta
            j0 = j1
            if p[j0] == 0:
                break
        while True:
            j1 = int(way[j0])
            p[j0] = p[j1]
            j0 = j1
            if j0 == 0:
                break
    row_ind = np.empty(rows, dtype=np.int64)
    col_ind = np.empty(rows, dtype=np.int64)
    for j in range(1, cols + 1):
        if p[j] > 0:
            r = int(p[j]) - 1
            row_ind[r] = r
            col_ind[r] = j - 1
    order = np.argsort(row_ind, kind='stable')
    row_ind, col_ind = row_ind[order], col_ind[order]
    if transposed:
        row_ind, col_ind = col_ind, row_ind
        order = np.argsort(row_ind, kind='stable')
        row_ind, col_ind = row_ind[order], col_ind[order]
    return row_ind, col_ind


def iou_batch(boxes_a: np.ndarray, boxes_b: np.ndarray) -> np.ndarray:
    """Compute pairwise IoU matrix of shape (len(boxes_a), len(boxes_b))."""
    a = np.asarray(boxes_a, dtype=np.float64).reshape(-1, 4)
    b = np.asarray(boxes_b, dtype=np.float64).reshape(-1, 4)
    if a.shape[0] == 0 or b.shape[0] == 0:
        return np.zeros((a.shape[0], b.shape[0]), dtype=np.float64)
    x1 = np.maximum(a[:, None, 0], b[None, :, 0])
    y1 = np.maximum(a[:, None, 1], b[None, :, 1])
    x2 = np.minimum(a[:, None, 2], b[None, :, 2])
    y2 = np.minimum(a[:, None, 3], b[None, :, 3])
    inter = np.maximum(0.0, x2 - x1) * np.maximum(0.0, y2 - y1)
    area_a = np.maximum(0.0, a[:, 2] - a[:, 0]) * np.maximum(0.0, a[:, 3] - a[:, 1])
    area_b = np.maximum(0.0, b[:, 2] - b[:, 0]) * np.maximum(0.0, b[:, 3] - b[:, 1])
    union = np.maximum(area_a[:, None] + area_b[None, :] - inter, 1e-12)
    return inter / union


def _xyxy_to_z(box) -> np.ndarray:
    x1, y1, x2, y2 = (float(v) for v in box)
    w = max(1e-4, x2 - x1)
    h = max(1e-4, y2 - y1)
    cx = x1 + 0.5 * w
    cy = y1 + 0.5 * h
    return np.array([cx, cy, w / h, h], dtype=np.float64)


def _mean_to_xyxy(mean: np.ndarray) -> np.ndarray:
    cx, cy, aspect, h = mean[:4]
    h = max(1e-4, float(h))
    w = max(1e-4, float(aspect) * h)
    return np.array([cx - 0.5 * w, cy - 0.5 * h, cx + 0.5 * w, cy + 0.5 * h], dtype=np.float64)


class KalmanBoxFilter:
    """8-state constant-velocity Kalman filter for normalized [cx, cy, a, h] boxes."""

    def __init__(self):
        self._H = np.eye(4, 8, dtype=np.float64)
        self._std_pos = 0.05
        self._std_vel_init = 2.5
        self._std_vel_proc = 0.6

    def initiate(self, measurement_box) -> tuple[np.ndarray, np.ndarray]:
        z = _xyxy_to_z(measurement_box)
        mean = np.zeros(8, dtype=np.float64)
        mean[:4] = z
        h = max(1e-2, float(z[3]))
        std = np.array([
            2.0 * self._std_pos * h,
            2.0 * self._std_pos * h,
            2e-2,
            2.0 * self._std_pos * h,
            self._std_vel_init * h,
            self._std_vel_init * h,
            0.05,
            0.5 * self._std_vel_init * h,
        ], dtype=np.float64)
        covariance = np.diag(np.square(std))
        return mean, covariance

    def predict(self, mean: np.ndarray, covariance: np.ndarray, dt_seconds: float = 1.0, *, lost: bool = False) -> tuple[np.ndarray, np.ndarray]:
        dt = max(1e-3, min(5.5, float(dt_seconds)))
        h = max(1e-2, float(mean[3]))
        F = np.eye(8, dtype=np.float64)
        for i in range(4):
            F[i, i + 4] = dt
        pos_scale = self._std_pos * h * max(0.25, dt)
        vel_scale = self._std_vel_proc * h * max(0.25, dt)
        std_q = np.array([
            pos_scale, pos_scale, 1e-2 * max(0.25, dt), pos_scale,
            vel_scale, vel_scale, 1e-3 * max(0.25, dt), 0.5 * vel_scale,
        ], dtype=np.float64)
        if lost:
            mean = mean.copy()
            mean[6] = 0.0  # Keep aspect-ratio velocity damped while occluded.
            mean[7] = 0.0  # Avoid runaway box collapse/expansion during long gaps.
        next_mean = F @ mean
        next_mean[2] = max(1e-2, min(100.0, float(next_mean[2])))
        next_mean[3] = max(1e-4, min(2.0, float(next_mean[3])))
        next_cov = F @ covariance @ F.T + np.diag(np.square(std_q))
        return next_mean, next_cov

    def update(self, mean: np.ndarray, covariance: np.ndarray, measurement_box) -> tuple[np.ndarray, np.ndarray]:
        z = _xyxy_to_z(measurement_box)
        h = max(1e-2, float(mean[3]))
        std_r = np.array([
            self._std_pos * h,
            self._std_pos * h,
            1e-1,
            self._std_pos * h,
        ], dtype=np.float64)
        R = np.diag(np.square(std_r))
        projected_mean = self._H @ mean
        projected_cov = self._H @ covariance @ self._H.T + R
        kalman_gain = np.linalg.solve(projected_cov.T, (covariance @ self._H.T).T).T
        innovation = z - projected_mean
        next_mean = mean + kalman_gain @ innovation
        next_mean[2] = max(1e-2, min(100.0, float(next_mean[2])))
        next_mean[3] = max(1e-4, min(2.0, float(next_mean[3])))
        I_KH = np.eye(8, dtype=np.float64) - kalman_gain @ self._H
        next_cov = I_KH @ covariance @ I_KH.T + kalman_gain @ R @ kalman_gain.T
        next_cov = 0.5 * (next_cov + next_cov.T)
        return next_mean, next_cov


class _KalmanTrack:
    def __init__(self, kf: KalmanBoxFilter, class_name: str, box, confidence: float, step_idx: int, media_ms: int, *, confirmed: bool = True):
        self.track_id = str(uuid.uuid4())
        self.kf = kf
        self.class_name = class_name
        self.mean, self.covariance = kf.initiate(box)
        self.score = float(confidence)
        self.state = 'tracked' if confirmed else 'tentative'
        self.hits = 1
        self.age = 1
        self.missed_steps = 0
        self.first_seen_ms = int(media_ms)
        self.last_seen_ms = int(media_ms)
        self.last_seen_step = int(step_idx)
        self.last_observation = np.asarray(box, dtype=np.float64).copy()
        self.observations: deque[tuple[int, int, np.ndarray]] = deque(maxlen=16)
        self.observations.append((int(step_idx), int(media_ms), self.last_observation.copy()))
        self.velocity: tuple[float, float] | None = None

    def predict(self, dt_seconds: float):
        self.mean, self.covariance = self.kf.predict(
            self.mean, self.covariance, dt_seconds, lost=(self.state == 'lost')
        )
        self.age += 1

    @property
    def predicted_xyxy(self) -> np.ndarray:
        return _mean_to_xyxy(self.mean)

    def _previous_observation(self, delta_t: int) -> np.ndarray | None:
        if len(self.observations) < 2:
            return None
        target_step = self.last_seen_step - delta_t
        best_box = None
        best_dist = None
        for step_idx, _, box in self.observations:
            if step_idx == self.last_seen_step:
                continue
            dist = abs(step_idx - target_step)
            if best_dist is None or dist < best_dist:
                best_dist = dist
                best_box = box
        return best_box

    def update(self, box, confidence: float, step_idx: int, media_ms: int, *, delta_t: int = 3, reupdate_oru: bool = False):
        obs = np.asarray(box, dtype=np.float64).copy()
        prev_obs = self.last_observation
        prev_ms = self.last_seen_ms
        gap_steps = max(0, int(step_idx) - self.last_seen_step - 1)
        if reupdate_oru and gap_steps > 0 and prev_obs is not None:
            # Observation-Centric Re-Update (ORU): replay virtual linear steps over the gap
            # so Kalman velocity/covariance catch up to actual re-appearance without drift.
            steps = min(gap_steps, 8)
            dt_step = max(1e-3, (int(media_ms) - prev_ms) / 1000.0 / (steps + 1))
            for idx in range(1, steps + 1):
                alpha = idx / (steps + 1)
                interp = prev_obs * (1.0 - alpha) + obs * alpha
                self.mean, self.covariance = self.kf.predict(self.mean, self.covariance, dt_step, lost=False)
                self.mean, self.covariance = self.kf.update(self.mean, self.covariance, interp)
            self.mean, self.covariance = self.kf.predict(self.mean, self.covariance, dt_step, lost=False)
        self.mean, self.covariance = self.kf.update(self.mean, self.covariance, obs)
        self.last_observation = obs
        self.score = float(confidence)
        self.state = 'tracked'
        self.hits += 1
        self.missed_steps = 0
        self.last_seen_ms = int(media_ms)
        self.last_seen_step = int(step_idx)
        self.observations.append((int(step_idx), int(media_ms), obs.copy()))
        anchor = self._previous_observation(delta_t)
        if anchor is not None:
            dx = 0.5 * ((obs[0] + obs[2]) - (anchor[0] + anchor[2]))
            dy = 0.5 * ((obs[1] + obs[3]) - (anchor[1] + anchor[3]))
            norm = math.hypot(dx, dy)
            self.velocity = (dx / norm, dy / norm) if norm > 1e-6 else (0.0, 0.0)


def _match_by_iou(tracks: list[_KalmanTrack], det_boxes: np.ndarray, *, max_cost: float, use_last_obs: bool = False) -> tuple[list[tuple[int, int]], list[int], list[int]]:
    if not tracks or det_boxes.shape[0] == 0:
        return [], list(range(len(tracks))), list(range(det_boxes.shape[0]))
    track_boxes = np.asarray(
        [t.last_observation if use_last_obs else t.predicted_xyxy for t in tracks],
        dtype=np.float64,
    )
    cost = 1.0 - iou_batch(track_boxes, det_boxes)
    rows, cols = linear_sum_assignment(cost)
    matched, row_set, col_set = [], set(), set()
    for r, c in zip(rows.tolist(), cols.tolist()):
        if cost[r, c] <= max_cost:
            matched.append((r, c))
            row_set.add(r)
            col_set.add(c)
    unmatched_tracks = [i for i in range(len(tracks)) if i not in row_set]
    unmatched_dets = [j for j in range(det_boxes.shape[0]) if j not in col_set]
    return matched, unmatched_tracks, unmatched_dets


def _ocm_cost_matrix(tracks: list[_KalmanTrack], det_boxes: np.ndarray, inertia: float) -> tuple[np.ndarray, np.ndarray]:
    track_boxes = np.asarray([t.predicted_xyxy for t in tracks], dtype=np.float64)
    ious = iou_batch(track_boxes, det_boxes)
    cost = 1.0 - ious
    if inertia > 0 and tracks and det_boxes.shape[0] > 0:
        det_cx = 0.5 * (det_boxes[:, 0] + det_boxes[:, 2])
        det_cy = 0.5 * (det_boxes[:, 1] + det_boxes[:, 3])
        for i, track in enumerate(tracks):
            if track.velocity is None:
                continue
            vx, vy = track.velocity
            if vx == 0.0 and vy == 0.0:
                continue
            last_box = track.last_observation
            lcx = 0.5 * (last_box[0] + last_box[2])
            lcy = 0.5 * (last_box[1] + last_box[3])
            dx = det_cx - lcx
            dy = det_cy - lcy
            norms = np.hypot(dx, dy)
            valid = norms > 1e-6
            cos_sim = np.zeros(det_boxes.shape[0], dtype=np.float64)
            cos_sim[valid] = (dx[valid] / norms[valid]) * vx + (dy[valid] / norms[valid]) * vy
            cost[i, valid] -= inertia * cos_sim[valid]
    return cost, ious


class _ByteTrackClassPool:
    def __init__(self, class_name: str, *, high_thresh: float = 0.35, low_thresh: float = 0.10,
                 match_thresh: float = 0.75, second_thresh: float = 0.50,
                 max_lost_ms: int = 5500, max_tracks: int = 256):
        self.class_name = class_name
        self.high_thresh = float(high_thresh)
        self.low_thresh = float(low_thresh)
        self.match_thresh = float(match_thresh)
        self.second_thresh = float(second_thresh)
        self.max_lost_ms = int(max_lost_ms)
        self.max_tracks = int(max_tracks)
        self.kf = KalmanBoxFilter()
        self.tracked: list[_KalmanTrack] = []
        self.lost: list[_KalmanTrack] = []
        self.step_idx = 0

    def update(self, detections: list[dict], media_ms: int, dt_seconds: float, *, low_detections: list[dict] = ()) -> dict:
        self.step_idx += 1
        high_list = []
        low_list = list(low_detections)
        for idx, det in enumerate(detections):
            if det['confidence'] >= self.high_thresh:
                high_list.append((idx, det))
            elif det['confidence'] >= self.low_thresh:
                low_list.append(det)
        for track in self.tracked + self.lost:
            track.predict(dt_seconds)
        pool = self.tracked + self.lost
        high_boxes = np.asarray([d['bbox_normalized'] for _, d in high_list], dtype=np.float64).reshape(-1, 4)
        matched, u_track_idx, u_det_idx = _match_by_iou(pool, high_boxes, max_cost=self.match_thresh)
        next_tracked: list[_KalmanTrack] = []
        recovered = 0
        new_tracks = 0
        assignments: dict[int, tuple[_KalmanTrack, bool]] = {}
        matched_set = set()
        for r, c in matched:
            track = pool[r]
            was_lost = track.state == 'lost'
            orig_idx, det = high_list[c]
            track.update(det['bbox_normalized'], det['confidence'], self.step_idx, media_ms)
            next_tracked.append(track)
            assignments[orig_idx] = (track, was_lost)
            matched_set.add(track)
            if was_lost:
                recovered += 1
        rem_tracked = [pool[i] for i in u_track_idx if pool[i].state == 'tracked']
        rem_lost = [pool[i] for i in u_track_idx if pool[i].state == 'lost']
        if low_list and rem_tracked:
            low_boxes = np.asarray([d['bbox_normalized'] for d in low_list], dtype=np.float64).reshape(-1, 4)
            m_low, u_low_track, _ = _match_by_iou(rem_tracked, low_boxes, max_cost=self.second_thresh)
            for r, c in m_low:
                track = rem_tracked[r]
                det = low_list[c]
                track.update(det['bbox_normalized'], det['confidence'], self.step_idx, media_ms)
                next_tracked.append(track)
                matched_set.add(track)
            rem_tracked = [rem_tracked[i] for i in u_low_track]
        for track in rem_tracked:
            track.state = 'lost'
            track.missed_steps += 1
            if media_ms - track.last_seen_ms <= self.max_lost_ms:
                rem_lost.append(track)
        next_lost = []
        for track in rem_lost:
            if track in rem_tracked:
                next_lost.append(track)
            else:
                track.missed_steps += 1
                if media_ms - track.last_seen_ms <= self.max_lost_ms:
                    next_lost.append(track)
        for c in u_det_idx:
            orig_idx, det = high_list[c]
            track = _KalmanTrack(self.kf, self.class_name, det['bbox_normalized'], det['confidence'], self.step_idx, media_ms)
            next_tracked.append(track)
            assignments[orig_idx] = (track, False)
            new_tracks += 1
        if len(next_tracked) + len(next_lost) > self.max_tracks:
            excess = len(next_tracked) + len(next_lost) - self.max_tracks
            next_lost.sort(key=lambda t: t.last_seen_ms)
            next_lost = next_lost[min(excess, len(next_lost)):]
        self.tracked = next_tracked
        self.lost = next_lost
        return {'assignments': assignments, 'recovered': recovered, 'new_tracks': new_tracks,
                'active_tracks': len(self.tracked), 'lost_tracks': len(self.lost)}


class _OCSortClassPool:
    def __init__(self, class_name: str, *, det_thresh: float = 0.25, iou_thresh: float = 0.25,
                 delta_t: int = 3, inertia: float = 0.20, max_lost_ms: int = 5500, max_tracks: int = 256):
        self.class_name = class_name
        self.det_thresh = float(det_thresh)
        self.iou_thresh = float(iou_thresh)
        self.delta_t = int(delta_t)
        self.inertia = float(inertia)
        self.max_lost_ms = int(max_lost_ms)
        self.max_tracks = int(max_tracks)
        self.kf = KalmanBoxFilter()
        self.tracked: list[_KalmanTrack] = []
        self.lost: list[_KalmanTrack] = []
        self.step_idx = 0

    def update(self, detections: list[dict], media_ms: int, dt_seconds: float, *, low_detections: list[dict] = ()) -> dict:
        self.step_idx += 1
        eligible = [(idx, det) for idx, det in enumerate(detections) if det['confidence'] >= self.det_thresh]
        for track in self.tracked + self.lost:
            track.predict(dt_seconds)
        pool = self.tracked + self.lost
        det_boxes = np.asarray([d['bbox_normalized'] for _, d in eligible], dtype=np.float64).reshape(-1, 4)
        next_tracked: list[_KalmanTrack] = []
        recovered = 0
        new_tracks = 0
        assignments: dict[int, tuple[_KalmanTrack, bool]] = {}
        u_tracks = list(range(len(pool)))
        u_dets = list(range(len(eligible)))
        if pool and eligible:
            cost, ious = _ocm_cost_matrix(pool, det_boxes, self.inertia)
            # Shift cost positive while gating any pair with IoU < self.iou_thresh
            gated_cost = cost + self.inertia + 1.0
            rows, cols = linear_sum_assignment(gated_cost)
            r_matched, c_matched = set(), set()
            for r, c in zip(rows.tolist(), cols.tolist()):
                if ious[r, c] >= self.iou_thresh:
                    track = pool[r]
                    was_lost = track.state == 'lost'
                    orig_idx, det = eligible[c]
                    track.update(det['bbox_normalized'], det['confidence'], self.step_idx, media_ms,
                                 delta_t=self.delta_t, reupdate_oru=was_lost)
                    next_tracked.append(track)
                    assignments[orig_idx] = (track, was_lost)
                    r_matched.add(r)
                    c_matched.add(c)
                    if was_lost:
                        recovered += 1
            u_tracks = [i for i in range(len(pool)) if i not in r_matched]
            u_dets = [j for j in range(len(eligible)) if j not in c_matched]
        # Observation-Centric Recovery (OCR): match unmatched detections against last real observation.
        if u_tracks and u_dets:
            rem_pool = [pool[i] for i in u_tracks]
            rem_boxes = det_boxes[u_dets]
            m_ocr, u_ocr_t, u_ocr_d = _match_by_iou(rem_pool, rem_boxes, max_cost=1.0 - self.iou_thresh, use_last_obs=True)
            for r, c in m_ocr:
                track = rem_pool[r]
                was_lost = track.state == 'lost'
                det_pos = u_dets[c]
                orig_idx, det = eligible[det_pos]
                track.update(det['bbox_normalized'], det['confidence'], self.step_idx, media_ms,
                             delta_t=self.delta_t, reupdate_oru=was_lost)
                next_tracked.append(track)
                assignments[orig_idx] = (track, was_lost)
                if was_lost:
                    recovered += 1
            u_tracks = [u_tracks[i] for i in u_ocr_t]
            u_dets = [u_dets[j] for j in u_ocr_d]
        next_lost: list[_KalmanTrack] = []
        for idx in u_tracks:
            track = pool[idx]
            track.state = 'lost'
            track.missed_steps += 1
            if media_ms - track.last_seen_ms <= self.max_lost_ms:
                next_lost.append(track)
        for det_pos in u_dets:
            orig_idx, det = eligible[det_pos]
            track = _KalmanTrack(self.kf, self.class_name, det['bbox_normalized'], det['confidence'], self.step_idx, media_ms)
            next_tracked.append(track)
            assignments[orig_idx] = (track, False)
            new_tracks += 1
        if len(next_tracked) + len(next_lost) > self.max_tracks:
            excess = len(next_tracked) + len(next_lost) - self.max_tracks
            next_lost.sort(key=lambda t: t.last_seen_ms)
            next_lost = next_lost[min(excess, len(next_lost)):]
        self.tracked = next_tracked
        self.lost = next_lost
        return {'assignments': assignments, 'recovered': recovered, 'new_tracks': new_tracks,
                'active_tracks': len(self.tracked), 'lost_tracks': len(self.lost)}


class MotTrackerManager:
    """Drop-in local MOT manager preserving camera/run/epoch and class boundaries."""

    def __init__(self, mode: str = 'bytetrack', *, max_gap_ms: int = 5500, high_thresh: float = 0.35,
                 low_thresh: float = 0.10, match_thresh: float = 0.75, ocsort_iou_thresh: float = 0.25,
                 ocsort_inertia: float = 0.20, camera_limit: int = 12):
        if mode not in {'bytetrack', 'ocsort'}:
            raise ValueError('unsupported MOT mode')
        if not 500 <= max_gap_ms <= 30000 or not 0 < high_thresh <= 1 or not 0 < low_thresh <= high_thresh:
            raise ValueError('invalid MOT thresholds')
        self.mode = mode
        self.association = TRACKER_ASSOCIATIONS[mode]
        self.max_gap_ms = int(max_gap_ms)
        self.high_thresh = float(high_thresh)
        self.low_thresh = float(low_thresh)
        self.match_thresh = float(match_thresh)
        self.ocsort_iou_thresh = float(ocsort_iou_thresh)
        self.ocsort_inertia = float(ocsort_inertia)
        self.camera_limit = int(camera_limit)
        self.cameras: dict[str, dict] = {}

    def clear(self):
        self.cameras.clear()

    def _new_pool(self, class_name: str):
        if self.mode == 'bytetrack':
            return _ByteTrackClassPool(class_name, high_thresh=self.high_thresh, low_thresh=self.low_thresh,
                                       match_thresh=self.match_thresh, max_lost_ms=self.max_gap_ms)
        return _OCSortClassPool(class_name, det_thresh=self.high_thresh, iou_thresh=self.ocsort_iou_thresh,
                                inertia=self.ocsort_inertia, max_lost_ms=self.max_gap_ms)

    def assign(self, camera: str, run_id: str, epoch: int, media_ms: int, targets: list[dict],
               *, low_targets: list[dict] = ()) -> dict:
        started = time.perf_counter()
        context = (camera, run_id, epoch)
        prev = self.cameras.get(camera)
        context_reset = False
        dt_seconds = 1.0
        if (prev is None or prev['context'] != context
                or media_ms <= prev['last_ms']
                or media_ms - prev['last_ms'] > self.max_gap_ms):
            if prev is not None:
                context_reset = True
            self.cameras.pop(camera, None)
            if len(self.cameras) >= self.camera_limit:
                self.cameras.pop(next(iter(self.cameras)))
            prev = {'context': context, 'last_ms': int(media_ms), 'pools': {}}
            self.cameras[camera] = prev
        else:
            dt_seconds = (media_ms - prev['last_ms']) / 1000.0
            prev['last_ms'] = int(media_ms)
        by_class: dict[str, list[tuple[int, dict]]] = {}
        for idx, target in enumerate(targets):
            if not valid_box(target['bbox_normalized']):
                raise ValueError('invalid MOT target box')
            by_class.setdefault(target['class_name'], []).append((idx, target))
        low_by_class: dict[str, list[dict]] = {}
        for target in low_targets:
            if not valid_box(target['bbox_normalized']):
                raise ValueError('invalid MOT low target box')
            low_by_class.setdefault(target['class_name'], []).append(target)
        recovered_total = 0
        new_total = 0
        active_total = 0
        lost_total = 0
        seen_classes = set(by_class) | set(low_by_class) | set(prev['pools'])
        for cls in sorted(seen_classes):
            pool = prev['pools'].get(cls)
            if pool is None:
                if cls not in by_class:
                    continue
                pool = self._new_pool(cls)
                prev['pools'][cls] = pool
            indexed = by_class.get(cls, [])
            cls_dets = [det for _, det in indexed]
            cls_low = low_by_class.get(cls, [])
            stats = pool.update(cls_dets, media_ms, dt_seconds, low_detections=cls_low)
            recovered_total += stats['recovered']
            new_total += stats['new_tracks']
            active_total += stats['active_tracks']
            lost_total += stats['lost_tracks']
            for local_idx, (track, _was_lost) in stats['assignments'].items():
                orig_idx = indexed[local_idx][0]
                targets[orig_idx]['track_id'] = track.track_id
                targets[orig_idx]['association'] = self.association
            for local_idx, (orig_idx, target) in enumerate(indexed):
                if local_idx not in stats['assignments']:
                    target['track_id'] = str(uuid.uuid4())
                    target['association'] = self.association
            if not pool.tracked and not pool.lost:
                prev['pools'].pop(cls, None)
        return {
            'schema': 'target-mot-v1',
            'tracker_mode': self.mode,
            'association': self.association,
            'context_reset': context_reset,
            'active_tracks': active_total,
            'lost_tracks': lost_total,
            'recovered_tracks': recovered_total,
            'new_tracks': new_total,
            'elapsed_ms': round((time.perf_counter() - started) * 1000, 3),
        }
