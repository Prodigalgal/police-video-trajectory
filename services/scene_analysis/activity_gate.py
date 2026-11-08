"""Business empty = no effective activity, including stationary vehicles.

Presence scores never keep a stationary scene active. Unknown is accounted
separately. Static audit ticks do not create analysis requests.
"""
from dataclasses import dataclass
import math

from .motion_guard import guard_activity,guard_uncertain


@dataclass(frozen=True)
class ActivityPolicy:
    sample_seconds:float=5.
    quiet_seconds:float=10.
    audit_seconds:float=300.
    guard_width:int=320
    motion_method:str='robust'

    def validate(self):
        if (any(not math.isfinite(v) for v in (self.sample_seconds,self.quiet_seconds,self.audit_seconds))
            or not 1<=self.sample_seconds<=3600 or not .4<=self.quiet_seconds<=120
            or not 10<=self.audit_seconds<=3600 or self.guard_width not in {320,640}
            or self.motion_method not in {'robust','mog2','knn'}):
            raise ValueError('invalid activity policy')


class ActivityGate:
    def __init__(self,policy):
        policy.validate()
        self.policy = policy
        self.last_time = self.start = self.last_motion = self.next_sample = self.last_audit = None
        self.last_state = 'unknown'

    def observe(self,now,guard,motion,*,quality_ok=True):
        if not math.isfinite(now) or self.last_time is not None and now<=self.last_time:
            raise ValueError('activity media time must increase')
        self.last_time = now
        if self.start is None:
            self.start = now
        uncertain = guard_uncertain(guard) or bool(motion.get('shake'))
        active = guard_activity(guard,self.policy.guard_width)
        if self.policy.motion_method!='robust':
            fraction = motion.get(self.policy.motion_method+'_fraction')
            if fraction is None or not math.isfinite(fraction) or not 0<=fraction<=1:
                uncertain = True
            else:
                active = active and fraction>=.0001
        # Camera/illumination disturbances are not confirmed quiet intervals.
        # Real localized movement can wake the pipeline before warmup ends.
        active = active and not uncertain
        observation_complete = not uncertain and bool(quality_ok)
        if not observation_complete:
            # A blind interval restarts quiet confirmation even during a short
            # activity hold; an older quiet period cannot bridge that interval.
            self.start = now
        if active:
            self.last_motion = now
        held = self.last_motion is not None and now-self.last_motion<self.policy.quiet_seconds
        if active or held:
            state = 'active'
        elif uncertain or not quality_ok:
            state = 'unknown'
            # Require a fresh quiet observation period after a blind interval.
            self.start = now
        elif now-self.start<self.policy.quiet_seconds:
            state = 'unknown'
        else:
            state = 'empty_candidate'
        reason = None
        if state=='active':
            if self.last_state!='active':
                reason = 'activity_trigger'
                self.next_sample = now+self.policy.sample_seconds
            elif self.next_sample is not None and now+.001>=self.next_sample:
                reason = 'activity_interval'
                while self.next_sample<=now+.001:
                    self.next_sample += self.policy.sample_seconds
        audit = self.last_audit is None or now-self.last_audit>=self.policy.audit_seconds
        if audit:
            self.last_audit = now
        self.last_state = state
        return {'state':state,'reason':reason,'activity_now':active,'quiet_confirmed':state=='empty_candidate',
            'audit_due':audit,'audit_only':audit and reason is None,'semantic_presence_used':False,
            'observation_complete':observation_complete}
