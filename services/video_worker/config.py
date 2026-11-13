from __future__ import annotations
from dataclasses import dataclass, field
import os
import json
import math
from pathlib import Path
import re
from urllib.parse import urlsplit


@dataclass(frozen=True)
class Settings:
    core_url: str
    core_ca: str
    worker_token: str = field(repr=False)
    gateway_url: str = ""
    gateway_ca: str = ""
    gateway_token: str = field(default="", repr=False)
    worker_id: str = "video-worker"
    evidence_dir: Path = Path("/evidence")
    archive_dir: Path = Path("/recordings")
    stream_host: str = "rtsp-simulator.police-video-trial.svc"
    stream_port: int = 8322
    stream_username: str = field(default="", repr=False)
    stream_password: str = field(default="", repr=False)
    stream_ca: str = ""
    sample_seconds: int = 60
    model_timeout: int = 360
    analysis_slots: int = 2
    analysis_lease_seconds: int = 120
    analysis_heartbeat_seconds: int = 30
    analysis_job_timeout_seconds: int = 480
    analysis_shutdown_grace_seconds: int = 20
    module_slots: int = 2
    module_job_timeout_seconds: int = 90
    max_evidence_bytes: int = 2 * 1024 * 1024 * 1024
    evidence_backend: str = "legacy"
    scene_gate_mode: str = "off"
    scene_model: Path = Path('/models/nanodet.onnx')
    scene_probe_seconds: float = 1.0
    scene_detector_seconds: float = 5.0
    scene_fallback_seconds: float = 300.0
    scene_rois_json: str = '{}'
    scene_minimum_submission_seconds: float = 0.0
    target_pipeline: bool = False
    ingestion_jobs_enabled: bool = False
    exports_enabled: bool = False
    export_dir: Path = Path('/evidence-exports')
    export_bytes: int = 4*1024**3
    export_job_timeout_seconds: int = 900
    spool_payload_bytes: int = 512 * 1024 * 1024
    spool_entries: int = 2000
    target_threshold: float = .35
    analysis_image_transport: str = 'json'
    scene_policy: str = 'legacy'
    scene_motion_method: str = 'robust'
    scene_detector_kind: str = 'nanodet'
    scene_detector_variant: str = 'raw'
    frame_selection: str = 'fixed'
    quiet_dedup_seconds: float = 0
    target_detector_kind: str = 'nanodet'
    target_detector_variant: str = 'raw'
    target_model: Path | None = None
    scene_negative_confirmations:int=3
    scene_active_hold_seconds:float=10.
    scene_guard_width:int=320
    scene_empty_verifier:str='off'
    scene_verifier_model:Path=Path('/models/yolox.onnx')
    scene_verifier_confirmations:int=2
    scene_candidate_merge:bool=False
    target_quality_mode:str='off'
    specialist_selection_mode:str='off'
    processing_catalog_file:str=''
    specialist_models_root:str=''
    target_motion_mode:str='off'
    target_duplicate_mode:str='off'
    scene_motion_context:str='off'
    scene_audit_enabled:bool=False
    scene_audit_seconds:float=300.
    scene_audit_bytes:int=64*1024*1024
    scene_audit_entries:int=4096
    scene_audit_retention_seconds:float=86400.
    capture_analysis_handoff_enabled:bool=True
    media_jobs_enabled:bool=False
    media_slots:int=1
    media_job_timeout_seconds:int=480
    media_clip_dir:Path=Path('/media-clips')
    media_clip_bytes:int=2*1024*1024*1024
    media_clip_entries:int=64
    scene_activity_evidence:str='legacy'
    target_short_motion_strategy:str='legacy'
    target_stationary_filter:str='off'

    @classmethod
    def from_env(cls):
        get = lambda key, default="": os.environ.get(key, default).strip()
        if get('CAPTURE_ANALYSIS_HANDOFF_ENABLED','true') not in {'true','false'}:
            raise ValueError('invalid capture analysis handoff setting')
        return cls(core_url=get("CORE_URL"), core_ca=get("CORE_CA_FILE"), worker_token=get("WORKER_TOKEN"),ingestion_jobs_enabled=get('INGESTION_JOBS_ENABLED','false')=='true',
                   exports_enabled=get('EXPORTS_ENABLED','false')=='true',export_dir=Path(get('EXPORT_DIR','/evidence-exports')),
                   export_bytes=int(get('EXPORT_BYTES',str(4*1024**3))),export_job_timeout_seconds=int(get('EXPORT_JOB_TIMEOUT_SECONDS','900')),
                   gateway_url=get("GATEWAY_URL"), gateway_ca=get("GATEWAY_CA_FILE"), gateway_token=get("GATEWAY_TOKEN"),
                   worker_id=get("WORKER_ID", "video-worker"), evidence_dir=Path(get("EVIDENCE_DIR", "/evidence")),
                   archive_dir=Path(get("ARCHIVE_DIR", "/recordings")), stream_host=get("STREAM_HOST", "rtsp-simulator.police-video-trial.svc"),
                   stream_port=int(get("STREAM_PORT", "8322")), stream_username=get("STREAM_USERNAME"),
                   stream_password=get("STREAM_PASSWORD"), stream_ca=get("STREAM_CA_FILE"),
                   sample_seconds=max(1, min(3600, int(get("SAMPLE_SECONDS", "60")))), model_timeout=360,
                   analysis_slots=int(get('ANALYSIS_SLOTS','2')),
                   analysis_lease_seconds=int(get('ANALYSIS_LEASE_SECONDS','120')),
                   analysis_heartbeat_seconds=int(get('ANALYSIS_HEARTBEAT_SECONDS','30')),
                   analysis_job_timeout_seconds=int(get('ANALYSIS_JOB_TIMEOUT_SECONDS','480')),
                   analysis_shutdown_grace_seconds=int(get('ANALYSIS_SHUTDOWN_GRACE_SECONDS','20')),
                   module_slots=int(get('MODULE_SLOTS','2')),
                   module_job_timeout_seconds=int(get('MODULE_JOB_TIMEOUT_SECONDS','90')),
                   max_evidence_bytes=max(64 * 1024 * 1024, int(get("MAX_EVIDENCE_BYTES", str(2 * 1024 * 1024 * 1024)))),
                   evidence_backend=get("EVIDENCE_BACKEND", "legacy"),
                   scene_gate_mode=get('SCENE_GATE_MODE', 'off'), scene_model=Path(get('SCENE_MODEL_FILE', '/models/nanodet.onnx')),
                   scene_probe_seconds=float(get('SCENE_PROBE_SECONDS', '1')),
                   scene_detector_seconds=float(get('SCENE_DETECTOR_SECONDS', '5')),
                   scene_fallback_seconds=float(get('SCENE_FALLBACK_SECONDS', '300')),
                   scene_rois_json=get('SCENE_ROIS_JSON', '{}'),
                   scene_minimum_submission_seconds=float(get('SCENE_MINIMUM_SUBMISSION_SECONDS', '0')),
                   target_pipeline=get('TARGET_PIPELINE', '0') == '1',
                   spool_payload_bytes=int(get('SPOOL_PAYLOAD_BYTES', str(512 * 1024 * 1024))),
                   spool_entries=int(get('SPOOL_ENTRIES', '2000')),
                   target_threshold=float(get('TARGET_THRESHOLD', '.35')),
                   analysis_image_transport=get('ANALYSIS_IMAGE_TRANSPORT','json'),
                   scene_policy=get('SCENE_POLICY','legacy'),scene_motion_method=get('SCENE_MOTION_METHOD','robust'),
                   scene_activity_evidence=get('SCENE_ACTIVITY_EVIDENCE','legacy'),
                   scene_detector_kind=get('SCENE_DETECTOR_KIND','nanodet'),scene_detector_variant=get('SCENE_DETECTOR_VARIANT','raw'),
                   frame_selection=get('FRAME_SELECTION','fixed'),quiet_dedup_seconds=float(get('QUIET_DEDUP_SECONDS','0')),
                   target_detector_kind=get('TARGET_DETECTOR_KIND',get('SCENE_DETECTOR_KIND','nanodet')),
                   target_detector_variant=get('TARGET_DETECTOR_VARIANT','raw'),
                   target_model=Path(get('TARGET_MODEL_FILE')) if get('TARGET_MODEL_FILE') else None,
                   scene_negative_confirmations=int(get('SCENE_NEGATIVE_CONFIRMATIONS','3')),
                   scene_active_hold_seconds=float(get('SCENE_ACTIVE_HOLD_SECONDS','10')),
                   scene_guard_width=int(get('SCENE_GUARD_WIDTH','320')),
                   scene_empty_verifier=get('SCENE_EMPTY_VERIFIER','off'),
                   scene_verifier_model=Path(get('SCENE_VERIFIER_MODEL_FILE','/models/yolox.onnx')),
                   scene_verifier_confirmations=int(get('SCENE_VERIFIER_CONFIRMATIONS','2')),
                   scene_candidate_merge=get('SCENE_CANDIDATE_MERGE','0')=='1',target_quality_mode=get('TARGET_QUALITY_MODE','off'),
                   specialist_selection_mode=get('SPECIALIST_SELECTION_MODE','off'),processing_catalog_file=get('PROCESSING_CATALOG_FILE'),
                   specialist_models_root=get('SPECIALIST_MODELS_ROOT'),
                   target_motion_mode=get('TARGET_MOTION_MODE','off'),target_duplicate_mode=get('TARGET_DUPLICATE_MODE','off'),
                   scene_motion_context=get('SCENE_MOTION_CONTEXT','off'),
                   target_short_motion_strategy=get('TARGET_SHORT_MOTION_STRATEGY','legacy'),target_stationary_filter=get('TARGET_STATIONARY_FILTER','off'),
                   scene_audit_enabled=get('SCENE_AUDIT_ENABLED','0')=='1',scene_audit_seconds=float(get('SCENE_AUDIT_SECONDS','300')),
                   scene_audit_bytes=int(get('SCENE_AUDIT_BYTES',str(64*1024*1024))),scene_audit_entries=int(get('SCENE_AUDIT_ENTRIES','4096')),
                   scene_audit_retention_seconds=float(get('SCENE_AUDIT_RETENTION_SECONDS','86400')),
                   capture_analysis_handoff_enabled=get('CAPTURE_ANALYSIS_HANDOFF_ENABLED','true')=='true',
                   media_jobs_enabled=get('MEDIA_JOBS_ENABLED','false')=='true',media_slots=int(get('MEDIA_SLOTS','1')),
                   media_job_timeout_seconds=int(get('MEDIA_JOB_TIMEOUT_SECONDS','480')),media_clip_dir=Path(get('MEDIA_CLIP_DIR','/media-clips')),
                   media_clip_bytes=int(get('MEDIA_CLIP_BYTES',str(2*1024*1024*1024))),media_clip_entries=int(get('MEDIA_CLIP_ENTRIES','64')))

    def scene_rois(self):
        values = json.loads(self.scene_rois_json)
        if not isinstance(values, dict) or len(values) > 6:
            raise ValueError('invalid scene ROI configuration')
        for camera, rectangle in values.items():
            if (not re.fullmatch(r'recording-0[1-6]', camera) or not isinstance(rectangle, list) or len(rectangle) != 4 or
                    any(isinstance(v, bool) or not isinstance(v, (int, float)) or not math.isfinite(v) or not 0 <= v <= 1 for v in rectangle) or
                    not (rectangle[0] < rectangle[2] and rectangle[1] < rectangle[3])):
                raise ValueError('invalid scene ROI configuration')
        return values

    def validate(self, role):
        if role=='export' and (not self.exports_enabled or type(self.export_bytes) is not int or not 516*1024**2<=self.export_bytes<=16*1024**3
                or type(self.export_job_timeout_seconds) is not int or not 300<=self.export_job_timeout_seconds<=1800
                or any(self.export_dir.resolve().is_relative_to(source.resolve()) or source.resolve().is_relative_to(self.export_dir.resolve()) for source in (self.archive_dir,self.evidence_dir,self.media_clip_dir))):
            raise ValueError('invalid export consumer configuration')
        if role=='media' and (not self.media_jobs_enabled or type(self.media_slots) is not int or not 1<=self.media_slots<=2
                or type(self.media_job_timeout_seconds) is not int or not 420<=self.media_job_timeout_seconds<=1800
                or type(self.media_clip_bytes) is not int or not 64*1024*1024<=self.media_clip_bytes<=16*1024**3
                or type(self.media_clip_entries) is not int or not 1<=self.media_clip_entries<=4096
                or any(self.media_clip_dir.resolve().is_relative_to(source.resolve()) or source.resolve().is_relative_to(self.media_clip_dir.resolve())
                       for source in (self.archive_dir,self.evidence_dir))):
            raise ValueError('invalid media consumer configuration')
        if role in {'analyze','modules','media','specialist'} and (self.model_timeout!=360 or
                any(type(value) is not int for value in (self.analysis_slots,self.analysis_lease_seconds,
                    self.analysis_heartbeat_seconds,self.analysis_job_timeout_seconds,self.analysis_shutdown_grace_seconds)) or
                not 1<=self.analysis_slots<=2 or not 60<=self.analysis_lease_seconds<=600 or
                not 5<=self.analysis_heartbeat_seconds<=self.analysis_lease_seconds/3 or
                not self.model_timeout+60<=self.analysis_job_timeout_seconds<=1800 or
                not 0<=self.analysis_shutdown_grace_seconds<=300):
            raise ValueError('invalid analysis consumer timing or concurrency')
        if role in {'analyze','modules'} and (type(self.module_slots) is not int or not 0<=self.module_slots<=8 or
                role=='modules' and self.module_slots==0 or type(self.module_job_timeout_seconds) is not int or
                not 5<=self.module_job_timeout_seconds<=600):
            raise ValueError('invalid independent module consumer configuration')
        if (type(self.scene_audit_enabled) is not bool or not math.isfinite(self.scene_audit_seconds) or not 10<=self.scene_audit_seconds<=3600
                or type(self.scene_audit_bytes) is not int or not 1<=self.scene_audit_bytes<=512*1024*1024
                or type(self.scene_audit_entries) is not int or not 1<=self.scene_audit_entries<=100000
                or not math.isfinite(self.scene_audit_retention_seconds) or not 10<=self.scene_audit_retention_seconds<=30*86400
                or self.scene_audit_enabled and self.scene_policy!='activity_v1'):
            raise ValueError('invalid independent audit configuration')
        if self.target_quality_mode not in {'off','observe'}:raise ValueError('invalid target quality mode')
        if self.specialist_selection_mode not in {'off','observe','select'}:
            raise ValueError('invalid specialist selection mode')
        if (self.specialist_selection_mode != 'off' or self.processing_catalog_file) and not self.target_pipeline and role=='ingest':
            raise ValueError('specialist selection requires target pipeline')
        if self.target_motion_mode not in {'off','observe'} or self.target_duplicate_mode not in {'off','observe','mutual_085','mutual_095'}:
            raise ValueError('invalid target motion or duplicate mode')
        if self.scene_motion_context not in {'off','observe'} or self.scene_motion_context!='off' and (not self.target_pipeline or self.scene_policy!='activity_v1'):
            raise ValueError('short motion context requires the activity target pipeline')
        if (self.target_short_motion_strategy not in {'legacy','grid_batch_v1'} or self.target_stationary_filter not in {'off','observe','confirmed'}
                or self.target_short_motion_strategy!='legacy' and self.scene_motion_context!='observe'
                or self.target_stationary_filter!='off' and self.target_short_motion_strategy!='grid_batch_v1'):
            raise ValueError('stationary review requires the bound grid motion strategy')
        if (self.target_motion_mode!='off' or self.target_duplicate_mode!='off') and not self.target_pipeline:
            raise ValueError('target observations require target pipeline')
        if self.target_duplicate_mode not in {'off','observe'} and self.target_detector_variant in {'adaptive_1','adaptive_2'}:
            raise ValueError('active duplicate suppression is incompatible with protected adaptive detection')
        if self.target_quality_mode!='off' and not self.target_pipeline:raise ValueError('target quality requires target pipeline')
        if (self.scene_activity_evidence not in {'legacy','foreground','shadow_protected'}
                or self.scene_activity_evidence!='legacy' and (self.scene_policy!='activity_v1' or self.scene_motion_method not in {'knn','mog2'})):
            raise ValueError('spatial activity needs an activity_v1 background model')
        if (self.scene_policy not in {'legacy','road_v1','road_v2','activity_v1'} or self.scene_motion_method not in {'mad','robust','mog2','knn'} or
                self.scene_detector_kind not in {'nanodet','yolox'} or self.target_detector_kind not in {'nanodet','yolox'} or
                self.scene_detector_variant not in {'raw','clahe'} or self.target_detector_variant not in {'raw','clahe','tiled','adaptive_1','adaptive_2'} or
                self.frame_selection not in {'fixed','best','temporal'} or not math.isfinite(self.quiet_dedup_seconds) or
                not 0<=self.quiet_dedup_seconds<=60 or
                (self.scene_policy=='legacy' and (self.frame_selection!='fixed' or self.quiet_dedup_seconds>0))):
            raise ValueError('invalid scene/target policy')
        if self.scene_policy=='road_v2':
            if (self.frame_selection!='temporal' or self.scene_motion_method!='robust' or self.scene_probe_seconds!=.2
                    or self.scene_detector_seconds not in {1.,5.} or self.scene_negative_confirmations not in {1,2,3}
                    or self.scene_active_hold_seconds not in {0.,2.,10.} or self.scene_guard_width not in {320,640}):
                raise ValueError('invalid temporal scene policy')
        elif self.frame_selection=='temporal' or self.scene_candidate_merge:
            raise ValueError('temporal selection requires road_v2')
        if self.scene_policy=='activity_v1':
            from services.scene_analysis.activity_gate import ActivityPolicy
            ActivityPolicy(self.sample_seconds,self.scene_active_hold_seconds,self.scene_fallback_seconds,
                           self.scene_guard_width,self.scene_motion_method).validate()
            if self.frame_selection!='fixed' or self.scene_probe_seconds!=.2 or self.scene_empty_verifier!='off':
                raise ValueError('activity policy requires 5Hz motion probes and fixed effective-frame cadence')
        if self.scene_empty_verifier not in {'off','selective_yolox'} or self.scene_verifier_confirmations not in {2,3}:
            raise ValueError('invalid empty verifier policy')
        if self.scene_empty_verifier!='off':
            if (self.scene_policy!='road_v2' or self.scene_detector_kind!='nanodet' or self.scene_detector_variant!='raw'
                    or self.scene_detector_seconds!=5 or self.scene_guard_width!=320
                    or role=='ingest' and not self.scene_verifier_model.is_file()):
                raise ValueError('empty verifier requires tested road_v2/model configuration')
        if self.target_model is None and self.target_detector_kind!=self.scene_detector_kind:
            raise ValueError('different target detector requires its own model file')
        if role=='ingest' and self.target_model is not None and not self.target_model.is_file():
            raise ValueError('target model unavailable')
        def valid_url(value):
            try:
                parsed = urlsplit(value)
                return parsed.scheme == "https" and parsed.hostname and not any((parsed.username, parsed.password, parsed.query, parsed.fragment))
            except ValueError:
                return False
        if role not in {"ingest", "analyze","modules","media","specialist","export"} or not valid_url(self.core_url) or not Path(self.core_ca).is_file() or not self.worker_token:
            raise ValueError("worker service configuration incomplete")
        if self.scene_gate_mode not in {'off', 'active'}:
            raise ValueError('unknown scene gate mode')
        if self.analysis_image_transport not in {'json','multipart'}:
            raise ValueError('unknown analysis image transport')
        if self.target_pipeline:
            if (not 64 * 1024 * 1024 <= self.spool_payload_bytes <= 2 * 1024 * 1024 * 1024 or
                    not 6 <= self.spool_entries <= 10000 or not math.isfinite(self.target_threshold) or
                    not .05 <= self.target_threshold <= .95 or (role == 'ingest' and self.scene_gate_mode != 'active')):
                raise ValueError('target pipeline configuration incomplete')
        if role == 'ingest' and os.environ.get('SCENE_REQUIRED') == '1' and self.scene_gate_mode != 'active':
            raise ValueError('this scene-worker image requires the scene gate')
        if self.ingestion_jobs_enabled and role=='ingest' and (not self.target_pipeline or self.scene_policy!='activity_v1' or self.scene_gate_mode!='active'):
            raise ValueError('managed ingestion needs the durable activity_v1 target pipeline')
        if role == 'ingest' and self.scene_gate_mode == 'active':
            from .scene_gate import SceneGatePolicy
            SceneGatePolicy(sample_seconds=self.sample_seconds, fallback_seconds=self.scene_fallback_seconds).validate()
            if (not math.isfinite(self.scene_probe_seconds) or not math.isfinite(self.scene_detector_seconds) or
                    not .2 <= self.scene_probe_seconds <= min(5, self.sample_seconds) or
                    not self.scene_probe_seconds <= self.scene_detector_seconds <= 30 or
                    not math.isfinite(self.scene_minimum_submission_seconds) or
                    not 0 <= self.scene_minimum_submission_seconds <= self.sample_seconds or not self.scene_model.is_file()):
                raise ValueError('scene gate model or timing configuration incomplete')
            self.scene_rois()
        if role == "ingest" and self.sample_seconds < 60 and self.scene_gate_mode == 'off':
            # The live worker does not yet apply the required scene gate. Do
            # not silently send every 1s/5s frame to the model when configured.
            raise ValueError("ingestion faster than 60 seconds requires the scene filter")
        if self.evidence_backend not in {"legacy", "ledger"}:
            raise ValueError("unknown evidence backend")
        if not re.fullmatch(r"[A-Za-z0-9_.-]{1,70}", self.worker_id):
            raise ValueError("worker ID invalid")
        if role == "analyze" and (not valid_url(self.gateway_url) or not Path(self.gateway_ca).is_file() or not self.gateway_token):
            raise ValueError("gateway configuration incomplete")
