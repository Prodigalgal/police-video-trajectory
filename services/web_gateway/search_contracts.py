"""Bounded, backward-compatible search DTOs; clients cannot supply a raw search plan."""
from typing import Annotated, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator

CameraId=Annotated[str,Field(min_length=1,max_length=100,pattern=r'^[A-Za-z0-9_-]+$')]


class SearchAdjustment(BaseModel):
    model_config=ConfigDict(extra='forbid',strict=True)
    context:str=Field(min_length=20,max_length=32768,pattern=r'^[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+$',repr=False)
    action:Literal['remove','choose_cameras']
    condition:str|None=Field(default=None,min_length=1,max_length=40)
    camera_ids:list[CameraId]|None=Field(default=None,min_length=1,max_length=50)

    @model_validator(mode='after')
    def operation_fields(self):
        if self.action=='remove' and (self.condition is None or self.camera_ids is not None):raise ValueError('condition required')
        if self.action=='choose_cameras' and (self.condition is not None or not self.camera_ids or len(set(self.camera_ids))!=len(self.camera_ids)):raise ValueError('distinct camera IDs required')
        return self


class Search(BaseModel):
    model_config=ConfigDict(extra='forbid')
    query:str=Field(min_length=1,max_length=1000)
    page_size:int=Field(default=20,ge=1,le=50,strict=True)
    cursor:str|None=Field(default=None,min_length=20,max_length=32768,repr=False)
    adjustment:SearchAdjustment|None=None
    mode:Literal['natural','keyword']='natural'

    @model_validator(mode='after')
    def independent_operations(self):
        if self.cursor is not None and self.adjustment is not None:raise ValueError('paging cannot edit conditions')
        if self.mode=='keyword' and (self.cursor is not None or self.adjustment is not None or not self.query.strip() or len(self.query.strip().encode('utf-16-le',errors='surrogatepass'))//2>200):
            raise ValueError('keyword mode requires a new query of at most 200 characters')
        return self


class CloseSearch(BaseModel):
    model_config=ConfigDict(extra='forbid')
    query:str=Field(min_length=1,max_length=1000)
    cursor:str=Field(min_length=20,max_length=32768,repr=False)
