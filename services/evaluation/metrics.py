"""Accuracy against verified labels. Predictions and counts are never truth."""
import math


def divide(a,b):return round(a/b,6) if b else None


def scores(counts):
    tp,fp,fn,tn=(counts.get(k,0) for k in ['tp','fp','fn','tn'])
    return {**counts,'precision':divide(tp,tp+fp),'recall':divide(tp,tp+fn),
            'f1':divide(2*tp,2*tp+fp+fn),'accuracy':divide(tp+tn,tp+fp+fn+tn)}


def iou(a,b):
    intersection=max(0,min(a[2],b[2])-max(a[0],b[0]))*max(0,min(a[3],b[3])-max(a[1],b[1]))
    union=(a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-intersection
    return intersection/union if union>0 else 0.


def presence_counts(labels,predictions):
    counts={'tp':0,'fp':0,'fn':0,'tn':0}
    for label,predicted in zip(labels,predictions):
        if label is None:continue
        counts['tp' if label and predicted else 'fn' if label else 'fp' if predicted else 'tn']+=1
    return counts


def box_counts(truth,predicted,overlap=.5):
    matched=set();tp=0;fp=0
    for candidate in sorted(predicted,key=lambda v:v['confidence'],reverse=True):
        eligible=[(iou(candidate['bbox_xyxy'],v['bbox_xyxy']),i) for i,v in enumerate(truth)
                  if i not in matched and v['object_type']==candidate['object_type']]
        best=max(eligible,default=(0,None))
        if best[0]>=overlap:tp+=1;matched.add(best[1])
        else:fp+=1
    return {'tp':tp,'fp':fp,'fn':len(truth)-len(matched),'matched_indices':matched}


def subset_scores(dataset,labels,model,threshold):
    truth=[];pred=[];objects={'tp':0,'fp':0,'fn':0};box_frames=0
    small_tp=small_fn=occluded_tp=occluded_fn=0
    type_boxes={kind:{'tp':0,'fp':0,'fn':0} for kind in ['person','vehicle']}
    type_presence={kind:{'tp':0,'fp':0,'fn':0,'tn':0} for kind in ['person','vehicle']}
    diagnostic={'frames_with_boxes':0,'annotated_targets':0,'matched_targets':0,'unmatched_targets':0,
                'by_type':{kind:{'annotated_targets':0,'matched_targets':0,'unmatched_targets':0} for kind in ['person','vehicle']}}
    for key,label in labels.items():
        candidates=[v for v in dataset.frames[key]['cases'][model]['targets'] if v['confidence']>=threshold]
        for kind in ['person','vehicle']:
            actual=label['presence_'+kind];detected=any(v['object_type']==kind for v in candidates)
            truth.append(actual);pred.append(detected)
            values=presence_counts([actual],[detected])
            type_presence[kind]={k:type_presence[kind][k]+values[k] for k in type_presence[kind]}
        counts=box_counts(label['objects'],candidates)
        if label['objects']:
            diagnostic['frames_with_boxes']+=1
            diagnostic['annotated_targets']+=len(label['objects'])
            diagnostic['matched_targets']+=counts['tp'];diagnostic['unmatched_targets']+=counts['fn']
            for kind in diagnostic['by_type']:
                values=box_counts([v for v in label['objects'] if v['object_type']==kind],[v for v in candidates if v['object_type']==kind])
                diagnostic['by_type'][kind]['annotated_targets']+=values['tp']+values['fn']
                diagnostic['by_type'][kind]['matched_targets']+=values['tp']
                diagnostic['by_type'][kind]['unmatched_targets']+=values['fn']
        if not label['boxes_complete']:continue
        box_frames+=1
        objects={k:objects[k]+counts[k] for k in objects}
        for kind in type_boxes:
            values=box_counts([v for v in label['objects'] if v['object_type']==kind],[v for v in candidates if v['object_type']==kind])
            type_boxes[kind]={k:type_boxes[kind][k]+values[k] for k in type_boxes[kind]}
        area=dataset.frames[key]['width']*dataset.frames[key]['height']
        for index,mark in enumerate(label['objects']):
            box=mark['bbox_xyxy'];matched=index in counts['matched_indices']
            if (box[2]-box[0])*(box[3]-box[1])/area<.01:
                small_tp+=int(matched);small_fn+=int(not matched)
            if mark.get('occluded'):
                occluded_tp+=int(matched);occluded_fn+=int(not matched)
    boxed=scores(objects);boxed.pop('accuracy',None)
    diagnostic['match_rate']=divide(diagnostic['matched_targets'],diagnostic['annotated_targets'])
    diagnostic['scope']='Annotated-target diagnostic including partial boxes; not complete-frame recall or precision.'
    for value in diagnostic['by_type'].values():value['match_rate']=divide(value['matched_targets'],value['annotated_targets'])
    return {'frames':len(labels),'presence':scores(presence_counts(truth,pred)),
       'by_type':{kind:scores(value) for kind,value in type_presence.items()},
       'boxes_iou50':boxed,'box_reviewed_frames':box_frames,
       'type_box_metrics':{kind:{k:v for k,v in scores(value).items() if k!='accuracy'} for kind,value in type_boxes.items()},
       'small_targets':{'matched':small_tp,'missed':small_fn,'recall':divide(small_tp,small_tp+small_fn)},
       'occluded_targets':{'matched':occluded_tp,'missed':occluded_fn,'recall':divide(occluded_tp,occluded_tp+occluded_fn)},
       'annotated_target_diagnostic':diagnostic}


def evaluate(dataset,labels,threshold=.35,reference_source='human'):
    if not math.isfinite(threshold) or not .05<=threshold<=.95:raise ValueError('invalid evaluation threshold')
    if reference_source not in {'human','ai_visual_review'}:raise ValueError('invalid reference source')
    verified={key:value for key,value in labels.items() if value['status']=='verified' and
              value.get('review_source','human')=='human' and value['image_sha256']==dataset.frames[key]['sha256']}
    ai_reviewed={key:value for key,value in labels.items() if value['status']=='ai_reviewed' and
              value.get('annotation_source')=='ai_visual_review' and value.get('review_source')=='ai_visual_review' and
              value['image_sha256']==dataset.frames[key]['sha256']}
    reference=verified if reference_source=='human' else ai_reviewed
    eligible={key:value for key,value in reference.items() if value['scene'] not in {'unknown','unreadable'}}
    presence_coverage={kind:sum(v['presence_'+kind] is not None for v in eligible.values()) for kind in ['person','vehicle']}
    class_balance={kind:{'positive':sum(v['presence_'+kind] is True for v in eligible.values()),
                         'negative':sum(v['presence_'+kind] is False for v in eligible.values()),
                         'unknown':sum(v['presence_'+kind] is None for v in eligible.values())} for kind in ['person','vehicle']}
    scenario_coverage={name:sum(name in v['conditions'] for v in eligible.values()) for name in ['day','night','headlights','stationary','small_target','occlusion']}
    rows=[]
    for model in dataset.models:
        rows.append({'model':model,**subset_scores(dataset,eligible,model,threshold),
           'by_scenario':{name:subset_scores(dataset,{k:v for k,v in eligible.items() if name in v['conditions']},model,threshold)
                          for name in scenario_coverage},'latency':dataset.latencies[model]})
    positive=sum(v['scene']=='occupied' for v in eligible.values());empty=sum(v['scene']=='empty' for v in eligible.values())
    false_empty=sum(v['scene']=='occupied' and dataset.frames[key]['state']=='empty_candidate' for key,v in eligible.items())
    rejected_empty=sum(v['scene']=='empty' and dataset.frames[key]['state']=='empty_candidate' for key,v in eligible.items())
    ready=bool(eligible) and empty>0 and all(any(v['presence_'+kind] is True for v in eligible.values()) for kind in ['person','vehicle']) and scenario_coverage['day']>0 and scenario_coverage['night']>0 and any(v['box_reviewed_frames']>0 for v in rows)
    return {'label_status':'ready_for_preliminary_comparison' if ready else 'needs_verified_labels_or_scenario_coverage',
      'total_frames':len(dataset.frames),'verified_frames':len(verified),'ai_reviewed_frames':len(ai_reviewed),
      'reference_source':reference_source,'reference_reviewed_frames':len(reference),'eligible_frames':len(eligible),
      'reference_warning':'AI visual review is provisional and has not been independently human-verified.' if reference_source=='ai_visual_review' else 'Human-confirmed reference only; AI reviews excluded.',
      'class_balance':class_balance,
      'excluded_unreadable_or_unknown':len(reference)-len(eligible),'presence_coverage':presence_coverage,
      'scenario_coverage':scenario_coverage,'threshold':threshold,'models':rows,
      'empty_gate':{'scope':'recorded offline baseline, not production recall acceptance','occupied_reviewed':positive,
                    'occupied_marked_empty':false_empty,'false_empty_rate':divide(false_empty,positive),
                    'empty_reviewed':empty,'empty_rejection_rate':divide(rejected_empty,empty)},
      'event_recall':None,'event_recall_status':'Temporal event intervals have not been independently annotated.',
      'selection_decision':'No automatic model promotion. Compare misses, false positives and scene strata before speed.',
      'limitations':['Reference sources are separate and hash-matched. Drafts and detector suggestions are excluded.',
        'AI-reviewed comparisons are provisional; no independent human accuracy or model promotion is implied.',
        'Partial boxes only contribute to annotated-target diagnostics, never complete-frame precision or recall.',
        'Missing negative samples prevent false-positive-rate or empty-filter effectiveness acceptance.',
        'Presence accuracy, box precision/recall at IoU 0.5 and temporal event recall are different metrics.',
        'Sparse four-camera samples do not prove production recall or unseen weather/infrared performance.']}
