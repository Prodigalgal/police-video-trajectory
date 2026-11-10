"""Metadata protocol contracts; these fixtures never send inference or field media."""
import asyncio,json
import httpx
import pytest
from fastapi.testclient import TestClient
from services.ai_gateway.config import Settings
from services.ai_gateway.app import create_app
from services.ai_gateway.provider_catalog import ProviderCatalog,CatalogRejected,normalize_models,configuration_sha,MAX_RESPONSE
from test_simple_accounts import Directory,config,client,login,ORIGIN


def registry(tmp_path,**updates):
    provider=dict(id='approved',label='已配置提供商',base_url='https://provider.invalid/v1',credential_file='approved.token',
                  protocols=['responses','chat_completions'],roles=['vision','text'],activation_allowed=True)
    provider.update(updates)
    (tmp_path/'providers.json').write_text(json.dumps(dict(schema_version='model-providers-v1',providers=[provider])),encoding='utf-8')
    if not (tmp_path/'approved.token').exists():(tmp_path/'approved.token').write_text('protocol-credential')
    return provider


def upstream(*models):
    return {'object':'list','data':[dict(id=name,owned_by='approved',name='候选模型',capabilities={'streaming':True,
        'multimodal':{'input':['text','image','file']},'supported_parameters':{'responses':[],'chat_completions':[]},
        'reasoning':{'levels':['none','high']},'tools':{'supported':True}},metadata={'private':'never-export'}) for name in models or ['candidate']]}


def settings(tmp_path):return Settings(shared_token='protocol-gateway',providers_file=str(tmp_path/'providers.json'),credentials_root=str(tmp_path))


@pytest.mark.asyncio
async def test_cache_refresh_and_rotation_recheck_authority_without_changing_inference(tmp_path):
    provider=registry(tmp_path);requests=[]
    def serve(request):
        requests.append(request);assert str(request.url)=='https://provider.invalid/v1/models' and request.method=='GET'
        return httpx.Response(200,json=upstream())
    async with httpx.AsyncClient(transport=httpx.MockTransport(serve)) as http:
        catalog=ProviderCatalog(settings(tmp_path),http)
        first=await catalog.read('approved');assert first['state']=='ready' and first['configuration_sha256']==configuration_sha(provider)
        assert first['models'][0]['allowed_roles']==['text','vision'] and first['inference_verified'] is False
        first['models'].clear();cached=await catalog.read('approved');assert len(cached['models'])==1 and cached['cached'] and len(requests)==1
        await catalog.read('approved',True);assert len(requests)==2
        (tmp_path/'approved.token').write_text('rotated-protocol-credential')
        await catalog.read('approved');assert len(requests)==3 and requests[-1].headers['authorization']=='Bearer rotated-protocol-credential'
        registry(tmp_path,roles=['text']);updated=await catalog.read('approved');assert len(requests)==4 and updated['models'][0]['allowed_roles']==['text']
        registry(tmp_path,activation_allowed=False)
        with pytest.raises(CatalogRejected) as stopped:await catalog.read('approved')
        assert stopped.value.status==409 and len(requests)==4
        assert all('private' not in json.dumps(value) for value in (cached,updated))


@pytest.mark.asyncio
@pytest.mark.parametrize('change',['key','roles','disabled'])
async def test_changes_during_io_never_cache_stale_success(tmp_path,change):
    registry(tmp_path)
    def serve(request):
        if change=='key':(tmp_path/'approved.token').write_text('changed-during-io')
        else:registry(tmp_path,**({'roles':['text']} if change=='roles' else {'activation_allowed':False}))
        return httpx.Response(200,json=upstream())
    async with httpx.AsyncClient(transport=httpx.MockTransport(serve)) as http:
        catalog=ProviderCatalog(settings(tmp_path),http);value=await catalog.read('approved')
        assert value['state']=='unavailable' and value['diagnostics']['category']=='configuration_changed' and catalog.cache=={}


@pytest.mark.asyncio
@pytest.mark.parametrize('status,category',[(401,'credential_rejected'),(403,'credential_rejected'),(429,'catalog_rate_limited'),(404,'catalog_not_found'),(502,'catalog_unavailable'),(302,'catalog_unavailable')])
async def test_failed_metadata_keeps_actual_status_safe_code_and_id_without_raw_error(tmp_path,status,category):
    registry(tmp_path);identity='11111111-2222-4333-8444-555555555555'
    def serve(request):return httpx.Response(status,json={'error':{'code':'credential_rejected','message':'private credential and endpoint'}},headers={'x-request-id':identity,'location':'https://not-allowed.invalid'})
    async with httpx.AsyncClient(transport=httpx.MockTransport(serve),follow_redirects=False) as http:
        value=await ProviderCatalog(settings(tmp_path),http).read('approved')
        assert value['diagnostics']==dict(category=category,upstream_status=status,upstream_request_id=identity,upstream_error_code='credential_rejected')
        assert value['models']==[] and 'private' not in json.dumps(value) and 'endpoint' not in json.dumps(value)


@pytest.mark.asyncio
async def test_non_json_error_retains_http_status_and_unknown_ids_are_not_exposed(tmp_path):
    registry(tmp_path)
    async with httpx.AsyncClient(transport=httpx.MockTransport(lambda r:httpx.Response(503,text='private HTML',headers={'x-request-id':'private-not-uuid'}))) as http:
        value=await ProviderCatalog(settings(tmp_path),http).read('approved')
        assert value['diagnostics']==dict(category='catalog_unavailable',upstream_status=503)


@pytest.mark.asyncio
@pytest.mark.parametrize('kind',['duplicate','invalid_json','oversized','timeout','network'])
async def test_invalid_and_network_failures_release_capacity_without_retry(tmp_path,kind):
    registry(tmp_path);requests=[]
    def serve(request):
        requests.append(request)
        if kind=='timeout':raise httpx.ReadTimeout('private address')
        if kind=='network':raise httpx.ConnectError('private address')
        return httpx.Response(200,json=upstream('same','same')) if kind=='duplicate' else httpx.Response(200,content=b'x'*(MAX_RESPONSE+1) if kind=='oversized' else b'not json')
    async with httpx.AsyncClient(transport=httpx.MockTransport(serve)) as http:
        catalog=ProviderCatalog(settings(tmp_path),http);value=await catalog.read('approved')
        assert value['state']=='unavailable' and value['models']==[] and len(requests)==1
        assert value['diagnostics']['category']=={'timeout':'catalog_timeout','network':'catalog_network_unavailable'}.get(kind,'catalog_invalid')
        assert catalog.active==set() and not catalog.slots.locked() and catalog.cache=={}


@pytest.mark.asyncio
async def test_duplicate_and_cancelled_refresh_are_bounded_and_release_capacity(tmp_path):
    registry(tmp_path);entered=asyncio.Event();release=asyncio.Event()
    async def serve(request):entered.set();await release.wait();return httpx.Response(200,json=upstream())
    async with httpx.AsyncClient(transport=httpx.MockTransport(serve)) as http:
        catalog=ProviderCatalog(settings(tmp_path),http);pending=asyncio.create_task(catalog.read('approved'))
        await entered.wait();assert (await catalog.read('approved',True))['diagnostics']['category']=='catalog_busy'
        pending.cancel()
        with pytest.raises(asyncio.CancelledError):await pending
        assert catalog.active==set() and not catalog.slots.locked()
        release.set();assert (await catalog.read('approved'))['state']=='ready'


def test_unknown_capabilities_and_media_generation_are_not_promoted_to_analysis(tmp_path):
    provider=registry(tmp_path);raw=upstream();raw['data'][0]['capabilities']={}
    result=normalize_models(raw,provider)[0];assert result['allowed_roles']==[] and result['streaming'] is None and result['tools'] is None
    provider['id']='grok_web';raw=upstream('grok-imagine-video');raw['data'][0]['owned_by']='grok_web'
    result=normalize_models(raw,provider)[0];assert result['analysis_reason']=='media_endpoint_required' and result['allowed_roles']==[]
    provider['roles']=['text'];raw=upstream('grok-chat-fast');raw['data'][0]['owned_by']='grok_web'
    result=normalize_models(raw,provider)[0];assert result['declared_roles']==['text','vision'] and result['allowed_roles']==['text']


@pytest.mark.parametrize('change',['too_many','owner','id','capability_type','media_type','streaming_type'])
def test_catalog_shape_and_count_are_bounded(tmp_path,change):
    provider=registry(tmp_path);raw=upstream()
    if change=='too_many':raw=upstream(*(f'model-{index}' for index in range(513)))
    elif change=='owner':raw['data'][0]['owned_by']='another'
    elif change=='id':raw['data'][0]['id']='model\nprivate'
    elif change=='capability_type':raw['data'][0]['capabilities']=[]
    elif change=='media_type':raw['data'][0]['capabilities']['multimodal']['input']='text'
    else:raw['data'][0]['capabilities']['streaming']=1
    with pytest.raises(ValueError):normalize_models(raw,provider)


@pytest.mark.asyncio
@pytest.mark.parametrize('update',[{'base_url':'http://provider.invalid/v1'},{'base_url':'https://private@provider.invalid/v1'},
    {'base_url':'https://provider.invalid/v1?key=private'},{'credential_file':'../outside'},{'roles':['face']}])
async def test_unapproved_endpoint_or_reference_never_issues_network_request(tmp_path,update):
    registry(tmp_path,**update)
    def forbidden(request):raise AssertionError('configuration must fail before HTTP')
    async with httpx.AsyncClient(transport=httpx.MockTransport(forbidden)) as http:
        with pytest.raises(CatalogRejected) as error:await ProviderCatalog(settings(tmp_path),http).read('approved')
        assert error.value.status==503


def test_internal_catalog_requires_token_and_never_consumes_inference_slot(tmp_path):
    registry(tmp_path);requests=[]
    def serve(request):requests.append(request);return httpx.Response(200,json=upstream())
    app=create_app(settings(tmp_path),httpx.MockTransport(serve))
    with TestClient(app) as browser:
        path='/internal/v1/providers/approved/models'
        assert browser.get(path).status_code==401 and requests==[]
        response=browser.get(path,headers={'Authorization':'Bearer protocol-gateway'});assert response.status_code==200
        assert app.state.model_client.capacity()['active_total']==0 and app.state.model_runtime.revision==-1
        assert browser.get('/internal/v1/providers/not-configured/models',headers={'Authorization':'Bearer protocol-gateway'}).status_code==404
        assert len(requests)==1


def test_web_catalog_requires_current_admin_and_origin_and_preserves_actor(config):
    directory=Directory(config);original=directory.handle
    def serve(request):
        if '/models/providers/' in request.url.path:
            directory.calls.append((request.method,request.url.path,dict(request.headers),{}))
            return httpx.Response(200,json={'state':'unavailable','models':[]})
        return original(request)
    directory.handle=serve
    with client(config,directory) as browser:
        path='/api/admin/models/providers/approved/models'
        assert browser.get(path).status_code==401
        assert login(browser,'reader').status_code==200
        assert browser.get(path).status_code==403
        assert login(browser).status_code==200
        assert browser.post(path+'/refresh').status_code==403
        assert browser.get(path).status_code==200
        response=browser.post(path+'/refresh',headers={'Origin':ORIGIN,'X-Actor-Id':'attacker'})
        assert response.status_code==200 and response.headers['cache-control']=='no-store'
        calls=[c for c in directory.calls if '/models/providers/' in c[1]];assert len(calls)==2 and calls[-1][0]=='POST'
        assert calls[-1][2]['x-actor-id']=='admin' and calls[-1][2]['x-actor-revision']=='1'
        directory.accounts['admin']['credential_revision']=2
        assert browser.get(path).status_code==401 and len([c for c in directory.calls if '/models/providers/' in c[1]])==2
