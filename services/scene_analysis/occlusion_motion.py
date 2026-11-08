"""Source-bound grid consensus; batch optical flow never implies identity."""
from collections import Counter
import hashlib
import time

import cv2
import numpy as np

from services.video_worker.motion_context import validate_binding
from .activity_regions import valid_box
from .target_motion import _mask,isolated_target_mask,outside_other_targets


STRATEGY='grid_batch_v1'
TARGET_LIMIT=16
MAX_POINTS=608
MIN_STATIC_POINTS=8
MIN_MOVING_POINTS=6


def grid_features(image,mask,box,columns,rows,per_cell):
    height,width=image.shape
    left,top,right,bottom=[round(value*size) for value,size in zip(box,(width,height,width,height))]
    points=[];cells=[];counts=Counter()
    if min(right-left,bottom-top)<4:return np.empty((0,2),np.float32),np.empty(0,np.int32)
    # Detect across the ROI: tiny target cells cannot each sustain a 3-pixel block.
    bounded_mask=np.zeros_like(mask);bounded_mask[top:bottom,left:right]=mask[top:bottom,left:right]
    found=cv2.goodFeaturesToTrack(image,maxCorners=columns*rows*per_cell*4,
        qualityLevel=.015,minDistance=2,mask=bounded_mask,blockSize=3)
    if found is not None:
        for point in found.reshape(-1,2):
            column=min(columns-1,int((point[0]-left)*columns/(right-left)))
            row=min(rows-1,int((point[1]-top)*rows/(bottom-top)));cell=row*columns+column
            if counts[cell]>=per_cell:continue
            counts[cell]+=1;points.append(point);cells.append(cell)
    return np.asarray(points,np.float32).reshape(-1,2),np.asarray(cells,np.int32)


def cell_coverage(cells,columns,rows,minimum=6):
    cells=set(map(int,cells))
    quadrants={(int(cell%columns>=columns/2),int(cell//columns>=rows/2)) for cell in cells}
    return len(cells)>=minimum and len(quadrants)>=3


def batch_tracks(current,past,points):
    if not len(points):return np.empty((0,2),np.float32),np.zeros(0,dtype=bool)
    source=points.reshape(-1,1,2)
    following,status,error=cv2.calcOpticalFlowPyrLK(current,past,source,None,winSize=(21,21),maxLevel=3)
    if following is None or status is None or error is None:return points.copy(),np.zeros(len(points),dtype=bool)
    backward,back_status,_=cv2.calcOpticalFlowPyrLK(past,current,following,None,winSize=(21,21),maxLevel=3)
    if backward is None or back_status is None:return points.copy(),np.zeros(len(points),dtype=bool)
    matched=following.reshape(-1,2)
    valid=(status.ravel()==1)&(back_status.ravel()==1)&np.isfinite(matched).all(axis=1)&np.isfinite(backward.reshape(-1,2)).all(axis=1)
    valid&=np.isfinite(error.ravel())&(error.ravel()<=20)
    valid&=np.linalg.norm(backward.reshape(-1,2)-points,axis=1)<=.75
    valid&=(matched[:,0]>=0)&(matched[:,0]<past.shape[1])&(matched[:,1]>=0)&(matched[:,1]<past.shape[0])
    return matched,valid


def background_estimate(points,matched,valid,cells):
    if valid.sum()<12:return {'state':'unknown','reason':'background_features_unavailable'}
    delta=matched[valid]-points[valid];selected=cells[valid]
    vectors=np.asarray([np.median(delta[selected==cell],axis=0) for cell in set(map(int,selected))])
    shift=np.median(vectors,axis=0)
    inliers=np.linalg.norm(delta-shift,axis=1)<=1.
    cell_agreement=float(np.mean(np.linalg.norm(vectors-shift,axis=1)<=1.))
    if (inliers.sum()<12 or cell_agreement<.7 or np.linalg.norm(shift)>6
            or not cell_coverage(cells[valid][inliers],6,4)):
        return {'state':'unknown','reason':'background_coverage_or_consensus_unavailable'}
    return {'state':'measured','method':STRATEGY,'dx':round(float(shift[0]),5),'dy':round(float(shift[1]),5),
        'features':int(inliers.sum()),'cells':len(set(map(int,cells[valid][inliers]))),
        'cell_agreement':round(cell_agreement,4),'inlier_fraction':round(float(inliers.mean()),4)}


def target_pair(points,matched,valid,cells,translation,seconds,shape):
    if translation['state']!='measured':return {'state':'unknown','reason':translation['reason']}
    if valid.sum()<MIN_MOVING_POINTS or not cell_coverage(cells[valid],4,4):
        return {'state':'unknown','reason':'target_coverage_or_correspondence_unavailable',
            'features':int(valid.sum()),'eligible_features':len(points),'cells':len(set(map(int,cells[valid])))}
    fraction=float(valid.mean());delta=matched[valid]-points[valid]-np.array([translation['dx'],translation['dy']])
    selected_cells=cells[valid];vectors=[];stationary=[];moving=[]
    for cell in sorted(set(map(int,selected_cells))):
        displacement=delta[selected_cells==cell];vector=np.median(displacement,axis=0);length=np.linalg.norm(displacement,axis=1)
        vectors.append(vector)
        if np.quantile(length,.75)<=.35:stationary.append(cell)
        elif np.median(length)>=.6 and np.median(length)*640/max(shape)/seconds>=5:moving.append(cell)
    vectors=np.asarray(vectors);vector=np.median(vectors,axis=0);median=float(np.median(np.linalg.norm(vectors,axis=1)))
    coherence=float(np.mean(np.linalg.norm(vectors-vector,axis=1)<=max(.75,median*.5)))
    state='unknown';reason='grid_motion_conflict_or_insufficient_support'
    if valid.sum()>=MIN_STATIC_POINTS and fraction>=.6 and len(stationary)>=.8*len(vectors) and not moving and cell_coverage(stationary,4,4):
        state='stationary';reason='distributed_cells_stationary'
    elif fraction>=.4 and len(moving)>=.75*len(vectors) and len(stationary)<2 and coherence>=.8 and cell_coverage(moving,4,4):
        state='moving';reason='distributed_cells_move_coherently'
    return {'state':state,'reason':reason,'features':int(valid.sum()),'eligible_features':len(points),'matched_fraction':round(fraction,4),
        'cells':len(vectors),'stationary_cells':stationary,'moving_cells':moving,'vector':[round(float(value),5) for value in vector],
        'pixels640_per_second':round(median*640/max(shape)/seconds,4),'coherent_fraction':round(coherence,4),
        'occlusion_or_conflict':fraction<.6 or bool(len(moving)>=2 and len(stationary)>=2)}


def overlapping_fraction(target,others):
    box=target['bbox_normalized'];area=(box[2]-box[0])*(box[3]-box[1]);maximum=0.
    for other in others:
        if other['target_id']==target['target_id']:continue
        bounds=other['bbox_normalized']
        overlap=max(0,min(box[2],bounds[2])-max(box[0],bounds[0]))*max(0,min(box[3],bounds[3])-max(box[1],bounds[1]))
        maximum=max(maximum,overlap/area)
    return maximum


def consensus(views,*,overlap=False,edge=False):
    near=[views.get(role,{'state':'unknown'}) for role in ('near_200ms','near_600ms')]
    if all(value['state']=='moving' for value in near):
        a,b=(np.asarray(value['vector']) for value in near);denominator=float(np.linalg.norm(a)*np.linalg.norm(b))
        if denominator>0 and float(a@b)/denominator>=.8:return 'moving','two_distributed_intervals_agree'
    if all(views.get(role,{}).get('state')=='stationary' for role in ('near_200ms','near_600ms','long_3000ms')):
        common=set.intersection(*(set(views[role]['stationary_cells']) for role in ('near_200ms','near_600ms','long_3000ms')))
        if cell_coverage(common,4,4) and not overlap and not edge:return 'stationary','same_cells_stationary_across_three_seconds'
        return 'unknown','overlap_border_or_changing_stationary_support'
    if overlap or any(value.get('occlusion_or_conflict') for value in views.values()):return 'unknown','occlusion_or_correspondence_conflict'
    return 'unknown','insufficient_or_disagreeing_time_evidence'


def observe_occlusion_motion(image,targets,metadata,frame_id,jpeg_sha256,raw,*,issue=None,all_targets=None,budget_ms=120):
    started=time.perf_counter();all_targets=targets if all_targets is None else all_targets
    if len(targets)>100 or len(all_targets)>100 or any(not valid_box(target['bbox_normalized']) for target in all_targets):raise ValueError('invalid motion target geometry')
    output={'schema':'short-target-motion-v2','strategy':STRATEGY,'mode':'observe','routing_applied':False,'frame_suppression':False,
        'frame_id':frame_id,'jpeg_sha256':jpeg_sha256,'targets':[],'evaluated_target_limit':TARGET_LIMIT,
        'feature_point_limit':MAX_POINTS,'budget_ms':budget_ms,'human_confirmed':False,'physical_identity_proven':False}
    if raw is None:
        output.update(state='unavailable',reason=issue or 'motion_context_not_captured',
            targets=[{'target_id':target['target_id'],'state':'unknown','reason':issue or 'motion_context_not_captured'} for target in targets],states={'unknown':len(targets)})
        return output
    header,_,pictures=validate_binding(raw,frame_id,jpeg_sha256,metadata,decode=True)
    if (header['selected_width'],header['selected_height'])!=(image.shape[1],image.shape[0]):raise ValueError('selected_image_geometry_conflict')
    current=pictures[-1];current_row=header['frames'][-1]
    output.update(context_manifest={**header,'context_sha256':hashlib.sha256(raw).hexdigest(),'context_bytes':len(raw)},
        source={key:metadata[key] for key in ('camera_id','scene_run_id','media_epoch','relative_media_ms')})
    background,cells=grid_features(current,_mask(current.shape,all_targets),(0,0,1,1),6,4,4)
    groups=[(background,cells)];all_points=[background];offsets=[0,len(background)]
    for target in targets[:TARGET_LIMIT]:
        mask=isolated_target_mask(current.shape,target,all_targets);box=target['bbox_normalized'];dx=(box[2]-box[0])*.15;dy=(box[3]-box[1])*.15
        points,cells=grid_features(current,mask,(box[0]+dx,box[1]+dy,box[2]-dx,box[3]-dy),4,4,2)
        groups.append((points,cells));all_points.append(points);offsets.append(offsets[-1]+len(points))
    points=np.concatenate(all_points) if all_points else np.empty((0,2),np.float32)
    if len(points)>MAX_POINTS:raise ValueError('motion feature bound exceeded')
    per_target=[{} for _ in targets[:TARGET_LIMIT]];translations={};batch_calls=0
    for row,past in zip(header['frames'][:-1],pictures[:-1]):
        role=row['role']
        if (not current_row['usable'] or not row['usable'] or min(current.std(),past.std())<8
                or (time.perf_counter()-started)*1000>=budget_ms):
            translations[role]={'state':'unknown','reason':'source_quality_or_motion_budget_unavailable'}
            for values in per_target:values[role]={'state':'unknown','reason':translations[role]['reason']}
            continue
        matched,valid=batch_tracks(current,past,points);batch_calls+=1
        translation=background_estimate(points[:offsets[1]],matched[:offsets[1]],valid[:offsets[1]],groups[0][1]);translations[role]=translation
        seconds=(current_row['pts_us']-row['pts_us'])/1000000
        for index,values in enumerate(per_target):
            start,end=offsets[index+1:index+3]
            target_valid=valid[start:end]&outside_other_targets(matched[start:end],current.shape,targets[index],all_targets)
            values[role]=target_pair(points[start:end],matched[start:end],target_valid,groups[index+1][1],translation,seconds,current.shape)
    for index,target in enumerate(targets):
        if index>=TARGET_LIMIT:output['targets'].append({'target_id':target['target_id'],'state':'unknown','reason':'motion_target_budget'});continue
        box=target['bbox_normalized'];overlap=overlapping_fraction(target,all_targets)>.2;edge=min(box[:2])<=.001 or max(box[2:])>=.999
        state,reason=consensus(per_target[index],overlap=overlap,edge=edge)
        output['targets'].append({'target_id':target['target_id'],'state':state,'reason':reason,'views':per_target[index],
            'overlap_suspected':overlap,'frame_border_contact':edge})
    output.update(state='observed',states=dict(Counter(value['state'] for value in output['targets'])),
        camera_translation_backwards=translations,feature_points=len(points),batch_flow_calls=batch_calls,
        elapsed_ms=round((time.perf_counter()-started)*1000,3))
    return output
