"""Update only this application's existing GitOps image references, by digest."""
import json
from pathlib import Path
import re
import sys

root,core,runtime,version,revision,run = sys.argv[1:]
if not re.fullmatch(r'\d+\.\d+\.\d+',version) or not re.fullmatch(r'[a-f0-9]{40}',revision):
    raise ValueError('formal version and source commit required')
for image in (core,runtime):
    if not re.fullmatch(r'[a-z0-9./_-]+@sha256:[a-f0-9]{64}',image):raise ValueError('immutable digest required')
root=Path(root)/'police-video-trajectory'
names={'core-api','web-ui','ai-gateway','rtsp-worker','archive-worker','analysis-worker'}
for name in sorted(names):
    path=root/'runtime'/(name+'.json')
    value=json.loads(path.read_text())
    if value['kind']!='Deployment' or value['metadata']['name']!=name:raise ValueError('unexpected deployment')
    value['spec']['template']['spec']['containers'][0]['image']=core if name=='core-api' else runtime
    annotations=value['spec']['template']['metadata'].setdefault('annotations',{})
    annotations.update({'video/version':version,'video/source-revision':revision,'video/actions-run':run})
    path.write_text(json.dumps(value,indent=2)+'\n')
path=root/'runtime'/'application-migration.json'
value=json.loads(path.read_text());value['spec']['template']['spec']['containers'][0]['image']=runtime
path.write_text(json.dumps(value,indent=2)+'\n')
(root/'release.json').write_text(json.dumps({'version':version,'source_revision':revision,'actions_run':run,
                                          'core_image':core,'runtime_image':runtime},indent=2)+'\n')
