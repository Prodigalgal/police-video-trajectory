"""Observable target readability features, not a learned validity classifier.

Scores rank candidate images. They are not calibrated recognition confidence,
occlusion estimates, identity, empty-scene evidence or permission to drop frames.
"""
import hashlib
import json
import math

import cv2
import numpy as np

SCHEMA='target-readability-v1'
POLICY={'patch_max_side':192,'patch_min_side':4,'reference_short_side_px':96,
        'weights':{'size':.35,'focus':.30,'exposure':.20,'border':.15}}
POLICY_SHA256=hashlib.sha256(json.dumps(POLICY,sort_keys=True).encode()).hexdigest()


def clamp(value):return min(1.,max(0.,float(value)))


def target_quality(image,target):
    if image is None or image.dtype!=np.uint8 or image.ndim!=3 or image.shape[2]!=3:
        raise ValueError('invalid target quality image')
    height,width=image.shape[:2]
    base={'target_id':target.get('target_id'),'state':'unavailable','occlusion':None,
          'completeness_evidence':'frame_border_only','ranks':{}}
    box=target.get('bbox_xyxy')
    if (not isinstance(box,(list,tuple)) or len(box)!=4
            or any(isinstance(v,bool) or not isinstance(v,(int,float)) or not math.isfinite(v) for v in box)):
        return {**base,'reason':'invalid_box'}
    x1,y1,x2,y2=map(float,box)
    if not 0<=x1<x2<=width or not 0<=y1<y2<=height:
        return {**base,'reason':'box_outside_image'}
    left,top,right,bottom=math.floor(x1),math.floor(y1),math.ceil(x2),math.ceil(y2)
    patch=image[top:bottom,left:right]
    short=min(x2-x1,y2-y1)
    border=bool(x1<=1 or y1<=1 or x2>=width-1 or y2>=height-1)
    geometry={'width_px':round(x2-x1,3),'height_px':round(y2-y1,3),'short_side_px':round(short,3),
              'area_fraction':round((x2-x1)*(y2-y1)/(width*height),7),'frame_border_contact':border}
    if min(patch.shape[:2])<POLICY['patch_min_side']:
        return {**base,'reason':'crop_too_small_for_texture','geometry':geometry}
    # Downsample only: enlarging a tiny object must not invent native detail.
    ratio=min(1.,POLICY['patch_max_side']/max(patch.shape[:2]))
    if ratio<1:
        patch=cv2.resize(patch,(max(1,round(patch.shape[1]*ratio)),max(1,round(patch.shape[0]*ratio))),interpolation=cv2.INTER_AREA)
    if min(patch.shape[:2])<4:
        return {**base,'reason':'extreme_aspect_ratio','geometry':geometry}
    gray=cv2.cvtColor(patch,cv2.COLOR_BGR2GRAY)
    median=float(np.median(gray));dark=float(np.mean(gray<30));glare=float(np.mean(gray>245))
    contrast=float(np.percentile(gray,90)-np.percentile(gray,10))
    noise=float(np.median(cv2.absdiff(gray,cv2.medianBlur(gray,3))))
    laplacian=float(cv2.Laplacian(gray,cv2.CV_32F).var())
    size=clamp(math.log1p(short)/math.log1p(POLICY['reference_short_side_px']))
    focus=clamp(math.log1p(max(0.,math.sqrt(laplacian)-2*noise))/math.log1p(80))
    focus*=clamp(contrast/40)*(1-min(.8,noise/24))
    exposure=clamp(1-dark*.75-glare*1.5)*clamp(median/25)
    components={'size':size,'focus':focus,'exposure':exposure,'border':.5 if border else 1.}
    geometric=math.prod(max(.001,components[k])**w for k,w in POLICY['weights'].items())
    ranks={'size_only':size,'focus_only':focus,'target_balanced':geometric,
           'target_bottleneck':min(size,focus,exposure)*(.7+.3*components['border'])}
    return {**base,'state':'scored','geometry':geometry,
            'features':{'median_luma':round(median,3),'dark_fraction':round(dark,6),'glare_fraction':round(glare,6),
                        'contrast_p90_p10':round(contrast,3),'noise_mad':round(noise,3),
                        'laplacian_variance':round(laplacian,3),'patch_width':gray.shape[1],'patch_height':gray.shape[0]},
            'components':{k:round(v,6) for k,v in components.items()},'ranks':{k:round(clamp(v),6) for k,v in ranks.items()}}


class TargetQualityMemory:
    """Bounded geometric-candidate history; recommendations never suppress frames."""
    def __init__(self,max_gap_ms=10000,max_entries=256):
        if not 1000<=max_gap_ms<=30000 or not 1<=max_entries<=512:raise ValueError('invalid quality history bounds')
        self.max_gap_ms=max_gap_ms;self.max_entries=max_entries;self.entries={};self.clocks={}

    def observe(self,metadata,frame_id,digest,targets,scores):
        camera=metadata['camera_id'];run=metadata['scene_run_id'];epoch=metadata['media_epoch'];now=metadata['relative_media_ms']
        if type(now) is not int or now<0:raise ValueError('invalid target quality clock')
        context=(camera,run,epoch);previous_clock=self.clocks.get(camera)
        reset=previous_clock is not None and (previous_clock[:3]!=context or now<=previous_clock[3] or now-previous_clock[3]>self.max_gap_ms)
        if reset:self.entries={k:v for k,v in self.entries.items() if k[0]!=camera}
        self.clocks.pop(camera,None)
        self.clocks[camera]=(*context,now)
        if len(self.clocks)>6:
            evicted=next(iter(self.clocks));self.clocks.pop(evicted)
            self.entries={k:v for k,v in self.entries.items() if k[0]!=evicted}
        self.entries={k:v for k,v in self.entries.items() if k[:3]!=context or 0<=now-v['last_ms']<=self.max_gap_ms}
        recommendations=[]
        for target,score in zip(targets,scores):
            track=target.get('track_id');rank=score.get('ranks',{}).get('target_balanced')
            if not track or target.get('association')!='local_geometry_candidate' or rank is None:
                recommendations.append({'target_id':target.get('target_id'),'reason':'retain_unscored_or_unassociated',
                                        'semantic_change':None});continue
            key=(*context,track);old=self.entries.get(key);box=target['bbox_normalized']
            cx=(box[0]+box[2])/2;cy=(box[1]+box[3])/2;area=(box[2]-box[0])*(box[3]-box[1])
            improved=old is None or rank>old['best_rank']+.03
            if old is None:reason='first_geometric_candidate'
            elif improved:reason='better_readability_candidate'
            else:reason='retain_existing_candidate'
            best={'best_rank':rank,'best_frame_id':frame_id,'best_sha256':digest,'best_ms':now} if improved else {k:old[k] for k in ['best_rank','best_frame_id','best_sha256','best_ms']}
            # References cannot remain fresh forever just because new frames arrive.
            if now-best['best_ms']>self.max_gap_ms:
                best={'best_rank':rank,'best_frame_id':frame_id,'best_sha256':digest,'best_ms':now};reason='bounded_refresh_candidate'
            recommendations.append({'target_id':target['target_id'],'reason':reason,'semantic_change':None,
                'candidate_gap_ms':now-old['last_ms'] if old else None,
                'normalized_center_shift':round(math.hypot(cx-old['cx'],cy-old['cy']),6) if old else None,
                'area_change_ratio':round(area/old['area'],6) if old and old['area']>0 else None,
                'representative':{k:best[k] for k in ['best_frame_id','best_sha256','best_ms','best_rank']}})
            if len(self.entries)>=self.max_entries and key not in self.entries:
                self.entries.pop(min(self.entries,key=lambda k:self.entries[k]['last_ms']))
            self.entries[key]={**best,'last_ms':now,'cx':cx,'cy':cy,'area':area}
        return {'recommendations':recommendations,'history_entries':len(self.entries),'context_reset':reset,
                'association_semantics':'local_geometry_candidate_not_identity','frame_suppression':False}


def score_plan(image,targets,metadata,frame_id,digest,memory):
    if len(targets)>100:raise ValueError('target quality count bound exceeded')
    if not isinstance(digest,str) or len(digest)!=64 or any(c not in '0123456789abcdef' for c in digest):
        raise ValueError('invalid quality image digest')
    scores=[target_quality(image,t) for t in targets]
    return {'schema':SCHEMA,'method':'rules_not_trained_model','calibrated':False,'mode':'observe',
            'policy_sha256':POLICY_SHA256,'jpeg_sha256':digest,'image_width':image.shape[1],'image_height':image.shape[0],
            'state':'scored' if any(v['state']=='scored' for v in scores) else 'no_scored_targets',
            'targets':scores,'temporal':memory.observe(metadata,frame_id,digest,targets,scores),
            'original_targets_changed':False,'frame_suppression':False}
