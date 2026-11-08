"""One bounded native invocation. No credentials, downloads or image files."""
import json
import os
import struct
import sys


def main():
    os.environ['OMP_NUM_THREADS']='1';os.environ['OPENBLAS_NUM_THREADS']='1'
    header=sys.stdin.buffer.read(4)
    if len(header)!=4:raise ValueError('process header missing')
    size=struct.unpack('>I',header)[0]
    if not 0<size<=65536:raise ValueError('process metadata exceeds bound')
    payload=json.loads(sys.stdin.buffer.read(size));image=sys.stdin.buffer.read(8*1024*1024+1)
    if set(payload)!={'root','definition','request'}:raise ValueError('invalid process invocation')
    from .catalog import ModelDefinition
    from .contracts import RecognitionRequest,validate_image
    definition=ModelDefinition.model_validate(payload['definition']);metadata=RecognitionRequest.model_validate(payload['request'])
    if definition.profile!=metadata.profile:raise ValueError('native profile differs')
    validate_image(metadata,image)
    import cv2
    import numpy as np
    from .plate_onnx import PlateOnnxEngine
    decoded=cv2.imdecode(np.frombuffer(image,np.uint8),cv2.IMREAD_COLOR|cv2.IMREAD_IGNORE_ORIENTATION)
    if decoded is None or (decoded.shape[1],decoded.shape[0])!=(metadata.image_width,metadata.image_height):raise ValueError('JPEG decode failed')
    # Installed adapters are explicit code, never module paths supplied by config.
    engine=PlateOnnxEngine(payload['root'],definition)
    result=engine.recognize(decoded,metadata)
    from services.video_worker.specialist_consumer import validate_result
    validate_result(metadata.job(),result['result'])
    raw=json.dumps(result,ensure_ascii=False,separators=(',',':'),allow_nan=False).encode()
    if len(raw)>192*1024:raise ValueError('native output exceeds bound')
    sys.stdout.buffer.write(raw);sys.stdout.buffer.flush()


if __name__=='__main__':
    try:main()
    except Exception as error:
        sys.stderr.write('native_process_failed:'+type(error).__name__+'\n');sys.exit(1)
