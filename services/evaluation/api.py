"""Evaluation routes share the existing browser session, TLS and origin guard."""
import math
import re
from typing import Literal

from fastapi import HTTPException,Request
from fastapi.responses import FileResponse,JSONResponse
from pydantic import BaseModel,ConfigDict,Field,field_validator,model_validator

from .metrics import evaluate
from .store import Conflict,Dataset,Labels
from .regions import RegionDataset
from .policy_report import PolicyReport


CONDITIONS={'day','night','headlights','stationary','small_target','occlusion'}


class Mark(BaseModel):
    model_config=ConfigDict(extra='forbid')
    id:str=Field(min_length=1,max_length=80)
    object_type:Literal['person','vehicle']
    class_name:Literal['person','vehicle','bicycle','car','motorcycle','bus','truck']
    bbox_xyxy:list[float]=Field(min_length=4,max_length=4)
    occluded:bool=False

    @model_validator(mode='after')
    def validate_box(self):
        x1,y1,x2,y2=self.bbox_xyxy
        if not all(math.isfinite(v) for v in self.bbox_xyxy) or not 0<=x1<x2<=8192 or not 0<=y1<y2<=8192:
            raise ValueError('目标框坐标无效')
        if (self.class_name=='person')!=(self.object_type=='person'):raise ValueError('目标类别不一致')
        return self


class Draft(BaseModel):
    model_config=ConfigDict(extra='forbid')
    revision:int=Field(ge=0)
    actor:str=Field(min_length=1,max_length=80)
    annotation_source:Literal['human','ai_visual_review']='human'
    scene:Literal['occupied','empty','unknown','unreadable']='unknown'
    presence_person:bool|None=None
    presence_vehicle:bool|None=None
    objects:list[Mark]=Field(default_factory=list,max_length=100)
    boxes_complete:bool=False
    conditions:list[str]=Field(default_factory=list,max_length=6)
    notes:str=Field(default='',max_length=500)

    @field_validator('actor')
    @classmethod
    def actor_required(cls,value):
        if not value.strip() or any(ord(v)<32 for v in value):raise ValueError('请填写标注人员标识')
        return value.strip()

    @model_validator(mode='after')
    def consistent_truth(self):
        if len(set(self.conditions))!=len(self.conditions) or not set(self.conditions)<=CONDITIONS:raise ValueError('场景标签无效')
        if 'day' in self.conditions and 'night' in self.conditions:raise ValueError('日夜标签冲突')
        if len({v.id for v in self.objects})!=len(self.objects):raise ValueError('目标标识重复')
        if self.scene=='empty' and (self.objects or self.presence_person is not False or self.presence_vehicle is not False):raise ValueError('空景标注必须明确无人无车')
        if self.scene=='occupied' and self.presence_person is not True and self.presence_vehicle is not True:raise ValueError('请明确可见的人或车')
        if self.scene in {'unknown','unreadable'} and self.boxes_complete:raise ValueError('未知或不可读画面不能标为完整框标注')
        for mark in self.objects:
            if (self.presence_person if mark.object_type=='person' else self.presence_vehicle) is not True:raise ValueError('目标框与存在性标注冲突')
        if self.boxes_complete:
            if self.presence_person is None or self.presence_vehicle is None:
                raise ValueError('完整框标注需要先明确是否有人和是否有车；不能确认时请保留部分标注')
            for kind,present in [('person',self.presence_person),('vehicle',self.presence_vehicle)]:
                if present is True and not any(v.object_type==kind for v in self.objects):raise ValueError('完整标注缺少可见目标框')
        return self


class Verify(BaseModel):
    model_config=ConfigDict(extra='forbid')
    revision:int=Field(ge=1)
    actor:str=Field(min_length=1,max_length=80)
    human_confirmed:Literal[True]

    @field_validator('human_confirmed',mode='before')
    @classmethod
    def explicit_confirmation(cls,value):
        if value is not True:raise ValueError('请明确确认本次人工标注')
        return value


class AIReview(BaseModel):
    model_config=ConfigDict(extra='forbid')
    revision:int=Field(ge=1)
    actor:str=Field(min_length=1,max_length=80)
    ai_visual_review_confirmed:Literal[True]

    @field_validator('ai_visual_review_confirmed',mode='before')
    @classmethod
    def explicit_ai_review(cls,value):
        if value is not True:raise ValueError('需要明确 AI 复核来源')
        return value


def mount_evaluation(app,cfg,authenticated,origin_ok):
    root=cfg.get('evaluation_root','');state={'dataset':None,'labels':None}
    if root:
        try:
            state['dataset']=Dataset(root,cfg.get('recordings_dir'))
            state['labels']=Labels(cfg['annotations_db'],state['dataset'])
        except Exception:state={'dataset':None,'labels':None}
    app.state.evaluation=state
    region_data=None
    if cfg.get('region_root'):
        try:region_data=RegionDataset(cfg['region_root'],cfg.get('region_reference') or None)
        except (OSError,ValueError,KeyError,TypeError):pass
    app.state.region_evaluation=region_data
    policy_data=None
    if region_data is not None and cfg.get('gate_comparison_file'):
        try:policy_data=PolicyReport(cfg['gate_comparison_file'],region_data.identity)
        except (OSError,ValueError,KeyError,TypeError,EOFError):pass
    app.state.policy_evaluation=policy_data

    @app.middleware('http')
    async def guard(request,call_next):
        if request.url.path.startswith('/api/evaluation/'):
            if not authenticated(request):return JSONResponse({'detail':'请先登录'},status_code=401)
            if request.method in {'POST','PUT','PATCH','DELETE'}:
                try:origin_ok(request)
                except HTTPException:return JSONResponse({'detail':'请求来源无效'},status_code=403)
                length=request.headers.get('content-length','')
                if not length.isdigit():return JSONResponse({'detail':'需要明确请求大小'},status_code=411)
                if int(length)>128*1024:return JSONResponse({'detail':'标注请求超过大小限制'},status_code=413)
        return await call_next(request)

    def ready():
        if state['dataset'] is None or state['labels'] is None:raise HTTPException(503,'评测数据尚未准备好')
        return state['dataset'],state['labels']

    def frame(identity):
        data,labels=ready()
        try:return data.frame(identity),labels
        except KeyError:raise HTTPException(404,'评测样本不存在') from None

    def summary(value,label):
        return {'id':value['id'],'camera':value['camera'],'offset_ms':value['offset_ms'],
                'width':value['width'],'height':value['height'],'sha256':value['sha256'],
                'status':label['status'],'annotation_source':label.get('annotation_source','human'),
                'review_source':label.get('review_source','human' if label['status']=='verified' else None),
                'image_url':'/api/evaluation/frames/'+value['id']+'/image'}

    @app.get('/api/evaluation/health')
    def health():return {'ready':state['dataset'] is not None,'regions_ready':region_data is not None,
                        'policies_ready':policy_data is not None,'mode':'offline_evaluation','creates_inference_jobs':False}

    @app.get('/api/evaluation/regions/policies')
    def policy_report(model:str='nanodet',interval:int=5,mode:str='none',quality:str='strict'):
        if policy_data is None:raise HTTPException(503,'策略对照尚未准备好')
        try:return policy_data.query(model,interval,mode,quality)
        except ValueError:raise HTTPException(422,'策略对照条件无效') from None

    def regions():
        if region_data is None:raise HTTPException(503,'空景区域对照尚未准备好')
        return region_data

    @app.get('/api/evaluation/regions/dataset')
    def region_dataset():return regions().public()

    @app.get('/api/evaluation/regions/frames/{identity}/image')
    def region_image(identity:str):
        try:return FileResponse(regions().image(identity),media_type='image/jpeg',headers={'Cache-Control':'no-store'})
        except KeyError:raise HTTPException(404,'区域复核样本不存在') from None
        except ValueError:raise HTTPException(409,'区域复核原图已变化') from None

    @app.get('/api/evaluation/regions/frames/{identity}')
    def region_frame(identity:str,region:str='near_field'):
        try:return regions().at(identity,region)
        except KeyError:raise HTTPException(404,'区域复核样本不存在') from None
        except ValueError:raise HTTPException(422,'观察区域无效') from None

    @app.get('/api/evaluation/regions/report')
    def region_report(camera:str='recording-01',region:str='near_field',interval:int=5):
        try:return regions().report(camera,region,interval)
        except ValueError:raise HTTPException(422,'点位、区域或抽帧间隔无效') from None

    @app.get('/api/evaluation/dataset')
    def dataset_info():
        data,labels=ready();all_labels=labels.all()
        return {'id':data.identity,'cameras':list(data.source_hashes),'models':data.models,
          'frames':[summary(value,all_labels.get(key,labels.empty(key))) for key,value in data.frames.items()],
          'timestamp_basis':'片内相对时间，不是已核验的拍摄时间','label_policy':'人工确认和AI复核分开统计，草稿和检测建议不计入参考集'}

    @app.get('/api/evaluation/frames/{identity}')
    def frame_info(identity:str):
        value,labels=frame(identity);label=labels.get(identity)
        # Older confirmed records predate provenance fields. Normalize only the
        # response; retain their original stored payload and audit history.
        public={**label,'annotation_source':label.get('annotation_source','human'),
                'review_source':label.get('review_source','human' if label['status']=='verified' else None)}
        return {**summary(value,public),'label':public}

    @app.get('/api/evaluation/frames/{identity}/image')
    def image(identity:str):
        frame(identity);data,_=ready()
        try:return FileResponse(data.image(identity),media_type='image/jpeg',headers={'Cache-Control':'no-store'})
        except ValueError:raise HTTPException(409,'原图摘要已变化，暂停标注') from None

    @app.get('/api/evaluation/frames/{identity}/predictions')
    def predictions(identity:str,model:str,threshold:float=.35):
        value,_=frame(identity)
        if model not in value['cases'] or not math.isfinite(threshold) or not .05<=threshold<=.95:raise HTTPException(422,'模型或阈值无效')
        return {'source':'model_suggestion_not_ground_truth','model':model,
                'targets':[v for v in value['cases'][model]['targets'] if v['confidence']>=threshold]}

    @app.post('/api/evaluation/frames/{identity}/label')
    @app.put('/api/evaluation/frames/{identity}/label')
    def save_label(identity:str,body:Draft):
        value,labels=frame(identity)
        for mark in body.objects:
            if mark.bbox_xyxy[2]>value['width'] or mark.bbox_xyxy[3]>value['height']:raise HTTPException(422,'目标框超出原图范围')
        try:return labels.write(identity,body.revision,body.actor,body.model_dump(exclude={'revision','actor'}))
        except Conflict:raise HTTPException(409,'标注版本已变化，请重新加载后核对') from None
        except ValueError:raise HTTPException(409,'原图变化或标注容量达到上限') from None

    @app.post('/api/evaluation/frames/{identity}/verify')
    def verify_label(identity:str,body:Verify):
        _,labels=frame(identity)
        if not body.actor.strip() or any(ord(v)<32 for v in body.actor):raise HTTPException(422,'请填写标注人员标识')
        try:return labels.write(identity,body.revision,body.actor.strip(),verify=True)
        except Conflict:raise HTTPException(409,'请先保存当前草稿，或重新加载最新版本') from None
        except ValueError:raise HTTPException(409,'原图变化或标注容量达到上限') from None

    @app.post('/api/evaluation/frames/{identity}/ai-review')
    def ai_review_label(identity:str,body:AIReview):
        _,labels=frame(identity)
        if not body.actor.strip() or any(ord(v)<32 for v in body.actor):raise HTTPException(422,'请填写AI复核标识')
        try:return labels.write(identity,body.revision,body.actor.strip(),ai_review=True)
        except Conflict:raise HTTPException(409,'请先保存AI来源草稿，或重新加载最新版本') from None
        except ValueError:raise HTTPException(409,'原图变化、来源不一致或标注容量达到上限') from None

    @app.get('/api/evaluation/report')
    def report(threshold:float=.35,reference_source:str='human'):
        data,labels=ready()
        try:return evaluate(data,labels.all(),threshold,reference_source)
        except ValueError:raise HTTPException(422,'评测阈值或参考来源无效') from None

    @app.get('/api/evaluation/annotations/export')
    def export():
        data,labels=ready()
        return JSONResponse({'dataset_id':data.identity,'source_sha256':data.source_hashes,'annotations':labels.all()},
                  headers={'Content-Disposition':'attachment; filename="evaluation-annotations.json"'})

    @app.get('/api/evaluation/recordings/{camera}')
    def recording(camera:str,request:Request):
        data,_=ready();requested=request.headers.get('range','')
        if requested and not re.fullmatch(r'bytes=\d*-\d*',requested):raise HTTPException(416,'录像范围无效')
        try:return FileResponse(data.video(camera),media_type='video/mp4')
        except KeyError:raise HTTPException(404,'原录像不可用') from None
        except ValueError:raise HTTPException(409,'原录像摘要不一致') from None
