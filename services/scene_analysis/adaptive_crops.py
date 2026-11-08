"""Experimental same-JPEG crop proposals with protected full-frame detections.

No annotation, camera-specific ROI or quality utility grade is used to select
regions. A crop never establishes emptiness outside its own coverage.
"""
from copy import deepcopy
import hashlib
import json
import math

import numpy as np

POLICY={'schema':'protected-adaptive-crops-v1','crop_fraction':.45,'minimum_crop_side':96,
        'proposal_min_confidence':.08,'small_short_side_px':64,'proposal_limit':64,
        'duplicate_iou':.5,'crop_edge_margin_px':2,'target_limit':100,
        'exploration':'sha256-context one of 3x3 centers; no exhaustive coverage claim'}
POLICY_SHA256=hashlib.sha256(json.dumps(POLICY,sort_keys=True).encode()).hexdigest()


def iou(a,b):
    intersection=max(0,min(a[2],b[2])-max(a[0],b[0]))*max(0,min(a[3],b[3])-max(a[1],b[1]))
    union=(a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-intersection
    return intersection/union if union>0 else 0.


def centered_region(cx,cy,width,height):
    if type(width) is not int or type(height) is not int or min(width,height)<96:raise ValueError('invalid crop geometry')
    cw=min(width,max(POLICY['minimum_crop_side'],round(width*POLICY['crop_fraction'])))
    ch=min(height,max(POLICY['minimum_crop_side'],round(height*POLICY['crop_fraction'])))
    x=max(0,min(width-cw,round(cx-cw/2)));y=max(0,min(height-ch,round(cy-ch/2)))
    return [x,y,x+cw,y+ch]


def proposals(width,height,hypotheses,context):
    if not isinstance(context,str) or not context or len(context)>200:raise ValueError('invalid crop context')
    candidates=[]
    for target in hypotheses[:100]:
        box=target.get('bbox_xyxy');confidence=target.get('confidence')
        if (not isinstance(box,(list,tuple)) or len(box)!=4 or any(type(v) not in {int,float} or not math.isfinite(v) for v in box)
                or type(confidence) not in {int,float} or not math.isfinite(confidence)):continue
        x1,y1,x2,y2=box
        if not (0<=x1<x2<=width and 0<=y1<y2<=height):continue
        short=min(x2-x1,y2-y1)
        if confidence<POLICY['proposal_min_confidence'] or short>POLICY['small_short_side_px']:continue
        weight=confidence*(1-short/(POLICY['small_short_side_px']+1))*(1 if confidence<.35 else .5)
        candidates.append(((x1+x2)/2,(y1+y2)/2,weight))
    candidates=sorted(candidates,key=lambda t:-t[2])[:POLICY['proposal_limit']]
    regions={tuple(centered_region(cx,cy,width,height)) for cx,cy,_ in candidates}
    ranked=[]
    for region in regions:
        score=sum(w for x,y,w in candidates if region[0]<=x<region[2] and region[1]<=y<region[3])
        ranked.append((-score,region))
    cells=[centered_region(width*(x+.5)/3,height*(y+.5)/3,width,height) for y in range(3) for x in range(3)]
    index=int.from_bytes(hashlib.sha256(context.encode()).digest()[:8],'big')%9
    exploration=cells[index]
    primary=list(min(ranked)[1]) if ranked else exploration
    # An explicit second region also explores where the primary model was silent.
    # If almost identical, choose the next low-overlap grid cell deterministically.
    if iou(primary,exploration)>.8:
        exploration=min(((iou(primary,cells[(index+i)%9]),i,cells[(index+i)%9]) for i in range(1,9)))[2]
    return [{'kind':'small_hypothesis' if ranked else 'exploration_fallback','bbox_xyxy':primary},
            {'kind':'exploration','bbox_xyxy':exploration}]


def map_crop(target,region,width,height):
    x1,y1,x2,y2=region;box=target['bbox_xyxy'];cw=x2-x1;ch=y2-y1
    if len(box)!=4 or not all(math.isfinite(v) for v in box) or not (0<=box[0]<box[2]<=cw and 0<=box[1]<box[3]<=ch):
        raise ValueError('crop detector returned invalid coordinates')
    # Reject artificial crop-boundary fragments; real image edges remain legal.
    margin=POLICY['crop_edge_margin_px']
    internal_edge=((x1>0 and box[0]<=margin) or (y1>0 and box[1]<=margin)
        or (x2<width and box[2]>=cw-margin) or (y2<height and box[3]>=ch-margin))
    full=[round(float(v+offset),3) for v,offset in zip(box,[x1,y1,x1,y1])]
    return {**deepcopy(target),'bbox_xyxy':full,'bbox_normalized':[round(v/s,6) for v,s in zip(full,[width,height,width,height])]},internal_edge


def protected_union(baseline,regional,width,height,threshold=.35):
    if len(baseline)>POLICY['target_limit']:raise ValueError('baseline exceeds target bound')
    targets=deepcopy(baseline);added=[];duplicates=fragments=overflow=0
    mapped=[]
    for region,result in regional:
        for t in result['targets']:
            if t['confidence']<threshold:continue
            value,edge=map_crop(t,region['bbox_xyxy'],width,height)
            if edge:fragments+=1;continue
            mapped.append((value,region['kind']))
    for value,origin in sorted(mapped,key=lambda row:-row[0]['confidence']):
        if any(old['object_type']==value['object_type'] and iou(old['bbox_xyxy'],value['bbox_xyxy'])>=POLICY['duplicate_iou'] for old in targets):
            duplicates+=1;continue
        if len(targets)>=POLICY['target_limit']:overflow+=1;continue
        targets.append(value);added.append({'index':len(targets)-1,'region_kind':origin})
    return {'targets':targets,'added':added,'baseline_count':len(baseline),'baseline_preserved':targets[:len(baseline)]==baseline,
        'duplicate_candidates':duplicates,'crop_edge_fragments':fragments,'budget_excluded_candidates':overflow,
        'full_image_width':width,'full_image_height':height,'policy_sha256':POLICY_SHA256,
        'high_resolution_coverage_complete':False,'empty_scene_evidence':False}


def detect_adaptive(probe,image,threshold,context,budget=1):
    """One mandatory full-frame pass; failed regional work retains its baseline."""
    import cv2
    if budget not in {1,2} or type(budget) is not int:raise ValueError('invalid extra crop budget')
    if not math.isfinite(threshold) or not .01<=threshold<=1:raise ValueError('invalid crop detection threshold')
    height,width=image.shape[:2]
    full=probe.detect(image,.05)
    baseline=[t for t in full['targets'] if t['confidence']>=threshold]
    regions=[];regional=[];failures=0;attempts=1;merge=None
    try:
        regions=proposals(width,height,full['targets'],context)[:budget]
        for region in regions:
            x1,y1,x2,y2=region['bbox_xyxy'];attempts+=1
            try:regional.append((region,probe.detect(image[y1:y2,x1:x2],.05)))
            except (ValueError,RuntimeError,cv2.error):failures+=1
        merge=protected_union(baseline,regional,width,height,threshold)
    except (ValueError,TypeError,KeyError,OverflowError,cv2.error):failures+=1
    if merge is None:merge={'targets':deepcopy(baseline),'added':[],'baseline_preserved':True}
    metrics={'schema':POLICY['schema'],'policy_sha256':POLICY_SHA256,'state':'complete' if failures==0 else 'partial_baseline_retained',
        'extra_pass_budget':budget,'forward_attempts':attempts,'successful_extra_passes':len(regional),'regional_failures':failures,
        'baseline_count':len(baseline),'added_count':len(merge['added']),'baseline_preserved':merge['baseline_preserved'],
        'crop_regions':regions,'context_sha256':hashlib.sha256(context.encode()).hexdigest() if isinstance(context,str) else None,
        'high_resolution_coverage_complete':False,'empty_scene_evidence':False,'frame_suppression':False,
        'input_role':'same_selected_jpeg_not_native_video_frame',
        **{k:merge.get(k,0) for k in ['duplicate_candidates','crop_edge_fragments','budget_excluded_candidates']}}
    return {**full,'targets':merge['targets'],'detector_variant':'adaptive_'+str(budget),'crop_metrics':metrics}
