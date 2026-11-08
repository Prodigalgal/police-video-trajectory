"""Scope-bound interval diagnostics. AI references never become human truth."""
from bisect import bisect_right
import hashlib
import json
from pathlib import Path

from .regions import scope_id


def load_reference(path,workspace,source_scopes):
    value=json.loads(Path(path).read_text(encoding='utf-8'))
    if (value['schema']!='scoped-temporal-reference-v1' or value['reference_source']!='ai_visual_review'
            or value['human_independently_verified'] is not False):raise ValueError('invalid reference provenance')
    seen={}
    for block in value['blocks']:
        camera=block['camera'];expected=source_scopes[camera]
        if (block['region']!='near_field' or block['source_sha256']!=expected['source_sha256'] or block['roi']!=expected['roi']
                or block['scope_id']!=scope_id(camera,expected['source_sha256'],expected['roi'])):raise ValueError('reference scope differs')
        manifest_path=(Path(workspace)/block['manifest']).resolve()
        if not manifest_path.is_relative_to(Path(workspace).resolve()/'runtime'):raise ValueError('unsafe reference manifest')
        raw=manifest_path.read_bytes()
        if hashlib.sha256(raw).hexdigest()!=block['manifest_sha256']:raise ValueError('reference manifest changed')
        manifest=json.loads(raw);frames=[v for v in manifest['frames'] if v['camera']==camera]
        if len(frames)!=block['native_frames_reviewed']:raise ValueError('incomplete native review')
        offsets=[v['offset_ms'] for v in frames]
        if offsets!=sorted(set(offsets)) or any(b-a>35 for a,b in zip(offsets,offsets[1:])):raise ValueError('native frame gap')
        if offsets[0]!=block['window_ms'][0] or block['window_ms'][1]-offsets[-1]>35:raise ValueError('native review window mismatch')
        for frame in frames:
            image=(manifest_path.parent/frame['image']).resolve()
            if (not image.is_relative_to(manifest_path.parent) or frame['source_sha256']!=block['source_sha256'] or frame['roi']!=block['roi']
                    or hashlib.sha256(image.read_bytes()).hexdigest()!=frame['sha256']):raise ValueError('native review image changed')
        cursor=block['window_ms'][0]
        for segment in block['segments']:
            if (segment['state'] not in {'occupied','empty','unknown'} or segment['start_ms']!=cursor
                    or not cursor<segment['end_ms']<=block['window_ms'][1]):raise ValueError('reference interval gap or overlap')
            cursor=segment['end_ms']
        if cursor!=block['window_ms'][1]:raise ValueError('reference does not cover full review window')
        for left,right in seen.get(camera,[]):
            if max(left,block['window_ms'][0])<min(right,block['window_ms'][1]):raise ValueError('overlapping review blocks')
        seen.setdefault(camera,[]).append(block['window_ms'])
    episode_ids=set()
    for event in value['episodes']:
        key=(event['camera'],event['id']);start,end=event['core_ms']
        if key in episode_ids or not start<end:raise ValueError('invalid presence episode')
        episode_ids.add(key)
        occupied=[s for b in value['blocks'] if b['camera']==event['camera'] for s in b['segments'] if s['state']=='occupied']
        if not any(s['start_ms']<=start<end<=s['end_ms'] for s in occupied):raise ValueError('episode core lacks occupied reference')
    return value


def interval_metrics(trace,reference,camera,max_age_ms=1000):
    samples=trace['samples'];offsets=[v['offset_ms'] for v in samples]
    if offsets!=sorted(set(offsets)):raise ValueError('nonmonotonic gate trace')
    totals={'occupied_ms':0,'empty_ms':0,'reference_unknown_ms':0,'occupied_marked_empty_ms':0,
            'empty_marked_empty_ms':0,'known_reference_unmatched_ms':0,'known_reference_gate_unknown_ms':0}
    for block in reference['blocks']:
        if block['camera']!=camera:continue
        for segment in block['segments']:
            truth=segment['state'];start,end=segment['start_ms'],segment['end_ms']
            if truth=='unknown':totals['reference_unknown_ms']+=end-start;continue
            totals[truth+'_ms']+=end-start;cursor=start
            while cursor<end:
                index=bisect_right(offsets,cursor)-1
                if index<0:
                    bound=min(end,offsets[0]) if offsets else end;state=None
                else:
                    bound=min(end,offsets[index]+max_age_ms,offsets[index+1] if index+1<len(offsets) else end)
                    state=samples[index]['state'] if cursor<offsets[index]+max_age_ms else None
                    if bound<=cursor:bound=min(end,offsets[index+1] if index+1<len(offsets) else end);state=None
                elapsed=bound-cursor
                if state is None:totals['known_reference_unmatched_ms']+=elapsed
                elif state=='unknown':totals['known_reference_gate_unknown_ms']+=elapsed
                elif state=='empty_candidate':totals[truth+'_marked_empty_ms']+=elapsed
                cursor=bound
    selected=[v['offset_ms'] for v in samples if v['reason'] is not None];events=[]
    for event in reference['episodes']:
        if event['camera']!=camera:continue
        start,end=event['core_ms'];index=bisect_right(selected,start-1)
        first=selected[index] if index<len(selected) and selected[index]<end else None
        events.append({'id':event['id'],'complete_within_review':event['complete_within_review'],
                       'covered_visible_core':first is not None,'first_core_candidate_ms':first,
                       'core_start_delay_ms':first-start if first is not None else None})
    complete=[v for v in events if v['complete_within_review']]
    return {**totals,'complete_presence_episodes':len(complete),'missed_complete_presence_episodes':sum(not v['covered_visible_core'] for v in complete),
            'episodes':events,'production_event_recall':None,'reference_source':'ai_visual_review'}


def fixed_cadence_phase_coverage(start_ms,end_ms,period_ms,phase_step_ms=100):
    """Geometric sampling ceiling only; no detector/gate inference is simulated."""
    if not 0<=start_ms<end_ms or period_ms<=0 or not 0<phase_step_ms<=period_ms:raise ValueError('invalid cadence geometry')
    phases=list(range(0,period_ms,phase_step_ms));covered=0
    for phase in phases:
        first=phase+max(0,(start_ms-phase+period_ms-1)//period_ms)*period_ms
        covered+=start_ms<=first<end_ms
    return {'period_ms':period_ms,'phase_step_ms':phase_step_ms,'phases':len(phases),'covered_phases':covered,
            'kind':'fixed_cadence_geometric_ceiling_not_measured_detection_accuracy'}
