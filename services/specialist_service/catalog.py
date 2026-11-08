"""Content-addressed native models; operator config changes never retarget a frozen job."""
import hashlib
import json
import logging
import os
from pathlib import Path
import stat
import threading
from typing import Literal

from pydantic import BaseModel,ConfigDict,Field,model_validator


def canonical(value):
    return json.dumps(value,sort_keys=True,ensure_ascii=False,separators=(',',':'),allow_nan=False).encode()


class Artifact(BaseModel):
    model_config=ConfigDict(extra='forbid',strict=True,frozen=True)
    file:str=Field(pattern=r'^[A-Za-z0-9][A-Za-z0-9_.-]{0,159}$')
    sha256:str=Field(pattern=r'^[a-f0-9]{64}$')


class PlatePolicy(BaseModel):
    model_config=ConfigDict(extra='forbid',strict=True,frozen=True)
    extra_regions:int=Field(default=0,ge=0,le=4)
    minimum_width:int=Field(default=64,ge=16,le=512)
    minimum_height:int=Field(default=16,ge=6,le=128)
    minimum_character_probability:float=Field(default=.8,ge=0,le=1)
    minimum_character_margin:float=Field(default=.25,ge=0,le=1)


def model_identity(definition):
    identity={key:value for key,value in definition.items() if key not in {'model_id','active'}}
    return 'plate-'+hashlib.sha256(canonical(identity)).hexdigest()


def artifact_bytes(root,artifact):
    root=Path(root).resolve(strict=True);path=root/artifact.file
    if path.is_symlink():raise ValueError('artifact cannot be a symbolic link')
    descriptor=os.open(path,os.O_RDONLY|getattr(os,'O_NOFOLLOW',0)|getattr(os,'O_BINARY',0))
    with os.fdopen(descriptor,'rb') as source:
        info=os.fstat(source.fileno())
        if not stat.S_ISREG(info.st_mode) or not 0<info.st_size<=128*1024*1024:raise ValueError('artifact exceeds bound')
        raw=source.read(128*1024*1024+1)
    if len(raw)>128*1024*1024 or hashlib.sha256(raw).hexdigest()!=artifact.sha256:raise ValueError('artifact digest differs')
    return raw


class ModelDefinition(BaseModel):
    model_config=ConfigDict(extra='forbid',strict=True,frozen=True)
    model_id:str=Field(pattern=r'^plate-[a-f0-9]{64}$')
    profile:str=Field(pattern=r'^[A-Za-z0-9_.-]{1,80}$')
    adapter:Literal['plate_onnx_v1']='plate_onnx_v1'
    active:bool=True
    detector:Artifact
    recognizer:Artifact
    policy:PlatePolicy=Field(default_factory=PlatePolicy)

    @model_validator(mode='after')
    def immutable_identity(self):
        if self.model_id!=model_identity(self.model_dump()):raise ValueError('model ID must bind artifacts and policy')
        return self


class CatalogDocument(BaseModel):
    model_config=ConfigDict(extra='forbid',strict=True,frozen=True)
    schema_version:Literal['specialist-service-catalog-v1']
    revision:int=Field(ge=1,le=9007199254740991)
    models:list[ModelDefinition]=Field(min_length=1,max_length=16)

    @model_validator(mode='after')
    def distinct_models(self):
        if len({model.model_id for model in self.models})!=len(self.models):raise ValueError('duplicate model ID')
        return self


class ModelUnavailable(ValueError):
    pass


class ModelCatalog:
    def __init__(self,path):
        self.path=Path(path);self.lock=threading.Lock();self.current=None;self.digest=None;self.last_error=None

    def snapshot(self):
        with self.lock:
            try:
                with self.path.open('rb') as source:raw=source.read(65537)
                if not 0<len(raw)<=65536:raise ValueError('catalog exceeds bound')
                document=CatalogDocument.model_validate_json(raw)
                digest=hashlib.sha256(canonical(document.model_dump())).hexdigest()
                if self.current and (document.revision<self.current.revision or document.revision==self.current.revision and digest!=self.digest):
                    raise ValueError('catalog revision was reused')
                self.current,self.digest,self.last_error=document,digest,None
            except (OSError,ValueError) as error:
                reason=type(error).__name__
                if self.last_error!=reason:logging.getLogger(__name__).warning('Native catalog rejected; retained last valid snapshot: %s',reason)
                self.last_error=reason
                if self.current is None:raise ModelUnavailable('catalog unavailable') from None
            return self.current

    def resolve(self,profile,model_id=None):
        document=self.snapshot()
        matches=[model for model in document.models if model.profile==profile and (model.model_id==model_id if model_id else True)]
        if len(matches)!=1 or not model_id and not matches[0].active:raise ModelUnavailable('requested model unavailable or ambiguous')
        return matches[0]
