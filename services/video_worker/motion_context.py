"""Bounded 5 Hz grayscale witnesses, atomically stored with a selected JPEG.

Only selected frames cause PNG encoding. No detector, model, network or routing.
"""
from collections import deque
import hashlib
import json
import math
import re
import struct
import uuid

import cv2
import numpy as np

MAGIC=b'MCTX1\0'
MAX_HEADER_BYTES=8192
MAX_PNG_BYTES=128*1024
MAX_CONTEXT_BYTES=544*1024
ROLES={'near_200ms':200000,'near_600ms':600000,'long_3000ms':3000000}


class ContextError(ValueError):pass


def motion_witness_usable(quality):
    """Eligibility to measure flow, never permission to declare a scene empty.

    Low median brightness alone does not erase textured motion evidence. The
    observer still requires distributed, forward/backward-consistent features.
    """
    if not isinstance(quality,dict):return False
    contrast,glare=quality.get('contrast_p90_p10'),quality.get('glare_fraction')
    return (type(contrast) in {int,float} and type(glare) in {int,float}
            and math.isfinite(contrast) and math.isfinite(glare)
            and contrast>=12 and 0<=glare<.25)


def _sha(data):return hashlib.sha256(data).hexdigest()


def _pack(header,images):
    encoded=json.dumps(header,sort_keys=True,separators=(',',':'),allow_nan=False).encode()
    raw=MAGIC+struct.pack('>I',len(encoded))+encoded+b''.join(images)
    if len(encoded)>MAX_HEADER_BYTES or len(raw)>MAX_CONTEXT_BYTES:raise ContextError('context_size_limit')
    return raw


def read_context(raw,*,decode=False):
    if not isinstance(raw,bytes) or not len(MAGIC)+4<=len(raw)<=MAX_CONTEXT_BYTES or not raw.startswith(MAGIC):
        raise ContextError('invalid_context_envelope')
    size=struct.unpack('>I',raw[len(MAGIC):len(MAGIC)+4])[0];start=len(MAGIC)+4
    if not 1<=size<=MAX_HEADER_BYTES or start+size>len(raw):raise ContextError('invalid_context_header_size')
    try:header=json.loads(raw[start:start+size])
    except (ValueError,UnicodeDecodeError):raise ContextError('invalid_context_header') from None
    fields={'schema','parent_frame_id','parent_jpeg_sha256','selected_width','selected_height','source','context_generation','frames','missing_roles'}
    if not isinstance(header,dict) or set(header)!=fields or header['schema']!='short-motion-context-v1':raise ContextError('invalid_context_schema')
    if not isinstance(header['parent_jpeg_sha256'],str) or not re.fullmatch('[0-9a-f]{64}',header['parent_jpeg_sha256']):raise ContextError('invalid_parent_digest')
    if header['parent_frame_id'] is not None:
        try:
            if str(uuid.UUID(header['parent_frame_id']))!=header['parent_frame_id']:raise ValueError()
        except (ValueError,TypeError,AttributeError):raise ContextError('invalid_parent_identity') from None
    if any(type(header[k]) is not int or not 1<=header[k]<=8192 for k in ['selected_width','selected_height']):raise ContextError('invalid_selected_geometry')
    source=header['source']
    expected={'camera_id','scene_run_id','media_epoch','relative_media_ms','media_offset_ms','source_type','source_reference'}
    if (not isinstance(source,dict) or set(source)!=expected or any(not isinstance(source[k],str) or not 1<=len(source[k])<=150 for k in ['camera_id','scene_run_id','source_reference'])
            or not isinstance(source['source_type'],str) or source['source_type'] not in {'archive','rtsp'} or type(source['media_epoch']) is not int or source['media_epoch']<0
            or type(source['relative_media_ms']) is not int or source['relative_media_ms']<0
            or source['media_offset_ms'] is not None and (type(source['media_offset_ms']) is not int or source['media_offset_ms']<0)
            or type(header['context_generation']) is not int or header['context_generation']<0):raise ContextError('invalid_context_source')
    frames=header['frames'];missing=header['missing_roles']
    if (not isinstance(frames,list) or not 1<=len(frames)<=4 or not isinstance(missing,list) or any(not isinstance(v,str) for v in missing)
            or len(set(missing))!=len(missing) or not set(missing)<=set(ROLES)):
        raise ContextError('invalid_context_frames')
    offset=start+size;images=[];decoded=[];last_time=None;roles=[];shape=None
    for row in frames:
        if not isinstance(row,dict) or set(row)!={'role','pts_us','relative_us','width','height','usable','png_bytes','png_sha256','pixels_sha256'}:raise ContextError('invalid_context_frame_header')
        if not isinstance(row['role'],str) or row['role'] not in {*ROLES,'current'} or row['role'] in roles:raise ContextError('invalid_context_roles')
        if (type(row['pts_us']) is not int or abs(row['pts_us'])>=2**63 or type(row['relative_us']) is not int or row['relative_us']<0 or last_time is not None and row['pts_us']<=last_time
                or any(type(row[k]) is not int or not 2<=row[k]<=320 for k in ['width','height'])
                or type(row['usable']) is not bool or type(row['png_bytes']) is not int or not 1<=row['png_bytes']<=MAX_PNG_BYTES):
            raise ContextError('invalid_context_clock_or_geometry')
        if any(not isinstance(row[key],str) or not re.fullmatch('[0-9a-f]{64}',row[key]) for key in ['png_sha256','pixels_sha256']):
            raise ContextError('invalid_context_image_digest')
        current_shape=(row['height'],row['width'])
        if shape is not None and shape!=current_shape:raise ContextError('context_geometry_changed')
        shape=current_shape;last_time=row['pts_us'];roles.append(row['role'])
        data=raw[offset:offset+row['png_bytes']];offset+=row['png_bytes']
        if len(data)!=row['png_bytes'] or _sha(data)!=row['png_sha256']:raise ContextError('context_image_integrity')
        # Bound decompression before passing a PNG to OpenCV.
        if (len(data)<29 or data[:8]!=b'\x89PNG\r\n\x1a\n' or data[8:16]!=b'\0\0\0\rIHDR'
                or struct.unpack('>II',data[16:24])!=(row['width'],row['height']) or data[24:26]!=b'\x08\x00'):
            raise ContextError('invalid_grayscale_png_geometry')
        images.append(data)
        if decode:
            image=cv2.imdecode(np.frombuffer(data,np.uint8),cv2.IMREAD_GRAYSCALE)
            if image is None or image.shape!=current_shape or _sha(image.tobytes())!=row['pixels_sha256']:raise ContextError('context_pixel_integrity')
            decoded.append(image)
    if offset!=len(raw) or roles[-1]!='current' or set(roles[:-1])!=set(ROLES)-set(missing):raise ContextError('context_length_or_role_conflict')
    current=frames[-1]
    if abs(round(current['relative_us']/1000)-source['relative_media_ms'])>1:raise ContextError('context_parent_clock_conflict')
    for row in frames[:-1]:
        if abs(current['pts_us']-row['pts_us']-ROLES[row['role']])>100000:raise ContextError('context_witness_age_conflict')
        if abs((current['relative_us']-row['relative_us'])-(current['pts_us']-row['pts_us']))>2:raise ContextError('context_relative_clock_conflict')
    return header,images,decoded


def validate_binding(raw,frame_id,jpeg_sha256,metadata,*,allow_unbound=False,decode=False):
    header,images,decoded=read_context(raw,decode=decode)
    if header['parent_frame_id']!=frame_id and not (allow_unbound and header['parent_frame_id'] is None):raise ContextError('context_frame_binding_conflict')
    if header['parent_jpeg_sha256']!=jpeg_sha256:raise ContextError('context_jpeg_binding_conflict')
    for key in ['camera_id','scene_run_id','media_epoch','relative_media_ms']:
        if header['source'][key]!=metadata[key]:raise ContextError('context_source_binding_conflict')
    for key in ['media_offset_ms','source_type','source_reference']:
        if key in metadata and header['source'][key]!=metadata[key]:raise ContextError('context_source_binding_conflict')
    return header,images,decoded


def bind_context(raw,frame_id,jpeg_sha256,metadata):
    header,images,_=validate_binding(raw,frame_id,jpeg_sha256,metadata,allow_unbound=True)
    header={**header,'parent_frame_id':frame_id}
    bound=_pack(header,images)
    validate_binding(bound,frame_id,jpeg_sha256,metadata)
    return bound


class MotionContextRing:
    def __init__(self):self.frames=deque(maxlen=18);self.context=None;self.generation=0

    def clear(self):self.frames.clear();self.context=None;self.generation+=1

    def observe(self,image,pts,relative_seconds,context,*,usable=True):
        if not math.isfinite(pts) or not math.isfinite(relative_seconds) or relative_seconds<0:raise ContextError('invalid_probe_clock')
        now=round(pts*1000000);relative=round(relative_seconds*1000000)
        if self.context!=context or self.frames and not 0<now-self.frames[-1]['pts_us']<=350000:self.clear()
        self.context=context
        scale=min(1.,320/max(image.shape[:2]));size=(max(2,round(image.shape[1]*scale)),max(2,round(image.shape[0]*scale)))
        gray=cv2.cvtColor(cv2.resize(image,size),cv2.COLOR_BGR2GRAY) if image.ndim==3 else cv2.resize(image,size)
        if self.frames and gray.shape!=self.frames[-1]['image'].shape:self.clear();self.context=context
        self.frames.append({'image':gray.copy(),'pts_us':now,'relative_us':relative,'usable':bool(usable)})

    def snapshot(self,jpeg_sha256,selected_width,selected_height,source):
        if not self.frames:raise ContextError('context_history_missing')
        current=self.frames[-1];chosen=[];missing=[]
        for role,age in ROLES.items():
            candidates=[row for row in self.frames if row['pts_us']<current['pts_us'] and abs(current['pts_us']-row['pts_us']-age)<=100000]
            if not candidates:missing.append(role);continue
            row=min(candidates,key=lambda value:abs(current['pts_us']-value['pts_us']-age));chosen.append((role,row))
        chosen.sort(key=lambda value:value[1]['pts_us']);chosen.append(('current',current));rows=[];images=[]
        for role,row in chosen:
            ok,encoded=cv2.imencode('.png',row['image'],[cv2.IMWRITE_PNG_COMPRESSION,1])
            if not ok or len(encoded)>MAX_PNG_BYTES:raise ContextError('context_encoding_unavailable')
            raw=encoded.tobytes();images.append(raw)
            rows.append({'role':role,'pts_us':row['pts_us'],'relative_us':row['relative_us'],'usable':row['usable'],
                'width':row['image'].shape[1],'height':row['image'].shape[0],'png_bytes':len(raw),
                'png_sha256':_sha(raw),'pixels_sha256':_sha(row['image'].tobytes())})
        header={'schema':'short-motion-context-v1','parent_frame_id':None,'parent_jpeg_sha256':jpeg_sha256,
            'selected_width':selected_width,'selected_height':selected_height,'source':dict(source),
            'context_generation':self.generation,'frames':rows,'missing_roles':missing}
        raw=_pack(header,images);read_context(raw)
        return raw
