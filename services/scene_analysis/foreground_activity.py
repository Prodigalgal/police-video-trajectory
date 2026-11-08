"""Spatial agreement of measured change and current background evidence.

Masks are short-lived low-resolution signals, never person/vehicle truth.
"""
import cv2
import numpy as np

from .motion_guard import guard_uncertain,measure_motion


MODES=frozenset({'legacy','foreground','shadow_protected'})


def spatial_activity(guard,difference,foreground,*,mode,warming_up=False):
    if mode not in MODES:raise ValueError('invalid spatial activity mode')
    diagnostic={'mode':mode,'state':'baseline','applied':False}
    if mode=='legacy':return guard,diagnostic
    if warming_up or guard_uncertain(guard):
        diagnostic['reason']='background_warmup' if warming_up else 'guard_unavailable_or_disturbed'
        return guard,diagnostic
    if (not isinstance(difference,np.ndarray) or difference.dtype!=np.uint8 or difference.ndim!=2
            or not isinstance(foreground,np.ndarray) or foreground.dtype!=np.uint8 or foreground.ndim!=2
            or min(difference.shape)<8 or min(foreground.shape)<8
            or max(difference.shape)>2048 or max(foreground.shape)>2048
            or not np.isin(difference,[0,1]).all() or not np.isin(foreground,[0,127,255]).all()):
        diagnostic['reason']='foreground_geometry_or_mask_unavailable'
        return guard,diagnostic
    support=(foreground==255) if mode=='foreground' else (foreground!=0)
    support=cv2.resize(support.astype(np.uint8),(difference.shape[1],difference.shape[0]),interpolation=cv2.INTER_NEAREST)
    # A one-pixel margin protects edge displacement and small rounding changes
    # between the existing 3x3 guard and the background model's 5x5 smoothing.
    support=cv2.dilate(support,np.ones((3,3),np.uint8))
    supported=np.bitwise_and(difference,support)
    if mode=='shadow_protected':
        # Retain whole observed components once supported. Pixel cutting can
        # fragment one moving edge into many regions and make a previously
        # complete activity map incomplete, which would retain parked targets.
        count,labels,_,_=cv2.connectedComponentsWithStats(difference,8)
        votes=np.bincount(labels[supported!=0],minlength=count)
        accepted=votes>0;accepted[0]=False
        aligned=accepted[labels].astype(np.uint8)
    else:aligned=supported
    effective=measure_motion(aligned,guard['illumination_shift'],include_regions=True)
    # Global disturbances must remain visible even after localized filtering.
    effective['changed_fraction']=guard['changed_fraction']
    diagnostic.update(state='spatially_measured',applied=True,changed_pixels=int(difference.sum()),
        supported_pixels=int(supported.sum()),retained_pixels=int(aligned.sum()),foreground_pixels=int(np.count_nonzero(foreground==255)),
        shadow_pixels=int(np.count_nonzero(foreground==127)))
    return effective,diagnostic
