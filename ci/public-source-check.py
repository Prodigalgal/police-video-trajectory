"""Fail closed on private data or operational material in the public Git tree."""
from pathlib import Path
import re
import subprocess
import sys

root = Path(__file__).resolve().parents[1]
allowed_roots = {'services','web','tests','ci','.github'}
allowed_files = {'VERSION','.gitignore','.gitattributes','.dockerignore','README.md','deploy/check-version-contract.py',
                 'deploy/appearance-catalog.json','deploy/appearance-models.json'}
forbidden_extensions = {'.mp4','.mkv','.avi','.mov','.jpg','.jpeg','.png','.webp','.pcap','.onnx','.pt',
                        '.pem','.key','.p12','.pfx','.crt','.sqlite','.sqlite3','.db','.docx','.pdf','.zip','.jar'}
patterns = [re.compile(pattern,re.I) for pattern in (
    r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',
    r'\b(?:gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{30,}|sk-or-v1-[A-Za-z0-9]{30,})',
    r'\bAKIA[A-Z0-9]{16}\b',
    r'(?:mnnu\.eu\.org|cmecloud\.cn)',
    r'[A-Za-z]:[\\/](?:WorkSpace|Users[\\/]zzp)',
)]
files = subprocess.check_output(['git','ls-files','-z'],cwd=root).decode().split('\0')
violations = []
for name in filter(None,files):
    path = Path(name)
    if name not in allowed_files and path.parts[0] not in allowed_roots:
        violations.append((name,'unapproved path'));continue
    if any(part in {'runtime','private','node_modules','build','dist','.gradle','__pycache__','fixtures'} for part in path.parts) or path.suffix.lower() in forbidden_extensions:
        violations.append((name,'private or generated material'));continue
    payload = (root/path).read_bytes()
    if len(payload)>2*1024*1024 or b'\0' in payload:
        violations.append((name,'binary or oversized file'));continue
    content = payload.decode('utf-8-sig')
    # The guard necessarily names forbidden patterns, never their credential values.
    if name != 'ci/public-source-check.py' and any(pattern.search(content) for pattern in patterns):
        violations.append((name,'private marker or credential pattern'))
if violations:
    for name,reason in violations:print(f'{name}: {reason}',file=sys.stderr)
    raise SystemExit(1)
print(f'Public source boundary checked: {len(list(filter(None,files)))} text files.')
