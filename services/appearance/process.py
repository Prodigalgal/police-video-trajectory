"""Disposable native encoder. The owning runner enforces time and cancellation."""
import json
import struct
import sys
from pydantic import BaseModel,ConfigDict,Field


class EncodeRequest(BaseModel):
    model_config=ConfigDict(extra='forbid')
    bbox_xyxy:list[float]=Field(min_length=4,max_length=4)
    image_sha256:str=Field(pattern=r'^[a-f0-9]{64}$')


def main():
    size=struct.unpack('>I',sys.stdin.buffer.read(4))[0]
    if not 0<size<=65536:raise ValueError('metadata exceeds bound')
    payload=json.loads(sys.stdin.buffer.read(size));raw=sys.stdin.buffer.read(8*1024*1024+1)
    if set(payload)!={'root','definition','request'}:raise ValueError('invalid invocation')
    from .catalog import Definition
    from .encoder import Encoder
    definition=Definition.model_validate(payload['definition']);request=EncodeRequest.model_validate(payload['request'])
    result=Encoder(payload['root'],definition).encode(raw,request.bbox_xyxy,request.image_sha256)
    sys.stdout.buffer.write(json.dumps(result,allow_nan=False,separators=(',',':')).encode());sys.stdout.buffer.flush()


if __name__=='__main__':
    try:main()
    except Exception as error:sys.stderr.write('appearance_process_failed:'+type(error).__name__+'\n');sys.exit(1)
