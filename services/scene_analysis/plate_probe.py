"""Detection-only plate adapter. No character model, text decoder or identity."""
import hashlib
from pathlib import Path
import threading

import cv2
import numpy as np


class OpenCvPlateProbe:
    adapter = 'opencv_plate_v1'

    def __init__(self, root, definition):
        root = Path(root).resolve(strict=True)
        path = (root / definition['model_file']).resolve(strict=True)
        if not path.is_relative_to(root) or not path.is_file() or not 0 < path.stat().st_size <= 128*1024*1024:
            raise ValueError('invalid plate detector artifact')
        with path.open('rb') as stream:
            self.sha256 = hashlib.file_digest(stream, 'sha256').hexdigest()
        if self.sha256 != definition['model_sha256']:
            raise ValueError('plate detector digest differs')
        self.net = cv2.dnn.readNetFromONNX(str(path))
        self.lock = threading.RLock()
        self.closed = False

    def locate(self, image):
        if not isinstance(image, np.ndarray) or image.dtype != np.uint8 or image.ndim != 3 or image.shape[2] != 3:
            raise ValueError('invalid plate detector image')
        height, width = image.shape[:2]
        if min(height, width) < 1 or height*width > 50_000_000:
            raise ValueError('invalid plate detector dimensions')
        ratio = min(640/width, 640/height)
        resized_width, resized_height = int(width*ratio), int(height*ratio)
        left, top = (640-resized_width)//2, (640-resized_height)//2
        resized = cv2.resize(image, (resized_width, resized_height))
        canvas = cv2.copyMakeBorder(resized, top, 640-resized_height-top,
                                   left, 640-resized_width-left, cv2.BORDER_CONSTANT, value=(114,114,114))
        with self.lock:
            if self.closed:
                raise ValueError('plate detector was retired')
            self.net.setInput(cv2.dnn.blobFromImage(canvas, 1/255, (640,640), swapRB=True))
            output = np.asarray(self.net.forward()).copy()
        if output.ndim != 3 or output.shape[0] != 1 or output.shape[2] != 16 or output.shape[1] > 100000:
            raise ValueError('unsupported plate detection tensor')
        detections = output[0]
        detections = detections[np.isfinite(detections).all(axis=1) & (detections[:,4] > .4)]
        if len(detections) > 5000:
            raise ValueError('plate detection candidate bound exceeded')
        candidates, invalid = [], 0
        for value in detections:
            # Match the pinned publisher adapter's two layout scores. This is
            # not a general YOLO decoder for arbitrary replacement weights.
            score = float(value[4] * max(value[13], value[14]))
            if not .4 <= score <= 1:
                continue
            cx,cy,w,h = map(float,value[:4])
            box=[(cx-w/2-left)/ratio,(cy-h/2-top)/ratio,(cx+w/2-left)/ratio,(cy+h/2-top)/ratio]
            box=[max(0,min(width,int(np.floor(box[0])))),max(0,min(height,int(np.floor(box[1])))),
                 max(0,min(width,int(np.ceil(box[2])))),max(0,min(height,int(np.ceil(box[3]))))]
            if box[0]>=box[2] or box[1]>=box[3]:
                invalid+=1;continue
            candidates.append({'bbox_xyxy':box,'localization_score':score,
                               'layout':'double' if value[14]>value[13] else 'single'})
        candidates.sort(key=lambda value:-value['localization_score'])
        kept=[]
        from .adaptive_crops import iou
        for candidate in candidates:
            if not any(iou(candidate['bbox_xyxy'], previous['bbox_xyxy'])>.5 for previous in kept):
                kept.append(candidate)
                if len(kept)==100:break
        return {'schema_version':'plate-regions-v1','adapter':self.adapter,'model_sha256':self.sha256,
                'state':'complete','regions':kept,'invalid_geometry_count':invalid,
                'character_recognition_performed':False}

    def close(self):
        with self.lock:
            self.closed = True
            self.net = None
