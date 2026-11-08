"""Bounded experimental road-scene policies; keep unknown and stationary targets.

These candidates are evaluated offline before any live profile is changed.
Motion masks describe activity, not the absence of people or vehicles.
"""
from dataclasses import dataclass, asdict
import math

import cv2
import numpy as np


def resized_gray(image, width=320):
    if image is None or image.ndim!=3 or image.shape[2]!=3 or min(image.shape[:2])<8:
        raise ValueError('invalid camera frame')
    height=max(8,round(image.shape[0]*width/image.shape[1]))
    return cv2.cvtColor(cv2.resize(image,(width,height),interpolation=cv2.INTER_AREA),cv2.COLOR_BGR2GRAY)


def quality(image):
    gray=resized_gray(image)
    median=float(np.median(gray));dark=float(np.mean(gray<30));glare=float(np.mean(gray>245))
    blur=float(cv2.Laplacian(gray,cv2.CV_32F).var())
    residual=cv2.absdiff(gray,cv2.medianBlur(gray,3))
    noise=float(np.median(residual));contrast=float(np.percentile(gray,90)-np.percentile(gray,10))
    # Headlights can reduce the dark-pixel fraction while most of the road
    # remains dim. Do not require a second dark-fraction threshold here.
    low_light=median<45
    # A bounded ranking score, never a calibrated recognition probability.
    rank=(min(1,math.log1p(blur)/8)*.45+min(1,contrast/100)*.35+
          (1-min(1,dark*.7+glare*2+noise/30))*.20)
    return {'median_luma':round(median,3),'dark_fraction':round(dark,6),'glare_fraction':round(glare,6),
            'laplacian_variance':round(blur,3),'noise_mad':round(noise,3),'contrast_p90_p10':round(contrast,3),
            'low_light':low_light,'quality_rank':round(rank,6),
            'usable_for_empty_confirmation':not low_light and contrast>=12 and glare<.25}


def enhance(image,mode):
    if mode=='raw':return image
    if mode!='clahe':raise ValueError('unsupported enhancement')
    lab=cv2.cvtColor(image,cv2.COLOR_BGR2LAB)
    lab[:,:,0]=cv2.createCLAHE(clipLimit=2.0,tileGridSize=(8,8)).apply(lab[:,:,0])
    return cv2.cvtColor(lab,cv2.COLOR_LAB2BGR)


class MotionComparison:
    def __init__(self,warmup_samples=20,methods=None,*,retain_foreground=False):
        self.previous=None;self.count=0;self.warmup_samples=warmup_samples
        if type(retain_foreground) is not bool:raise ValueError('invalid foreground retention')
        self.retain_foreground=retain_foreground;self.foregrounds={}
        self.methods=set(methods or ('mad','robust','mog2','knn'))
        if not self.methods <= {'mad','robust','mog2','knn'}:raise ValueError('invalid motion method')
        self.mog=cv2.createBackgroundSubtractorMOG2(history=120,varThreshold=24,detectShadows=True) if 'mog2' in self.methods else None
        self.knn=cv2.createBackgroundSubtractorKNN(history=120,dist2Threshold=400,detectShadows=True) if 'knn' in self.methods else None

    def observe(self,image):
        gray=resized_gray(image);smooth=cv2.GaussianBlur(gray,(5,5),0)
        mog=self.mog.apply(smooth,learningRate=.02) if self.mog is not None else None
        knn=self.knn.apply(smooth,learningRate=.02) if self.knn is not None else None
        if self.retain_foreground:
            self.foregrounds={name:mask for name,mask in (('mog2',mog),('knn',knn)) if mask is not None}
            for mask in self.foregrounds.values():mask.flags.writeable=False
        self.count+=1
        result={'warming_up':self.count<=self.warmup_samples,
                'mog2_fraction':float(np.mean(mog==255)) if mog is not None else None,
                'knn_fraction':float(np.mean(knn==255)) if knn is not None else None,
                'mad':None,'robust_diff_fraction':None,'shift_pixels':None,'shake':False}
        if self.previous is not None:
            result['mad']=float(np.mean(cv2.absdiff(smooth,self.previous)))
            shift,response=cv2.phaseCorrelate(self.previous.astype(np.float32),smooth.astype(np.float32)) if 'robust' in self.methods else ((0.,0.),0.)
            displacement=math.hypot(*shift)
            result['shift_pixels']=displacement if math.isfinite(displacement) else None
            result['shake']=bool(response>.12 and displacement>2)
            if response>.12 and displacement<=8:
                matrix=np.float32([[1,0,shift[0]],[0,1,shift[1]]])
                previous=cv2.warpAffine(self.previous,matrix,(gray.shape[1],gray.shape[0]),borderMode=cv2.BORDER_REFLECT)
            else:previous=self.previous
            if 'robust' in self.methods:
                difference=smooth.astype(np.float32)-previous.astype(np.float32)
                difference-=np.median(difference)  # global illumination shift is not object motion
                mask=(np.abs(difference)>12).astype(np.uint8)
                mask=cv2.morphologyEx(mask,cv2.MORPH_OPEN,np.ones((3,3),np.uint8))
                result['robust_diff_fraction']=float(np.mean(mask))
        self.previous=smooth
        return {k:round(v,6) if isinstance(v,float) else v for k,v in result.items()}


def scene_state(motion_fraction,presence_score,quality_ok,negative_updates,*,warming_up=False,shake=False):
    if presence_score is not None and (not math.isfinite(presence_score) or not 0<=presence_score<=1):
        raise ValueError('invalid presence score')
    moving=motion_fraction is not None and motion_fraction>=.01
    if presence_score is not None and presence_score>=.35:
        return 'active' if moving else 'occupied_quiet'
    if warming_up or shake or not quality_ok or presence_score is None or .10<=presence_score<.35:
        return 'unknown'
    if moving:return 'active'
    return 'empty_candidate' if negative_updates>=3 else 'unknown'


def detect_targets(probe,image,variant='raw',threshold=.35):
    """Same-JPEG boxes for raw, luminance CLAHE, or four overlapping tiles."""
    if variant in {'raw','clahe'}:
        result=probe.detect(enhance(image,variant),threshold)
        return {**result,'detector':probe.kind,'detector_variant':variant}
    if variant!='tiled':raise ValueError('unsupported target detector variant')
    from .detectors import _nms
    height,width=image.shape[:2];targets=[];presence=0.
    for top in (0,int(height*.4)):
        for left in (0,int(width*.4)):
            crop=image[top:min(height,top+int(height*.6)),left:min(width,left+int(width*.6))]
            result=probe.detect(crop,threshold);presence=max(presence,result['presence_score'])
            for value in result['targets']:
                box=np.array(value['bbox_xyxy'])+np.array([left,top,left,top])
                targets.append({**value,'bbox_xyxy':box.tolist(),'bbox_normalized':(box/[width,height,width,height]).tolist()})
    if targets:
        names={v:i for i,v in enumerate(sorted({t['class_name'] for t in targets}))}
        keep=_nms(np.asarray([v['bbox_xyxy'] for v in targets]),np.asarray([v['confidence'] for v in targets]),
                  np.asarray([names[v['class_name']] for v in targets]),.5,100)
        targets=[targets[i] for i in keep]
    return {'targets':targets,'presence_score':presence,'image_width':width,'image_height':height,
            'detector':probe.kind,'detector_variant':'tiled'}


class PolicyProbe:
    def __init__(self,probe,variant='raw',filtered_presence=False):
        if variant not in {'raw','clahe','tiled','adaptive_1','adaptive_2'}:raise ValueError('invalid detector policy')
        self.probe=probe;self.kind=probe.kind;self.variant=variant;self.filtered_presence=filtered_presence

    def detect(self,image,threshold=.35,*,context=None):
        if self.variant in {'adaptive_1','adaptive_2'}:
            from .adaptive_crops import detect_adaptive
            return detect_adaptive(self.probe,image,threshold,context,int(self.variant[-1]))
        return detect_targets(self.probe,image,self.variant,threshold)

    def max_target_score(self,image):
        if self.variant in {'adaptive_1','adaptive_2'}:raise ValueError('adaptive targets cannot become empty-scene evidence')
        if self.variant=='raw' and not self.filtered_presence:return self.probe.max_target_score(image)
        result=self.detect(image,.05)
        return max((v['confidence'] for v in result['targets']),default=0.)


class EffectiveSelector:
    """Compare fixed cadence, best-in-window, and quiet-scene redundancy reduction.

The dedup candidate only suppresses occupied_quiet with stable target geometry
and low motion; unknown/active frames bypass it. A maximum refresh is mandatory.
No original footage is removed by this decision.
"""
    def __init__(self,interval=5,mode='best',refresh_seconds=30):
        if interval not in {1,5} or mode not in {'fixed','best','quiet_dedup'} or not interval<=refresh_seconds<=60:
            raise ValueError('invalid selector policy')
        self.interval=interval;self.mode=mode;self.refresh=refresh_seconds
        self.bucket=None;self.best=None;self.last_time=None;self.last_signature=None
        self.selected=[];self.suppressed=0;self.offered=0;self.last_observed=None

    def offer(self,time_seconds,frame_id,rank,state,signature=None):
        if not math.isfinite(time_seconds) or not math.isfinite(rank) or time_seconds<0 or not 0<=rank<=1:
            raise ValueError('invalid frame ranking')
        if state not in {'active','occupied_quiet','unknown','empty_candidate'}:raise ValueError('invalid scene state')
        if self.last_observed is not None and time_seconds<self.last_observed:raise ValueError('time moved backwards')
        self.last_observed=time_seconds
        bucket=int(time_seconds//self.interval)
        if self.bucket is not None and bucket!=self.bucket:self._flush()
        self.bucket=bucket;self.offered+=1
        item={'time_seconds':time_seconds,'frame_id':frame_id,'quality_rank':rank,'state':state,'signature':signature}
        priority={'active':3,'unknown':2,'occupied_quiet':1,'empty_candidate':0}
        if self.best is None or (self.mode!='fixed' and (priority[state],rank)>(priority[self.best['state']],self.best['quality_rank'])):
            self.best=item

    def _flush(self):
        if self.best is None:return
        frame=self.best;self.best=None
        quiet=(frame['state']=='occupied_quiet' and frame['signature'] is not None)
        recent=self.last_time is not None and frame['time_seconds']-self.last_time<self.refresh
        if self.mode=='quiet_dedup' and quiet and recent and frame['signature']==self.last_signature:
            self.suppressed+=1;return
        if len(self.selected)>=256:raise ValueError('decision buffer full; drain before continuing')
        self.selected.append(frame);self.last_time=frame['time_seconds'];self.last_signature=frame['signature']

    def drain(self):
        decisions,self.selected=self.selected,[]
        return decisions

    def finish(self):
        self._flush();return self.selected
