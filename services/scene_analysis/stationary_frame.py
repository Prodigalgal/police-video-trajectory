"""Conservative whole-frame veto; mixed, occluded and unknown scenes stay intact."""
import hashlib
import json
import math

from .target_duplicates import iou


class StationaryFrameReview:
    def __init__(self):self.cameras={}

    def clear(self):self.cameras.clear()

    def evaluate(self,targets,observation,metadata,frame_id,jpeg_sha256,*,mode,detection_complete):
        if mode not in {'observe','confirmed'}:raise ValueError('invalid stationary frame policy')
        camera,run,epoch,now=(metadata[key] for key in ('camera_id','scene_run_id','media_epoch','relative_media_ms'))
        if type(now) is not int or now<0 or type(epoch) is not int or epoch<0 or len(targets)>100:raise ValueError('invalid stationary source')
        previous=self.cameras.get(camera);context=(run,epoch)
        fresh=previous is not None and previous['context']==context and 0<now-previous['time']<=5500 and previous['frame']!=frame_id
        histories=previous['tracks'] if fresh else {};current={};decisions=[]
        bound=(observation.get('schema')=='short-target-motion-v2' and observation.get('strategy')=='grid_batch_v1'
            and observation.get('state')=='observed' and observation.get('frame_id')==frame_id and observation.get('jpeg_sha256')==jpeg_sha256
            and observation.get('source')=={key:metadata[key] for key in ('camera_id','scene_run_id','media_epoch','relative_media_ms')})
        measured={value['target_id']:value for value in observation.get('targets',[])} if bound else {}
        for target in targets:
            evidence=measured.get(target['target_id'],{});track=target.get('track_id');prior=histories.get(track,{})
            box=target['bbox_normalized'];stable_box=prior and iou(prior['box'],box)>=.85
            if stable_box:
                distance=math.hypot((box[0]+box[2]-sum(prior['box'][::2]))/2,(box[1]+box[3]-sum(prior['box'][1::2]))/2)
                stable_box=distance<=.0025
            eligible=(detection_complete and bound and target.get('object_type')=='vehicle' and evidence.get('state')=='stationary'
                and evidence.get('reason')=='same_cells_stationary_across_three_seconds' and not evidence.get('overlap_suspected',True)
                and not evidence.get('frame_border_contact',True))
            last_move=now if evidence.get('state')=='moving' else prior.get('last_move')
            recent_move=last_move is not None and now-last_move<3000
            count=(prior.get('count',0)+1 if stable_box else 1) if eligible and not recent_move else 0
            if track:current[track]={'count':count,'box':list(box),'last_move':last_move}
            decisions.append({'target_id':target['target_id'],'state':evidence.get('state','unknown'),'confirmations':count,
                'eligible':bool(eligible and count>=2 and not recent_move),'recent_movement':recent_move})
        self.cameras.pop(camera,None);self.cameras[camera]={'context':context,'time':now,'frame':frame_id,'tracks':current}
        while len(self.cameras)>6:self.cameras.pop(next(iter(self.cameras)))
        activity=(metadata.get('selection_metrics') or {}).get('activity')
        complete=isinstance(activity,dict) and activity.get('policy')=='motion-v1' and activity.get('regions_complete') is True
        would_filter=bool(detection_complete and bound and complete and targets and all(value['eligible'] for value in decisions))
        proof={key:observation.get(key) for key in ('strategy','frame_id','jpeg_sha256','source','targets')}
        proof['context_sha256']=(observation.get('context_manifest') or {}).get('context_sha256')
        return {'schema':'stationary-frame-review-v1','strategy':'grid_batch_v1','mode':mode,'applied':mode=='confirmed' and would_filter,
            'would_complete_locally':would_filter,'frame_suppression':False,'partial_target_filtering':False,
            'frame_id':frame_id,'jpeg_sha256':jpeg_sha256,'coarse_activity_complete':complete,
            'evidence_sha256':hashlib.sha256(json.dumps(proof,sort_keys=True,separators=(',',':'),allow_nan=False).encode()).hexdigest(),
            'targets':decisions,'reason':'confirmed_static_vehicle_frame' if would_filter else 'mixed_unknown_occluded_or_confirmation_pending'}
