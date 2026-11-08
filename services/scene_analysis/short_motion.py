"""Observe object motion against durable short-interval grayscale witnesses."""
from collections import Counter
import hashlib
import math

import cv2
import numpy as np

from services.video_worker.motion_context import validate_binding
from .target_motion import _tracks,camera_translation,feature_coverage,isolated_target_mask


def pair_observation(current,past,target,targets,translation,seconds):
    if translation['state']!='measured':return {'state':'unknown','reason':translation['reason']}
    height,width=current.shape;box=target['bbox_normalized']
    if min((box[2]-box[0])*width,(box[3]-box[1])*height)<8:return {'state':'unknown','reason':'target_too_small'}
    points=cv2.goodFeaturesToTrack(current,maxCorners=64,qualityLevel=.015,minDistance=2,
                                  mask=isolated_target_mask(current.shape,target,targets))
    matched=_tracks(current,past,points)
    if matched is None:return {'state':'unknown','reason':'target_correspondence_unavailable'}
    a,b=matched
    if not feature_coverage(a,box,current.shape):return {'state':'unknown','reason':'target_feature_coverage_incomplete'}
    # Translation was measured backwards from current to past, as were points.
    delta=a-b+np.array([translation['dx'],translation['dy']]);vector=np.median(delta,axis=0)
    magnitude=np.linalg.norm(delta,axis=1);median=float(np.median(magnitude));q75=float(np.quantile(magnitude,.75))
    coherent=float(np.mean(np.linalg.norm(delta-vector,axis=1)<=max(.75,median*.5)))
    velocity=median*640/max(current.shape)/seconds
    state='stationary' if q75<=.35 else 'moving' if median>=.75 and velocity>=5 and coherent>=.75 else 'unknown'
    return {'state':state,'features':len(a),'median_pixels':round(median,4),'q75_pixels':round(q75,4),
        'pixels640_per_second':round(velocity,4),'coherent_fraction':round(coherent,4),
        'vector':[round(float(v),4) for v in vector]}


def observe_short_motion(image,targets,metadata,frame_id,jpeg_sha256,raw,*,issue=None):
    result={'schema':'short-target-motion-v1','mode':'observe','routing_applied':False,'frame_suppression':False,
        'frame_id':frame_id,'jpeg_sha256':jpeg_sha256,'evaluated_target_limit':32,'targets':[],
        'human_confirmed':False,'physical_identity_proven':False}
    if raw is None:
        result.update(state='unavailable',reason=issue or 'motion_context_not_captured',
            targets=[{'target_id':t['target_id'],'state':'unknown','reason':issue or 'motion_context_not_captured'} for t in targets],
            states={'unknown':len(targets)})
        return result
    header,_,images=validate_binding(raw,frame_id,jpeg_sha256,metadata,decode=True)
    if (header['selected_width'],header['selected_height'])!=(image.shape[1],image.shape[0]):raise ValueError('selected_image_geometry_conflict')
    current=images[-1];current_row=header['frames'][-1]
    result['context_manifest']={**header,'context_sha256':hashlib.sha256(raw).hexdigest(),'context_bytes':len(raw)}
    views={row['role']:(row,picture) for row,picture in zip(header['frames'][:-1],images[:-1])}
    translations={}
    for role,(row,past) in views.items():
        if not current_row['usable'] or not row['usable'] or current.std()<8 or past.std()<8:
            translations[role]={'state':'unknown','reason':'source_quality_unavailable'}
        else:translations[role]=camera_translation(current,past,targets,[])
    result['camera_translation_backwards']=translations
    for ordinal,target in enumerate(targets):
        if ordinal>=32:
            result['targets'].append({'target_id':target['target_id'],'state':'unknown','reason':'motion_evaluation_budget'});continue
        pairs={}
        for role,(row,past) in views.items():
            seconds=(current_row['pts_us']-row['pts_us'])/1000000
            pairs[role]=pair_observation(current,past,target,targets,translations[role],seconds)
        near=[pairs.get(name,{'state':'unknown'}) for name in ['near_200ms','near_600ms']]
        state='unknown';reason='insufficient_or_disagreeing_short_time_evidence'
        if all(v['state']=='moving' for v in near):
            a,b=(np.array(v['vector']) for v in near)
            denominator=float(np.linalg.norm(a)*np.linalg.norm(b))
            if denominator>0 and float(a@b)/denominator>=.7:state='moving';reason='two_short_intervals_agree'
        elif all(pairs.get(name,{}).get('state')=='stationary' for name in ['near_200ms','near_600ms','long_3000ms']):
            long_age=current_row['pts_us']-views['long_3000ms'][0]['pts_us']
            if long_age>=2900000:state='stationary';reason='short_and_three_second_views_agree'
        result['targets'].append({'target_id':target['target_id'],'state':state,'reason':reason,'views':pairs})
    result['states']=dict(Counter(v['state'] for v in result['targets']))
    result['state']='observed'
    return result
