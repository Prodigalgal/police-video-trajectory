import importlib.util
import json
from pathlib import Path
import yaml


SPEC=importlib.util.spec_from_file_location('usability_gitops',Path(__file__).resolve().parents[1]/'ci/usability_gitops.py')
MODULE=importlib.util.module_from_spec(SPEC);SPEC.loader.exec_module(MODULE)


def test_manifest_reconciliation_keeps_operator_pauses_and_is_idempotent(tmp_path):
    runtime=tmp_path/'runtime';runtime.mkdir()
    def save(name,value):(runtime/(name+'.json')).write_text(json.dumps(value))
    for name in ('core-api','web-ui','rtsp-simulator','rtsp-worker'):
        container={'name':name,'image':'runtime@sha256:baseline','env':[],
                   'resources':{'limits':{'cpu':'1','memory':'512Mi'}},'volumeMounts':[]}
        pod={'containers':[container],'volumes':[],'nodeSelector':{'zone':'trial'}}
        if name=='core-api':
            container['env']=[{'name':'VIDEO_INVESTIGATION_ENABLED','value':'false'},
                              {'name':'VIDEO_GATEWAY_URL','value':'https://vision.internal'},
                              {'name':'VIDEO_GATEWAY_CA','value':'/gateway-ca/ca.crt'},
                              {'name':'VIDEO_GATEWAY_TOKEN','valueFrom':{'secretKeyRef':{'name':'shared-gateway','key':'token'}}}]
            pod['volumes']=[{'name':'gateway-ca','secret':{'secretName':'shared-gateway'}}]
        if name=='web-ui':container['env']=[{'name':'WEB_LIVE_CAMERA_PATHS_JSON','value':'{"one":"recording-01"}'},
                                         {'name':'WEB_LIVE_MEDIA_URL','value':'https://stream.internal:8888'}]
        if name=='rtsp-worker':
            container['env']=[{'name':'CORE_URL','value':'https://core.internal'},
                              {'name':'WORKER_TOKEN','valueFrom':{'secretKeyRef':{'name':'shared-token','key':'token'}}},
                              {'name':'AI_ENDPOINT','value':'https://never-copy.invalid'}]
        save(name,{'apiVersion':'apps/v1','kind':'Deployment','metadata':{'name':name,'namespace':'trial'},
                   'spec':{'replicas':0,'selector':{'matchLabels':{'app':name}},
                           'template':{'metadata':{'labels':{'app':name}},'spec':pod}}})
    original={'authInternalUsers':[{'user':'any','permissions':[{'action':'publish','path':'original'}]},
                                   {'user':'reader','permissions':[{'action':'read','path':'original'}]}],
              'rtspsAddress':':8322','paths':{'recording-01':{'runOnInit':'original-publisher'},
                                           'recording-05':{'runOnInit':'retired-publisher','runOnInitRestart':True}}}
    save('rtsp-simulator-config',{'apiVersion':'v1','kind':'ConfigMap','metadata':{'name':'stream'},'data':{'mediamtx.yml':yaml.safe_dump(original)}})
    (tmp_path/'kustomization.yaml').write_text('resources:\n- runtime/core-api.json\n')
    MODULE.configure(tmp_path,'runtime@sha256:new','0.23.0','revision','build')
    worker_path=runtime/'appearance-worker.json';worker=json.loads(worker_path.read_text())
    assert worker['spec']['replicas']==1
    worker['spec']['replicas']=0;worker_path.write_text(json.dumps(worker))
    MODULE.configure(tmp_path,'runtime@sha256:new','0.23.0','revision','build')
    before={p.name:p.read_bytes() for p in runtime.iterdir()}
    MODULE.configure(tmp_path,'runtime@sha256:new','0.23.0','revision','build')
    assert before=={p.name:p.read_bytes() for p in runtime.iterdir()}
    core=json.loads((runtime/'core-api.json').read_text())
    assert core['spec']['replicas']==0
    assert next(v for v in core['spec']['template']['spec']['containers'][0]['env'] if v['name']=='VIDEO_INVESTIGATION_ENABLED')['value']=='false'
    worker=json.loads(worker_path.read_text());assert worker['spec']['replicas']==0
    assert 'AI_ENDPOINT' not in {v['name'] for v in worker['spec']['template']['spec']['containers'][0]['env']}
    media=yaml.safe_load(json.loads((runtime/'rtsp-simulator-config.json').read_text())['data']['mediamtx.yml'])
    assert media['paths']['recording-01']['runOnInit']=='original-publisher'
    assert media['paths']['recording-05']['runOnInit']==''
    assert media['authInternalUsers'][1]['permissions'][0]=={'action':'read','path':'original'}
    assert len(media['authInternalUsers'][1]['permissions'])==2
    assert len([v for v in media['paths'] if v.startswith('preview-')])==4
