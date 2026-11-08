"""Conservative cross-subtype vehicle proposals; rider/person overlap is preserved."""
import math

from .activity_regions import valid_box


def iou(a,b):
    intersection=max(0,min(a[2],b[2])-max(a[0],b[0]))*max(0,min(a[3],b[3])-max(a[1],b[1]))
    union=(a[2]-a[0])*(a[3]-a[1])+(b[2]-b[0])*(b[3]-b[1])-intersection
    return intersection/union if union>0 else 0.


def propose_vehicle_dedup(targets,threshold=.85):
    if not .7<=threshold<=.99 or len(targets)>100:raise ValueError('invalid vehicle duplicate policy')
    neighbors=[[] for _ in targets];pairs=[]
    for n,a in enumerate(targets):
        if not valid_box(a['bbox_normalized']):raise ValueError('invalid duplicate geometry')
        for m in range(n+1,len(targets)):
            b=targets[m]
            if (a.get('object_type')!='vehicle' or b.get('object_type')!='vehicle'
                    or a.get('class_name') not in {'car','bus','truck'} or b.get('class_name') not in {'car','bus','truck'}
                    or a['class_name']==b['class_name']):continue
            x,y=a['bbox_normalized'],b['bbox_normalized']
            if not valid_box(y):raise ValueError('invalid duplicate geometry')
            overlap=iou(x,y)
            aw,ah=x[2]-x[0],x[3]-x[1];bw,bh=y[2]-y[0],y[3]-y[1]
            area_ratio=min(aw*ah,bw*bh)/max(aw*ah,bw*bh)
            center=math.hypot((x[0]+x[2]-y[0]-y[2])/2,(x[1]+x[3]-y[1]-y[3])/2)
            if overlap>=threshold and area_ratio>=.8 and center<=.1*min(aw,ah,bw,bh):
                neighbors[n].append(m);neighbors[m].append(n);pairs.append((n,m,overlap))
    removed=set();proposals=[]
    for n,m,overlap in pairs:
        # No transitive chains or crowded many-to-one merges.
        if len(neighbors[n])!=1 or len(neighbors[m])!=1:continue
        keep,drop=(n,m) if targets[n]['confidence']>=targets[m]['confidence'] else (m,n)
        removed.add(drop)
        proposals.append({'retained_target_id':targets[keep]['target_id'],'suppressed_target_id':targets[drop]['target_id'],
            'retained_class':targets[keep]['class_name'],'suppressed_class':targets[drop]['class_name'],
            'iou':round(overlap,6),'reason':'mutual_cross_subtype_vehicle_candidate'})
    return {'schema':'vehicle-duplicate-v1','threshold':threshold,'input_targets':len(targets),
        'kept_indices':[n for n in range(len(targets)) if n not in removed], 'proposals':proposals,
        'candidate_pairs':len(pairs),'ambiguous_targets':sum(len(v)>1 for v in neighbors),
        'human_confirmed':False,'physical_identity_proven':False}
