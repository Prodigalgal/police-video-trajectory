"""Lifecycle/selection unit fixtures do not represent recognition accuracy."""
import asyncio
from copy import deepcopy
import hashlib
import json
from pathlib import Path
import threading
from concurrent.futures import ThreadPoolExecutor
from uuid import uuid4

import certifi
import numpy as np
import pytest

from services.scene_analysis.specialist_selection import SpecialistSelector, POLICY_SHA256
from services.video_worker.client import ServiceError
from services.video_worker.processing_catalog import ProcessingCatalog, PluginSlot, validate_catalog, fingerprint
from services.video_worker.selection_runtime import SelectionRuntime
from services.video_worker.specialist_consumer import SpecialistSettings
from services.video_worker.specialist_runtime import SpecialistRuntimeClient


def profile(name='plate-a'):
    return {'profile': name, 'adapter': 'specialist_sse_v1', 'capabilities': ['license_plate'],
            'base_url': 'https://example.invalid/' + name, 'token_file': 'token', 'ca_file': 'ca.pem',
            'model_id': name, 'contract': 'specialist-result-v1', 'streaming': True, 'timeout_seconds': 360}


def catalog(revision=1, active='plate-a'):
    return {'schema_version': 'processing-catalog-v1', 'revision': revision,
            'modules': {'license_plate': {'enabled': True, 'profile': active},
                        'specialist_selection': {'enabled': True, 'plugin': 'rules_v1', 'mode': 'select', 'policy_sha256': POLICY_SHA256},
                        'face_quality': {'enabled': False}}, 'profiles': [profile('plate-a'), profile('plate-b')]}


def write_catalog(path, value):
    temporary = path.with_suffix('.new')
    temporary.write_text(json.dumps(value), encoding='utf-8')
    temporary.replace(path)


def test_catalog_hot_bind_disable_restore_and_immutable_snapshot(tmp_path):
    path = tmp_path / 'catalog.json'
    write_catalog(path, catalog())
    registry = ProcessingCatalog(path)
    first = registry.current()
    write_catalog(path, catalog(2, 'plate-b'))
    assert registry.current().module('license_plate')['profile'] == 'plate-b'
    assert first.module('license_plate')['profile'] == 'plate-a'
    disabled = catalog(3)
    disabled['modules']['license_plate']['enabled'] = False
    write_catalog(path, disabled)
    assert not registry.current().module('license_plate')['enabled']
    write_catalog(path, catalog(4))
    assert registry.current().module('license_plate')['profile'] == 'plate-a'


@pytest.mark.parametrize('fault', ['revision', 'rollback', 'profile', 'broken'])
def test_catalog_bad_update_does_not_silently_serve_cached_binding(tmp_path, fault):
    path = tmp_path / 'catalog.json'
    write_catalog(path, catalog(2))
    registry = ProcessingCatalog(path)
    registry.current()
    value = catalog(2 if fault == 'revision' else 1 if fault == 'rollback' else 3, 'plate-b')
    if fault == 'profile':
        value['profiles'][0]['base_url'] = 'https://changed.invalid/'
    if fault == 'broken':
        path.write_text('{', encoding='utf-8')
    else:
        write_catalog(path, value)
    with pytest.raises(ValueError):
        registry.current()


@pytest.mark.parametrize('fault', ['stream', 'timeout', 'http', 'secret_path', 'unknown_plugin', 'face_identity', 'missing_capability', 'duplicate_profile', 'boolean_revision'])
def test_catalog_rejects_unsupported_contracts(fault):
    value = catalog()
    if fault == 'stream': value['profiles'][0]['streaming'] = False
    if fault == 'timeout': value['profiles'][0]['timeout_seconds'] = 60
    if fault == 'http': value['profiles'][0]['base_url'] = 'http://example.invalid/'
    if fault == 'secret_path': value['profiles'][0]['token_file'] = '../token'
    if fault == 'unknown_plugin': value['modules']['specialist_selection']['plugin'] = 'arbitrary_import'
    if fault == 'face_identity': value['modules']['face_quality']['enabled'] = True
    if fault == 'missing_capability': value['profiles'][0]['capabilities'] = ['text_ocr']
    if fault == 'duplicate_profile': value['profiles'].append(profile())
    if fault == 'boolean_revision': value['revision'] = True
    with pytest.raises(ValueError): validate_catalog(value)


class Stage:
    def __init__(self, name): self.name, self.closed = name, False
    def close(self): self.closed = True


def test_stage_switch_disable_and_restore_do_not_close_active_instance():
    stages = []
    def factory(configuration):
        value = Stage(configuration['name']); stages.append(value); return value
    slot = PluginSlot({'installed': factory})
    slot.configure('a', 'installed', {'name': 'a'})
    lease = slot.acquire('a')
    first = lease.__enter__()
    slot.configure('b', 'installed', {'name': 'b'})
    assert not first.closed
    slot.configure('a', 'installed', {'name': 'a-new'})
    with slot.acquire('a') as fresh:
        assert fresh is not first and fresh.name == 'a-new'
    lease.__exit__(None, None, None)
    assert first.closed and not fresh.closed
    slot.disable()
    assert fresh.closed and all(stage.closed for stage in stages)


def test_late_warmup_cannot_overwrite_newer_stage():
    waiting, release = threading.Event(), threading.Event()
    def factory(configuration):
        if configuration['name'] == 'slow':
            waiting.set(); assert release.wait(3)
        return Stage(configuration['name'])
    slot = PluginSlot({'installed': factory})
    with ThreadPoolExecutor(max_workers=1) as pool:
        slow = pool.submit(slot.configure, 'slow', 'installed', {'name': 'slow'})
        assert waiting.wait(3)
        slot.configure('new', 'installed', {'name': 'new'})
        release.set()
        with pytest.raises(ValueError, match='superseded'): slow.result()
    with slot.acquire('new') as value: assert value.name == 'new'
    slot.close()


def context(now=0, camera='recording-01', run=None):
    return {'camera_id': camera, 'scene_run_id': run or str(uuid4()), 'media_epoch': 0, 'relative_media_ms': now,
            'specialist_intent': 'verify_vehicle'}


class Probe:
    def locate(self, image):
        return {'state': 'complete', 'adapter': 'opencv_plate_v1', 'model_sha256': 'c'*64,
                'regions': [{'bbox_xyxy': [200, 300, 320, 340], 'localization_score': .9}]}

    def close(self):
        pass


def target(kind='vehicle', track=None):
    return {'target_id': str(uuid4()), 'track_id': track or str(uuid4()), 'association': 'local_geometry_candidate',
            'object_type': kind, 'class_name': 'car' if kind == 'vehicle' else 'person', 'bbox_xyxy': [100, 100, 500, 400]}


@pytest.fixture
def quality(monkeypatch):
    state = {'rank': .6}
    monkeypatch.setattr('services.scene_analysis.specialist_selection.target_quality', lambda image, value: {
        'state': 'scored', 'geometry': {'width_px': value['bbox_xyxy'][2]-value['bbox_xyxy'][0], 'height_px': value['bbox_xyxy'][3]-value['bbox_xyxy'][1]},
        'ranks': {'target_balanced': state['rank']}})
    return state


def test_selector_cooldown_improvement_replay_and_context_reset(quality):
    selector = SpecialistSelector('select', Probe()); image = np.zeros((600, 1000, 3), dtype=np.uint8)
    meta, vehicle = context(), target(); digest = 'a'*64
    def select(now):
        return selector.select(image, [vehicle], {**meta, 'relative_media_ms': now}, str(uuid4()), digest, detection_complete=True)
    first = select(0); assert first['modules'][0]['decision'] == 'selected'
    assert select(500)['modules'][0]['decision'] == 'skipped'
    quality['rank'] = .72
    assert select(1000)['modules'][0]['decision'] == 'selected'
    quality['rank'] = .95
    assert select(2000)['modules'][0]['decision'] == 'selected'
    quality['rank'] = 1.0
    assert select(3000)['modules'][0]['targets'][0]['reasons'] == ['event_recommendation_limit']
    result = select(100)
    assert result['context_reset'] and result['modules'][0]['decision'] == 'selected'
    frame = str(uuid4()); meta['relative_media_ms'] = 200
    one = selector.select(image, [vehicle], meta, frame, digest, detection_complete=True)
    assert selector.select(image, [vehicle], meta, frame, digest, detection_complete=True) == one
    with pytest.raises(ValueError, match='replay changed'):
        selector.select(image, [], meta, frame, digest, detection_complete=True)


def test_selector_small_bad_unknown_and_person_are_not_full_frame_recognition(quality):
    selector = SpecialistSelector('select', Probe()); image = np.zeros((600, 1000, 3), dtype=np.uint8)
    vehicle = target(); vehicle['bbox_xyxy'] = [1, 1, 50, 30]
    person = target('person')
    result = selector.select(image, [vehicle, person], context(), str(uuid4()), 'a'*64, detection_complete=True)
    assert result['modules'][0]['decision'] == 'deferred'
    assert result['modules'][1]['decision'] == 'deferred' and not result['modules'][1]['identity_matching']
    assert not result['frame_suppression'] and not result['semantic_modules_changed']
    assert selector.select(image, [], context(), str(uuid4()), 'a'*64, detection_complete=False)['modules'][0]['decision'] == 'deferred'
    assert selector.select(image, [], context(), str(uuid4()), 'a'*64, detection_complete=True)['modules'][0]['decision'] == 'skipped'


def test_selector_history_and_target_budget_are_bounded(quality):
    selector = SpecialistSelector('select', Probe()); image = np.zeros((600, 1000, 3), dtype=np.uint8); meta = context()
    for now in range(0, 3000, 10):
        result = selector.select(image, [target()], {**meta, 'relative_media_ms': now}, str(uuid4()), 'a'*64, detection_complete=True)
        assert len(result['modules'][0]['selected_target_ids']) <= 1
    assert len(selector.entries) <= 256


def test_selection_stage_hot_disable_preserves_frame_and_has_no_face_fallback(tmp_path, quality):
    path = tmp_path / 'catalog.json'; value = catalog(); write_catalog(path, value)
    runtime = SelectionRuntime(catalog_file=path)
    image = np.zeros((600,1000,3),dtype=np.uint8); metadata=context(); vehicle=target()
    result = runtime.select(image,[vehicle],metadata,str(uuid4()),'a'*64,detection_complete=True)
    assert result['mode']=='select' and result['catalog_revision']==1
    value=catalog(2);value['modules']['specialist_selection']['enabled']=False;write_catalog(path,value)
    disabled=runtime.select(image,[vehicle],metadata,str(uuid4()),'a'*64,detection_complete=True)
    assert disabled['state']=='disabled' and not disabled['frame_suppression']
    runtime.close()


@pytest.mark.parametrize('fault,reason', [
    ('no_probe', 'plate_probe_unavailable'), ('no_plate', 'plate_not_visible'),
    ('tiny', 'wait_for_larger_plate_image'), ('blur', 'wait_for_better_plate_image'),
    ('two_plates', 'plate_association_ambiguous'), ('two_vehicles', 'plate_association_ambiguous'),
    ('probe_failure', 'plate_probe_unavailable')])
def test_plate_region_is_required_before_character_admission(quality, fault, reason):
    class EvidenceProbe(Probe):
        def locate(self, image):
            if fault == 'probe_failure': raise RuntimeError('detector failed')
            value = super().locate(image)
            if fault == 'no_plate': value['regions'] = []
            if fault == 'tiny': value['regions'][0]['bbox_xyxy'] = [200, 300, 230, 310]
            if fault == 'two_plates': value['regions'] *= 2
            return value
    if fault == 'blur': quality['rank'] = .2
    vehicles = [target()]
    if fault == 'two_vehicles': vehicles.append(target())
    selector = SpecialistSelector('select', None if fault == 'no_probe' else EvidenceProbe())
    result = selector.select(np.zeros((600, 1000, 3), np.uint8), vehicles, context(), str(uuid4()), 'a'*64, detection_complete=True)
    assert not result['modules'][0]['selected_target_ids']
    assert result['modules'][0]['targets'][0]['reasons'] == [reason]
    assert result['modules'][0]['business_state'] == 'waiting_evidence'


def test_stationary_only_is_not_a_passage_but_recent_moving_stop_can_improve(quality):
    selector = SpecialistSelector('select', Probe()); vehicle = target()
    image = np.zeros((600, 1000, 3), np.uint8)
    meta = {**context(), 'specialist_intent': 'collect_vehicle_passages'}
    def select(now, motion):
        return selector.select(image, [vehicle], {**meta, 'relative_media_ms': now,
            'specialist_motion': {vehicle['target_id']: motion}}, str(uuid4()), 'a'*64, detection_complete=True)['modules'][0]
    assert select(0, 'stationary')['business_state'] == 'not_applicable'
    assert select(1000, 'unknown')['targets'][0]['reasons'] == ['passage_evidence_required']
    moving = select(2000, 'moving'); assert moving['decision'] == 'selected'
    quality['rank'] = .8
    stopped = select(3000, 'stationary'); assert stopped['decision'] == 'selected'
    assert stopped['targets'][0]['event_id'] == moving['targets'][0]['event_id']
    assert stopped['targets'][0]['passage_evidence'] == 'recent_moving_observed'
    assert select(14000, 'stationary')['business_state'] == 'not_applicable'


def test_only_plate_quality_is_scored_and_recommendation_is_not_completed_inference(quality):
    selector = SpecialistSelector('select', Probe()); vehicle = target()
    value = selector.select(np.zeros((600, 1000, 3), np.uint8), [vehicle], context(), str(uuid4()), 'a'*64, detection_complete=True)
    candidate = value['modules'][0]['targets'][0]
    assert candidate['native_width_px'] == 120 and candidate['native_height_px'] == 40
    assert candidate['quality_scope'] == 'localized_plate_not_character_correctness'
    assert candidate['plate_evidence']['frame_id'] == value['frame_id']
    assert not value['recommendation_is_inference_completion']


def test_probe_config_is_fixed_adapter_and_safe_file_reference():
    value = catalog(); module = value['modules']['specialist_selection']
    module['plate_probe'] = {'adapter': 'opencv_plate_v1', 'model_file': 'detector.onnx', 'model_sha256': 'a'*64}
    validate_catalog(value)
    for key, invalid in [('adapter', 'uninstalled'), ('model_file', '../model.onnx'), ('model_sha256', 'wrong')]:
        changed = deepcopy(value); changed['modules']['specialist_selection']['plate_probe'][key] = invalid
        with pytest.raises(ValueError): validate_catalog(changed)


def job(name='plate-a'):
    descriptor={'schema_version':'specialist-input-v1','input_id':str(uuid4()),'input_manifest_sha256':'a'*64,
                'frame_id':str(uuid4()),'camera_id':'recording-01','evidence_sha256':hashlib.sha256(b'unit').hexdigest(),
                'image_width':1000,'image_height':600,'operation':'license_plate','profile':name}
    route={'schema_version':'specialist-route-v1','catalog_revision':1,'catalog_sha256':'b'*64,'definition':profile(name)}
    return {'jobId':str(uuid4()),'generation':1,'inputSha256':fingerprint(descriptor),'descriptor':descriptor,
            'routing':route,'routingSha256':fingerprint(route)}


def credentials(tmp_path):
    (tmp_path/'token').write_text('unit-token-a')
    (tmp_path/'ca.pem').write_bytes(Path(certifi.where()).read_bytes())
    return SpecialistSettings(enabled=True,accept_routing=True,secrets_root=str(tmp_path))


def test_model_switch_credential_rotation_and_close_wait_for_leases(tmp_path):
    async def scenario():
        created=[];entered=asyncio.Event();release=asyncio.Event()
        class Adapter:
            def __init__(self,config,**kwargs):self.config,self.closed=config,False;created.append(self)
            async def recognize(self,request,image):
                if self.config.token=='unit-token-a': entered.set();await release.wait()
                assert not self.closed
                return self.config.profile,self.config.token
            async def close(self):self.closed=True
        pool=SpecialistRuntimeClient(credentials(tmp_path),factories={'specialist_sse_v1':Adapter})
        first=asyncio.create_task(pool.recognize(job(),b'unit'));await entered.wait()
        (tmp_path/'token').write_text('unit-token-b')
        assert await pool.recognize(job(),b'unit')==('plate-a','unit-token-b')
        assert not created[0].closed
        assert await pool.recognize(job('plate-b'),b'unit')==('plate-b','unit-token-b')
        await pool.close();assert not created[0].closed
        release.set();assert await first==('plate-a','unit-token-a')
        assert all(adapter.closed for adapter in created)
        with pytest.raises(ServiceError):await pool.recognize(job(),b'unit')
    asyncio.run(scenario())


def test_model_pool_capacity_does_not_evict_busy_adapter(tmp_path):
    async def scenario():
        entered=asyncio.Event();release=asyncio.Event();created=[]
        class Adapter:
            def __init__(self,config,**kwargs):self.closed=False;created.append(self)
            async def recognize(self,request,image):entered.set();await release.wait();assert not self.closed;return {}
            async def close(self):self.closed=True
        pool=SpecialistRuntimeClient(credentials(tmp_path),factories={'specialist_sse_v1':Adapter},max_entries=1)
        active=asyncio.create_task(pool.recognize(job(),b'unit'));await entered.wait()
        with pytest.raises(ServiceError):await pool.recognize(job('plate-b'),b'unit')
        assert not created[0].closed
        release.set();await active;await pool.close();assert created[0].closed
    asyncio.run(scenario())


def test_tampered_route_never_constructs_adapter(tmp_path):
    async def scenario():
        def forbidden(*args,**kwargs):pytest.fail('tampered route reached adapter')
        pool=SpecialistRuntimeClient(credentials(tmp_path),factories={'specialist_sse_v1':forbidden})
        request=job();request['routing']['definition']['base_url']='https://changed.invalid/'
        with pytest.raises(ValueError):await pool.recognize(request,b'unit')
        await pool.close()
    asyncio.run(scenario())
