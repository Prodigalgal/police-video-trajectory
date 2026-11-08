"""SHA-pinned CPU encoding of one target crop. Query pixels never touch disk."""
import hashlib
import io
import math
from pathlib import Path
import time
import warnings
from PIL import Image,ImageOps
import numpy as np


MAX_BYTES=8*1024*1024


def decode_crop(raw,box,expected_sha=None):
    if not 0<len(raw)<=MAX_BYTES or expected_sha is not None and hashlib.sha256(raw).hexdigest()!=expected_sha:
        raise ValueError('image digest or length differs')
    with warnings.catch_warnings():
        warnings.simplefilter('error',Image.DecompressionBombWarning)
        with Image.open(io.BytesIO(raw)) as source:
            if source.format not in {'JPEG','PNG'} or getattr(source,'n_frames',1)!=1:raise ValueError('only single JPEG or PNG supported')
            width,height=source.size
            if max(width,height)>20000 or width*height>16_777_216:raise ValueError('image dimensions exceed bound')
            image=ImageOps.exif_transpose(source).convert('RGB')
    width,height=image.size
    if len(box)!=4 or any(isinstance(x,bool) or not isinstance(x,(int,float)) or not math.isfinite(x) for x in box):raise ValueError('invalid target box')
    left,top,right,bottom=box
    if not (0<=left<right<=width and 0<=top<bottom<=height) or right-left<12 or bottom-top<12:raise ValueError('target box is too small or outside image')
    bounds=(math.floor(left),math.floor(top),math.ceil(right),math.ceil(bottom))
    return image.crop(bounds),(width,height)


def pixels(crop,normalization):
    mean,std=([.485,.456,.406],[.229,.224,.225]) if normalization=='imagenet' else ([.48145466,.4578275,.40821073],[.26862954,.26130258,.27577711])
    width,height=crop.size;scale=min(224/width,224/height)
    resized=crop.resize((max(1,round(width*scale)),max(1,round(height*scale))),Image.Resampling.BICUBIC)
    padded=Image.new('RGB',(224,224),tuple(round(x*255) for x in mean));padded.paste(resized,((224-resized.width)//2,(224-resized.height)//2))
    array=np.asarray(padded,dtype=np.float32)/255
    return np.ascontiguousarray(((array-np.array(mean,np.float32))/np.array(std,np.float32)).transpose(2,0,1)[None])


class Encoder:
    def __init__(self,root,definition):
        root=Path(root).resolve(strict=True);path=root/definition.model_file
        if path.is_symlink() or path.parent!=root or not path.is_file():raise ValueError('model asset unavailable')
        digest=hashlib.sha256()
        with path.open('rb') as source:
            for block in iter(lambda:source.read(1024*1024),b''):digest.update(block)
        if digest.hexdigest()!=definition.model_sha256:raise ValueError('model asset digest differs')
        import onnxruntime as ort
        options=ort.SessionOptions();options.intra_op_num_threads=1;options.inter_op_num_threads=1
        self.session=ort.InferenceSession(str(path),sess_options=options,providers=['CPUExecutionProvider']);self.definition=definition
        self.output='last_hidden_state' if definition.output=='cls_token' else 'image_embeds'
        if [x.name for x in self.session.get_inputs()]!=['pixel_values'] or self.output not in {x.name for x in self.session.get_outputs()}:raise ValueError('appearance model interface differs')

    def encode_crop(self,crop):
        tensor=pixels(crop,self.definition.normalization);started=time.perf_counter()
        output=self.session.run([self.output],{'pixel_values':tensor})[0]
        vector=np.asarray(output[0,0] if self.definition.output=='cls_token' else output[0],np.float32)
        if vector.shape!=(self.definition.dimensions,) or not np.isfinite(vector).all():raise ValueError('appearance vector invalid')
        norm=float(np.linalg.norm(vector))
        if norm<1e-8:raise ValueError('appearance vector is empty')
        return (vector/norm).tolist(),round((time.perf_counter()-started)*1000,3)

    def encode(self,raw,box,sha=None):
        crop,size=decode_crop(raw,box,sha);vector,millis=self.encode_crop(crop)
        return {'result':{'vector':vector,'profile':self.definition.profile,'profile_sha256':self.definition.sha256,'image_sha256':hashlib.sha256(raw).hexdigest(),'image_width':size[0],'image_height':size[1],'bbox_xyxy':box},'diagnostics':{'inference_ms':millis,'provider':'CPUExecutionProvider'}}
