"""Install model-control and dedicated plate consumers without resuming ingestion."""
from copy import deepcopy
import json
from pathlib import Path
import yaml
from usability_gitops import environment,volume,write


def configure(app_root,runtime,version,revision,run):
    root=Path(app_root);directory=root/'runtime'
    def load(name):return json.loads((directory/(name+'.json')).read_text())
    core,gateway,analysis=load('core-api'),load('ai-gateway'),load('analysis-worker')
    core['spec']['strategy']={'type':'RollingUpdate','rollingUpdate':{'maxSurge':1,'maxUnavailable':0}}
    core_pod,gateway_pod=[value['spec']['template']['spec'] for value in (core,gateway)]
    core_container,gateway_container=[value['containers'][0] for value in (core_pod,gateway_pod)]
    environment(gateway_container,'VIDEO_SOURCE_REVISION',revision)
    environment(core_container,'VIDEO_REVIEWED_PLATE_SEARCH_ENABLED','true')
    environment(core_container,'VIDEO_MODEL_PROVIDERS_FILE','/model-providers/providers.json')
    environment(core_container,'VIDEO_PROCESSING_CATALOG_FILE','/processing-catalog/catalog.json')
    volume(core_pod,'model-providers',{'configMap':{'name':'model-provider-registry'}},'/model-providers')
    volume(gateway_pod,'model-providers',{'configMap':{'name':'model-provider-registry','items':[{'key':'providers.json','path':'providers.json'}]}},'/model-providers')
    environment(gateway_container,'MODEL_PROVIDERS_FILE','/model-providers/providers.json')
    volume(core_pod,'processing-catalog',{'configMap':{'name':'specialist-processing-catalog'}},'/processing-catalog')
    core_ca=next(value for value in analysis['spec']['template']['spec']['volumes'] if value['name']=='core-ca')
    core_ca=deepcopy(core_ca)
    if 'secret' in core_ca:core_ca['secret']['items']=[{'key':'ca.crt','path':'ca.crt'}]
    volume(gateway_pod,'core-ca',{key:deepcopy(value) for key,value in core_ca.items() if key!='name'},'/core-ca')
    registry_file=directory/'model-provider-registry.json'
    registry=json.loads(registry_file.read_text()) if registry_file.exists() else {}
    projection=registry.get('data',{}).get('credential-projection.json')
    credentials={'projected':json.loads(projection)} if projection else {'secret':{'secretName':'model-provider-credentials','defaultMode':0o440}}
    if projection:
        allowed={provider['credential_file'] for provider in json.loads(registry['data']['providers.json'])['providers'] if provider['activation_allowed'] is True}
        projected=credentials['projected'];sources=[];covered=set()
        for source_ref in projected['sources']:
            source_ref=deepcopy(source_ref)
            source_ref['secret']['items']=[entry for entry in source_ref['secret']['items'] if entry['path'] in allowed]
            for entry in source_ref['secret']['items']:
                if entry['path'] in covered:raise ValueError('Active provider credential projection is ambiguous')
                covered.add(entry['path'])
            if source_ref['secret']['items']:sources.append(source_ref)
        if covered!=allowed:raise ValueError('Active provider credential projection is incomplete')
        projected['sources']=sources
        if not sources:credentials={'emptyDir':{'sizeLimit':'1Mi'}}
        # This mount is derived from the operations-owned registry, including revocations.
        for entry in gateway_pod.get('volumes',[]):
            if entry['name']=='model-credentials':
                entry.clear();entry.update({'name':'model-credentials',**deepcopy(credentials)})
    volume(gateway_pod,'model-credentials',credentials,'/model-credentials')
    environment(gateway_container,'MODEL_CONTROL_URL',next(value['value'] for value in analysis['spec']['template']['spec']['containers'][0]['env'] if value['name']=='CORE_URL'))
    environment(gateway_container,'MODEL_CONTROL_CA_FILE','/core-ca/ca.crt')
    environment(gateway_container,'MODEL_CREDENTIALS_ROOT','/model-credentials')
    environment(gateway_container,'MODEL_CONTROL_CONSUMER','ai-gateway')
    for name,deployment in [('core-api',core),('ai-gateway',gateway)]:write(directory/(name+'.json'),deployment)

    source=Path(__file__).resolve().parents[1]
    native_catalog=json.loads((source/'deploy/plate-catalog.json').read_text())
    model=native_catalog['models'][0]
    namespace=core['metadata']['namespace'];hostname='specialist-service.'+namespace+'.svc'
    metadata=lambda name:{'name':name,'namespace':namespace,'labels':{'app.kubernetes.io/part-of':'police-video-trajectory'}}
    write(directory/'specialist-native-catalog.json',{'apiVersion':'v1','kind':'ConfigMap','metadata':metadata('specialist-native-catalog'),'data':{'specialist.json':json.dumps(native_catalog)}})
    catalog_path=directory/'specialist-processing-catalog.json'
    if not catalog_path.exists():
        profile={'profile':model['profile'],'adapter':'specialist_sse_v1','capabilities':['license_plate'],'base_url':'https://'+hostname+':8446',
            'token_file':'plate.token','ca_file':'ca.crt','model_id':model['model_id'],'contract':'specialist-result-v1','streaming':True,'timeout_seconds':360}
        catalog={'schema_version':'processing-catalog-v2','revision':1,'modules':{'license_plate':{'enabled':True,'profile':model['profile']},
            'specialist_selection':{'enabled':True,'plugin':'rules_v1','mode':'select','policy_sha256':'723c3ca3e1918200eb2775718508fcb917bbf868ee319af936d76a1bbd3d6e80'},
            'text_ocr':{'enabled':False,'profile':None},'face_quality':{'enabled':False}},'profiles':[profile]}
        write(catalog_path,{'apiVersion':'v1','kind':'ConfigMap','metadata':metadata('specialist-processing-catalog'),'data':{'catalog.json':json.dumps(catalog)}})

    certificate={'apiVersion':'cert-manager.io/v1','kind':'Certificate','metadata':metadata('specialist-service'),
        'spec':{'secretName':'specialist-service-tls','issuerRef':{'kind':'Issuer','name':'police-video-origin-ca'},'dnsNames':[hostname,hostname+'.cluster.local'],
            'privateKey':{'algorithm':'ECDSA','size':256,'rotationPolicy':'Always'},'usages':['server auth'],'duration':'2160h','renewBefore':'360h'}}
    write(directory/'specialist-service-certificate.json',certificate)
    service={'apiVersion':'v1','kind':'Service','metadata':metadata('specialist-service'),'spec':{'selector':{'app':'specialist-service'},'ports':[{'name':'https','port':8446,'targetPort':8446}]}}
    write(directory/'specialist-service-service.json',service)
    worker_path=directory/'specialist-worker.json'
    worker=load('specialist-worker') if worker_path.exists() else deepcopy(analysis)
    if not worker_path.exists():
        worker['metadata']=metadata('specialist-worker');worker['spec'].update(replicas=1,revisionHistoryLimit=0,selector={'matchLabels':{'app':'specialist-worker'}})
        worker['spec']['template']['metadata']={'labels':{'app':'specialist-worker'}}
        pod=worker['spec']['template']['spec'];container=pod['containers'][0];container['name']='specialist-worker'
        container['command']=['python','-m','services.video_worker'];container.pop('args',None)
        for name in ('startupProbe','livenessProbe','readinessProbe','ports'):container.pop(name,None)
        container['env']=[entry for entry in container.get('env',[]) if entry['name'] in {'CORE_URL','CORE_CA_FILE','WORKER_TOKEN','PYTHONPATH','PYTHONDONTWRITEBYTECODE','PYTHONUNBUFFERED'}]
        for name,value in {'WORKER_ROLE':'specialist','WORKER_ID':'plate-1','SPECIALIST_ENABLED':'true','SPECIALIST_ACCEPT_ROUTING':'true',
                           'SPECIALIST_OPERATIONS':'license_plate','SPECIALIST_SECRETS_ROOT':'/specialist-credentials','SPECIALIST_SLOTS':'1',
                           'MODEL_TIMEOUT_SECONDS':'360','SPECIALIST_JOB_TIMEOUT_SECONDS':'480','OMP_NUM_THREADS':'1','OPENBLAS_NUM_THREADS':'1'}.items():environment(container,name,value)
        container['volumeMounts']=[entry for entry in container.get('volumeMounts',[]) if entry['mountPath'] in {'/core-ca','/tmp'}]
        used={entry['name'] for entry in container['volumeMounts']};pod['volumes']=[entry for entry in pod.get('volumes',[]) if entry['name'] in used];pod.pop('initContainers',None)
        for entry in pod['volumes']:
            if entry['name']=='core-ca' and 'secret' in entry:entry['secret']['items']=[{'key':'ca.crt','path':'ca.crt'}]
        volume(pod,'specialist-credentials',{'secret':{'secretName':'specialist-consumer-credentials','defaultMode':0o440}},'/specialist-credentials')
        container['resources']={'requests':{'cpu':'25m','memory':'96Mi'},'limits':{'cpu':'500m','memory':'384Mi','ephemeral-storage':'128Mi'}}
    native_path=directory/'specialist-service.json'
    native=load('specialist-service') if native_path.exists() else deepcopy(worker)
    if not native_path.exists():
        native['metadata']=metadata('specialist-service');native['spec']['selector']={'matchLabels':{'app':'specialist-service'}};native['spec']['template']['metadata']={'labels':{'app':'specialist-service'}}
        pod=native['spec']['template']['spec'];container=pod['containers'][0];container['name']='specialist-service';container['command']=['python','-m','services.specialist_service']
        container['env']=[{'name':name,'value':value} for name,value in {'NATIVE_SPECIALIST_CATALOG':'/model-catalog/specialist.json','NATIVE_SPECIALIST_MODELS_ROOT':'/models',
            'NATIVE_SPECIALIST_SLOTS':'1','NATIVE_SPECIALIST_CAMERAS':'recording-01,recording-02,recording-03,recording-04','OMP_NUM_THREADS':'1','OPENBLAS_NUM_THREADS':'1','PYTHONUNBUFFERED':'1'}.items()]
        container['env'].append({'name':'NATIVE_SPECIALIST_TOKEN','valueFrom':{'secretKeyRef':{'name':'specialist-consumer-credentials','key':'plate.token'}}})
        container['volumeMounts']=[];pod['volumes']=[]
        volume(pod,'tls',{'secret':{'secretName':'specialist-service-tls','defaultMode':0o440}},'/tls')
        volume(pod,'models',{'persistentVolumeClaim':{'claimName':'appearance-models'}},'/models')
        container['volumeMounts'][-1]['subPath']='plate'
        volume(pod,'catalog',{'configMap':{'name':'specialist-native-catalog'}},'/model-catalog')
        pod['volumes'].append({'name':'temporary','emptyDir':{'sizeLimit':'128Mi'}});container['volumeMounts'].append({'name':'temporary','mountPath':'/tmp'})
        container['ports']=[{'name':'https','containerPort':8446}]
        container['readinessProbe']={'httpGet':{'path':'/readyz','port':8446,'scheme':'HTTPS'},'periodSeconds':10,'timeoutSeconds':5,'failureThreshold':6}
        container['livenessProbe']={'httpGet':{'path':'/healthz','port':8446,'scheme':'HTTPS'},'periodSeconds':20,'timeoutSeconds':5}
        container['startupProbe']={'httpGet':{'path':'/healthz','port':8446,'scheme':'HTTPS'},'periodSeconds':5,'failureThreshold':30}
        container['resources']={'requests':{'cpu':'50m','memory':'256Mi'},'limits':{'cpu':'1000m','memory':'1Gi','ephemeral-storage':'128Mi'}}
    for name,deployment in [('specialist-worker',worker),('specialist-service',native)]:
        container=deployment['spec']['template']['spec']['containers'][0];container['image']=runtime;container['imagePullPolicy']='IfNotPresent'
        deployment['spec']['template']['metadata'].setdefault('annotations',{}).update({'video/version':version,'video/source-revision':revision,'video/actions-run':run})
        write(directory/(name+'.json'),deployment)
    path=root/'kustomization.yaml';document=yaml.safe_load(path.read_text())
    for name in ('specialist-native-catalog','specialist-processing-catalog','specialist-service-certificate','specialist-service-service','specialist-worker','specialist-service'):
        entry='runtime/'+name+'.json'
        if entry not in document['resources']:document['resources'].append(entry)
    path.write_text(yaml.safe_dump(document,sort_keys=False))
