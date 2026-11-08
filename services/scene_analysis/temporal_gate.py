"""Fresh-evidence scene gate shared by the Worker and offline diagnostics."""
import math
from services.video_worker.scene_gate import SceneGate


class FreshEvidenceGate(SceneGate):
    def observe(self,now,motion,target_score,*,detector_update,quality_ok=True):
        if not math.isfinite(now) or self.last_time is not None and now<self.last_time:raise ValueError('nonmonotonic replay time')
        if motion is not None and (not math.isfinite(motion) or not 0<=motion<=255):raise ValueError('invalid motion')
        if target_score is not None and (not math.isfinite(target_score) or not 0<=target_score<=1):raise ValueError('invalid target score')
        self.last_time=now
        changed=motion is not None and motion>=self.policy.motion_threshold
        target=target_score is not None and target_score>=self.policy.target_threshold
        # Held strong scores still protect occupied scenes; only new evidence
        # extends the post-activity grace period. A failed detector is unknown.
        if changed or target and detector_update:
            self.active_until=now+self.policy.active_hold_seconds;self.empty_streak=0
        elif detector_update and target_score is not None and target_score<self.policy.ambiguous_threshold and quality_ok:
            self.empty_streak+=1
        elif detector_update:self.empty_streak=0
        active=target or self.active_until is not None and now<=self.active_until
        ambiguous=(not quality_ok or target_score is None or self.policy.ambiguous_threshold<=target_score<self.policy.target_threshold)
        if active and quality_ok:state='active'
        elif ambiguous or self.empty_streak<self.policy.empty_confirmations:state='unknown'
        else:state='empty_candidate'
        elapsed=None if self.last_selected is None else now-self.last_selected
        if elapsed is None or elapsed+self.policy.scheduling_jitter_seconds>=self.policy.fallback_seconds:reason='periodic_fallback'
        elif state=='active' and self.last_state!='active':reason='active_trigger'
        elif state=='unknown' and self.last_state=='empty_candidate':reason='unknown_trigger'
        elif state in {'active','unknown'} and now+self.policy.scheduling_jitter_seconds>=self.next_sample:
            reason='active_interval' if state=='active' else 'unknown_interval'
        else:reason=None
        if reason:
            self.last_selected=now
            if reason in {'periodic_fallback','active_trigger','unknown_trigger'}:self.next_sample=now+self.policy.sample_seconds
            else:
                while self.next_sample<=now+self.policy.scheduling_jitter_seconds:self.next_sample+=self.policy.sample_seconds
        self.last_state=state
        return state,reason


