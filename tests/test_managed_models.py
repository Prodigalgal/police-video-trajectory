"""Configuration contracts use protocol fixtures, never field media or credentials."""
import hashlib
import json
import time
from dataclasses import replace
from uuid import uuid4

import httpx
import pytest

from services.ai_gateway.config import ModelConfig,Settings
from services.ai_gateway.client import GatewayError
from services.ai_gateway.model_runtime import ModelRuntime
from test_simple_accounts import Directory,config,client,login,ORIGIN


def snapshot(tmp_path,revision=1,model='first-model',protocol='chat_completions',vision=True):
    (tmp_path/'provider.token').write_text('protocol-fixture-token')
    definition=dict(display_name='协议模型',provider='approved',model=model,protocol=protocol,roles=['text','vision'],
        image_transport='file',output_mode='text',json_mode=False,reasoning_effort='',base_url='https://provider.invalid/v1',credential_file='provider.token')
    binding=dict(profile_id='11111111-2222-4333-8444-555555555555',version=revision,definition=definition)
    value=dict(schema_version='model-bindings-v1',revision=revision,managed=True,bindings={'text':binding,'vision':binding if vision else None})
    value['sha256']=hashlib.sha256(json.dumps({key:value[key] for key in ('revision','bindings')},ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    return value


def runtime(tmp_path,transport=None):
    return ModelRuntime(Settings(shared_token='protocol-control-token',control_url='https://core.invalid',credentials_root=str(tmp_path)),transport or httpx.MockTransport(lambda request:httpx.Response(503)))


@pytest.mark.asyncio
async def test_model_binding_freezes_inflight_config_and_disables_new_vision(tmp_path):
    resolver=runtime(tmp_path)
    try:
        resolver.apply(snapshot(tmp_path));original=resolver.resolve('vision')
        resolver.apply(snapshot(tmp_path,2,model='second-model',protocol='responses',vision=False))
        assert original.model=='first-model' and original.image_transport=='file'
        assert resolver.resolve('text').model=='second-model' and resolver.resolve('text').style=='responses'
        assert resolver.resolve('text').image_transport=='inline'
        assert original.binding_id!=resolver.resolve('text').binding_id
        with pytest.raises(GatewayError,match='已停用'):resolver.resolve('vision')
        resolver.apply(snapshot(tmp_path,3,model='first-model'))
        assert resolver.resolve('vision').model=='first-model'
    finally:await resolver.close()


@pytest.mark.asyncio
async def test_runtime_rejects_rollback_tampering_escaped_secret_and_stale_config(tmp_path):
    resolver=runtime(tmp_path)
    try:
        resolver.apply(snapshot(tmp_path,2))
        for value in [snapshot(tmp_path,1),{**snapshot(tmp_path,3),'sha256':'0'*64},{**snapshot(tmp_path,2),'managed':False}]:
            with pytest.raises(ValueError):resolver.apply(value)
        with pytest.raises(ValueError):resolver._credential('../outside.token')
        external=tmp_path.parent/'external.token';external.write_text('fixture-token')
        try:
            link=tmp_path/'escape.token'
            try:link.symlink_to(external)
            except OSError:pass
            else:
                with pytest.raises(ValueError):resolver._credential('escape.token')
        finally:external.unlink()
        resolver.refreshed_at=time.monotonic()-31
        with pytest.raises(GatewayError,match='同步'):resolver.resolve('text')
    finally:await resolver.close()


@pytest.mark.asyncio
async def test_runtime_refresh_acknowledges_only_valid_loaded_snapshot_and_recovers(tmp_path):
    value=snapshot(tmp_path);calls=[];broken=False
    def serve(request):
        calls.append(request)
        assert request.headers['authorization']=='Bearer protocol-control-token'
        if request.method=='POST':
            assert json.loads(request.content)==dict(consumer_id='ai-gateway',revision=1,sha256=value['sha256'])
            return httpx.Response(200,json={'accepted':True})
        return httpx.Response(503) if broken else httpx.Response(200,json=value)
    resolver=runtime(tmp_path,httpx.MockTransport(serve))
    try:
        await resolver.refresh();assert resolver.fresh and resolver.revision==1
        broken=True;await resolver.refresh();assert resolver.fresh and resolver.resolve('text').model=='first-model'
        resolver.refreshed_at=time.monotonic()-31
        await resolver.refresh();assert not resolver.fresh
        broken=False;await resolver.refresh();assert resolver.fresh
        assert sum(request.method=='POST' for request in calls)==1
    finally:await resolver.close()


def test_legacy_environment_mode_stays_compatible_and_config_repr_hides_key():
    config=ModelConfig(api_url='https://provider.invalid/v1/chat/completions',api_key='protocol-sensitive-value',model='legacy')
    resolver=ModelRuntime(Settings(text=config,vision=config))
    assert resolver.resolve('vision') is config and resolver.fresh
    assert 'protocol-sensitive-value' not in repr(config)


def test_model_management_proxy_authorization_csrf_and_revision_contract(config):
    directory=Directory(config)
    with client(config,directory) as browser:
        assert browser.get('/api/admin/models/configuration').status_code==401
        assert login(browser).status_code==200
        profile=str(uuid4());body=dict(request_id=str(uuid4()),expected_version=0,definition=dict(display_name='测试',provider='approved',model='model',protocol='responses',roles=['text']))
        assert browser.put('/api/admin/models/profiles/'+profile,json=body).status_code==403
        response=browser.put('/api/admin/models/profiles/'+profile,headers={'Origin':ORIGIN},json=body)
        assert response.status_code==200
        method,path,headers,actual=directory.calls[-1]
        assert method=='PUT' and path.endswith(profile) and headers['x-actor-id']=='admin'
        assert actual['definition']['protocol']=='responses' and actual['expected_version']==0
        assert 'api_key' not in actual['definition']
        assert browser.put('/api/admin/models/profiles/'+profile,headers={'Origin':ORIGIN},json={**body,'definition':{**body['definition'],'api_key':'refuse-this-field'}}).status_code==422
        assert browser.post('/api/admin/models/bindings',headers={'Origin':ORIGIN},json=dict(request_id=str(uuid4()),expected_revision=1,bindings={'text':None,'vision':None})).status_code==200
        assert browser.post('/api/admin/models/bindings',headers={'Origin':ORIGIN},json=dict(request_id=str(uuid4()),expected_revision=1,bindings={'text':None,'vision':None,'face':None})).status_code==422
    with client(config,directory) as reader:
        assert login(reader,'reader').status_code==200
        assert reader.get('/api/admin/models/configuration').status_code==403


@pytest.mark.asyncio
async def test_bad_snapshot_never_replaces_last_verified_binding(tmp_path):
    resolver=runtime(tmp_path)
    try:
        value=snapshot(tmp_path);resolver.apply(value);before=resolver.resolve('vision')
        damaged=snapshot(tmp_path,2);damaged['bindings']['vision']['definition']['credential_file']='absent.token'
        damaged['sha256']=hashlib.sha256(json.dumps({key:damaged[key] for key in ('revision','bindings')},ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        with pytest.raises(OSError):resolver.apply(damaged)
        assert resolver.resolve('vision') is before and resolver.revision==1
    finally:await resolver.close()


def test_requested_plate_proxy_keeps_intent_and_admin_consumer_control_separate(config):
    settings={**config,'specialist_jobs':True,'analysis_inputs':True};directory=Directory(settings)
    with client(settings,directory) as browser:
        assert login(browser).status_code==200
        identity=str(uuid4());payload=dict(request_id=str(uuid4()),input_manifest_sha256='a'*64,operation='license_plate',requested_intent='verify_vehicle')
        assert browser.post('/api/v1/analysis-inputs/'+identity+'/specialist-jobs',headers={'Origin':ORIGIN},json=payload).status_code==200
        assert directory.calls[-1][3]['requestedIntent']=='verify_vehicle'
        assert browser.get('/api/v1/analysis-inputs/'+identity+'/specialist-selection?intent=plate_query').status_code==200
        assert browser.post('/api/v1/analysis-inputs/'+identity+'/specialist-jobs',headers={'Origin':ORIGIN},json={**payload,'operation':'text_ocr'}).status_code==422
        assert browser.post('/api/admin/specialist-control',json={'enabled':True,'expected_revision':1}).status_code==403
        assert browser.post('/api/admin/specialist-control',headers={'Origin':ORIGIN},json={'enabled':True,'expected_revision':1}).status_code==200
    with client(settings,directory) as reader:
        assert login(reader,'reader').status_code==200
        assert reader.get('/api/admin/specialist-control').status_code==403
