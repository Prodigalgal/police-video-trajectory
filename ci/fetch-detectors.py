"""Pin both compared local detector weights and retain each model's license."""
import hashlib
import json
from pathlib import Path
import urllib.request

root=Path('/models');root.mkdir(parents=True,exist_ok=True);manifest={}
models={'nanodet':('object_detection_nanodet','object_detection_nanodet_2022nov.onnx','4b82da9944b88577175ee23a459dce2e26e6e4be573def65b1055dc2d9720186'),
        'yolox':('object_detection_yolox','object_detection_yolox_2022nov.onnx','c5c2d13e59ae883e6af3b45daea64af4833a4951c92d116ec270d9ddbe998063')}
for kind,(folder,filename,expected) in models.items():
    url='https://media.githubusercontent.com/media/opencv/opencv_zoo/main/models/'+folder+'/'+filename
    digest=hashlib.sha256();size=0;target=root/(kind+'.onnx')
    with urllib.request.urlopen(url,timeout=120) as source,target.open('wb') as output:
        while data:=source.read(1048576):
            size+=len(data)
            if size>64*1024*1024:raise RuntimeError('model file exceeded bound')
            digest.update(data);output.write(data)
    if digest.hexdigest()!=expected:raise RuntimeError('pinned model hash mismatch')
    license_url='https://raw.githubusercontent.com/opencv/opencv_zoo/main/models/'+folder+'/LICENSE'
    with urllib.request.urlopen(license_url,timeout=60) as source:license_data=source.read(65537)
    if len(license_data)>65536 or b'Apache License' not in license_data or b'Version 2.0' not in license_data:
        raise RuntimeError('model license verification failed')
    (root/('LICENSE.'+kind)).write_bytes(license_data)
    manifest[kind]={'source':url,'sha256':expected,'bytes':size,'license_source':license_url,
                    'license_sha256':hashlib.sha256(license_data).hexdigest()}
(root/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('Two pinned detector weights and licenses verified.',flush=True)
