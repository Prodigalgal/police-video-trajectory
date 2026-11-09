"""Wire installed application modules into this application's private manifests."""
from copy import deepcopy
import hashlib
import json
from pathlib import Path
from urllib.parse import urlsplit
import yaml


def write(path,value):path.write_text(json.dumps(value,indent=2)+'\n')


def environment(container,name,value):
    entries=container.setdefault('env',[])
    if not any(entry['name']==name for entry in entries):entries.append({'name':name,'value':value})


def volume(pod,name,source,path):
    if not any(entry['name']==name for entry in pod.setdefault('volumes',[])):pod['volumes'].append({'name':name,**source})
    mounts=pod['containers'][0].setdefault('volumeMounts',[])
    if not any(entry['name']==name for entry in mounts):mounts.append({'name':name,'mountPath':path,'readOnly':True})


def configure(app_root,runtime,version,revision,run):
    source=Path(__file__).resolve().parents[1];root=Path(app_root);directory=root/'runtime'
    def deployment(name):return json.loads((directory/(name+'.json')).read_text())
    core,web,stream=deployment('core-api'),deployment('web-ui'),deployment('rtsp-simulator')
    core_pod,web_pod,stream_pod=[value['spec']['template']['spec'] for value in (core,web,stream)]
    core_container,web_container,stream_container=[pod['containers'][0] for pod in (core_pod,web_pod,stream_pod)]
    environment(core_container,'VIDEO_INVESTIGATION_ENABLED','true')
    environment(core_container,'VIDEO_APPEARANCE_ENABLED','true')
    environment(core_container,'VIDEO_APPEARANCE_PENDING_LIMIT','128')
    environment(core_container,'VIDEO_APPEARANCE_ACTIVE_LIMIT','1')
    environment(core_container,'VIDEO_APPEARANCE_SCAN_MS','15000')
    environment(core_container,'VIDEO_APPEARANCE_CATALOG_FILE','/appearance-catalog/catalog.json')
    environment(web_container,'WEB_INVESTIGATION_ENABLED','true')
    environment(web_container,'WEB_APPEARANCE_ENABLED','true')
    environment(web_container,'WEB_AMAP_CONFIG_FILE','/map-provider/amap.json')
    environment(web_container,'APPEARANCE_CATALOG_FILE','/appearance-catalog/catalog.json')
    environment(web_container,'APPEARANCE_MODEL_DIR','/appearance-models')
    for suffix in ('URL','CA','TOKEN'):
        name='WEB_APPEARANCE_GATEWAY_'+suffix
        if not any(entry['name']==name for entry in web_container['env']):
            inherited=deepcopy(next(entry for entry in core_container['env'] if entry['name']=='VIDEO_GATEWAY_'+suffix))
            inherited['name']=name;web_container['env'].append(inherited)
    gateway_volume=next(entry for entry in core_pod['volumes'] if entry['name']=='gateway-ca')
    volume(web_pod,'gateway-ca',{key:deepcopy(value) for key,value in gateway_volume.items() if key!='name'},'/gateway-ca')
    if not any(entry['name']=='WEB_LIVE_PREVIEW_PATHS_JSON' for entry in web_container['env']):
        primary=json.loads(next(entry['value'] for entry in web_container['env'] if entry['name']=='WEB_LIVE_CAMERA_PATHS_JSON'))
        environment(web_container,'WEB_LIVE_PREVIEW_PATHS_JSON',json.dumps({camera:'preview-'+path for camera,path in primary.items()}))
    for pod in (core_pod,web_pod):volume(pod,'appearance-catalog',{'configMap':{'name':'appearance-catalog'}},'/appearance-catalog')
    volume(web_pod,'appearance-models',{'persistentVolumeClaim':{'claimName':'appearance-models'}},'/appearance-models')
    volume(web_pod,'map-provider',{'secret':{'secretName':'police-video-amap-provider','defaultMode':0o440}},'/map-provider')
    annotations=web['spec']['template']['metadata'].setdefault('annotations',{})
    if 'video/appearance-resources' not in annotations:
        web_container['resources']['limits'].update({'cpu':'1500m','memory':'1Gi'})
        annotations['video/appearance-resources']='1'

    config_path=directory/'rtsp-simulator-config.json';config=json.loads(config_path.read_text())
    media=yaml.safe_load(config['data']['mediamtx.yml'])
    media.update(hlsVariant='fmp4',hlsSegmentCount=6,hlsSegmentDuration='1s',hlsMuxerCloseAfter='5s')
    preview_rule='~^preview-recording-0[1-4]$'
    for user,action in [(media['authInternalUsers'][0],'publish'),(media['authInternalUsers'][1],'read')]:
        permission={'action':action,'path':preview_rule}
        if permission not in user['permissions']:user['permissions'].append(permission)
    for number in range(1,5):
        media['paths']['preview-recording-0'+str(number)]={'source':'publisher','runOnDemand':'/bin/sh /config/live-preview.sh',
            'runOnDemandRestart':True,'runOnDemandStartTimeout':'20s','runOnDemandCloseAfter':'2s'}
    for number in (5,6):
        existing=media['paths'].get('recording-0'+str(number))
        if existing is not None:existing['runOnInit']='';existing['runOnInitRestart']=False
    config['data']['mediamtx.yml']=yaml.safe_dump(media,sort_keys=False)
    config['data']['live-preview.sh']=(source/'services/video_worker/live-preview.sh').read_text()
    host=urlsplit(next(entry['value'] for entry in web_container['env'] if entry['name']=='WEB_LIVE_MEDIA_URL')).hostname
    environment(stream_container,'PREVIEW_RTSP_HOST',host)
    environment(stream_container,'PREVIEW_RTSP_PORT',str(int(media['rtspsAddress'].rsplit(':',1)[1])))
    environment(stream_container,'PREVIEW_RTSP_USER',media['authInternalUsers'][1]['user'])
    if not any(entry['name']=='preview-temporary' for entry in stream_pod['volumes']):
        stream_pod['volumes'].append({'name':'preview-temporary','emptyDir':{'sizeLimit':'32Mi'}})
        stream_container['volumeMounts'].append({'name':'preview-temporary','mountPath':'/tmp'})
    stream_annotations=stream['spec']['template']['metadata'].setdefault('annotations',{})
    stream_annotations.update({'video/preview-configuration':hashlib.sha256(json.dumps(config['data'],sort_keys=True).encode()).hexdigest(),
                               'video/version':version,'video/source-revision':revision,'video/actions-run':run})
    stream_container['resources']['limits']['cpu']='2'
    for name,value in [('core-api',core),('web-ui',web),('rtsp-simulator',stream)]:write(directory/(name+'.json'),value)
    write(config_path,config)

    namespace=core['metadata']['namespace']
    catalog={'apiVersion':'v1','kind':'ConfigMap','metadata':{'name':'appearance-catalog','namespace':namespace},
        'data':{'catalog.json':(source/'deploy/appearance-catalog.json').read_text()}}
    write(directory/'appearance-catalog.json',catalog)
    pvc={'apiVersion':'v1','kind':'PersistentVolumeClaim','metadata':{'name':'appearance-models','namespace':namespace,
        'labels':{'video/protected':'true','video/resource-role':'model-assets'},'annotations':{'argocd.argoproj.io/sync-options':'Prune=false'}},
        'spec':{'accessModes':['ReadWriteOnce'],'storageClassName':'','volumeName':namespace+'-appearance-models-local',
                'resources':{'requests':{'storage':'1Gi'}}}}
    if not (directory/'appearance-models.json').exists():write(directory/'appearance-models.json',pvc)

    worker_path=directory/'appearance-worker.json'
    if worker_path.exists():worker=json.loads(worker_path.read_text())
    else:
        worker=deepcopy(deployment('rtsp-worker'));worker['metadata']['name']='appearance-worker'
        worker['metadata'].pop('annotations',None);worker['metadata']['labels']={'app':'appearance-worker'}
        worker['spec'].update(replicas=1,revisionHistoryLimit=0,selector={'matchLabels':{'app':'appearance-worker'}})
        worker['spec']['template']['metadata']={'labels':{'app':'appearance-worker'}}
        pod=worker['spec']['template']['spec'];container=pod['containers'][0]
        container['name']='appearance-worker';container['command']=['python','-m','services.appearance.worker'];container.pop('args',None)
        for field in ('ports','startupProbe','livenessProbe','readinessProbe'):container.pop(field,None)
        container['env']=[entry for entry in container['env'] if entry['name'] in {'CORE_URL','CORE_CA_FILE','WORKER_TOKEN','PYTHONDONTWRITEBYTECODE','PYTHONUNBUFFERED','PYTHONPATH'}]
        for key,value in {'APPEARANCE_CATALOG_FILE':'/appearance-catalog/catalog.json','APPEARANCE_MODEL_DIR':'/appearance-models',
                          'APPEARANCE_WORKER_ID':'appearance-1','APPEARANCE_JOB_PAUSE_SECONDS':'0.2','OMP_NUM_THREADS':'1','OPENBLAS_NUM_THREADS':'1'}.items():environment(container,key,value)
        container['volumeMounts']=[entry for entry in container['volumeMounts'] if entry['mountPath'] in {'/core-ca','/tmp'}]
        used={entry['name'] for entry in container['volumeMounts']};pod['volumes']=[entry for entry in pod['volumes'] if entry['name'] in used];pod.pop('initContainers',None)
        volume(pod,'appearance-models',{'persistentVolumeClaim':{'claimName':'appearance-models'}},'/appearance-models')
        volume(pod,'appearance-catalog',{'configMap':{'name':'appearance-catalog'}},'/appearance-catalog')
        container['resources']={'requests':{'cpu':'100m','memory':'256Mi'},'limits':{'cpu':'750m','memory':'768Mi','ephemeral-storage':'128Mi'}}
    worker['spec']['template']['spec']['containers'][0]['image']=runtime
    worker['spec']['template']['spec']['containers'][0]['imagePullPolicy']='IfNotPresent'
    worker['spec']['template']['metadata'].setdefault('annotations',{}).update({'video/version':version,'video/source-revision':revision,'video/actions-run':run})
    write(worker_path,worker)

    path=root/'kustomization.yaml';kustomization=yaml.safe_load(path.read_text())
    for name in ('appearance-catalog','appearance-models','appearance-worker'):
        resource='runtime/'+name+'.json'
        if resource not in kustomization['resources']:kustomization['resources'].append(resource)
    path.write_text(yaml.safe_dump(kustomization,sort_keys=False))
