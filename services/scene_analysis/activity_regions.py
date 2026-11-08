"""Bind recent measured activity to full-image target boxes, without identity claims."""
import math


def valid_box(box):
    return (isinstance(box,(list,tuple)) and len(box)==4 and all(type(v) in (int,float) and math.isfinite(v) and 0<=v<=1 for v in box)
            and box[0]<box[2] and box[1]<box[3])


def overlaps(a,b):
    return min(a[2],b[2])>max(a[0],b[0]) and min(a[3],b[3])>max(a[1],b[1])


class ActivityRegions:
    def __init__(self,hold_seconds=10):
        if not .4<=hold_seconds<=120:raise ValueError('invalid activity retention')
        self.hold=hold_seconds;self.history=[];self.last_time=None;self.last_incomplete=None

    def observe(self,now,guard,active,roi=(0,0,1,1),*,observation_complete=True):
        if not valid_box(roi) or not math.isfinite(now) or self.last_time is not None and now<=self.last_time:
            raise ValueError('invalid activity region time/geometry')
        if type(observation_complete) is not bool:
            raise ValueError('invalid activity observation completeness')
        self.last_time=now
        self.history=[v for v in self.history if now-v[0]<self.hold]
        if not observation_complete or guard.get('regions_truncated',True):
            self.last_incomplete=now
        if active:
            if 'component_regions' not in guard:raise ValueError('activity regions not available')
            for component in guard['component_regions']:
                box=component['bbox_normalized']
                if not valid_box(box):raise ValueError('invalid measured motion box')
                x1,y1,x2,y2=roi
                # A small spatial margin covers the edge of a moving object's
                # image difference without turning a whole scene into motion.
                mapped=[max(x1,x1+(x2-x1)*box[0]-.00625),max(y1,y1+(y2-y1)*box[1]-.00625),
                        min(x2,x1+(x2-x1)*box[2]+.00625),min(y2,y1+(y2-y1)*box[3]+.00625)]
                self.history.append((now,mapped))
        merged=[]
        for _,box in self.history[-512:]:
            box=list(box)
            changed=True
            while changed:
                changed=False
                for previous in list(merged):
                    if overlaps(box,previous):
                        merged.remove(previous)
                        box=[min(box[0],previous[0]),min(box[1],previous[1]),max(box[2],previous[2]),max(box[3],previous[3])]
                        changed=True
            merged.append(box)
        if len(self.history)>512:
            # Evicting a still-live region is an observation gap. Keep that
            # uncertainty until the omitted region's original hold expires.
            omitted_until=max(v[0] for v in self.history[:-512])
            self.last_incomplete=max(omitted_until,self.last_incomplete) if self.last_incomplete is not None else omitted_until
        self.history=self.history[-512:]
        complete=self.last_incomplete is None or now-self.last_incomplete>=self.hold
        return {'policy':'motion-v1','regions_normalized':[[round(v,6) for v in box] for box in merged[:32]],
                'regions_complete':complete and len(merged)<=32,
                'hold_seconds':self.hold,'stationary_presence_is_empty':True}


def active_target(box,context):
    if context.get('policy')!='motion-v1' or not valid_box(box):raise ValueError('invalid target activity context')
    regions=context.get('regions_normalized')
    if not isinstance(regions,list) or len(regions)>32 or any(not valid_box(v) for v in regions):
        raise ValueError('invalid activity regions')
    # An incomplete motion map cannot safely classify an individual target as static.
    return not context.get('regions_complete',False) or any(overlaps(box,region) for region in regions)
