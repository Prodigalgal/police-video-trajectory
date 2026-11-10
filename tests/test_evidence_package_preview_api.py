"""Nonvisual source/transport contracts. Fixtures are not images or accuracy evidence."""
import copy,hashlib,io,json,zipfile
import asyncio,threading
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4
import httpx
import pytest
from fastapi.testclient import TestClient
from services.web_gateway.app import create_app
from services.web_gateway.evidence_package_api import source_snapshot,preview_manifest
from test_clue_api import settings
from test_simple_accounts import Directory,login,ORIGIN

COLLECTION,FRAME,OBSERVATION,OTHER=map(str,(uuid4(),uuid4(),uuid4(),uuid4()))
ASSET=b'nonvisual transport contract, not an image'
SHA=hashlib.sha256(ASSET).hexdigest()


def manifest():
    node=dict(observation_id=OBSERVATION,frame_id=FRAME,camera_id='camera-1',record_version=1,target_type='vehicle',review_state='retained',review_stale=False,available=True)
    return dict(schema_version='evidence-package-v1',collection={'collection':{'collection_id':COLLECTION,'revision':1},
        'nodes':[node,{**node,'observation_id':OTHER,'target_type':'person','review_state':'excluded','review_stale':True}],'links':[]},part=1,parts=1,frame_count=1,
        images=[{'frame_id':FRAME,'filename':'frames/'+FRAME+'.jpg','bytes':len(ASSET),'sha256':SHA}],
        observations=[dict(observation_id=OBSERVATION,frame_id=FRAME,camera_id='camera-1',camera_name='点位一',record_version=1,
            source_time=None,source_time_origin='unknown',received_at='2026-11-10T09:00:00Z',evidence=dict(available=True,bytes=len(ASSET),sha256=SHA,capture_integrity='matched'))],
        prepared_at='2026-11-10T09:00:00Z',prepared_by='reader',video_bytes_included=False)


def wrapper(value):
    payload=json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'))
    return dict(manifest_payload=payload,manifest_sha256=hashlib.sha256(payload.encode()).hexdigest(),part=1,parts=1)


def test_source_snapshot_ignores_only_volatile_prepare_metadata():
    value=manifest();other=copy.deepcopy(value);other.update(prepared_at='2026-11-10T09:01:00Z',prepared_by='another-reader')
    assert source_snapshot(other)==source_snapshot(value)
    other['collection']['nodes'][1]['review_state']='retained'
    assert source_snapshot(other)!=source_snapshot(value)


def test_preview_includes_every_same_frame_target_and_no_private_full_manifest():
    value=manifest();value['private_unused_root']='bound but not exposed'
    preview=preview_manifest(value,COLLECTION,1,1)
    assert preview['source_sha256']==source_snapshot(value) and preview['download_allowed'] is True
    assert len(preview['frames'])==1 and len(preview['frames'][0]['observations'])==2
    assert preview['frames'][0]['observations'][1]['review_state']=='excluded' and preview['frames'][0]['observations'][1]['review_stale'] is True
    assert 'private_unused_root' not in json.dumps(preview) and 'manifest_payload' not in preview


@pytest.mark.parametrize('scenario',['duplicate','wrong_frame','evidence_size','wrong_part_count','bad_review','wrong_camera','bad_sha'])
def test_preview_rejects_ambiguous_or_inconsistent_sources(scenario):
    value=manifest()
    if scenario=='duplicate':value['collection']['nodes'][1]['observation_id']=OBSERVATION
    elif scenario=='wrong_frame':value['images'][0]['frame_id']=str(uuid4())
    elif scenario=='evidence_size':value['observations'][0]['evidence']['bytes']+=1
    elif scenario=='wrong_part_count':value['parts']=2
    elif scenario=='bad_review':value['collection']['nodes'][1]['review_stale']='false'
    elif scenario=='wrong_camera':value['collection']['nodes'][1]['camera_id']='camera-2'
    else:value['images'][0]['sha256']='broken'
    with pytest.raises(ValueError):preview_manifest(value,COLLECTION,1,1)


def test_empty_preview_remains_explicit_without_extra_files():
    value=manifest();value.update(frame_count=0,images=[],observations=[]);value['collection']['nodes']=[]
    result=preview_manifest(value,COLLECTION,1,1)
    assert result['download_allowed'] is False and result['download_reason']=='empty_package' and result['image_bytes']==0


def test_metadata_capacity_check_blocks_a_large_part_before_asset_download():
    value=manifest();node=value['collection']['nodes'][0];detail=value['observations'][0]
    value['collection']['nodes']=[];value['images']=[];value['observations']=[];value['frame_count']=9
    for _ in range(9):
        frame,observation=str(uuid4()),str(uuid4());size=8*1024**2
        value['collection']['nodes'].append({**node,'frame_id':frame,'observation_id':observation})
        value['images'].append({'frame_id':frame,'filename':'frames/'+frame+'.jpg','bytes':size,'sha256':SHA})
        value['observations'].append({**detail,'frame_id':frame,'observation_id':observation,'evidence':{**detail['evidence'],'bytes':size}})
    result=preview_manifest(value,COLLECTION,1,1)
    assert result['image_bytes']==72*1024**2 and result['download_allowed'] is False and result['download_reason']=='package_size_limit'


def test_preview_checks_account_and_forwards_authoritative_actor_revision():
    cfg=settings();directory=Directory(cfg);calls=[]
    def core(request):
        if not request.url.path.endswith('/package-manifest'):return directory.handle(request)
        calls.append(request);assert request.headers['x-actor-id']=='reader' and request.headers['x-actor-revision']=='1'
        return httpx.Response(200,json=wrapper(manifest()))
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        url=f'/api/v1/collections/{COLLECTION}/package-preview?revision=1&part=1'
        assert client.get(url).status_code==401
        login(client,'reader');assert client.get(url).status_code==404
        cfg['investigation']=True;response=client.get(url,headers={'X-Actor-Id':'admin'})
        assert response.status_code==200 and response.headers['cache-control']=='no-store' and len(calls)==1
        assert client.get(url.replace('part=1','part=14')).status_code==422
        assert client.get(url.replace('revision=1','revision=9007199254740992')).status_code==422


def test_preparation_is_bounded_and_authentication_precedes_the_busy_response():
    cfg=settings();cfg['investigation']=True;directory=Directory(cfg);entered=threading.Event();release=threading.Event()
    async def pending_manifest():
        entered.set();assert await asyncio.to_thread(release.wait,5)
        return httpx.Response(200,json=wrapper(manifest()))
    def core(request):
        return pending_manifest() if request.url.path.endswith('/package-manifest') else directory.handle(request)
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client,ThreadPoolExecutor(max_workers=1) as executor:
        assert login(client,'reader').status_code==200
        url=f'/api/v1/collections/{COLLECTION}/package-preview?revision=1&part=1'
        first=executor.submit(client.get,url)
        try:
            assert entered.wait(5)
            assert client.get(url).status_code==429
            assert client.get(url,headers={'Cookie':''}).status_code==401
        finally:release.set()
        assert first.result(timeout=5).status_code==200


def test_malformed_manifest_releases_preparation_for_the_next_request():
    cfg=settings();cfg['investigation']=True;directory=Directory(cfg);broken=True
    def core(request):
        if not request.url.path.endswith('/package-manifest'):return directory.handle(request)
        return httpx.Response(200,json=wrapper([] if broken else manifest()))
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        login(client,'reader');url=f'/api/v1/collections/{COLLECTION}/package-preview?revision=1&part=1'
        assert client.get(url).status_code==503
        broken=False;assert client.get(url).status_code==200


@pytest.mark.parametrize('change',[None,'legacy','stale_preview','review_during_download','description_during_download'])
def test_download_binds_preview_and_rechecks_full_source_even_with_same_image(change):
    cfg=settings();cfg['investigation']=True;directory=Directory(cfg);value=manifest();reads=[];calls=0
    preview_sha=source_snapshot(value)
    if change=='stale_preview':value['collection']['nodes'][0]['review_state']='excluded'
    def core(request):
        nonlocal calls
        if request.url.path.endswith('/package-manifest'):
            calls+=1;prepared=copy.deepcopy(value);prepared['prepared_at']=f'2026-11-10T09:00:0{calls}Z'
            return httpx.Response(200,json=wrapper(prepared))
        if request.url.path==f'/api/v1/frames/{FRAME}/image':
            reads.append(request)
            assert request.headers['authorization']=='Bearer test-core-secret'
            if change=='review_during_download':value['collection']['nodes'][1]['review_state']='retained'
            if change=='description_during_download':value['observations'][0]['description']='描述已更新'
            return httpx.Response(200,content=ASSET)
        return directory.handle(request)
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        login(client,'reader');url=f'/api/v1/collections/{COLLECTION}/package?revision=1&part=1'
        if change!='legacy':url+='&expected_source_sha256='+preview_sha
        result=client.get(url)
        if change not in {None,'legacy'}:
            assert result.status_code==409
            if change=='stale_preview':assert not reads
        else:
            assert result.status_code==200 and hashlib.sha256(result.content).hexdigest()==result.headers['x-content-sha256']
            with zipfile.ZipFile(io.BytesIO(result.content)) as bundle:
                assert bundle.read('frames/'+FRAME+'.jpg')==ASSET
                assert json.loads(bundle.read('source-snapshot.json'))['sha256']==preview_sha
                assert 'manifest.sha256' in bundle.namelist()
            assert calls==2
