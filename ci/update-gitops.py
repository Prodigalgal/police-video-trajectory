"""Update only this application's existing GitOps image references, by digest."""
import json
from pathlib import Path
import re
import sys
from usability_gitops import configure
from midstream_gitops import configure as configure_midstream

root,core,runtime,version,revision,run = sys.argv[1:]
if not re.fullmatch(r'\d+\.\d+\.\d+',version) or not re.fullmatch(r'[a-f0-9]{40}',revision):
    raise ValueError('formal version and source commit required')
for image in (core,runtime):
    if not re.fullmatch(r'[a-z0-9./_-]+@sha256:[a-f0-9]{64}',image):raise ValueError('immutable digest required')
root=Path(root)/'police-video-trajectory'
names={'core-api','web-ui','ai-gateway','rtsp-worker','archive-worker','analysis-worker'}
initial_flags={'core-api':{'VIDEO_RECORDING_REVIEW_ENABLED':'true'},'web-ui':{'WEB_RECORDING_REVIEW_ENABLED':'true','WEB_PROCESSING_TRACE_ENABLED':'true'},
               'rtsp-worker':{'LIVE_RECORDING_ENABLED':'false','LIVE_RECORDING_CONTEXT_SECONDS':'0','PIPELINE_FEATURES_ENABLED':'true','CAPTURE_ANALYSIS_HANDOFF_ENABLED':'false'},
               'archive-worker':{'LIVE_RECORDING_ENABLED':'false','LIVE_RECORDING_CONTEXT_SECONDS':'0','PIPELINE_FEATURES_ENABLED':'true'}}
for name in sorted(names):
    path=root/'runtime'/(name+'.json')
    value=json.loads(path.read_text())
    if value['kind']!='Deployment' or value['metadata']['name']!=name:raise ValueError('unexpected deployment')
    value['spec']['template']['spec']['containers'][0]['image']=core if name=='core-api' else runtime
    value['spec']['template']['spec']['containers'][0]['imagePullPolicy']='IfNotPresent'
    environment=value['spec']['template']['spec']['containers'][0].setdefault('env',[])
    configured={entry['name'] for entry in environment}
    for key,setting in initial_flags.get(name,{}).items():
        # Managed recording uses persisted opt-in state. Keep the static fallback
        # closed across upgrades, without changing the operator-owned DB policy.
        if name in {'rtsp-worker','archive-worker'} and key in {'LIVE_RECORDING_ENABLED','LIVE_RECORDING_CONTEXT_SECONDS','PIPELINE_FEATURES_ENABLED'}:
            environment[:]=[entry for entry in environment if entry['name']!=key]
            environment.append({'name':key,'value':setting})
        elif key not in configured:environment.append({'name':key,'value':setting})
    annotations=value['spec']['template']['metadata'].setdefault('annotations',{})
    annotations.update({'video/version':version,'video/source-revision':revision,'video/actions-run':run})
    path.write_text(json.dumps(value,indent=2)+'\n')
path=root/'runtime'/'application-migration.json'
value=json.loads(path.read_text());value['spec']['template']['spec']['containers'][0]['image']=runtime
value['spec']['template']['spec']['containers'][0]['imagePullPolicy']='IfNotPresent'
path.write_text(json.dumps(value,indent=2)+'\n')
configure(root,runtime,version,revision,run)
configure_midstream(root,runtime,version,revision,run)
(root/'release.json').write_text(json.dumps({'version':version,'source_revision':revision,'actions_run':run,
                                          'core_image':core,'runtime_image':runtime},indent=2)+'\n')
