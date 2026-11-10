"""Read an approved credential reference, including Kubernetes projected files."""
from pathlib import Path
import re


def read_credential(directory,filename):
    if not isinstance(filename,str) or not re.fullmatch(r'[a-z0-9][a-z0-9_.-]{0,79}',filename) or '..' in filename:
        raise ValueError('invalid credential reference')
    root=Path(directory).resolve(strict=True);path=(root/filename).resolve(strict=True)
    if not path.is_relative_to(root) or not path.is_file():raise ValueError('invalid credential path')
    with path.open('rb') as source:raw=source.read(4097)
    key=raw.decode().strip()
    if not key or len(raw)>4096 or any(ord(char)<32 or ord(char)>126 for char in key):raise ValueError('invalid credential')
    return key
