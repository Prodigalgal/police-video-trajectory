"""Normalize one immutable native segment; keep the original stream clock explicit."""
import hashlib
import json
from pathlib import Path
import re
import time
import uuid

from .media_clips import MediaError,canonical,digest_file,committed_result,_decode

PROFILE='session-h264-10fps-v1'
VERSION='session-segment-input-v1'
SCHEMA='recorded-session-clip-v1'
FIELDS={'version','source_id','camera_id','source_fingerprint','namespace','segment_id','recording_manifest_sha256',
    'source_sha256','source_bytes','duration_us','task_id','task_generation','scene_run_id','media_epoch','base_pts_us'}


def validate_descriptor(value):
    if not isinstance(value,dict) or value.keys()!=FIELDS or value.get('version')!=VERSION:raise MediaError('media_session_changed')
    for key in ('source_id','segment_id','task_id','scene_run_id'):
        if not isinstance(value[key],str) or str(uuid.UUID(value[key]))!=value[key]:raise MediaError('media_session_changed')
    for key,pattern in (('camera_id',r'[A-Za-z0-9_-]{1,100}'),('namespace',r'[a-f0-9]{32}'),
        ('source_fingerprint',r'[a-f0-9]{64}'),('recording_manifest_sha256',r'[a-f0-9]{64}'),('source_sha256',r'[a-f0-9]{64}')):
        if not isinstance(value[key],str) or not re.fullmatch(pattern,value[key]):raise MediaError('media_session_changed')
    for key,low,high in (('source_bytes',1,16*1024**2),('duration_us',2000000,20000000),('task_generation',1,2**63-1),
        ('media_epoch',0,2**63-1),('base_pts_us',-2**63,2**63-1-value['duration_us'] if type(value['duration_us']) is int else -2**63)):
        if type(value[key]) is not int or not low<=value[key]<=high:raise MediaError('media_session_changed')


def input_value(descriptor):
    validate_descriptor(descriptor)
    return {'input_kind':'session','profile_version':PROFILE,'source_id':descriptor['source_id'],'camera_id':descriptor['camera_id'],
        'source_fingerprint':descriptor['source_fingerprint'],'start_ms':0,'end_ms':descriptor['duration_us']//1000,
        'source':{'bytes':descriptor['source_bytes'],'sha256':descriptor['source_sha256']},'session_input':descriptor}


def _json_file(path,limit,deadline):
    digest_file(path,limit,deadline)
    return json.loads(path.read_bytes())


def verify_native(recordings,descriptor,deadline):
    validate_descriptor(descriptor)
    root=Path(recordings).absolute();owner=root/descriptor['namespace'];folder=owner/uuid.UUID(descriptor['segment_id']).hex
    if any(path.is_symlink() or not path.is_dir() for path in (root,owner,folder)):raise MediaError('media_session_changed')
    if _json_file(folder/'owner.json',200,deadline)!={'schema':'live-segment-owner-v1','segment_id':descriptor['segment_id']}:raise MediaError('media_session_changed')
    native=_json_file(folder/'manifest.json',256*1024,deadline)
    if not isinstance(native,dict) or hashlib.sha256(canonical(native)).hexdigest()!=descriptor['recording_manifest_sha256']:raise MediaError('media_session_changed')
    expected={'schema_version':'same-session-recording-v1','segment_id':descriptor['segment_id'],'namespace':descriptor['namespace'],
        'camera_id':descriptor['camera_id'],'task_id':descriptor['task_id'],'task_generation':descriptor['task_generation'],
        'scene_run_id':descriptor['scene_run_id'],'media_epoch':descriptor['media_epoch'],'duration_us':descriptor['duration_us'],
        'playback_base_pts_us':descriptor['base_pts_us'],'codec':'h264',
        'video':{'bytes':descriptor['source_bytes'],'sha256':descriptor['source_sha256']},
        'quality':{'first_packet_keyframe':True,'packet_sequence_complete':True,'source_clock':'same_reader_session',
            'absolute_time_verified':False,'reencoded':False,'inference_invoked':False}}
    if any(native.get(key)!=value for key,value in expected.items()):raise MediaError('media_session_changed')
    source=folder/'source.mp4'
    if digest_file(source,16*1024**2,deadline)!=expected['video']:raise MediaError('media_source_changed')
    return source


def produce_session(store,recordings,descriptor,clip_id,*,timeout=360):
    validate_descriptor(descriptor)
    if not isinstance(clip_id,str) or str(uuid.UUID(clip_id))!=clip_id:raise MediaError('media_invalid_identity')
    if type(timeout) not in (int,float) or not 0<timeout<=360:raise MediaError('media_invalid_timeout')
    deadline=time.monotonic()+timeout
    expected={'schema_version':SCHEMA,'clip_id':clip_id,'source_id':descriptor['source_id'],'camera_id':descriptor['camera_id'],
        'requested_start_ms':0,'requested_end_ms':descriptor['duration_us']//1000,
        'source':{'bytes':descriptor['source_bytes'],'sha256':descriptor['source_sha256']},'session_input':descriptor}
    with store.building(clip_id) as (staging,final):
        # A committed result is self-contained. Core rechecks its frozen source
        # and frame bindings; it never substitutes another recording if evicted.
        if final.exists():return committed_result(final,expected,deadline)
        source=verify_native(recordings,descriptor,deadline)
        decoded=_decode(source,staging,0,expected['requested_end_ms'],deadline)
        verify_native(recordings,descriptor,deadline)
        manifest={**expected,'sample_period_ms':1000,'quality':{'window_complete':True,'source_clock':'same_session_segment_relative',
            'absolute_time_verified':False,'identity_association_verified':False,'inference_invoked':False},**decoded}
        sha=store.commit(staging,final,manifest)
    return {'clip_id':clip_id,'manifest_sha256':sha,'manifest':manifest}
