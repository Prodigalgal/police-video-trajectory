"""Read-only validation of an operator-installed, controlled ReID ONNX asset.

This does not download weights, change active bindings, or enable production.
"""
import argparse
import hashlib
import json
from pathlib import Path

from .catalog import Definition
from .encoder import Encoder


def inspect_profile(model,profile,target_type,dimensions,adapter='onnx_reid_v1'):
    path=Path(model)
    if path.is_symlink() or not path.is_file() or not 0<path.stat().st_size<=256*1024*1024:
        raise ValueError('ReID 模型文件无效或超过大小限制')
    path=path.resolve(strict=True);digest=hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda:source.read(1024*1024),b''):digest.update(block)
    native=adapter=='onnx_osnet_v1'
    preprocessing=('roi_resize_256x128_bgr_v1' if target_type=='person' else 'roi_resize_208x208_rgb_v1') if native else ('roi_resize_256x128_rgb_v1' if target_type=='person' else 'roi_resize_256x256_rgb_v1')
    definition=Definition(profile=profile,adapter=adapter,model_file=path.name,
        model_sha256=digest.hexdigest(),dimensions=dimensions,normalization='raw_0_255' if native else 'imagenet',output='embedding',
        preprocessing=preprocessing,
        target_types=[target_type])
    Encoder(path.parent,definition)
    return {'definition':definition.model_dump(),'profile_sha256':definition.sha256,
        'interface_verified':True,'retrieval_accuracy_verified':False,'active_binding_changed':False}


def main():
    parser=argparse.ArgumentParser(description='只读校验专用人车 ReID ONNX，并生成不可变特征定义')
    parser.add_argument('--model',required=True)
    parser.add_argument('--profile',required=True)
    parser.add_argument('--target-type',required=True,choices=['person','vehicle'])
    parser.add_argument('--dimensions',type=int,required=True,choices=[128,256,384,512,768,1024])
    parser.add_argument('--adapter',choices=['onnx_reid_v1','onnx_osnet_v1'],default='onnx_reid_v1')
    args=parser.parse_args()
    try:result=inspect_profile(args.model,args.profile,args.target_type,args.dimensions,args.adapter)
    except Exception as failure:parser.exit(2,'ReID 模型校验失败：'+type(failure).__name__+'\n')
    print(json.dumps(result,ensure_ascii=False,allow_nan=False,indent=2))


if __name__=='__main__':main()
