"""Bounded selected-JPEG motion observations. No routing or identity decisions."""
from collections import Counter
import math
import re

import cv2
import numpy as np

from .activity_regions import valid_box
from .target_duplicates import iou


def _tracks(previous,current,points):
    if points is None or len(points)<8:return None
    points=np.asarray(points,np.float32).reshape(-1,1,2)
    following,status,_=cv2.calcOpticalFlowPyrLK(previous,current,points,None,winSize=(21,21),maxLevel=3)
    if following is None or status is None:return None
    backward,back_status,_=cv2.calcOpticalFlowPyrLK(current,previous,following,None,winSize=(21,21),maxLevel=3)
    if backward is None or back_status is None:return None
    a=points.reshape(-1,2);b=following.reshape(-1,2)
    good=(status.ravel()==1)&(back_status.ravel()==1)&np.isfinite(b).all(axis=1)
    good&=np.linalg.norm(backward.reshape(-1,2)-a,axis=1)<=1.
    good&=(b[:,0]>=0)&(b[:,0]<current.shape[1])&(b[:,1]>=0)&(b[:,1]<current.shape[0])
    if good.sum()<8 or good.mean()<.6:return None
    return a[good],b[good]


def _mask(shape,targets,include=False):
    mask=np.zeros(shape,np.uint8) if include else np.full(shape,255,np.uint8)
    height,width=shape
    for target in targets:
        box=target['bbox_normalized']
        if not valid_box(box):raise ValueError('invalid motion geometry')
        margin=.15 if include else -.025
        dx=(box[2]-box[0])*margin;dy=(box[3]-box[1])*margin
        left,top=max(0,int((box[0]+dx)*width)),max(0,int((box[1]+dy)*height))
        right,bottom=min(width,math.ceil((box[2]-dx)*width)),min(height,math.ceil((box[3]-dy)*height))
        mask[top:bottom,left:right]=255 if include else 0
    return mask


def camera_translation(previous,current,old_targets,new_targets):
    mask=_mask(previous.shape,old_targets+new_targets)
    points=cv2.goodFeaturesToTrack(previous,maxCorners=160,qualityLevel=.015,minDistance=9,mask=mask)
    matched=_tracks(previous,current,points)
    if matched is None or len(matched[0])<12:return {'state':'unknown','reason':'background_features_unavailable'}
    delta=matched[1]-matched[0];shift=np.median(delta,axis=0)
    inliers=np.linalg.norm(delta-shift,axis=1)<=1.5
    if inliers.mean()<.75 or np.linalg.norm(shift)>6:
        return {'state':'unknown','reason':'camera_or_background_unstable','feature_count':len(delta)}
    return {'state':'measured','dx':round(float(shift[0]),5),'dy':round(float(shift[1]),5),
            'feature_count':len(delta),'inlier_fraction':round(float(inliers.mean()),4)}


def feature_coverage(points,box,shape):
    height,width=shape
    center=((box[0]+box[2])*width/2,(box[1]+box[3])*height/2)
    quadrants={(int(x>=center[0]),int(y>=center[1])) for x,y in points}
    spread=np.ptp(points,axis=0)
    return len(quadrants)>=3 and spread[0]>=.3*(box[2]-box[0])*width and spread[1]>=.3*(box[3]-box[1])*height


def isolated_target_mask(shape,target,others):
    identity=target.get('track_id',target.get('target_id'))
    others=[item for item in others if item.get('track_id',item.get('target_id'))!=identity]
    return cv2.bitwise_and(_mask(shape,[target],include=True),_mask(shape,others))


def outside_other_targets(points,shape,target,others):
    height,width=shape;keep=np.ones(len(points),dtype=bool);identity=target.get('track_id',target.get('target_id'))
    for other in others:
        if other.get('track_id',other.get('target_id'))==identity:continue
        box=other['bbox_normalized'];dx=(box[2]-box[0])*.025;dy=(box[3]-box[1])*.025
        keep&=~((points[:,0]>=(box[0]-dx)*width)&(points[:,0]<=(box[2]+dx)*width)
                &(points[:,1]>=(box[1]-dy)*height)&(points[:,1]<=(box[3]+dy)*height))
    return keep


def flow_observation(previous,current,old,target,translation,old_targets=(),new_targets=()):
    if translation['state']!='measured':return {'state':'unknown','reason':translation['reason']}
    box=old['bbox_normalized']
    if min((box[2]-box[0])*previous.shape[1],(box[3]-box[1])*previous.shape[0])<16:
        return {'state':'unknown','reason':'target_too_small_for_motion_features'}
    points=cv2.goodFeaturesToTrack(previous,maxCorners=48,qualityLevel=.015,minDistance=3,
                                  mask=isolated_target_mask(previous.shape,old,old_targets))
    matched=_tracks(previous,current,points)
    if matched is None:return {'state':'unknown','reason':'target_features_unavailable'}
    a,b=matched;height,width=current.shape;box=target['bbox_normalized']
    inside=(b[:,0]>=box[0]*width)&(b[:,0]<=box[2]*width)&(b[:,1]>=box[1]*height)&(b[:,1]<=box[3]*height)
    inside&=outside_other_targets(b,current.shape,target,new_targets)
    if inside.sum()<8 or inside.mean()<.6:return {'state':'unknown','reason':'unisolated_or_unreliable_target_correspondence'}
    a,b=a[inside],b[inside]
    # Require spatial coverage rather than a handful of pixels on one edge.
    if not feature_coverage(a,old['bbox_normalized'],previous.shape):
        return {'state':'unknown','reason':'target_feature_coverage_incomplete'}
    delta=b-a-np.array([translation['dx'],translation['dy']]);vector=np.median(delta,axis=0)
    median=float(np.median(np.linalg.norm(delta,axis=1)));q75=float(np.quantile(np.linalg.norm(delta,axis=1),.75))
    coherence=float(np.mean(np.linalg.norm(delta-vector,axis=1)<=max(1.5,median*.5)))
    state='stationary' if q75<=1.2 else 'moving' if median>=3 and coherence>=.7 else 'unknown'
    return {'state':state,'feature_count':len(delta),'median_pixels':round(median,4),
            'q75_pixels':round(q75,4),'coherent_fraction':round(coherence,4)}


def geometry_observation(old,target,shape,translation=None):
    a,b=old['bbox_normalized'],target['bbox_normalized'];height,width=shape
    dx=(b[0]+b[2]-a[0]-a[2])*width/2;dy=(b[1]+b[3]-a[1]-a[3])*height/2
    if translation and translation['state']=='measured':dx-=translation['dx'];dy-=translation['dy']
    distance=math.hypot(dx,dy)
    sizes=[(a[2]-a[0])*width,(a[3]-a[1])*height,(b[2]-b[0])*width,(b[3]-b[1])*height]
    scale=max(abs(sizes[2]/sizes[0]-1),abs(sizes[3]/sizes[1]-1))
    state='stationary' if distance<=1.5 and scale<=.1 and iou(a,b)>=.8 else (
        'moving' if distance>=max(3,.05*min(sizes)) else 'unknown')
    return {'state':state,'center_pixels':round(distance,4),'scale_change':round(scale,4),'iou':round(iou(a,b),4)}


class TargetMotionMemory:
    def __init__(self,max_gap_ms=5500,minimum_stationary_ms=3000,minimum_stationary_samples=3):
        self.max_gap_ms=max_gap_ms;self.minimum_ms=minimum_stationary_ms;self.minimum_samples=minimum_stationary_samples
        self.cameras={}

    def clear(self):self.cameras.clear()

    def observe(self,image,targets,metadata,frame_id,jpeg_sha256,*,detection_complete=True):
        camera,run,epoch,now=(metadata[k] for k in ['camera_id','scene_run_id','media_epoch','relative_media_ms'])
        if (not isinstance(camera,str) or not camera or not isinstance(run,str) or not run
                or type(epoch) is not int or epoch<0 or type(now) is not int or now<0 or len(targets)>100):
            raise ValueError('invalid target motion context')
        if not isinstance(jpeg_sha256,str) or not re.fullmatch('[0-9a-f]{64}',jpeg_sha256):raise ValueError('invalid motion image digest')
        if any(not valid_box(t['bbox_normalized']) for t in targets):raise ValueError('invalid motion box')
        scale=640/max(image.shape[:2])
        gray=cv2.cvtColor(cv2.resize(image,(max(2,round(image.shape[1]*scale)),max(2,round(image.shape[0]*scale)))),cv2.COLOR_BGR2GRAY)
        context=(run,epoch);previous=self.cameras.get(camera);reason='first_observation'
        if previous and (previous['context']!=context or not 0<now-previous['time_ms']<=self.max_gap_ms):
            previous=None;reason='session_epoch_or_clock_gap'
        usable=bool(detection_complete and gray.mean()>=18 and gray.mean()<=245 and gray.std()>=8)
        comparable=previous is not None and previous['usable'] and usable and previous['gray'].shape==gray.shape
        if comparable and abs(float(gray.mean())-float(previous['gray'].mean()))>20:
            comparable=False;reason='illumination_change'
        if not usable:reason='quality_or_detection_unavailable'
        old_targets=previous['targets'] if comparable else []
        by_track={t['track_id']:t for t in old_targets}
        paired=[t for t in targets if t.get('track_id') in by_track]
        translation=camera_translation(previous['gray'],gray,old_targets,targets) if comparable and paired else {
            'state':'unknown','reason':reason if not comparable else 'no_unambiguous_previous_track'}
        observations=[];histories={};methods=['geometry','flow','agreement']
        for target in targets:
            old=by_track.get(target.get('track_id'));rows={method:{'state':'unknown','reason':reason} for method in methods}
            if old is not None and old['class_name']==target['class_name']:
                rows['geometry']=geometry_observation(old,target,gray.shape,translation)
                rows['flow']=flow_observation(previous['gray'],gray,old,target,translation,old_targets,targets)
                agreement=(rows['geometry']['state'] if translation['state']=='measured'
                           and rows['geometry']['state']==rows['flow']['state'] else 'unknown')
                rows['agreement']={'state':agreement,'reason':'agreement' if agreement!='unknown' else 'insufficient_or_conflicting_evidence'}
            current_history={}
            for method,value in rows.items():
                prior=(previous['histories'].get(target.get('track_id'),{}).get(method)) if previous else None
                if value['state']=='stationary':
                    times=(prior or [])+[now];times=times[-6:];current_history[method]=times
                    value['stationary_samples']=len(times);value['stationary_span_ms']=now-times[0]
                    if len(times)<self.minimum_samples or now-times[0]<self.minimum_ms:
                        value.update(state='unknown',reason='stationary_confirmation_pending')
            histories[target['track_id']]=current_history
            observations.append({'target_id':target['target_id'],'track_id':target['track_id'],
                'state':rows['agreement']['state'],'methods':rows,
                'previous_frame_id':previous['frame_id'] if old is not None else None,
                'previous_jpeg_sha256':previous['sha256'] if old is not None else None,
                'previous_media_ms':previous['time_ms'] if old is not None else None})
        self.cameras.pop(camera,None)
        self.cameras[camera]={'context':context,'time_ms':now,'gray':gray,'targets':[dict(v) for v in targets],
            'usable':usable,'frame_id':frame_id,'sha256':jpeg_sha256,'histories':histories}
        while len(self.cameras)>6:self.cameras.pop(next(iter(self.cameras)))
        return {'schema':'target-motion-observation-v1','mode':'observe','routing_applied':False,'frame_suppression':False,
            'frame_id':frame_id,'jpeg_sha256':jpeg_sha256,'camera_id':camera,'scene_run_id':run,'media_epoch':epoch,
            'media_ms':now,'maximum_sample_gap_ms':self.max_gap_ms,'camera_translation':translation,
            'states':dict(Counter(v['state'] for v in observations)),
            'method_states':{method:dict(Counter(v['methods'][method]['state'] for v in observations)) for method in methods},
            'targets':observations,'camera_memory_entries':len(self.cameras),'human_confirmed':False,
            'local_feature_scope':'target_interior_excluding_other_candidates',
            'motion_identity_proven':False}
