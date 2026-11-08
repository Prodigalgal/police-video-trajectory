from datetime import datetime
from typing import Annotated, Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False)


class SearchRequest(StrictModel):
    query: str = Field(min_length=1, max_length=1000)
    reference_time: datetime
    timezone: str = Field(default="Asia/Shanghai", min_length=1, max_length=80)

    @field_validator("query")
    @classmethod
    def not_blank(cls, value):
        value = value.strip()
        if not value:
            raise ValueError("查询内容不能为空")
        return value

    @field_validator("reference_time")
    @classmethod
    def timezone_required(cls, value):
        if value.tzinfo is None or value.utcoffset() is None:
            raise ValueError("参考时间必须包含时区")
        return value

    @field_validator("timezone")
    @classmethod
    def valid_timezone(cls, value):
        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError):
            raise ValueError("无效时区") from None
        return value


class TimeRange(StrictModel):
    start: datetime | None = None
    end: datetime | None = None

    @field_validator("start", "end")
    @classmethod
    def timezone_required(cls, value):
        if value is not None and (value.tzinfo is None or value.utcoffset() is None):
            raise ValueError("查询时间必须包含时区")
        return value

    @model_validator(mode="after")
    def ordered(self):
        if self.start and self.end and self.start > self.end:
            raise ValueError("开始时间不能晚于结束时间")
        return self


class SearchAttributes(StrictModel):
    upper_color: str | None = Field(default=None, max_length=80)
    lower_color: str | None = Field(default=None, max_length=80)
    clothing_color: str | None = Field(default=None, max_length=80)
    carrying: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(default_factory=list, max_length=12)
    transport_type: str | None = Field(default=None, max_length=80)
    visible_action: str | None = Field(default=None, max_length=80)
    vehicle_color: str | None = Field(default=None,max_length=80)


class SearchPlan(StrictModel):
    schema_version: Literal["1.0"] = "1.0"
    target_type: Literal["person", "vehicle", "unknown"] = "unknown"
    time_range: TimeRange | None = None
    location_text: str | None = Field(default=None, max_length=200)
    camera_ids: list[str] = Field(default_factory=list, max_length=50)
    attributes: SearchAttributes = Field(default_factory=SearchAttributes)
    text_terms: list[str] = Field(default_factory=list, max_length=20)
    clarifications: list[str] = Field(default_factory=list, max_length=10)

    @field_validator("camera_ids", "text_terms", "clarifications")
    @classmethod
    def bounded_strings(cls, values):
        if any(not item.strip() or len(item) > 200 for item in values):
            raise ValueError("列表项不能为空且不能超过 200 字符")
        return [item.strip() for item in values]

    @model_validator(mode='after')
    def vehicle_color_scope(self):
        if self.target_type=='person' and self.attributes.vehicle_color is not None:
            raise ValueError('vehicle color cannot be a person condition')
        return self


class ImageMetadata(StrictModel):
    frame_id: str = Field(min_length=1, max_length=100)
    media_type: Literal["image/jpeg", "image/png"] = "image/jpeg"


class FrameRequest(ImageMetadata):
    image_base64: str = Field(min_length=4, max_length=12_000_000, repr=False)


class VisibleAttributes(StrictModel):
    upper_color: str | None = Field(default=None, max_length=80)
    lower_color: str | None = Field(default=None, max_length=80)
    carrying: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(default_factory=list, max_length=12)
    visible_action: str | None = Field(default=None, max_length=80)
    transport_type: str | None = Field(default=None, max_length=80)
    vehicle_color: str | None = Field(default=None,max_length=80)


class VisibleObject(StrictModel):
    object_type: Literal["person", "vehicle"]
    bbox_xyxy: tuple[float, float, float, float] | None = None
    attributes: VisibleAttributes = Field(default_factory=VisibleAttributes)
    uncertain_fields: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(default_factory=list, max_length=20)
    description: str | None = Field(default=None, max_length=500)

    @model_validator(mode='after')
    def vehicle_color_scope(self):
        if self.object_type=='person' and self.attributes.vehicle_color is not None:
            raise ValueError('vehicle color cannot describe a person')
        return self

    @field_validator("bbox_xyxy")
    @classmethod
    def valid_bbox(cls, value):
        if value is not None and (min(value) < 0 or value[2] <= value[0] or value[3] <= value[1]):
            raise ValueError("无效的目标框")
        return value


class FrameResult(StrictModel):
    schema_version: Literal["1.0"] = "1.0"
    objects: list[VisibleObject] = Field(max_length=100)


class TargetHint(StrictModel):
    target_id: str = Field(min_length=36, max_length=36)
    object_type: Literal['person', 'vehicle']
    class_name: Literal['person', 'bicycle', 'car', 'motorcycle', 'bus', 'truck']
    bbox_xyxy: tuple[float, float, float, float]

    @field_validator('target_id')
    @classmethod
    def canonical_uuid(cls, value):
        from uuid import UUID
        if str(UUID(value)) != value:
            raise ValueError('invalid target ID')
        return value


class ActivityContext(StrictModel):
    policy: Literal['motion-v1']
    regions_normalized: list[tuple[float,float,float,float]] = Field(max_length=32)
    regions_complete: bool
    hold_seconds: float = Field(ge=.4,le=120)
    stationary_presence_is_empty: Literal[True] = True

    @field_validator('regions_normalized')
    @classmethod
    def normalized_regions(cls,values):
        import math
        if any(not all(math.isfinite(v) and 0<=v<=1 for v in box) or box[0]>=box[2] or box[1]>=box[3] for box in values):
            raise ValueError('invalid activity region')
        return values


class TargetMetadata(ImageMetadata):
    image_width: int = Field(ge=1, le=8192)
    image_height: int = Field(ge=1, le=8192)
    targets: list[TargetHint] = Field(max_length=100)
    activity_context: ActivityContext | None = None


class TargetRequest(TargetMetadata):
    image_base64: str = Field(min_length=4, max_length=12_000_000, repr=False)


class TargetAttributes(StrictModel):
    target_id: str
    object_type: Literal['person', 'vehicle']
    visible: bool | None
    attributes: VisibleAttributes = Field(default_factory=VisibleAttributes)
    uncertain_fields: list[Annotated[str, Field(min_length=1, max_length=100)]] = Field(default_factory=list, max_length=20)
    description: str | None = Field(default=None, max_length=500)

    @model_validator(mode='after')
    def vehicle_color_scope(self):
        if self.object_type=='person' and self.attributes.vehicle_color is not None:
            raise ValueError('vehicle color cannot describe a person')
        return self


class TargetResult(StrictModel):
    schema_version: Literal['2.0'] = '2.0'
    targets: list[TargetAttributes] = Field(max_length=100)
    additional_objects: list[VisibleObject] = Field(default_factory=list, max_length=100)
    scene_description: str | None = Field(default=None, max_length=500)
