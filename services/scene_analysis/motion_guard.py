"""Small-motion candidate signals, not a person/vehicle detector or empty proof."""
import cv2
import numpy as np


class SmallMotionGuard:
    def __init__(self,include_regions=False):
        if type(include_regions) is not bool:raise ValueError('invalid motion region mode')
        self.previous=None;self.include_regions=include_regions;self.difference_mask=None

    def observe(self,image):
        if image is None or image.ndim!=3 or image.shape[2]!=3 or min(image.shape[:2])<16:raise ValueError('invalid guard image')
        gray=cv2.GaussianBlur(cv2.cvtColor(image,cv2.COLOR_BGR2GRAY),(3,3),0)
        if self.previous is None:
            self.previous=gray;self.difference_mask=None
            result={'available':False,'edge_component':0,'edge_changed':0,'largest_component':0,'changed_fraction':0.,'illumination_shift':0.}
            if self.include_regions:result.update(component_regions=[],regions_truncated=False)
            return result
        if gray.shape!=self.previous.shape:raise ValueError('guard geometry changed')
        difference=gray.astype(np.int16)-self.previous.astype(np.int16);self.previous=gray
        illumination=float(np.median(difference));mask=(np.abs(difference-illumination)>15).astype(np.uint8)
        mask.flags.writeable=False;self.difference_mask=mask
        return measure_motion(mask,illumination,include_regions=self.include_regions)


def measure_motion(mask,illumination,*,include_regions=False):
    height,width=mask.shape;by=max(2,round(height*.12));bx=max(2,round(width*.12))
    edge=mask.copy();edge[by:height-by,bx:width-bx]=0
    _,_,stats,_=cv2.connectedComponentsWithStats(mask,8)
    _,_,edge_stats,_=cv2.connectedComponentsWithStats(edge,8)
    result={'available':True,'edge_component':int(stats_value(edge_stats)),'edge_changed':int(edge.sum()),
            'largest_component':int(stats_value(stats)),'changed_fraction':round(float(mask.mean()),6),
            'illumination_shift':illumination}
    if include_regions:
        components=sorted((v for v in stats[1:] if v[cv2.CC_STAT_AREA]>=2),key=lambda v:int(v[cv2.CC_STAT_AREA]),reverse=True)
        result.update(component_regions=[{'bbox_normalized':[round(float(v[0])/width,6),round(float(v[1])/height,6),
            round(float(v[0]+v[2])/width,6),round(float(v[1]+v[3])/height,6)],'area':int(v[4])} for v in components[:32]],
            regions_truncated=len(components)>32)
    return result

def stats_value(stats):return stats[1:,cv2.CC_STAT_AREA].max(initial=0)


def guard_activity(value,width=640):
    if not value['available']:return False
    if width not in {320,640}:raise ValueError('unsupported guard resolution')
    scale=(width/640)**2
    return ((value['edge_component']>=max(3,round(6*scale)) and value['edge_changed']>=max(6,round(12*scale)))
            or value['largest_component']>=max(8,round(36*scale)))


def guard_uncertain(value):
    return not value['available'] or value['changed_fraction']>.30 or abs(value['illumination_shift'])>25
