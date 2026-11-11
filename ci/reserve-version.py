"""Reserve a formal application version without modifying dependency versions."""
import json
from pathlib import Path
import re
import sys

root=Path(__file__).resolve().parents[1]
version=sys.argv[1]
if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)',version):raise ValueError('formal x.y.z required')
previous=(root/'VERSION').read_text().strip()
if tuple(map(int,version.split('.')))<=tuple(map(int,previous.split('.'))):raise ValueError('version must increase')
for name in ('ai_gateway','web_gateway','video_worker','specialist_service'):
    path=root/'services'/name/'__init__.py'
    content,count=re.subn(r'''(?m)^__version__\s*=\s*['"][0-9.]+['"]''',f'__version__ = "{version}"',path.read_text())
    if count!=1:raise ValueError('single runtime version required')
    path.write_text(content,encoding='utf-8',newline='\n')
path=root/'services/core_api/build.gradle'
path.write_text(re.sub(r"(?m)^version = '[0-9.]+'$",f"version = '{version}'",path.read_text()),encoding='utf-8',newline='\n')
for path in (root/'services/core_api/src/main/resources/db').glob('*.sql'):
    content=path.read_text();updated=re.sub(r'Application version: [0-9.]+\.',f'Application version: {version}.',content)
    if updated!=content:path.write_text(updated,encoding='utf-8',newline='\n')
for name in ('package.json','package-lock.json'):
    path=root/'web'/name;value=json.loads(path.read_text());value['version']=version
    if name=='package-lock.json':value['packages']['']['version']=version
    path.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8',newline='\n')
(root/'VERSION').write_text(version+'\n',encoding='utf-8',newline='\n')
cluster_example=root/'deploy/cluster/config.example.json'
if cluster_example.exists():
    value=json.loads(cluster_example.read_text());old=value['version'];value['version']=version
    for key in ('core_image','runtime_image'):value[key]=value[key].replace('-v'+old+'-','-v'+version+'-')
    cluster_example.write_text(json.dumps(value,indent=2)+'\n',encoding='utf-8',newline='\n')
print('Reserved application version '+version)
