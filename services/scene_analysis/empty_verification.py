"""Bounded second-opinion evidence for quiet, readable, ambiguous scenes.

Only repeated fresh verifier negatives can resolve a primary ambiguity. Strong
primary detections, motion, unreadable images and stale/failed work never become
empty evidence. No model, transport or image I/O belongs in this state machine.
"""
from collections import Counter
from dataclasses import dataclass
import math


@dataclass(frozen=True)
class VerificationRequest:
    token: int
    source_seconds: float


class SelectiveEmptyVerifier:
    def __init__(self, confirmations=2, interval_seconds=5., max_age_seconds=7., primary_max_age_seconds=2.):
        if (confirmations not in {2, 3} or interval_seconds not in {1., 5.}
                or not interval_seconds <= max_age_seconds <= 10 or not 0 < primary_max_age_seconds <= 2):
            raise ValueError('invalid empty verifier bounds')
        self.confirmations = confirmations
        self.interval = interval_seconds
        self.max_age = max_age_seconds
        self.primary_max_age = primary_max_age_seconds
        self.last_time = self.last_request_time = None
        self.pending = self.secondary = None
        self.negative_streak = self.serial = 0
        self.safe = self.primary_fresh = self.completed_now = False
        self.primary = self.primary_time = None
        self.counts = Counter()

    @staticmethod
    def _valid(score):
        return score is not None and not isinstance(score, bool) and math.isfinite(score) and 0 <= score <= 1

    def _invalidate(self):
        if self.pending is not None or self.secondary is not None or self.negative_streak:
            self.counts['verifier_invalidations'] += 1
        self.pending = self.secondary = None
        self.negative_streak = 0

    def observe(self, now, primary_score, primary_time, *, primary_fresh, quality_ok, activity):
        if not math.isfinite(now) or now < 0 or self.last_time is not None and now <= self.last_time:
            raise ValueError('nonmonotonic verifier time')
        if primary_score is not None and not self._valid(primary_score):
            raise ValueError('invalid primary score')
        if primary_score is not None and (primary_time is None or not math.isfinite(primary_time) or not 0 <= primary_time <= now):
            raise ValueError('invalid primary source clock')
        self.last_time = now
        self.primary = primary_score
        self.primary_time = primary_time
        self.primary_fresh = bool(primary_fresh)
        self.completed_now = False
        self.safe = bool(quality_ok and not activity and self._valid(primary_score) and primary_score < .35)
        if not self.safe:
            self._invalidate()
            return None
        if self.secondary is not None and now - self.secondary[0] > self.max_age:
            self.secondary = None
            self.negative_streak = 0
            self.counts['verifier_expirations'] += 1
        if self.pending is not None and now - self.pending.source_seconds > self.max_age:
            self.pending = None
            self.secondary = None
            self.negative_streak = 0
            self.counts['verifier_expirations'] += 1
        if not primary_fresh or not .10 <= primary_score < .35 or self.pending is not None:
            return None
        if now - primary_time > self.primary_max_age:
            self.counts['verifier_late_primary_skipped'] += 1
            return None
        if self.last_request_time is not None and primary_time - self.last_request_time < self.interval - 1e-6:
            return None
        self.serial += 1
        self.pending = VerificationRequest(self.serial, primary_time)
        self.last_request_time = primary_time
        self.counts['verifier_requests'] += 1
        return self.pending

    def complete(self, request, now, score):
        if self.last_time is None or not math.isfinite(now) or abs(now-self.last_time) > 1e-6:
            raise ValueError('invalid verifier completion clock')
        if self.pending != request or not self.safe:
            self.counts['verifier_ignored_results'] += 1
            return
        self.pending = None
        if now - request.source_seconds > self.max_age:
            self._invalidate()
            self.counts['verifier_late_results'] += 1
            return
        if not self._valid(score):
            self._invalidate()
            self.counts['verifier_errors'] += 1
            return
        self.secondary = (request.source_seconds, float(score))
        self.completed_now = True
        self.counts['verifier_results'] += 1
        if score < .10:
            self.negative_streak += 1
            self.counts['verifier_negative_results'] += 1
        else:
            self.negative_streak = 0

    def decision(self):
        # A failed primary remains unknown. No secondary result can erase a
        # strong primary or turn a current activity/quality problem into empty.
        if not self.safe or self.secondary is None:
            return self.primary, self.primary_fresh
        source_time, score = self.secondary
        if self.last_time - source_time > self.max_age:
            return self.primary, self.primary_fresh
        if score >= .35:
            self.counts['verifier_positive_protections'] += 1
            return max(self.primary, score), self.completed_now
        if score < .10 and self.negative_streak >= self.confirmations and .10 <= self.primary < .35:
            self.counts['verifier_resolved_ambiguities'] += 1
            return score, self.completed_now
        return self.primary, self.primary_fresh

    def snapshot(self):
        return {**self.counts, 'verifier_negative_streak': self.negative_streak,
                'verifier_pending': self.pending is not None}
