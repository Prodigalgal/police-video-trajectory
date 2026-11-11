import json
from pathlib import Path
import sys
import yaml
import pytest

ROOT=Path(__file__).resolve().parents[1]
sys.path.insert(0,str(ROOT/'ci'))
from midstream_gitops import configure


def test_offline_cluster_manifests_are_portable_and_refuse_unsafe_storage_or_versions():
    import importlib.util
    spec=importlib.util.spec_from_file_location('cluster_render',ROOT/'deploy/cluster/render.py')
    renderer=importlib.util.module_from_spec(spec);spec.loader.exec_module(renderer)
    config=json.loads((ROOT/'deploy/cluster/config.example.json').read_text())
    bundle=renderer.render(config);assert bundle['kind']=='List'
    serialized=json.dumps(bundle)
    assert 'sydney' not in serialized and 'hostPath' not in serialized and 'stringData' not in serialized
    deployments=[item for item in bundle['items'] if item['kind']=='Deployment'];assert len(deployments)==3
    for deployment in deployments:
        assert deployment['spec']['replicas']==2 and deployment['spec']['strategy']['rollingUpdate']['maxUnavailable']==0
        pod=deployment['spec']['template']['spec']
        assert pod['nodeSelector']=={'kubernetes.io/arch':'arm64','video.platform/worker':'true'}
        assert pod['topologySpreadConstraints'][0]['topologyKey']=='kubernetes.io/hostname'
        assert pod['automountServiceAccountToken'] is False
        assert pod['containers'][0]['readinessProbe']['httpGet']['scheme']=='HTTPS'
    assert len([item for item in bundle['items'] if item['kind']=='PodDisruptionBudget'])==3
    core=next(item for item in deployments if item['metadata']['name']=='core-api')['spec']['template']['spec']['containers'][0]
    values={entry['name']:entry.get('value') for entry in core['env']}
    assert values['VIDEO_CLUSTER_COORDINATION_ENABLED']=='true' and values['VIDEO_CAPTURE_DISPATCHER_ENABLED']=='false'
    assert renderer.readiness(config)['ready_for_production'] is False
    for change in [{'media_access_mode':'ReadWriteOnce'},{'runtime_image':'example/runtime:latest'},{'replicas':1},{'text_reserved':2},{'origin':'http://video.invalid'},{'unrecognized_secret':'never-accept'}]:
        with pytest.raises(ValueError):renderer.render({**config,**change})
    inventory={'nodes':[],'claims':[],'volumes':[],'secrets':{},'configmaps':{},'images':{}}
    assert renderer.inspect_inventory(config,inventory)['foundation_metadata_ready'] is False
    for index in range(3):
        name='worker-'+str(index)
        inventory['nodes'].append({'metadata':{'name':name,'labels':{'video.platform/worker':'true','kubernetes.io/arch':'arm64'}},'spec':{},'status':{'conditions':[{'type':'Ready','status':'True'}]}})
        inventory['images'][name]=[config['core_image'],config['runtime_image']]
    inventory['claims']=[{'metadata':{'namespace':config['namespace'],'name':config['media_claim']},'spec':{'accessModes':['ReadWriteMany'],'volumeName':'media'},'status':{'phase':'Bound'}}]
    inventory['volumes']=[{'metadata':{'name':'media'},'spec':{'csi':{'driver':'reviewed-driver'}}}]
    for key in ('database_secret','elasticsearch_secret'):inventory['secrets'][config[key]]=['url','user','password','ca.crt']
    inventory['secrets'][config['service_secret']]=['session-secret','bootstrap-username','bootstrap-password-hash','admin-token','search-token','worker-token','gateway-token']
    inventory['secrets'][config['core_tls_secret']]=['keystore.p12','keystore-password','ca.crt']
    for key in ('web_tls_secret','gateway_tls_secret'):inventory['secrets'][config[key]]=['tls.crt','tls.key','ca.crt']
    inventory['secrets'][config['provider_credentials']]=['candidate.token'];inventory['configmaps'][config['provider_registry']]=['providers.json']
    checked=renderer.inspect_inventory(config,inventory)
    assert checked['foundation_metadata_ready'] is True and checked['ready_for_production'] is False
    inventory['volumes'][0]['spec']={'local':{'path':'/local-media'}}
    assert renderer.inspect_inventory(config,inventory)['foundation_metadata_ready'] is False
    inventory['secrets'][config['database_secret']]={'data':{'password':'protocol-must-not-accept-values'}}
    with pytest.raises(ValueError):renderer.inspect_inventory(config,inventory)


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
