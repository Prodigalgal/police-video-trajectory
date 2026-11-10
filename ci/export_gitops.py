"""Install an optional bounded export consumer from operator-owned storage and route."""
from copy import deepcopy
import json,re
from pathlib import Path
import yaml
from usability_gitops import volume,write

QUOTA=1536*1024**2
READINESS="import pathlib,tempfile,time; p=pathlib.Path(tempfile.gettempdir())/'police-video-export-ready'; assert p.is_file() and 0<=time.monotonic()-float(p.read_text())<60"


def environment(container,name,value):
    entries=container.setdefault('env',[])
    entries[:]=[entry for entry in entries if entry['name']!=name]
    entries.append({'name':name,'value':value})


def configure(app_root,runtime,version,revision,run):
    root=Path(app_root);directory=root/'runtime'
    def load(name):return json.loads((directory/(name+'.json')).read_text())
    core,web=load('core-api'),load('web-ui');namespace=core['metadata']['namespace']
    storage=directory/'evidence-exports-pvc.json';worker_file=directory/'export-worker.json';installed=storage.exists()
    if worker_file.exists() and not installed:raise ValueError('Export worker requires its configured storage')
    for name,deployment in [('core-api',core),('web-ui',web)]:
        container=deployment['spec']['template']['spec']['containers'][0]
        environment(container,'VIDEO_EXPORTS_ENABLED' if name=='core-api' else 'WEB_EXPORTS_ENABLED','false')
        environment(container,'VIDEO_EXPORTS_INSTALLED' if name=='core-api' else 'WEB_EXPORTS_INSTALLED','true' if installed else 'false')
    if not installed:
        for name,deployment in [('core-api',core),('web-ui',web)]:write(directory/(name+'.json'),deployment)
        return
    claim=load('evidence-exports-pvc');route=load('evidence-exports-route')
    size=claim.get('spec',{}).get('resources',{}).get('requests',{}).get('storage','');match=re.fullmatch(r'([1-9][0-9]*)(Mi|Gi)',size)
    if claim.get('kind')!='PersistentVolumeClaim' or claim.get('metadata',{}).get('namespace')!=namespace or not match:raise ValueError('Invalid operator-owned export storage')
    capacity=int(match[1])*(1024**2 if match[2]=='Mi' else 1024**3)
    if capacity<2*1024**3:raise ValueError('Export storage must include artifact and filesystem headroom')
    if route.get('kind')!='HTTPRoute' or route.get('metadata',{}).get('namespace')!=namespace:raise ValueError('Invalid export download route')
    rules=route.get('spec',{}).get('rules',[])
    if not rules or any(rule.get('matches')!=[{'path':{'type':'PathPrefix','value':'/api/v1/exports'}}] for rule in rules):raise ValueError('Export route must be scoped to export APIs')
    for rule in rules:
        timeouts=rule.get('timeouts',{})
        if timeouts.get('request')!='1000s' or timeouts.get('backendRequest')!='980s':raise ValueError('Export download route must permit bounded long transfers')
        backends=rule.get('backendRefs',[])
        if len(backends)!=1 or backends[0].get('name')!=web['metadata']['name'] or backends[0].get('kind','Service')!='Service' or backends[0].get('group','')!='' or backends[0].get('namespace',namespace)!=namespace or rule.get('filters'):raise ValueError('Export route must use the existing authenticated web service')
    core_pod=core['spec']['template']['spec'];core_container=core_pod['containers'][0]
    environment(core_container,'VIDEO_EXPORT_DIR','/evidence-exports')
    volume(core_pod,'evidence-exports',{'persistentVolumeClaim':{'claimName':claim['metadata']['name']}},'/evidence-exports')
    next(entry for entry in core_container['volumeMounts'] if entry['name']=='evidence-exports')['readOnly']=True
    worker=load('export-worker') if worker_file.exists() else deepcopy(load('analysis-worker'))
    if not worker_file.exists():
        worker['metadata']={'name':'export-worker','namespace':namespace,'labels':{'app.kubernetes.io/part-of':'police-video-trajectory'}}
        worker['spec'].update(replicas=1,revisionHistoryLimit=0,selector={'matchLabels':{'app':'export-worker'}},strategy={'type':'Recreate'})
        worker['spec']['template']['metadata']={'labels':{'app':'export-worker'}}
        pod=worker['spec']['template']['spec'];pod.pop('initContainers',None);container=pod['containers'][0];container['name']='export-worker'
        container['command']=['python','-m','services.video_worker'];container.pop('args',None)
        container['env']=[entry for entry in container.get('env',[]) if entry['name'] in {'CORE_URL','CORE_CA_FILE','WORKER_TOKEN','PYTHONPATH','PYTHONDONTWRITEBYTECODE','PYTHONUNBUFFERED'}]
        container['env'].append({'name':'WORKER_ID','valueFrom':{'fieldRef':{'fieldPath':'metadata.name'}}})
        for name,value in {'WORKER_ROLE':'export','EXPORTS_ENABLED':'true','EXPORT_DIR':'/evidence-exports','EXPORT_BYTES':str(QUOTA),'EXPORT_JOB_TIMEOUT_SECONDS':'900','OMP_NUM_THREADS':'1','OPENBLAS_NUM_THREADS':'1'}.items():environment(container,name,value)
        container['volumeMounts']=[entry for entry in container.get('volumeMounts',[]) if entry['mountPath'] in {'/core-ca','/tmp'}]
        used={entry['name'] for entry in container['volumeMounts']};pod['volumes']=[entry for entry in pod.get('volumes',[]) if entry['name'] in used]
        for entry in pod['volumes']:
            if entry['name']=='core-ca' and 'secret' in entry:entry['secret']['items']=[{'key':'ca.crt','path':'ca.crt'}]
        volume(pod,'evidence-exports',{'persistentVolumeClaim':{'claimName':claim['metadata']['name']}},'/evidence-exports')
        next(entry for entry in container['volumeMounts'] if entry['name']=='evidence-exports')['readOnly']=False
        for key in ('livenessProbe','readinessProbe','startupProbe','ports'):container.pop(key,None)
        probe={'exec':{'command':['python','-c',READINESS]},'periodSeconds':10,'timeoutSeconds':5,'failureThreshold':3}
        container['readinessProbe']=probe;container['startupProbe']={**deepcopy(probe),'periodSeconds':5,'failureThreshold':60}
        container['resources']={'requests':{'cpu':'25m','memory':'64Mi'},'limits':{'cpu':'500m','memory':'192Mi','ephemeral-storage':'128Mi'}}
    pod=worker['spec']['template']['spec'];container=pod['containers'][0];container['image']=runtime;container['imagePullPolicy']='IfNotPresent'
    values={entry['name']:entry.get('value') for entry in container['env']}
    if values.get('WORKER_ROLE')!='export' or values.get('EXPORTS_ENABLED')!='true' or values.get('EXPORT_DIR')!='/evidence-exports':raise ValueError('Invalid export consumer role or directory')
    for current_pod in (core_pod,pod):
        if next(entry for entry in current_pod['volumes'] if entry['name']=='evidence-exports').get('persistentVolumeClaim',{}).get('claimName')!=claim['metadata']['name']:raise ValueError('Export storage changed; explicit migration is required')
    mount=next(entry for entry in container['volumeMounts'] if entry['name']=='evidence-exports')
    if mount.get('mountPath')!='/evidence-exports' or mount.get('readOnly') is not False:raise ValueError('Export consumer requires its writable output mount')
    if not str(values.get('EXPORT_BYTES','')).isdigit() or not 516*1024**2<=int(values['EXPORT_BYTES'])<=min(16*1024**3,capacity-256*1024**2):raise ValueError('Invalid export storage quota')
    if not str(values.get('EXPORT_JOB_TIMEOUT_SECONDS','')).isdigit() or not 300<=int(values['EXPORT_JOB_TIMEOUT_SECONDS'])<=1800:raise ValueError('Invalid export processing deadline')
    worker['spec']['template']['metadata'].setdefault('annotations',{}).update({'video/version':version,'video/source-revision':revision,'video/actions-run':run})
    for name,deployment in [('core-api',core),('web-ui',web),('export-worker',worker)]:write(directory/(name+'.json'),deployment)
    path=root/'kustomization.yaml';document=yaml.safe_load(path.read_text())
    for name in ('evidence-exports-pvc','evidence-exports-route','export-worker'):
        entry='runtime/'+name+'.json'
        if entry not in document['resources']:document['resources'].append(entry)
    path.write_text(yaml.safe_dump(document,sort_keys=False))
