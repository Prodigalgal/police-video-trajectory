"""Portable offline control-plane manifests; no credentials or cluster writes."""
import argparse
import json
from pathlib import Path
import re
from urllib.parse import urlsplit

NAME=re.compile(r'[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?')
VERSION=re.compile(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)')


def validate(config):
    required={'namespace','version','core_image','runtime_image','origin','media_claim','database_secret','elasticsearch_secret','service_secret','core_tls_secret','web_tls_secret','gateway_tls_secret','provider_registry','provider_credentials','model_total','text_reserved','replicas','media_access_mode'}
    if not isinstance(config,dict) or config.keys()!=required:raise ValueError('集群配置字段不完整或含未知字段')
    for key in required-{'version','core_image','runtime_image','origin','model_total','text_reserved','replicas','media_access_mode'}:
        if not isinstance(config[key],str) or not NAME.fullmatch(config[key]):raise ValueError('资源引用名称无效：'+key)
    secrets=[config[key] for key in ('database_secret','elasticsearch_secret','service_secret','core_tls_secret','web_tls_secret','gateway_tls_secret','provider_credentials')]
    if len(set(secrets))!=len(secrets):raise ValueError('不同职责的秘密必须使用独立引用')
    if not isinstance(config['version'],str) or not VERSION.fullmatch(config['version']):raise ValueError('必须指定正式三段版本')
    for key in ('core_image','runtime_image'):
        value=config[key]
        if not isinstance(value,str) or not re.fullmatch(r'[a-z0-9./_-]+:[0-9]{8}-v'+re.escape(config['version'])+r'-[a-z0-9_.-]+',value):raise ValueError('离线镜像必须使用含日期与统一版本的不可变标签')
    if not isinstance(config['origin'],str):raise ValueError('浏览器来源必须为字符串')
    origin=urlsplit(config['origin'])
    try:valid_port=origin.port is None or origin.port>0
    except ValueError:raise ValueError('浏览器来源端口无效') from None
    if origin.scheme!='https' or not origin.hostname or not valid_port or any((origin.username,origin.password,origin.query,origin.fragment)) or origin.path not in ('','/'):raise ValueError('必须配置有效的浏览器HTTPS来源')
    if type(config['model_total']) is not int or not 1<=config['model_total']<=64 or type(config['text_reserved']) is not int or not 0<=config['text_reserved']<config['model_total']:raise ValueError('模型集群额度无效')
    if type(config['replicas']) is not int or not 2<=config['replicas']<=8:raise ValueError('集群服务副本数须为2至8')
    if config['media_access_mode']!='ReadWriteMany':raise ValueError('跨节点读取需要经过验证的共享媒体卷，不能使用节点本地卷')


def render(config):
    validate(config);c=config;ns=c['namespace'];resources=[]
    def metadata(name):return {'name':name,'namespace':ns,'labels':{'app.kubernetes.io/part-of':'police-video-trajectory'}}
    resources.append({'apiVersion':'v1','kind':'Namespace','metadata':{'name':ns}})
    resources.append({'apiVersion':'v1','kind':'ServiceAccount','metadata':metadata('video-application'),'automountServiceAccountToken':False})
    def literal(name,value):return {'name':name,'value':str(value)}
    def secret(name,resource,key):return {'name':name,'valueFrom':{'secretKeyRef':{'name':resource,'key':key}}}
    def credential(name,key):return secret(name,c['service_secret'],key)
    def pod_id(name):return {'name':name,'valueFrom':{'fieldRef':{'fieldPath':'metadata.uid'}}}
    def tls_mount(resource):return {'name':'tls','secret':{'secretName':resource,'defaultMode':288}}
    def shared_ca():return {'name':'core-ca','secret':{'secretName':c['core_tls_secret'],'items':[{'key':'ca.crt','path':'ca.crt'}],'defaultMode':288}}
    def mount(name,path,readonly=True):return {'name':name,'mountPath':path,'readOnly':readonly}
    def workload(name,image,command,port,environment,volumes,mounts):
        container={'name':name,'image':image,'imagePullPolicy':'IfNotPresent','command':command,'env':environment,
            'ports':[{'name':'https','containerPort':port}],'volumeMounts':mounts+[mount('temporary','/tmp',False)],
            'securityContext':{'allowPrivilegeEscalation':False,'readOnlyRootFilesystem':True,'capabilities':{'drop':['ALL']}},
            'resources':{'requests':{'cpu':'100m','memory':'256Mi'},'limits':{'cpu':'1000m','memory':'768Mi','ephemeral-storage':'256Mi'}},
            'startupProbe':{'tcpSocket':{'port':'https'},'periodSeconds':5,'failureThreshold':60},
            'readinessProbe':{'httpGet':{'path':'/internal/cluster/readyz' if name=='core-api' else '/readyz','port':'https','scheme':'HTTPS'},'periodSeconds':5,'timeoutSeconds':5,'failureThreshold':3}}
        pod={'serviceAccountName':'video-application','automountServiceAccountToken':False,'terminationGracePeriodSeconds':60,
             'nodeSelector':{'kubernetes.io/arch':'arm64','video.platform/worker':'true'},
             'securityContext':{'runAsNonRoot':True,'runAsUser':10001,'runAsGroup':10001,'fsGroup':10001,'seccompProfile':{'type':'RuntimeDefault'}},
             'containers':[container],'volumes':volumes+[{'name':'temporary','emptyDir':{'sizeLimit':'256Mi'}}],
             'topologySpreadConstraints':[{'maxSkew':1,'topologyKey':'kubernetes.io/hostname','whenUnsatisfiable':'DoNotSchedule','labelSelector':{'matchLabels':{'app':name}}}],
             'affinity':{'podAntiAffinity':{'preferredDuringSchedulingIgnoredDuringExecution':[{'weight':100,'podAffinityTerm':{'labelSelector':{'matchLabels':{'app':name}},'topologyKey':'kubernetes.io/hostname'}}]}}}
        resources.append({'apiVersion':'apps/v1','kind':'Deployment','metadata':metadata(name),'spec':{'replicas':c['replicas'],'revisionHistoryLimit':2,
            'strategy':{'type':'RollingUpdate','rollingUpdate':{'maxSurge':1,'maxUnavailable':0}},'selector':{'matchLabels':{'app':name}},
            'template':{'metadata':{'labels':{'app':name,'app.kubernetes.io/part-of':'police-video-trajectory'},'annotations':{'video/version':c['version']}},'spec':pod}}})
        resources.append({'apiVersion':'v1','kind':'Service','metadata':metadata(name),'spec':{'selector':{'app':name},'ports':[{'name':'https','port':port,'targetPort':'https'}]}})
        resources.append({'apiVersion':'policy/v1','kind':'PodDisruptionBudget','metadata':metadata(name),'spec':{'minAvailable':1,'selector':{'matchLabels':{'app':name}}}})
        return container
    core_url=f'https://core-api.{ns}.svc:8444';gateway_url=f'https://ai-gateway.{ns}.svc:8443'
    common=[literal('PYTHONDONTWRITEBYTECODE','1'),literal('PYTHONUNBUFFERED','1'),literal('OMP_NUM_THREADS','1'),literal('OPENBLAS_NUM_THREADS','1')]
    core_env=[literal('SERVER_PORT',8444),literal('SERVER_SSL_ENABLED','true'),literal('SERVER_SSL_KEY_STORE','/tls/keystore.p12'),literal('SERVER_SSL_KEY_STORE_TYPE','PKCS12'),
        secret('SERVER_SSL_KEY_STORE_PASSWORD',c['core_tls_secret'],'keystore-password'),literal('JAVA_TOOL_OPTIONS','-Xmx512m -Djava.io.tmpdir=/tmp'),
        secret('DATABASE_URL',c['database_secret'],'url'),secret('DATABASE_USER',c['database_secret'],'user'),secret('DATABASE_PASSWORD',c['database_secret'],'password'),
        secret('VIDEO_ES_URL',c['elasticsearch_secret'],'url'),secret('VIDEO_ES_USER',c['elasticsearch_secret'],'user'),secret('VIDEO_ES_PASSWORD',c['elasticsearch_secret'],'password'),
        credential('VIDEO_ADMIN_TOKEN','admin-token'),credential('VIDEO_SEARCH_TOKEN','search-token'),credential('VIDEO_WORKER_TOKEN','worker-token'),credential('VIDEO_GATEWAY_TOKEN','gateway-token'),
        literal('VIDEO_GATEWAY_URL',gateway_url),literal('VIDEO_GATEWAY_CA','/gateway-ca/ca.crt'),literal('VIDEO_CLUSTER_COORDINATION_ENABLED','true'),
        literal('VIDEO_CLUSTER_MODEL_TOTAL',c['model_total']),literal('VIDEO_CLUSTER_MODEL_TEXT_RESERVED',c['text_reserved']),
        literal('VIDEO_EVIDENCE_DIR','/media/evidence'),literal('VIDEO_ARCHIVE_DIR','/media/archives'),literal('VIDEO_MEDIA_CLIP_DIR','/media/clips'),literal('VIDEO_LIVE_RECORDING_DIR','/media/live'),
        literal('VIDEO_CAPTURE_DISPATCHER_ENABLED','false'),literal('VIDEO_INDEXER_ENABLED','false'),literal('VIDEO_EXPORTS_INSTALLED','false'),literal('VIDEO_EXPORTS_ENABLED','false')]
    core=workload('core-api',c['core_image'],['java','-jar','/app/app.jar'],8444,core_env,
        [tls_mount(c['core_tls_secret']),{'name':'database-ca','secret':{'secretName':c['database_secret'],'items':[{'key':'ca.crt','path':'ca.crt'}]}},
         {'name':'es-ca','secret':{'secretName':c['elasticsearch_secret'],'items':[{'key':'ca.crt','path':'ca.crt'}]}},
         {'name':'gateway-ca','secret':{'secretName':c['gateway_tls_secret'],'items':[{'key':'ca.crt','path':'ca.crt'}]}},
         {'name':'media','persistentVolumeClaim':{'claimName':c['media_claim'],'readOnly':True}},{'name':'providers','configMap':{'name':c['provider_registry']}}],
        [mount('tls','/tls'),mount('database-ca','/database-ca'),mount('es-ca','/es-ca'),mount('gateway-ca','/gateway-ca'),mount('media','/media'),mount('providers','/model-providers')])
    core['env'] += [literal('VIDEO_ES_CA','/es-ca/ca.crt'),literal('VIDEO_MODEL_PROVIDERS_FILE','/model-providers/providers.json')]
    web_env=common+[literal('CORE_URL',core_url),literal('CORE_CA_FILE','/core-ca/ca.crt'),
        literal('WEB_AUTH_MODE','password'),literal('WEB_SHARED_AUTH_STATE','true'),literal('WEB_ACCOUNT_DIRECTORY','true'),literal('WEB_ALLOWED_ORIGIN',c['origin'].rstrip('/')),
        credential('WEB_SESSION_SECRET','session-secret'),credential('WEB_USERNAME','bootstrap-username'),credential('WEB_PASSWORD_HASH','bootstrap-password-hash'),credential('CORE_ADMIN_TOKEN','admin-token'),credential('CORE_SEARCH_TOKEN','search-token')]
    workload('web-ui',c['runtime_image'],['python','-m','services.web_gateway.server'],8445,web_env,[tls_mount(c['web_tls_secret']),shared_ca()],[mount('tls','/tls'),mount('core-ca','/core-ca')])
    gateway_env=common+[credential('GATEWAY_SHARED_TOKEN','gateway-token'),
        literal('MODEL_CLUSTER_ADMISSION','true'),literal('MODEL_CONTROL_URL',core_url),literal('MODEL_CONTROL_CA_FILE','/core-ca/ca.crt'),pod_id('MODEL_CONTROL_CONSUMER'),
        literal('MODEL_TIMEOUT_SECONDS',360),literal('MODEL_MAX_INFLIGHT',min(c['model_total'],32)),literal('MODEL_TEXT_RESERVED',0),literal('MODEL_RESULT_CACHE_DIR',''),
        literal('MODEL_PROVIDERS_FILE','/providers/providers.json'),literal('MODEL_CREDENTIALS_ROOT','/provider-credentials')]
    workload('ai-gateway',c['runtime_image'],['python','-c',"import asyncio; from services.web_gateway.server import main; asyncio.run(main('services.ai_gateway.app:app',8443))"],8443,gateway_env,
        [tls_mount(c['gateway_tls_secret']),shared_ca(),{'name':'providers','configMap':{'name':c['provider_registry']}},{'name':'provider-credentials','secret':{'secretName':c['provider_credentials'],'defaultMode':288}}],
        [mount('tls','/tls'),mount('core-ca','/core-ca'),mount('providers','/providers'),mount('provider-credentials','/provider-credentials')])
    return {'apiVersion':'v1','kind':'List','items':resources}


def readiness(config):
    validate(config)
    return {'schema_version':'cluster-prerequisites-v1','version':config['version'],'ready_for_production':False,
        'requires':['三个Worker的Ready/标签/镜像导入','共享媒体卷RWX及文件一致性验证','PostgreSQL和Elasticsearch部署/HA与TLS','服务令牌与三个服务TLS证书','033数据库迁移与首个管理员初始化','模型端点与高德地图内网访问','入口TLS和业务就绪检查'],
        'not_included':['采集/媒体写入者的分布式账本与故障接管','跨Gateway持久推理结果去重缓存','目标节点故障和长稳验收'],
        'safety':'此基础配置关闭采集派发、自动索引、录像和导出生产；不自动应用到集群'}


def inspect_inventory(config,inventory):
    """Inspect operator-supplied metadata; never accept secret values."""
    result=readiness(config);failures=[]
    if not isinstance(inventory,dict) or inventory.keys()!={'nodes','claims','volumes','secrets','configmaps','images'}:raise ValueError('现场清单字段无效')
    if any(not isinstance(inventory[key],list) or len(inventory[key])>1000 for key in ('nodes','claims','volumes')):raise ValueError('现场资源清单超限')
    for key in ('secrets','configmaps','images'):
        if not isinstance(inventory[key],dict) or len(inventory[key])>1000 or any(not isinstance(values,list) or len(values)>1000 or not all(isinstance(value,str) for value in values) for values in inventory[key].values()):raise ValueError('现场清单只接受有界名称列表，不接受秘密内容')
    nodes=[]
    for node in inventory['nodes']:
        labels=node.get('metadata',{}).get('labels',{})
        ready=any(condition.get('type')=='Ready' and condition.get('status')=='True' for condition in node.get('status',{}).get('conditions',[]))
        blocked=node.get('spec',{}).get('unschedulable',False) or any(taint.get('effect') in {'NoSchedule','NoExecute'} for taint in node.get('spec',{}).get('taints',[]))
        if labels.get('video.platform/worker')=='true' and labels.get('kubernetes.io/arch')=='arm64' and ready and not blocked:nodes.append(node['metadata']['name'])
    if len(set(nodes))<3:failures.append('不足三个可调度且带业务标签的ARM64 Worker')
    namespace=config['namespace'];claim=next((item for item in inventory['claims'] if item.get('metadata',{}).get('name')==config['media_claim'] and item.get('metadata',{}).get('namespace')==namespace),None)
    if not claim or claim.get('status',{}).get('phase')!='Bound' or 'ReadWriteMany' not in claim.get('spec',{}).get('accessModes',[]):failures.append('共享媒体卷未绑定或不具备RWX访问模式')
    else:
        volume=next((item for item in inventory['volumes'] if item.get('metadata',{}).get('name')==claim.get('spec',{}).get('volumeName')),None)
        if not volume or 'local' in volume.get('spec',{}) or 'hostPath' in volume.get('spec',{}):failures.append('媒体卷仍是节点本地存储或缺少卷元数据')
    required_secrets={config['database_secret']:{'url','user','password','ca.crt'},config['elasticsearch_secret']:{'url','user','password','ca.crt'},
        config['service_secret']:{'session-secret','bootstrap-username','bootstrap-password-hash','admin-token','search-token','worker-token','gateway-token'},
        config['core_tls_secret']:{'keystore.p12','keystore-password','ca.crt'},config['web_tls_secret']:{'tls.crt','tls.key','ca.crt'},config['gateway_tls_secret']:{'tls.crt','tls.key','ca.crt'}}
    for name,keys in required_secrets.items():
        present=inventory['secrets'].get(name,[])
        if not isinstance(present,list) or not all(isinstance(key,str) for key in present):raise ValueError('秘密清单只允许键名，不接受秘密内容')
        if not keys<=set(present):failures.append('服务秘密未准备完整：'+name)
    if not inventory['secrets'].get(config['provider_credentials']):failures.append('模型凭据文件引用未准备')
    if 'providers.json' not in inventory['configmaps'].get(config['provider_registry'],[]):failures.append('提供商目录未准备')
    for name in nodes:
        present=inventory['images'].get(name,[])
        if not all(image in present or 'docker.io/'+image in present for image in (config['core_image'],config['runtime_image'])):failures.append('节点缺少同版离线镜像：'+name)
    result.update(foundation_metadata_ready=not failures,eligible_workers=len(set(nodes)),failures=failures)
    return result


if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--config',required=True);parser.add_argument('--output');parser.add_argument('--check',action='store_true');parser.add_argument('--inventory');args=parser.parse_args()
    cfg=json.loads(Path(args.config).read_text(encoding='utf-8'))
    if args.inventory and not args.check:parser.error('--inventory 必须与 --check 一起使用')
    if args.inventory and Path(args.inventory).stat().st_size>2*1024*1024:raise ValueError('现场元数据文件超限')
    document=inspect_inventory(cfg,json.loads(Path(args.inventory).read_text(encoding='utf-8'))) if args.inventory else readiness(cfg) if args.check else render(cfg)
    payload=json.dumps(document,ensure_ascii=False,indent=2)+'\n'
    if args.output:Path(args.output).write_text(payload,encoding='utf-8')
    else:print(payload,end='')
