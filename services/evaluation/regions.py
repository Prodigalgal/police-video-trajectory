"""Read-only, scope-bound region review data and causal gate diagnostics."""
from bisect import bisect_right
import hashlib
import json
import math
from pathlib import Path
import re


def scope_id(camera,source_sha256,roi):
    if not re.fullmatch(r'recording-0[1-4]',camera) or not re.fullmatch(r'[a-f0-9]{64}',source_sha256):
        raise ValueError('invalid region source identity')
    if (not isinstance(roi,list) or len(roi)!=4 or any(type(v) not in {int,float} or not math.isfinite(v) for v in roi)
            or not 0<=roi[0]<roi[2]<=1 or not 0<=roi[1]<roi[3]<=1):raise ValueError('invalid rectangle')
    pixels=[int(v*side) for v,side in zip(roi,[320,240,320,240])]
    return hashlib.sha256(json.dumps({'camera':camera,'source':source_sha256,'normalized_roi':roi,
        'probe_pixel_rectangle':pixels,'probe_dimensions':[320,240]},sort_keys=True).encode()).hexdigest()


def causal_sample(samples,timestamp_ms,max_age_ms=1000):
    offsets=[v['offset_ms'] for v in samples]
    if offsets!=sorted(set(offsets)):raise ValueError('nonmonotonic region trace')
    index=bisect_right(offsets,timestamp_ms)-1
    if index<0 or timestamp_ms-offsets[index]>max_age_ms:return None
    return samples[index]


class RegionDataset:
    def __init__(self,root,reference_file=None):
        supplied=Path(root)
        if supplied.is_symlink():raise ValueError('unsafe region root')
        self.root=supplied.resolve()
        self.result=json.loads((self.root/'result.json').read_text())
        if self.result['scope']!=[f'recording-0{i}' for i in range(1,5)]:raise ValueError('unexpected region scope')
        self.points={};self.frames={};self.references={};self.scopes={}
        for camera in self.result['scope']:
            value=json.loads((self.root/(camera+'.json')).read_text())
            if value['summary']['camera']!=camera:raise ValueError('region camera mismatch')
            summary=next(v for v in self.result['points'] if v['camera']==camera)
            if value['summary']['source_sha256']!=summary['source_sha256']:raise ValueError('region source changed')
            if set(value['regions'])!={'full_frame','near_field'}:raise ValueError('incomplete region scope')
            if len(value['review_stills'])!=40:raise ValueError('unexpected camera review count')
            value.pop('anchors',None)
            offsets=[v['offset_ms'] for v in value['samples']]
            if offsets!=sorted(set(offsets)):raise ValueError('nonmonotonic trace')
            self.points[camera]=value;self.scopes[camera]={}
            for name,roi in value['regions'].items():
                if name not in {'full_frame','near_field'}:raise ValueError('unexpected region name')
                self.scopes[camera][name]=scope_id(camera,value['summary']['source_sha256'],roi)
            for frame in value['review_stills']:
                identity=frame['id']
                if not re.fullmatch(camera+r'-\d{6}',identity) or frame['image']!='stills/'+identity+'.jpg':raise ValueError('unsafe region image')
                if (identity in self.frames or frame['source_sha256']!=summary['source_sha256']
                        or (frame['width'],frame['height'])!=(1280,960)):raise ValueError('region image source mismatch')
                path=self.root/frame['image']
                if path.is_symlink() or path.resolve().parent!=self.root/'stills' or not path.is_file():raise ValueError('unsafe region image')
                if hashlib.sha256(path.read_bytes()).hexdigest()!=frame['sha256']:raise ValueError('region image changed')
                self.frames[identity]={**frame,'camera':camera,'path':path}
        if len(self.frames)!=160:raise ValueError('unexpected dense review count')
        self.identity=hashlib.sha256(json.dumps({'images':{k:v['sha256'] for k,v in sorted(self.frames.items())},
            'scopes':self.scopes},sort_keys=True).encode()).hexdigest()
        if reference_file:
            reference=json.loads(Path(reference_file).read_text())
            if reference['dataset_id']!=self.identity or reference['reference_source']!='ai_visual_review':raise ValueError('region reference identity mismatch')
            for row in reference['frames']:
                identity=row['frame_id'];region=row['region'];frame=self.frames[identity]
                if (row['scope_id']!=self.scopes[frame['camera']][region] or row['image_sha256']!=frame['sha256']
                        or row['state'] not in {'empty','occupied','unknown'} or row['review_source']!='ai_visual_review'):
                    raise ValueError('region reference scope mismatch')
                key=(identity,region)
                if key in self.references:raise ValueError('duplicate region reference')
                self.references[key]=row

    def image(self,identity):
        frame=self.frames[identity]
        if frame['path'].is_symlink() or hashlib.sha256(frame['path'].read_bytes()).hexdigest()!=frame['sha256']:
            raise ValueError('region image changed')
        return frame['path']

    def public(self):
        return {'id':self.identity,'cameras':list(self.points),'scopes':self.scopes,
            'region_definitions':{k:v['regions'] for k,v in self.points.items()},
            'effective_rectangles':{k:{n:[int(x*s)*4 for x,s in zip(roi,[320,240,320,240])]
                for n,roi in v['regions'].items()} for k,v in self.points.items()},
            'reference_source':'ai_visual_review','reference_count':len(self.references),
            'frames':[{'id':k,'camera':v['camera'],'offset_ms':v['offset_ms'],
               'image_url':'/api/evaluation/regions/frames/'+k+'/image',
               'references':{r:self.references.get((k,r),{}).get('state','unreviewed') for r in self.scopes[v['camera']]}}
               for k,v in self.frames.items()],
            'production_regions_changed':False,'interval_ground_truth':False,
            'warning':'Near-field windows exclude other areas. Dense snapshots are not complete event-interval truth.'}

    def at(self,identity,region):
        frame=self.frames[identity];point=self.points[frame['camera']]
        if region not in point['regions']:raise ValueError('unknown region')
        sample=causal_sample(point['samples'],frame['offset_ms'])
        return {'reference':self.references.get((identity,region)),
                'sample':sample['regions'][region] if sample else None,
                'trace_offset_ms':sample['offset_ms'] if sample else None,
                'reference_offset_ms':frame['offset_ms'],'roi':point['regions'][region]}

    def report(self,camera,region,interval=5):
        if camera not in self.points or region not in self.points[camera]['regions'] or interval not in [1,5]:raise ValueError('invalid region comparison')
        point=self.points[camera];reference=[(k,self.references.get((k,region))) for k,v in self.frames.items() if v['camera']==camera]
        known=[(k,v) for k,v in reference if v and v['state']!='unknown']
        positive=sum(v['state']=='occupied' for _,v in known);negative=sum(v['state']=='empty' for _,v in known)
        rows=[]
        for model in ['nanodet','yolox']:
            for method in ['mad','robust','mog2','knn']:
                false_empty=correct_empty=unknown=unmatched=matched_positive=matched_negative=0
                for identity,label in known:
                    at=self.at(identity,region)
                    if at['sample'] is None:unmatched+=1;continue
                    matched_positive+=int(label['state']=='occupied');matched_negative+=int(label['state']=='empty')
                    state=at['sample']['models'][model]['gate_candidates'][method+'_'+str(interval)+'s']['state']
                    false_empty+=int(label['state']=='occupied' and state=='empty_candidate')
                    correct_empty+=int(label['state']=='empty' and state=='empty_candidate')
                    unknown+=int(state=='unknown')
                rows.append({'model':model,'motion_method':method,'occupied_reference_samples':positive,
                    'empty_reference_samples':negative,'occupied_marked_empty':false_empty,'empty_marked_empty':correct_empty,
                    'unknown_gate_samples':unknown,'unmatched_samples':unmatched,
                    'matched_occupied_samples':matched_positive,'matched_empty_samples':matched_negative,
                    'false_empty_rate':round(false_empty/matched_positive,6) if matched_positive else None,
                    'empty_candidate_rate':round(correct_empty/matched_negative,6) if matched_negative else None,
                    'candidate_frames_in_whole_clip':point['summary']['gate_candidate_counts'][region+'/'+model+'/'+method+'/'+str(interval)+'s'],
                    'latency':point['summary']['detector_latency'][region+'_'+model]})
        return {'camera':camera,'region':region,'scope_id':self.scopes[camera][region],'interval_seconds':interval,
            'roi':point['regions'][region],'reviewed_samples':len(known),
            'unknown_or_unreviewed_samples':len(reference)-len(known),'rows':rows,
            'reference_source':'ai_visual_review','reference_sampling_seconds':.5,
            'event_recall':None,'production_regions_changed':False,
            'limitations':['Causal preceding gate state only; no future samples or cross-region truth reuse.',
                'Snapshots at 0.5s do not prove full event-interval coverage or absence between snapshots.',
                'Candidate counts are gate-only and do not include best-frame selection, queueing or VLM throughput.',
                'Only the reviewed 20-second window is scored; candidate counts describe the full approximately two-minute clip.']}
