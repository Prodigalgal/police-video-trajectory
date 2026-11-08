"""Experimental, causal road-gate replay; never configures a running Worker.

The frozen v4 policy remains the baseline. FreshEvidenceGate distinguishes a
held positive score from a fresh positive observation when extending the hold.
"""
from dataclasses import asdict,dataclass
from bisect import bisect_right
import math

from services.scene_analysis.prefilter import scene_state
from services.video_worker.scene_gate import SceneGate,SceneGatePolicy


@dataclass(frozen=True)
class ReplayPolicy:
    detector_seconds:int=5
    negative_confirmations:int=3
    active_hold_seconds:int=10
    quality_mode:str='strict'
    hold_mode:str='legacy'

    def validate(self):
        if (self.detector_seconds not in {1,5} or self.negative_confirmations not in {1,2,3}
                or self.active_hold_seconds not in {0,2,10} or self.quality_mode not in {'strict','guarded_night'}
                or self.hold_mode not in {'legacy','fresh'}):raise ValueError('invalid replay policy')

    @property
    def identity(self):
        return f'd{self.detector_seconds}-n{self.negative_confirmations}-h{self.active_hold_seconds}-{self.quality_mode}-{self.hold_mode}'


from services.scene_analysis.temporal_gate import FreshEvidenceGate


def quality_eligible(value,mode):
    if mode=='strict':return value['usable_for_empty_confirmation']
    if mode!='guarded_night':raise ValueError('invalid quality mode')
    return value['usable_for_empty_confirmation'] or (
        value['median_luma']>=25 and value['contrast_p90_p10']>=20 and value['dark_fraction']<=.50
        and value['glare_fraction']<.10 and value['laplacian_variance']>=20 and value['noise_mad']<=8)


def policies():
    # Declared before evaluation. No per-camera threshold fitting.
    baseline=ReplayPolicy();result=[baseline]
    for detector in [1,5]:
        for negatives in [1,2,3]:
            for hold in [0,2,10]:
                for quality in ['strict','guarded_night']:
                    result.append(ReplayPolicy(detector,negatives,hold,quality,'fresh'))
    return result


def replay(samples,model,method,policy,output_seconds=5):
    policy.validate()
    if output_seconds not in {1,5} or model not in {'nanodet','yolox'} or method not in {'mad','robust','mog2','knn'}:
        raise ValueError('invalid replay combination')
    gate=(SceneGate if policy.hold_mode=='legacy' else FreshEvidenceGate)(SceneGatePolicy(
        sample_seconds=output_seconds,fallback_seconds=300,active_hold_seconds=policy.active_hold_seconds,empty_confirmations=1))
    rows=[];negative=0;score=None;next_detector=0.;previous=None;last_fresh=None
    for sample in samples:
        now=sample['offset_ms']/1000
        if previous is not None and (now<=previous or now-previous>1.05):raise ValueError('replay signals must be continuous and increasing')
        previous=now;q=sample['quality'];motion=sample['motion'];fresh=now+1e-6>=next_detector
        eligible=quality_eligible(q,policy.quality_mode)
        if fresh:
            next_detector+=policy.detector_seconds
            score=sample['models'][model]['score'];last_fresh=now
            if score is not None and (not math.isfinite(score) or not 0<=score<=1):raise ValueError('invalid detector score')
            negative=negative+1 if score is not None and score<.1 and eligible else 0
        if last_fresh is None or now-last_fresh>policy.detector_seconds+.05:score=None;negative=0
        feature={'mad':'mad','robust':'robust_diff_fraction','mog2':'mog2_fraction','knn':'knn_fraction'}[method]
        fraction=motion[feature]
        if method=='mad' and fraction is not None:fraction/=500
        category=scene_state(fraction,score,eligible,3 if negative>=policy.negative_confirmations else 0,
                             warming_up=motion['warming_up'],shake=motion['shake'])
        state,reason=gate.observe(now,None if fraction is None else 10. if fraction>=.01 else 0.,score,
                detector_update=fresh,quality_ok=8<=sample['mean_luma']<=247 and category!='unknown')
        rows.append({'offset_ms':sample['offset_ms'],'state':state,'reason':reason,
                     'scene_category':category,'fresh_detector':fresh,'negative_updates':negative,'quality_eligible':eligible})
    return {'policy':asdict(policy),'policy_id':policy.identity,'model':model,'method':method,
            'output_seconds':output_seconds,'samples':rows,
            'detector_updates':sum(v['fresh_detector'] for v in rows),
            'candidate_frames':sum(v['reason'] is not None for v in rows)}


def replay_guard(samples,guard_samples,model,policy,output_seconds=5,width=640,peak_selection=False):
    from .motion_guard import guard_activity,guard_uncertain
    policy.validate()
    if policy.hold_mode!='fresh' or width not in {320,640} or output_seconds not in {1,5} or model not in {'nanodet','yolox'}:
        raise ValueError('invalid guarded replay')
    offsets=[v['offset_ms'] for v in samples]
    if not offsets or offsets[0]!=0 or offsets!=sorted(set(offsets)):raise ValueError('invalid source signal times')
    guard_offsets=[v['offset_ms'] for v in guard_samples]
    if not set(offsets).issubset(guard_offsets):raise ValueError('guard and detector clocks are not aligned')
    gate=FreshEvidenceGate(SceneGatePolicy(sample_seconds=output_seconds,fallback_seconds=300,
        active_hold_seconds=policy.active_hold_seconds,empty_confirmations=1))
    rows=[];next_detector=0;score=None;negative=0;previous=None
    burst=[];burst_start=None;last_activity=None;last_burst=None

    def finish_burst(available_ms):
        nonlocal burst,burst_start
        if burst:
            # A bounded look-back selector, not a future gate observation.
            # One extra representative, at most once per two seconds. Both
            # capture and availability times remain explicit.
            best=max(burst,key=lambda v:v['small_motion_rank'])
            if best['reason'] is None:best.update(reason='small_motion_peak',selection_available_ms=available_ms)
        burst=[];burst_start=None
    for value in guard_samples:
        t=value['offset_ms'];index=bisect_right(offsets,t)-1
        if previous is not None and (t<=previous or t-previous>250):raise ValueError('discontinuous guard trace')
        previous=t
        if index<0 or t-offsets[index]>1000:raise ValueError('missing causal source signal')
        source=samples[index];q=source['quality'];motion=source['motion'];guard=value['guards'][str(width)]
        new_probe=t==source['offset_ms'];fresh=new_probe and t>=next_detector
        eligible=quality_eligible(q,policy.quality_mode)
        if fresh:
            next_detector+=policy.detector_seconds*1000;score=source['models'][model]['score']
            negative=negative+1 if score is not None and score<.1 and eligible else 0
        # A 1 Hz motion value is applied once; it is not new motion evidence on
        # each intermediate guard tick. The guard itself is freshly measured.
        fraction=motion['robust_diff_fraction'] if new_probe else 0.
        if guard_activity(guard,width):fraction=max(fraction or 0.,.02)
        uncertain=guard_uncertain(guard)
        category=scene_state(fraction,score,eligible and not uncertain,3 if negative>=policy.negative_confirmations else 0,
                             warming_up=motion['warming_up'],shake=motion['shake'] or uncertain)
        state,reason=gate.observe(t/1000,None if fraction is None else 10. if fraction>=.01 else 0.,score,
                detector_update=fresh,quality_ok=8<=source['mean_luma']<=247 and category!='unknown' and not uncertain)
        rows.append({'offset_ms':t,'state':state,'reason':reason,'scene_category':category,'fresh_detector':fresh,
                     'negative_updates':negative,'quality_eligible':eligible,'small_motion':guard_activity(guard,width),
                     'small_motion_rank':guard['largest_component']+guard['edge_component']})
        if peak_selection:
            if burst_start is not None:
                burst.append(rows[-1])
                if t-burst_start>=600:finish_burst(t)
            safe_activity=guard_activity(guard,width) and eligible and not uncertain and not motion['warming_up'] and not motion['shake']
            if safe_activity:
                if (burst_start is None and (last_activity is None or t-last_activity>=600)
                        and (last_burst is None or t-last_burst>=2000)):
                    burst_start=t;last_burst=t;burst=[rows[-1]]
                last_activity=t
    if peak_selection and burst:finish_burst(rows[-1]['offset_ms'])
    return {'policy':asdict(policy),'policy_id':policy.identity,'model':model,'method':'robust',
            'guard_mode':str(width)+'px_5hz'+('_peak' if peak_selection else ''),'output_seconds':output_seconds,'samples':rows,
            'detector_updates':sum(v['fresh_detector'] for v in rows),'candidate_frames':sum(v['reason'] is not None for v in rows)}
