import json
from pathlib import Path
import sys
import yaml
import pytest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'ci'))
from midstream_gitops import configure


def test_middle_modules_are_bounded_and_keep_operator_pause_and_private_bindings(tmp_path):
    directory=tmp_path/'runtime';directory.mkdir()
    def save(name,value):(directory/(name+'.json')).write_text(json.dumps(value))
    def load(name):return json.loads((directory/(name+'.json')).read_text())
    for name in ('core-api','ai-gateway','analysis-worker'):
        container={'name':name,'env':[{'name':'CORE_URL','value':'https://core.internal'},{'name':'CORE_CA_FILE','value':'/core-ca/ca.crt'}],
            'volumeMounts':[{'name':'core-ca','mountPath':'/core-ca','readOnly':True}]}
        save(name,{'apiVersion':'apps/v1','kind':'Deployment','metadata':{'name':name,'namespace':'trial'},'spec':{'replicas':0,
            'selector':{'matchLabels':{'app':name}},'template':{'metadata':{'labels':{'app':name}},'spec':{'containers':[container],
                'volumes':[{'name':'core-ca','secret':{'secretName':'core-private'}}],'automountServiceAccountToken':False}}}})
    (tmp_path/'kustomization.yaml').write_text('resources: []\n')
    save('model-provider-registry',{'data':{'providers.json':json.dumps({'providers':[{'credential_file':'approved.token','activation_allowed':True},{'credential_file':'saved.token','activation_allowed':False}]}),
        'credential-projection.json':json.dumps({'defaultMode':288,'sources':[{'secret':{'name':'approved','items':[{'key':'token','path':'approved.token'}]}},{'secret':{'name':'saved','items':[{'key':'token','path':'saved.token'}]}}]})}})
    configure(tmp_path,'runtime@sha256:fixture','0.24.0','revision','build')
    worker=load('specialist-worker');assert worker['spec']['replicas']==1 and worker['spec']['revisionHistoryLimit']==0
    worker['spec']['replicas']=0;save('specialist-worker',worker)
    prior=load('specialist-processing-catalog');prior['data']['operator_note']='retained';save('specialist-processing-catalog',prior)
    configure(tmp_path,'runtime@sha256:next','0.24.0','revision','build')
    once={p.name:p.read_bytes() for p in directory.glob('*.json')}
    configure(tmp_path,'runtime@sha256:next','0.24.0','revision','build')
    assert once=={p.name:p.read_bytes() for p in directory.glob('*.json')}
    assert load('specialist-worker')['spec']['replicas']==0 and load('analysis-worker')['spec']['replicas']==0
    assert load('core-api')['spec']['strategy']=={'type':'RollingUpdate','rollingUpdate':{'maxSurge':1,'maxUnavailable':0}}
    assert load('specialist-processing-catalog')['data']['operator_note']=='retained'
    credential_volume=next(entry for entry in load('ai-gateway')['spec']['template']['spec']['volumes'] if entry['name']=='model-credentials')
    assert [entry['path'] for source in credential_volume['projected']['sources'] for entry in source['secret']['items']]==['approved.token']
    gateway=load('ai-gateway')['spec']['template']['spec'];environment={e['name']:e for e in gateway['containers'][0]['env']}
    assert environment['MODEL_PROVIDERS_FILE']['value']=='/model-providers/providers.json'
    assert next(v for v in gateway['volumes'] if v['name']=='model-providers')['configMap']['items']==[{'key':'providers.json','path':'providers.json'}]
    assert next(v for v in gateway['containers'][0]['volumeMounts'] if v['name']=='model-providers')['readOnly'] is True
    native=load('specialist-service')['spec']['template']['spec'];container=native['containers'][0]
    assert not native['automountServiceAccountToken'] and container['resources']['limits']['cpu']=='1000m'
    assert container['readinessProbe']['httpGet']['scheme']=='HTTPS'
    assert any(mount.get('subPath')=='plate' and mount['readOnly'] for mount in container['volumeMounts'])
    assert len(yaml.safe_load((tmp_path/'kustomization.yaml').read_text())['resources'])==6

    # Adding a provider must update the authoritative credential mapping; old
    # Pod mounts cannot mask an incomplete operations-owned registry at release.
    registry=load('model-provider-registry');definition=json.loads(registry['data']['providers.json'])
    definition['providers'].append({'credential_file':'next.token','activation_allowed':True})
    registry['data']['providers.json']=json.dumps(definition);save('model-provider-registry',registry)
    before=load('ai-gateway')
    with pytest.raises(ValueError,match='incomplete'):configure(tmp_path,'runtime@sha256:next','0.24.0','revision','build')
    assert load('ai-gateway')==before
    projection=json.loads(registry['data']['credential-projection.json']);projection['sources'].append({'secret':{'name':'next','items':[{'key':'token','path':'next.token'}]}})
    registry['data']['credential-projection.json']=json.dumps(projection);save('model-provider-registry',registry)
    configure(tmp_path,'runtime@sha256:next','0.24.0','revision','build')
    credential_volume=next(entry for entry in load('ai-gateway')['spec']['template']['spec']['volumes'] if entry['name']=='model-credentials')
    assert {entry['path'] for source in credential_volume['projected']['sources'] for entry in source['secret']['items']}=={'approved.token','next.token'}
    projection['sources'].append({'secret':{'name':'conflicting','items':[{'key':'token','path':'next.token'}]}})
    registry['data']['credential-projection.json']=json.dumps(projection);save('model-provider-registry',registry)
    before=load('ai-gateway')
    with pytest.raises(ValueError,match='ambiguous'):configure(tmp_path,'runtime@sha256:next','0.24.0','revision','build')
    assert load('ai-gateway')==before


def test_published_plate_catalog_keeps_content_identity():
    from services.specialist_service.catalog import ModelCatalog
    catalog=ModelCatalog(ROOT/'deploy/plate-catalog.json')
    assert catalog.resolve('plate-native-v1').adapter=='plate_onnx_v1'
