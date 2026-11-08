"""A deployable push cannot reuse a previously occupied application version."""
from pathlib import Path
import subprocess

version=Path('VERSION').read_text().strip()
current=tuple(map(int,version.split('.')))
prior=subprocess.run(['git','show','HEAD^:VERSION'],capture_output=True,text=True)
if prior.returncode==0:
    previous=tuple(map(int,prior.stdout.strip().split('.')))
    if current<=previous:raise SystemExit('A release commit must reserve a higher formal x.y.z version.')
print('Formal release version accepted: '+version)
