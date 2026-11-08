"""The existing specialist wire contract and bounded JPEG header validation."""
import hashlib
from typing import Literal

from pydantic import BaseModel,ConfigDict,Field,model_validator

from services.video_worker.specialist_consumer import validate_job


class RecognitionRequest(BaseModel):
    model_config=ConfigDict(extra='forbid',strict=True)
    schema_version:Literal['specialist-request-v1']
    job_id:str
    input_sha256:str
    input_id:str
    input_manifest_sha256:str
    frame_id:str
    camera_id:str
    evidence_sha256:str
    image_width:int
    image_height:int
    operation:Literal['license_plate']
    profile:str

    @property
    def media_type(self):return 'image/jpeg'

    def job(self):
        descriptor=self.model_dump(exclude={'job_id','input_sha256'})
        descriptor['schema_version']='specialist-input-v1'
        return {'jobId':self.job_id,'generation':1,'inputSha256':self.input_sha256,'descriptor':descriptor}

    @model_validator(mode='after')
    def source_contract(self):
        try:validate_job(self.job())
        except (ValueError,TypeError,KeyError):raise ValueError('invalid specialist source binding') from None
        return self


def jpeg_dimensions(image):
    if not image.startswith(b'\xff\xd8\xff') or not image.endswith(b'\xff\xd9'):raise ValueError('complete JPEG required')
    cursor=2
    for _ in range(4096):
        if cursor>=len(image) or image[cursor]!=255:raise ValueError('invalid JPEG marker')
        while cursor<len(image) and image[cursor]==255:cursor+=1
        if cursor>=len(image):break
        marker=image[cursor];cursor+=1
        if marker in {0xd9,0xda}:break
        if marker in range(0xd0,0xd8) or marker==0x01:continue
        if cursor+2>len(image):break
        length=int.from_bytes(image[cursor:cursor+2],'big')
        if length<2 or cursor+length>len(image):break
        if marker in {0xc0,0xc1,0xc2}:
            if length<8 or image[cursor+2]!=8:raise ValueError('unsupported JPEG frame')
            height=int.from_bytes(image[cursor+3:cursor+5],'big');width=int.from_bytes(image[cursor+5:cursor+7],'big')
            if min(width,height)<1 or max(width,height)>20000 or width*height>12000000:raise ValueError('JPEG dimensions exceed service bound')
            return width,height
        cursor+=length
    raise ValueError('JPEG frame header missing')


def validate_image(metadata,image):
    if not 0<len(image)<=8*1024*1024 or hashlib.sha256(image).hexdigest()!=metadata.evidence_sha256:
        raise ValueError('image digest differs')
    if jpeg_dimensions(image)!=(metadata.image_width,metadata.image_height):raise ValueError('image dimensions differ')
