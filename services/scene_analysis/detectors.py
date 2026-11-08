"""Local person/vehicle detection and presence probes.

Preprocessing follows the public OpenCV Zoo NanoDet and YOLOX model examples.
Their model files and examples are Apache-2.0; see docs/14-video-pipeline.md.
"""
import cv2
import numpy as np
from dataclasses import dataclass, asdict


TARGET_CLASSES = (0, 1, 2, 3, 5, 7)  # COCO: person, bicycle, car, motorcycle, bus, truck
CLASS_NAMES = {0: 'person', 1: 'bicycle', 2: 'car', 3: 'motorcycle', 5: 'bus', 7: 'truck'}


@dataclass(frozen=True)
class Detection:
    object_type: str
    class_name: str
    confidence: float
    bbox_xyxy: tuple[float, float, float, float]
    bbox_normalized: tuple[float, float, float, float]

    def to_dict(self):
        return asdict(self)


def _nms(boxes, scores, labels, threshold, limit):
    """Class-aware NMS: a person must not suppress an overlapping vehicle."""
    order = np.argsort(-scores, kind='stable')
    keep = []
    while len(order) and len(keep) < limit:
        current = int(order[0])
        keep.append(current)
        remaining = order[1:]
        lo = np.maximum(boxes[current, :2], boxes[remaining, :2])
        hi = np.minimum(boxes[current, 2:], boxes[remaining, 2:])
        overlap = np.prod(np.maximum(0, hi - lo), axis=1)
        area = np.prod(boxes[:, 2:] - boxes[:, :2], axis=1)
        iou = overlap / np.maximum(area[current] + area[remaining] - overlap, 1e-9)
        order = remaining[(labels[remaining] != labels[current]) | (iou <= threshold)]
    return keep


def _nanodet_predictions(outputs, side=416):
    """DFL regression decoding from OpenCV Zoo's pinned NanoDet export."""
    scores, boxes = [], []
    for stride in (8, 16, 32, 64):
        grid_side = (side + stride - 1) // stride
        rows = grid_side ** 2
        classifiers = [np.asarray(v).reshape(-1, 80) for v in outputs
                       if v.shape[-1] == 80 and v.size // 80 == rows]
        regressors = [np.asarray(v).reshape(-1, 4, 8) for v in outputs
                      if v.shape[-1] == 32 and v.size // 32 == rows]
        if not classifiers and not regressors:
            continue
        if len(classifiers) != 1 or len(regressors) != 1:
            raise ValueError('unmatched NanoDet heads')
        logits = regressors[0]
        weights = np.exp(logits - logits.max(axis=-1, keepdims=True))
        distances = (weights / weights.sum(axis=-1, keepdims=True)) @ np.arange(8, dtype=np.float32)
        distances *= stride
        y, x = np.mgrid[:grid_side, :grid_side]
        centers = np.column_stack((x.ravel(), y.ravel())) * stride + .5 * (stride - 1)
        boxes.append(np.column_stack((centers - distances[:, :2], centers + distances[:, 2:])))
        scores.append(classifiers[0])
    if len(scores) not in {3, 4}:
        raise ValueError('unexpected NanoDet heads')
    return np.concatenate(boxes), np.concatenate(scores)


def _yolox_predictions(values, side=640):
    grids, scales = [], []
    for stride in (8, 16, 32):
        y, x = np.mgrid[:side // stride, :side // stride]
        grids.append(np.column_stack((x.ravel(), y.ravel())))
        scales.append(np.full((x.size, 1), stride))
    grid, scale = np.concatenate(grids), np.concatenate(scales)
    if len(values) != len(grid):
        raise ValueError('unexpected YOLOX grid')
    center = (values[:, :2] + grid) * scale
    size = np.exp(np.clip(values[:, 2:4], -20, 20)) * scale
    return np.column_stack((center - size / 2, center + size / 2)), values[:, 4:5] * values[:, 5:]


def _letterbox(image, side, fill, center):
    height, width = image.shape[:2]
    if height <= 0 or width <= 0:
        raise ValueError("empty image")
    ratio = min(side / width, side / height)
    new_width, new_height = max(1, int(width * ratio)), max(1, int(height * ratio))
    resized = cv2.resize(image, (new_width, new_height), interpolation=cv2.INTER_AREA if center else cv2.INTER_LINEAR)
    canvas = np.full((side, side, 3), fill, dtype=np.uint8)
    x = (side - new_width) // 2 if center else 0
    y = (side - new_height) // 2 if center else 0
    canvas[y:y + new_height, x:x + new_width] = resized
    return canvas


class SceneProbe:
    def __init__(self, kind, model_path):
        if kind not in {"nanodet", "yolox"}:
            raise ValueError("unsupported detector")
        self.kind = kind
        self.output_shapes = []
        self.net = cv2.dnn.readNetFromONNX(str(model_path))

    def _forward(self, image):
        if image is None or image.ndim != 3 or image.shape[2] != 3:
            raise ValueError("invalid image")
        if self.kind == "nanodet":
            canvas = _letterbox(image, 416, 0, True).astype(np.float32)
            canvas = (canvas - np.array([103.53, 116.28, 123.675], dtype=np.float32)) / np.array([57.375, 57.12, 58.395], dtype=np.float32)
            self.net.setInput(cv2.dnn.blobFromImage(canvas))
            outputs = self.net.forward(self.net.getUnconnectedOutLayersNames())
            self.output_shapes = [list(output.shape) for output in outputs]
            classifiers = [np.asarray(output) for output in outputs if output.ndim >= 2 and output.shape[-1] == 80]
            # The released graph exposes three score levels (8/16/32); some exports add stride 64.
            if len(classifiers) not in {3, 4}:
                raise ValueError("unexpected NanoDet outputs")
            scores = np.concatenate([v.reshape(-1, 80) for v in classifiers])
        else:
            canvas = _letterbox(image, 640, 114, False).astype(np.float32)
            self.net.setInput(np.transpose(canvas, (2, 0, 1))[None])
            output = np.asarray(self.net.forward(self.net.getUnconnectedOutLayersNames())[0])
            self.output_shapes = [list(output.shape)]
            if output.ndim != 3 or output.shape[0] != 1 or output.shape[-1] != 85:
                raise ValueError("unexpected YOLOX output")
            values = output[0]
            scores = values[:, 4:5] * values[:, 5:]
        score = float(np.max(scores[:, TARGET_CLASSES]))
        if not np.isfinite(score) or score < 0 or score > 1.001:
            raise ValueError("invalid detector score")
        return outputs if self.kind == 'nanodet' else [output], min(1.0, score)

    def max_target_score(self, image):
        return self._forward(image)[1]

    def detect(self, image, threshold=.35, nms_threshold=.5, limit=100):
        if not .01 <= threshold <= 1 or not 0 < nms_threshold < 1 or not 1 <= limit <= 100:
            raise ValueError('invalid detection policy')
        outputs, presence_score = self._forward(image)
        if self.kind == 'nanodet':
            boxes, scores = _nanodet_predictions(outputs)
            side, center = 416, True
        else:
            boxes, scores = _yolox_predictions(outputs[0][0])
            side, center = 640, False
        # Best COCO class must itself be a target; do not relabel another class.
        labels = np.argmax(scores, axis=1)
        confidence = scores[np.arange(len(scores)), labels]
        valid = np.isin(labels, TARGET_CLASSES) & (confidence >= threshold)
        boxes, confidence, labels = boxes[valid], confidence[valid], labels[valid]
        height, width = image.shape[:2]
        ratio = min(side / width, side / height)
        new_width, new_height = max(1, int(width * ratio)), max(1, int(height * ratio))
        left = (side - new_width) // 2 if center else 0
        top = (side - new_height) // 2 if center else 0
        boxes[:, [0, 2]] = np.clip((boxes[:, [0, 2]] - left) * width / new_width, 0, width)
        boxes[:, [1, 3]] = np.clip((boxes[:, [1, 3]] - top) * height / new_height, 0, height)
        valid = np.isfinite(boxes).all(axis=1) & np.isfinite(confidence) & (boxes[:, 2] > boxes[:, 0]) & (boxes[:, 3] > boxes[:, 1])
        boxes, confidence, labels = boxes[valid], confidence[valid], labels[valid]
        kept = _nms(boxes, confidence, labels, nms_threshold, limit)
        targets = [Detection('person' if int(labels[i]) == 0 else 'vehicle', CLASS_NAMES[int(labels[i])],
                             round(float(confidence[i]), 6), tuple(round(float(v), 3) for v in boxes[i]),
                             tuple(round(float(v), 6) for v in boxes[i] / [width, height, width, height])) for i in kept]
        return {'presence_score': presence_score, 'image_width': width, 'image_height': height,
                'detector': self.kind, 'threshold': threshold, 'targets': [v.to_dict() for v in targets]}
