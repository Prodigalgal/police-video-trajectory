"""Immutable feature spaces behind a reloadable, revisioned active binding."""
import hashlib
import json
from pathlib import Path
from typing import Literal
from pydantic import BaseModel,ConfigDict,Field,model_validator


def canonical(value):
    return json.dumps(value,sort_keys=True,separators=(',',':'),ensure_ascii=False,allow_nan=False).encode()


class Definition(BaseModel):
    model_config=ConfigDict(extra='forbid',frozen=True)
    profile:str=Field(pattern=r'^[A-Za-z0-9_-]{1,80}$')
    adapter:Literal['onnx_crop_v1']='onnx_crop_v1'
    model_file:str=Field(pattern=r'^[A-Za-z0-9_-]+\.onnx$')
    model_sha256:str=Field(pattern=r'^[a-f0-9]{64}$')
    dimensions:Literal[384,512]
    normalization:Literal['imagenet','clip']
    output:Literal['cls_token','image_embeds']
    preprocessing:Literal['roi_letterbox_224_rgb_v1']='roi_letterbox_224_rgb_v1'

    @property
    def sha256(self):return hashlib.sha256(canonical(self.model_dump())).hexdigest()


class Snapshot(BaseModel):
    model_config=ConfigDict(extra='forbid',frozen=True)
    schema_version:Literal['appearance-catalog-v1']
    revision:int=Field(ge=1,le=9007199254740991,strict=True)
    active:str
    profiles:list[Definition]=Field(min_length=1,max_length=8)

    @model_validator(mode='after')
    def bindings(self):
        ids=[p.profile for p in self.profiles]
        if len(set(ids))!=len(ids) or self.active not in ids:raise ValueError('invalid active appearance binding')
        return self


class Catalog:
    def __init__(self,path):self.path=Path(path);self.revision=0;self.digest=None;self.definitions={}

    def current(self):
        with self.path.open('rb') as source:raw=source.read(32769)
        if len(raw)>32768:raise ValueError('appearance catalog exceeds bound')
        snapshot=Snapshot.model_validate_json(raw);digest=hashlib.sha256(canonical(snapshot.model_dump())).hexdigest()
        if snapshot.revision<self.revision or snapshot.revision==self.revision and self.digest!=digest:raise ValueError('appearance catalog revision changed')
        for definition in snapshot.profiles:
            if definition.profile in self.definitions and self.definitions[definition.profile]!=definition.sha256:raise ValueError('appearance profile is immutable')
        self.definitions.update({p.profile:p.sha256 for p in snapshot.profiles});self.revision=snapshot.revision;self.digest=digest
        return snapshot

    def definition(self,profile=None):
        snapshot=self.current();selected=profile or snapshot.active
        return next((p for p in snapshot.profiles if p.profile==selected),None)


class InstalledModels:
    """Advertise installed immutable assets, not merely declared model names."""
    def __init__(self,root):self.root=Path(root).resolve(strict=True);self.checked={}

    def available(self,definitions):
        installed=[]
        for definition in definitions:
            path=self.root/definition.model_file
            try:
                if path.is_symlink() or not path.is_file():continue
                stat=path.stat();signature=(stat.st_size,stat.st_mtime_ns,stat.st_ctime_ns,stat.st_ino)
                if not 0<stat.st_size<=256*1024*1024:continue
                previous=self.checked.get(definition.model_file)
                if previous is not None and previous[0]==signature:digest=previous[1]
                else:
                    digest=hashlib.sha256()
                    with path.open('rb') as source:
                        for block in iter(lambda:source.read(1024*1024),b''):digest.update(block)
                    digest=digest.hexdigest();self.checked[definition.model_file]=(signature,digest)
                if digest==definition.model_sha256:installed.append(definition)
            except OSError:continue
        return installed
