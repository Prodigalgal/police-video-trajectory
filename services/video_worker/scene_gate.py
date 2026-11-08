"""Conservative, camera-local scene gate for shadow evaluation.

This state machine does not inspect video itself or delete recordings. A caller
supplies local scene-change/target signals with monotonic time. Thresholds are
experimental until compared against labeled footage from the real six points.
"""
from dataclasses import dataclass
import math


@dataclass(frozen=True)
class SceneGatePolicy:
    sample_seconds: float = 5.0
    fallback_seconds: float = 30.0
    active_hold_seconds: float = 10.0
    motion_threshold: float = 5.0
    target_threshold: float = .35
    ambiguous_threshold: float = .10
    empty_confirmations: int = 3
    scheduling_jitter_seconds: float = .05

    def validate(self):
        if not (1 <= self.sample_seconds <= 3600 and self.sample_seconds <= self.fallback_seconds <= 3600 and
                0 <= self.active_hold_seconds <= 3600 and 0 <= self.motion_threshold <= 255 and
                0 <= self.ambiguous_threshold < self.target_threshold <= 1 and 1 <= self.empty_confirmations <= 100 and
                0 <= self.scheduling_jitter_seconds <= .25):
            raise ValueError("invalid scene gate policy")


class SceneGate:
    def __init__(self, policy: SceneGatePolicy):
        policy.validate()
        self.policy = policy
        self.last_time = None
        self.last_selected = None
        self.next_sample = None
        self.last_state = None
        self.active_until = None
        self.empty_streak = 0

    def observe(self, now: float, motion: float | None, target_score: float | None,
                *, detector_update: bool, quality_ok: bool = True):
        """Return (state, selection_reason); a None score is unknown, not empty."""
        if not math.isfinite(now) or (self.last_time is not None and now < self.last_time):
            raise ValueError("scene timestamps must be monotonic")
        if motion is not None and (not math.isfinite(motion) or not 0 <= motion <= 255):
            raise ValueError("invalid motion score")
        if target_score is not None and (not math.isfinite(target_score) or not 0 <= target_score <= 1):
            raise ValueError("invalid target score")
        self.last_time = now
        changed = motion is not None and motion >= self.policy.motion_threshold
        target = target_score is not None and target_score >= self.policy.target_threshold
        if changed or target:
            self.active_until = now + self.policy.active_hold_seconds
            self.empty_streak = 0
        elif detector_update and target_score is not None and target_score < self.policy.ambiguous_threshold and quality_ok:
            self.empty_streak += 1
        elif detector_update:
            self.empty_streak = 0

        active = self.active_until is not None and now <= self.active_until
        ambiguous = (not quality_ok or target_score is None or
                     self.policy.ambiguous_threshold <= target_score < self.policy.target_threshold)
        if active and quality_ok:
            state = "active"
        elif ambiguous or self.empty_streak < self.policy.empty_confirmations:
            state = "unknown"
        else:
            # A candidate is not verified empty; the original video must remain available.
            state = "empty_candidate"

        elapsed = None if self.last_selected is None else now - self.last_selected
        if elapsed is None or elapsed + self.policy.scheduling_jitter_seconds >= self.policy.fallback_seconds:
            reason = "periodic_fallback"
        elif state == "active" and self.last_state != "active":
            # Capture the beginning of an effective scene without waiting for
            # the previous empty or uncertain interval to expire.
            reason = "active_trigger"
        elif state == "unknown" and self.last_state == "empty_candidate":
            reason = "unknown_trigger"
        elif state in ("active", "unknown") and now + self.policy.scheduling_jitter_seconds >= self.next_sample:
            # Unknown is not an empty scene. Continue at the configured sampling
            # interval when the detector is unavailable or the picture is poor.
            reason = "active_interval" if state == "active" else "unknown_interval"
        else:
            reason = None
        if reason:
            self.last_selected = now
            if reason in ("periodic_fallback", "active_trigger", "unknown_trigger"):
                self.next_sample = now + self.policy.sample_seconds
            else:
                # Keep deadlines anchored to the media clock. Scheduling from
                # the last late arrival would discard the next on-time frame.
                passed = math.floor((now + self.policy.scheduling_jitter_seconds - self.next_sample) /
                                    self.policy.sample_seconds) + 1
                self.next_sample += passed * self.policy.sample_seconds
        self.last_state = state
        return state, reason
