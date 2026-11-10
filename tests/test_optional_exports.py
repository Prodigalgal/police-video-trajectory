"""Export configuration, HTTP and capacity contracts; no field-media inference."""
import asyncio,hashlib,importlib.util,json,sys
from pathlib import Path
from types import SimpleNamespace
from uuid import uuid4
import httpx
import pytest
import yaml
from test_simple_accounts import Directory,config,client,login,ORIGIN
from services.video_worker import export_consumer
from services.video_worker.export_store import ExportError


def plan(native_bytes):
    identity=str(uuid4());asset={'identity':identity,'kind':'native','asset_key':'native-'+identity,'filename':'media/native/'+identity+'.mp4','bytes':native_bytes,'sha256':'a'*64}
    value={'schema_version':'evidence-export-v1','assets':[asset],'media_sources':[],'asset_bytes':native_bytes};raw=json.dumps(value)
    return {'manifest_payload':raw,'manifest_sha256':hashlib.sha256(raw.encode()).hexdigest()}


def test_native_context_capacity_accepts_current_windows_and_rejects_oversize():
    export_consumer.validate_plan(plan(16*1024**2+1));export_consumer.validate_plan(plan(128*1024**2))
    with pytest.raises(ExportError):export_consumer.validate_plan(plan(128*1024**2+1))


@pytest.mark.asyncio
async def test_consumer_presence_reports_capabilities_and_only_marks_acknowledged_health(tmp_path,monkeypatch):
    marker=tmp_path/'ready';monkeypatch.setattr(export_consumer,'HEALTH_FILE',marker);stop=asyncio.Event();seen=[]
    class Core:
        async def post(self,path,body):seen.append((path,body));stop.set();return {'accepted':True}
    await export_consumer.consumer_presence(SimpleNamespace(export_bytes=1024**3),Core(),'worker',stop)
    assert marker.is_file() and float(marker.read_text())>0
    assert seen[0][0]=='/internal/v2/exports/heartbeat' and seen[0][1]['contracts']==['evidence-export-v1','evidence-native-128-v1']
    assert seen[0][1]['quotaBytes']==1024**3 and 'dir' not in seen[0][1]
    marker.unlink();stop.clear()
    class Rejected:
        async def post(self,path,body):stop.set();return {'accepted':False}
    await export_consumer.consumer_presence(SimpleNamespace(export_bytes=1024**3),Rejected(),'worker',stop)
    assert not marker.exists()


def test_export_module_api_is_authenticated_and_admin_writes_are_scoped(config):
    directory=Directory(config);original=directory.handle;calls=[]
    def serve(request):
        if request.url.path.startswith(('/internal/exports','/internal/pipeline/features')):
            calls.append(request);return httpx.Response(200,json={'state':'protocol-receipt'})
        return original(request)
    directory.handle=serve
    with client(config,directory) as browser:
        assert browser.get('/api/v1/exports/status').status_code==401
        assert login(browser).status_code==200
        # Status stays readable even where the optional producer is not installed.
        assert browser.get('/api/v1/exports/status').status_code==200
        command={'request_id':str(uuid4()),'expected_version':1,'enabled':False,'plugin':'bound_video_package_v1','parameters':{}}
        url='/api/admin/pipeline-features/evidence_export'
        assert browser.put(url,json=command).status_code==403
        assert browser.put(url,json=command,headers={'Origin':ORIGIN}).status_code==200
        assert json.loads(calls[-1].content)['plugin']=='bound_video_package_v1'
        for update in [{'parameters':{'seconds':5}},{'plugin':'short_segment_v1'},{'enabled':'true'}]:
            assert browser.put(url,json={**command,**update},headers={'Origin':ORIGIN}).status_code==422
        assert login(browser,'reader').status_code==200
        assert browser.put(url,json=command,headers={'Origin':ORIGIN}).status_code==403


def test_gitops_export_installation_is_opt_in_bounded_and_preserves_operator_pause(tmp_path):
    sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'ci'))
    from export_gitops import configure
    directory=tmp_path/'runtime';directory.mkdir()
    def save(name,value):(directory/(name+'.json')).write_text(json.dumps(value))
    def load(name):return json.loads((directory/(name+'.json')).read_text())
    for name in ['core-api','web-ui','analysis-worker']:
        save(name,{'kind':'Deployment','metadata':{'name':name,'namespace':'fixture'},'spec':{'replicas':0,'selector':{'matchLabels':{'app':name}},'template':{'metadata':{'labels':{'app':name}},'spec':{'containers':[{'name':name,'env':[{'name':'VIDEO_EXPORTS_ENABLED','value':'true'},{'name':'CORE_URL','value':'https://core.invalid'},{'name':'CORE_CA_FILE','value':'/core-ca/ca.crt'}],'volumeMounts':[{'name':'core-ca','mountPath':'/core-ca','readOnly':True}]}],'volumes':[{'name':'core-ca','secret':{'secretName':'fixture-ca'}}],'automountServiceAccountToken':False}}}})
    (tmp_path/'kustomization.yaml').write_text('resources: []\n')
    configure(tmp_path,'runtime@sha256:fixture','0.35.0','revision','build')
    environment={e['name']:e.get('value') for e in load('core-api')['spec']['template']['spec']['containers'][0]['env']}
    assert environment['VIDEO_EXPORTS_ENABLED']=='false' and environment['VIDEO_EXPORTS_INSTALLED']=='false' and not (directory/'export-worker.json').exists()
    save('evidence-exports-pvc',{'kind':'PersistentVolumeClaim','metadata':{'name':'output','namespace':'fixture'},'spec':{'resources':{'requests':{'storage':'2Gi'}}}})
    save('evidence-exports-route',{'kind':'HTTPRoute','metadata':{'namespace':'fixture'},'spec':{'rules':[{'backendRefs':[{'name':'web-ui','port':8445}],'matches':[{'path':{'type':'PathPrefix','value':'/api/v1/exports'}}],'timeouts':{'request':'1000s','backendRequest':'980s'}}]}})
    configure(tmp_path,'runtime@sha256:first','0.35.0','revision','build')
    worker=load('export-worker');assert worker['spec']['replicas']==1 and worker['spec']['revisionHistoryLimit']==0
    container=worker['spec']['template']['spec']['containers'][0];env={e['name']:e.get('value') for e in container['env']}
    assert env['WORKER_ROLE']=='export' and env['EXPORT_BYTES']==str(1536*1024**2) and 'GATEWAY_URL' not in env
    assert container['readinessProbe']['exec']['command'][0]=='python'
    assert next(m for m in load('core-api')['spec']['template']['spec']['containers'][0]['volumeMounts'] if m['name']=='evidence-exports')['readOnly']
    assert next(m for m in container['volumeMounts'] if m['name']=='evidence-exports')['readOnly'] is False
    worker['spec']['replicas']=0;save('export-worker',worker)
    configure(tmp_path,'runtime@sha256:next','0.35.0','revision','build');before={p.name:p.read_bytes() for p in directory.glob('*.json')}
    configure(tmp_path,'runtime@sha256:next','0.35.0','revision','build')
    assert before=={p.name:p.read_bytes() for p in directory.glob('*.json')} and load('export-worker')['spec']['replicas']==0
