"""Fixed plate tensor adapter: native-pixel geometry, rectification and CTC rejection."""
from collections import Counter
import time

import cv2
import numpy as np
import onnxruntime as ort

from services.scene_analysis.adaptive_crops import iou
from services.scene_analysis.plate_candidates import prepare_plate_candidates
from . import __version__
from .catalog import artifact_bytes


CHARACTERS='#京沪津渝冀晋蒙辽吉黑苏浙皖闽赣鲁豫鄂湘粤桂琼川贵云藏陕甘青宁新学警港澳挂使领民航危0123456789ABCDEFGHJKLMNPQRSTUVWXYZ险品'


def decode_ctc(logits):
    logits=np.asarray(logits)
    if logits.shape!=(21,len(CHARACTERS)) or not np.isfinite(logits).all():raise ValueError('unsupported character tensor')
    exponent=np.exp(logits-logits.max(axis=1,keepdims=True));probabilities=exponent/exponent.sum(axis=1,keepdims=True)
    indices=probabilities.argmax(axis=1);characters=[];confidence=[];margins=[];previous=0;peak=None
    for offset,index in enumerate(indices):
        index=int(index)
        if index!=previous:
            if peak is not None:confidence.append(peak[0]);margins.append(peak[1])
            peak=None
            if index:characters.append(CHARACTERS[index])
        if index:
            ordered=np.sort(probabilities[offset]);value=(float(ordered[-1]),float(ordered[-1]-ordered[-2]))
            if peak is None or value[0]>peak[0]:peak=value
        previous=index
    if peak is not None:confidence.append(peak[0]);margins.append(peak[1])
    return ''.join(characters),min(confidence,default=0.),min(margins,default=0.)


def rectify(image,points,double):
    points=np.asarray(points,dtype=np.float32)
    sums=points.sum(axis=1);differences=points[:,1]-points[:,0]
    corners=points[[sums.argmin(),differences.argmin(),sums.argmax(),differences.argmax()]]
    if len({tuple(point) for point in corners})!=4 or not cv2.isContourConvex(corners):raise ValueError('invalid plate quadrilateral')
    top_left,top_right,bottom_right,bottom_left=corners
    width=int(max(np.linalg.norm(top_right-top_left),np.linalg.norm(bottom_right-bottom_left)))
    height=int(max(np.linalg.norm(bottom_left-top_left),np.linalg.norm(bottom_right-top_right)))
    if min(width,height)<2 or width*height>image.shape[0]*image.shape[1]:raise ValueError('invalid plate crop')
    target=np.array([[0,0],[width-1,0],[width-1,height-1],[0,height-1]],dtype=np.float32)
    patch=cv2.warpPerspective(image,cv2.getPerspectiveTransform(corners,target),(width,height))
    if double:
        upper=patch[:int(height*5/12)];lower=patch[int(height/3):]
        if min(upper.shape[:2])<1 or min(lower.shape[:2])<1:raise ValueError('invalid double plate crop')
        patch=np.hstack((cv2.resize(upper,(lower.shape[1],lower.shape[0])),lower))
    return patch


class PlateOnnxEngine:
    def __init__(self,root,definition):
        options=ort.SessionOptions();options.intra_op_num_threads=1;options.inter_op_num_threads=1
        options.execution_mode=ort.ExecutionMode.ORT_SEQUENTIAL;options.log_severity_level=4
        cv2.setNumThreads(1)
        self.detector=ort.InferenceSession(artifact_bytes(root,definition.detector),sess_options=options,providers=['CPUExecutionProvider'])
        self.recognizer=ort.InferenceSession(artifact_bytes(root,definition.recognizer),sess_options=options,providers=['CPUExecutionProvider'])
        for session,shape in [(self.detector,[1,3,640,640]),(self.recognizer,[1,3,48,168])]:
            if len(session.get_inputs())!=1 or session.get_inputs()[0].shape!=shape or session.get_inputs()[0].type!='tensor(float)' or len(session.get_outputs())!=1:
                raise ValueError('unsupported native model interface')
        self.definition=definition;self.rejected=Counter();self.detected=0

    def _detect(self,image):
        height,width=image.shape[:2];ratio=min(640/width,640/height)
        resized_width,resized_height=max(1,int(width*ratio)),max(1,int(height*ratio))
        left,top=(640-resized_width)//2,(640-resized_height)//2
        resized=cv2.resize(image,(resized_width,resized_height))
        canvas=cv2.copyMakeBorder(resized,top,640-resized_height-top,left,640-resized_width-left,cv2.BORDER_CONSTANT,value=(114,114,114))
        tensor=cv2.dnn.blobFromImage(canvas,1/255,(640,640),swapRB=True)
        output=np.asarray(self.detector.run(None,{self.detector.get_inputs()[0].name:tensor})[0])
        if output.shape!=(1,25200,16) or not np.isfinite(output).all():raise ValueError('unsupported detection tensor')
        candidates=[]
        for row in output[0][output[0,:,4]>.4]:
            score=float(row[4]*max(row[13],row[14]))
            if not .4<=score<=1:continue
            self.detected+=1
            cx,cy,w,h=map(float,row[:4]);box=[(cx-w/2-left)/ratio,(cy-h/2-top)/ratio,(cx+w/2-left)/ratio,(cy+h/2-top)/ratio]
            points=(row[5:13].reshape(4,2)-np.array([left,top]))/ratio
            if not (0<=box[0]<box[2]<=width and 0<=box[1]<box[3]<=height and np.all(points>=0) and np.all(points[:,0]<=width) and np.all(points[:,1]<=height)):
                self.rejected['partial_text']+=1;continue
            candidates.append({'axis':[int(np.floor(box[0])),int(np.floor(box[1])),int(np.ceil(box[2])),int(np.ceil(box[3]))],
                               'landmarks':points.tolist(),'score':score,'double':bool(row[14]>row[13])})
        kept=[]
        for candidate in sorted(candidates,key=lambda value:-value['score']):
            if any(iou(candidate['axis'],previous['axis'])>.5 for previous in kept):continue
            kept.append(candidate)
            if len(kept)>100:raise ValueError('plate candidate bound exceeded')
        return kept

    def infer(self,image):
        readings=[];policy=self.definition.policy
        for candidate in self._detect(image):
            left,top,right,bottom=candidate['axis']
            if right-left<policy.minimum_width or bottom-top<policy.minimum_height:
                self.rejected['low_resolution']+=1;continue
            try:patch=rectify(image,candidate['landmarks'],candidate['double'])
            except ValueError:self.rejected['partial_text']+=1;continue
            tensor=(cv2.resize(patch,(168,48)).astype(np.float32)/255-.588)/.193
            tensor=np.ascontiguousarray(tensor.transpose(2,0,1)[None])
            output=np.asarray(self.recognizer.run(None,{self.recognizer.get_inputs()[0].name:tensor})[0])
            if output.shape!=(1,21,len(CHARACTERS)):raise ValueError('unsupported recognition tensor')
            text,confidence,margin=decode_ctc(output[0])
            if confidence<policy.minimum_character_probability or margin<policy.minimum_character_margin:
                self.rejected['uncertain_characters']+=1;continue
            readings.append({key:value for key,value in {**candidate,'plate':text}.items() if key!='double'})
        return readings

    def recognize(self,image,metadata):
        started=time.monotonic();report=prepare_plate_candidates(image,self.infer,extra_regions=self.definition.policy.extra_regions)
        if report['state']!='complete':raise RuntimeError('regional inference did not complete')
        conflict=any('inconsistent_readings' in group['limitations'] or 'ambiguous_spatial_group' in group['limitations'] for group in report['groups'])
        accepted=[group for group in report['groups'] if group['accepted']]
        limitations={'uncalibrated_score'}
        if conflict:outcome='unreadable';accepted=[];limitations.add('inconsistent_readings')
        elif accepted:
            outcome='recognized';limitations.update(set(self.rejected)&{'low_resolution','blur'})
        elif self.detected or report['candidates']:
            outcome='unreadable';limitations.update(self.rejected)
            reasons={reason for group in report['groups'] for reason in group['limitations']}
            if 'unsupported_plate_format' in reasons:limitations.add('unsupported_language')
            if 'partial_text' in reasons or report['rejected_candidates']:limitations.add('partial_text')
            if not reasons and not self.rejected:limitations.add('partial_text')
        else:outcome='no_text'
        result={'schema_version':'specialist-result-v1','job_id':metadata.job_id,'input_sha256':metadata.input_sha256,
                **{key:getattr(metadata,key) for key in ('frame_id','evidence_sha256','operation','profile','image_width','image_height')},
                'engine':{'name':'plate_onnx_v1@'+__version__,'version':self.definition.model_id},'outcome':outcome,
                'regions':[{'index':index,'text':group['text'],'bbox_xyxy':list(group['bbox_xyxy']),'score':None} for index,group in enumerate(accepted)],
                'limitations':sorted(limitations)}
        return {'result':result,'diagnostics':{'detected_candidates':self.detected,'rejected_candidates':dict(self.rejected),
                    'native_inference_ms':round((time.monotonic()-started)*1000,3),'extra_regions':self.definition.policy.extra_regions}}
