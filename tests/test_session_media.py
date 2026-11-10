"""Provenance and failure contracts only; protocol bytes are not scene labels."""
import hashlib
import json
from pathlib import Path
from uuid import uuid4

import httpx
import pytest
from fastapi.testclient import TestClient
from services.video_worker import session_media as session
from services.video_worker.media_clips import ClipStore,MediaError,canonical,digest_file
from services.video_worker.media_producer import validate_job
from services.web_gateway.app import create_app
from test_clue_api import settings
from test_simple_accounts import Directory,login,ORIGIN


def descriptor():
    return {'version':session.VERSION,'source_id':str(uuid4()),'camera_id':'recording-01','source_fingerprint':'a'*64,
        'namespace':'b'*32,'segment_id':str(uuid4()),'recording_manifest_sha256':'c'*64,'source_sha256':'d'*64,
        'source_bytes':20,'duration_us':5000000,'task_id':str(uuid4()),'task_generation':1,'scene_run_id':str(uuid4()),'media_epoch':0,'base_pts_us':12345678}


def job(value):
    return {'jobId':str(uuid4()),'sourceId':value['source_id'],'cameraId':value['camera_id'],'startMs':0,'endMs':value['duration_us']//1000,
        'profileVersion':session.PROFILE,'inputKind':'session','sourceFingerprint':value['source_fingerprint'],'sourceBytes':value['source_bytes'],
        'sourceSha256':value['source_sha256'],'inputSha256':hashlib.sha256(canonical(session.input_value(value))).hexdigest(),'generation':1,'sessionInput':value}


@pytest.mark.parametrize('field,value',[('namespace','../other'),('source_id','not-a-uuid'),('duration_us',True),('duration_us',1999999),('duration_us',20000001),
    ('base_pts_us',2**63-2),('media_epoch',-1),('source_bytes',16*1024**2+1),('unknown',1),('camera_id','../recording-01')])
def test_session_descriptor_refuses_unsafe_or_ambiguous_metadata(field,value):
    record=descriptor();session.validate_descriptor(record);record[field]=value
    with pytest.raises(ValueError):session.validate_descriptor(record)


@pytest.mark.parametrize('field,value',[('inputKind','archive'),('profileVersion','archive-h264-10fps-v1'),('startMs',True),('endMs',6000),('sourceSha256','e'*64),('sourceBytes',10),('cameraId','recording-02'),('inputSha256','e'*64)])
def test_session_job_rechecks_outer_input_and_digest(field,value):
    record=job(descriptor());validate_job(record);record[field]=value
    with pytest.raises(ValueError):validate_job(record)


def protocol_native(root):
    value=descriptor();folder=root/value['namespace']/value['segment_id'].replace('-','');folder.mkdir(parents=True)
    (folder/'owner.json').write_bytes(canonical({'schema':'live-segment-owner-v1','segment_id':value['segment_id']}))
    (folder/'source.mp4').write_bytes(b'native-protocol-payload');original=digest_file(folder/'source.mp4',100)
    value.update(source_bytes=original['bytes'],source_sha256=original['sha256'])
    native={'schema_version':'same-session-recording-v1','segment_id':value['segment_id'],'namespace':value['namespace'],
        'camera_id':value['camera_id'],'task_id':value['task_id'],'task_generation':1,'scene_run_id':value['scene_run_id'],'media_epoch':0,
        'duration_us':value['duration_us'],'playback_base_pts_us':value['base_pts_us'],'codec':'h264','video':original,
        'quality':{'first_packet_keyframe':True,'packet_sequence_complete':True,'source_clock':'same_reader_session',
            'absolute_time_verified':False,'reencoded':False,'inference_invoked':False}}
    (folder/'manifest.json').write_bytes(canonical(native));value['recording_manifest_sha256']=hashlib.sha256(canonical(native)).hexdigest()
    return value,folder


@pytest.mark.parametrize('part',['owner','manifest','video'])
def test_native_tampering_stops_before_decoder(tmp_path,monkeypatch,part):
    value,folder=protocol_native(tmp_path/'live')
    (folder/({'owner':'owner.json','manifest':'manifest.json','video':'source.mp4'}[part])).write_bytes(b'{}')
    monkeypatch.setattr(session,'_decode',lambda *args:pytest.fail('unverified source reached decoder'))
    with pytest.raises(ValueError):session.produce_session(ClipStore(tmp_path/'clips'),tmp_path/'live',value,str(uuid4()))
    assert not list((tmp_path/'clips').glob('.building-*'))


@pytest.mark.parametrize('duration',[5_000_000,40_000_000])
def test_committed_session_replays_without_encoding_or_original_and_rechecks_sample_bytes(tmp_path,monkeypatch,duration):
    value,folder=protocol_native(tmp_path/'live');identity=str(uuid4());calls=[]
    if duration>20_000_000:
        value.update(version=session.CONTEXT_VERSION,duration_us=duration)
        native=json.loads((folder/'manifest.json').read_bytes());native.update(schema_version='same-session-recording-v2',duration_us=duration,context_window={'policy':'overlap-keyframe-v1','target_seconds':10})
        (folder/'manifest.json').write_bytes(canonical(native));value['recording_manifest_sha256']=hashlib.sha256(canonical(native)).hexdigest()
    def decode(source,staging,start,end,deadline):
        calls.append(source);samples=[]
        for index in range(40 if duration>20_000_000 else 2):
            path=staging/f'sample-{index:03}.jpg';path.write_bytes(b'sample-protocol-'+str(index).encode())
            samples.append({'index':index,'source_pts_us':index*1000000,'offset_ms':index*1000,**digest_file(path,100)})
        (staging/'clip.mp4').write_bytes(b'derivative-protocol')
        return {'samples':samples,'video':digest_file(staging/'clip.mp4',100)}
    monkeypatch.setattr(session,'_decode',decode);store=ClipStore(tmp_path/'clips')
    first=session.produce_session(store,tmp_path/'live',value,identity)
    (folder/'source.mp4').unlink()
    assert session.produce_session(store,tmp_path/'live',value,identity)==first and len(calls)==1
    assert first['manifest']['session_input']['base_pts_us']==value['base_pts_us']
    (store.root/identity.replace('-','')/'sample-001.jpg').write_bytes(b'wrong-sample')
    with pytest.raises(MediaError,match='conflicting_replay'):session.produce_session(store,tmp_path/'live',value,identity)


def test_gateway_frame_jobs_require_auth_origin_strict_dto_and_conditional_feature():
    config=settings();directory=Directory(config);calls=[];frame=str(uuid4())
    def upstream(request):
        if not request.url.path.endswith('/media-jobs'):return directory.handle(request)
        calls.append(request);return httpx.Response(200,json={'items':[],'has_more':False,'next_cursor':None})
    body={'request_id':str(uuid4()),'recording_manifest_sha256':'a'*64}
    with TestClient(create_app(config,httpx.MockTransport(upstream)),base_url=ORIGIN) as client:
        path=f'/api/v1/frames/{frame}/media-jobs'
        assert client.get(path).status_code==401
        login(client,'reader');assert client.get(path).status_code==404
        config['media_jobs']=True
        assert 'media_jobs' not in client.get('/api/v1/features').json()
        config['media_clips']=True
        assert client.get('/api/v1/features').json()['media_jobs'] is True
        assert client.post(path,json=body).status_code==403
        assert client.post(path,headers={'Origin':ORIGIN},json={**body,'file_path':'secret'}).status_code==422
        assert client.post(path,headers={'Origin':ORIGIN},json=body).status_code==200
        assert json.loads(calls[-1].content)=={'requestId':body['request_id'],'recordingManifestSha256':'a'*64}
        assert client.get(path+'?limit=1').status_code==200 and calls[-1].url.params['limit']=='1'
