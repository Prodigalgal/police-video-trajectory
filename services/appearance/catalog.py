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
    adapter:Literal['onnx_crop_v1','onnx_reid_v1','onnx_osnet_v1']='onnx_crop_v1'
    model_file:str=Field(pattern=r'^[A-Za-z0-9_-]+\.onnx$')
    model_sha256:str=Field(pattern=r'^[a-f0-9]{64}$')
    dimensions:Literal[128,256,384,512,768,1024]
    normalization:Literal['imagenet','clip','raw_0_255']
    output:Literal['cls_token','image_embeds','embedding']
    preprocessing:Literal['roi_letterbox_224_rgb_v1','roi_resize_256x128_rgb_v1','roi_resize_256x256_rgb_v1','roi_resize_256x128_bgr_v1','roi_resize_208x208_rgb_v1']='roi_letterbox_224_rgb_v1'
    target_types:list[Literal['person','vehicle']]|None=Field(default=None,min_length=1,max_length=2)

    @model_validator(mode='before')
    @classmethod
    def optional_contract(cls,value):
        if isinstance(value,dict) and 'target_types' in value and value['target_types'] is None:raise ValueError('target types must be omitted rather than null')
        return value

    def model_dump(self,**kwargs):
        # Optional extensions must not change pre-existing feature-space hashes.
        kwargs.setdefault('exclude_none',True)
        return super().model_dump(**kwargs)

    @model_validator(mode='after')
    def interface(self):
        if self.target_types is not None and len(set(self.target_types))!=len(self.target_types):raise ValueError('duplicate target types')
        if self.adapter=='onnx_crop_v1':
            if self.dimensions not in {384,512} or self.normalization not in {'imagenet','clip'} or self.output not in {'cls_token','image_embeds'} or self.preprocessing!='roi_letterbox_224_rgb_v1':raise ValueError('invalid general appearance interface')
        elif self.adapter=='onnx_reid_v1':
            if self.target_types is None or len(self.target_types)!=1 or self.normalization!='imagenet' or self.output!='embedding':raise ValueError('ReID profile requires one target type and ImageNet embedding output')
            expected='roi_resize_256x128_rgb_v1' if self.target_types==['person'] else 'roi_resize_256x256_rgb_v1'
            if self.preprocessing!=expected:raise ValueError('ReID input shape differs from target type')
        else:
            if self.target_types is None or len(self.target_types)!=1 or self.normalization!='raw_0_255' or self.output!='embedding':raise ValueError('native OSNet requires one target type and raw image pixels')
            dimensions,preprocessing=(256,'roi_resize_256x128_bgr_v1') if self.target_types==['person'] else (512,'roi_resize_208x208_rgb_v1')
            if self.dimensions!=dimensions or self.preprocessing!=preprocessing:raise ValueError('native OSNet interface differs from target type')
        return self

    def supports(self,target_type):return target_type in {'person','vehicle'} and (self.target_types is None or target_type in self.target_types)

    @property
    def sha256(self):return hashlib.sha256(canonical(self.model_dump())).hexdigest()


class Snapshot(BaseModel):
    model_config=ConfigDict(extra='forbid',frozen=True)
    schema_version:Literal['appearance-catalog-v1']
    revision:int=Field(ge=1,le=9007199254740991,strict=True)
    active:str
    active_by_type:dict[Literal['person','vehicle'],str]|None=Field(default=None,min_length=1,max_length=2)
    profiles:list[Definition]=Field(min_length=1,max_length=8)

    @model_validator(mode='before')
    @classmethod
    def optional_contract(cls,value):
        if isinstance(value,dict) and 'active_by_type' in value and value['active_by_type'] is None:raise ValueError('typed bindings must be omitted rather than null')
        return value

    @model_validator(mode='after')
    def bindings(self):
        ids=[p.profile for p in self.profiles]
        if len(set(ids))!=len(ids) or self.active not in ids:raise ValueError('invalid active appearance binding')
        by_id={p.profile:p for p in self.profiles}
        for target_type in ('person','vehicle'):
            selected=(self.active_by_type or {}).get(target_type,self.active)
            if selected not in by_id or not by_id[selected].supports(target_type):raise ValueError('appearance target type binding differs')
        return self


class Catalog:
    def __init__(self,path):self.path=Path(path);self.revision=0;self.digest=None;self.definitions={}

    def current(self):
        with self.path.open('rb') as source:raw=source.read(32769)
        if len(raw)>32768:raise ValueError('appearance catalog exceeds bound')
        snapshot=Snapshot.model_validate_json(raw);digest=hashlib.sha256(canonical(snapshot.model_dump(exclude_none=True))).hexdigest()
        if snapshot.revision<self.revision or snapshot.revision==self.revision and self.digest!=digest:raise ValueError('appearance catalog revision changed')
        for definition in snapshot.profiles:
            if definition.profile in self.definitions and self.definitions[definition.profile]!=definition.sha256:raise ValueError('appearance profile is immutable')
        self.definitions.update({p.profile:p.sha256 for p in snapshot.profiles});self.revision=snapshot.revision;self.digest=digest
        return snapshot

    def definition(self,profile=None,target_type=None):
        snapshot=self.current();selected=profile or (snapshot.active_by_type or {}).get(target_type,snapshot.active)
        result=next((p for p in snapshot.profiles if p.profile==selected),None)
        if result is not None and target_type is not None and not result.supports(target_type):raise ValueError('appearance target type unsupported')
        return result


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
