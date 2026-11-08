"""Immutable bounded storage with lease-authorized reclamation of obsolete attempts."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import re
import stat
import uuid

from .media_clips import writer_lock, sync_directory, digest_file, MediaError

MAX_ARTIFACT=516*1024**2


class ExportError(ValueError):
    def __init__(self,code):self.code=code;super().__init__(code)


class ExportStore:
    def __init__(self,root,limit,entries=128):
        requested=Path(root).absolute()
        if requested.is_symlink() or type(limit) is not int or not MAX_ARTIFACT<=limit<=16*1024**3 or type(entries) is not int or not 1<=entries<=4096:raise ExportError('export_storage_full')
        requested.mkdir(mode=0o700,parents=True,exist_ok=True);self.root=requested.resolve();self.limit=limit;self.entries=entries

    @contextmanager
    def exclusive(self):
        try:
            with writer_lock(self.root):yield
        except MediaError as error:raise ExportError('export_storage_busy' if str(error)=='media_store_busy' else 'export_artifact_changed') from None

    def attempt(self,job,worker):
        identity=str(uuid.UUID(job['export_id']));generation=job['generation']
        if type(generation) is not int or generation<1 or not re.fullmatch('[a-f0-9]{64}',job['manifest_sha256']) or not re.fullmatch('[A-Za-z0-9_.-]{1,100}',worker):raise ExportError('export_worker_error')
        owner=self.root/identity.replace('-','');attempt=owner/str(generation)
        if owner.is_symlink() or attempt.is_symlink() or os.path.ismount(owner) or os.path.ismount(attempt):raise ExportError('export_artifact_changed')
        marker={'schema':'evidence-export-attempt-v1','export_id':identity,'generation':generation,'worker_id':worker,'manifest_sha256':job['manifest_sha256']}
        return owner,attempt,marker

    @staticmethod
    def verify_marker(path,expected):
        if path.is_symlink() or not path.is_file() or path.stat().st_size>1024 or json.loads(path.read_bytes())!=expected:raise ExportError('export_artifact_changed')

    @contextmanager
    def building(self,job,worker,reservation):
        # The caller holds exclusive() and has renewed the Core lease before creating any files.
        if type(reservation) is not int or not 0<reservation<=MAX_ARTIFACT:raise ExportError('export_storage_full')
        owner,attempt,marker=self.attempt(job,worker)
        owner.mkdir(mode=0o700,exist_ok=True);attempt.mkdir(mode=0o700,exist_ok=True)
        marker_path=attempt/'owner.json'
        if marker_path.exists():self.verify_marker(marker_path,marker)
        else:
            with marker_path.open('x',encoding='utf-8') as stream:json.dump(marker,stream);stream.flush();os.fsync(stream.fileno())
            sync_directory(attempt);sync_directory(owner);sync_directory(self.root)
        final=attempt/'package.zip';partial=attempt/'package.part'
        if final.is_symlink() or partial.is_symlink():raise ExportError('export_artifact_changed')
        used=files=0
        for path in self.root.rglob('*'):
            if path.is_symlink():raise ExportError('export_artifact_changed')
            if path.is_file():
                used+=path.stat().st_size
                if path.name in {'package.zip','package.part'}:files+=1
            if used>self.limit or files>self.entries:raise ExportError('export_storage_full')
        if not final.exists() and (used+reservation>self.limit or files>=self.entries):raise ExportError('export_storage_full')
        try:yield partial,final
        finally:
            if partial.exists():
                if partial.is_symlink() or partial.resolve().parent!=attempt.resolve():raise ExportError('export_artifact_changed')
                partial.unlink();sync_directory(attempt)

    def reclaim(self,record):
        # Only the exact attempt authorized by Core is removed; never recurse through an arbitrary path.
        owner,attempt,marker=self.attempt(record,record['worker_id'])
        if not attempt.exists():return
        children=list(attempt.iterdir())
        if not children:attempt.rmdir()
        else:
            self.verify_marker(attempt/'owner.json',marker)
            for path in children:
                if path.name not in {'owner.json','package.zip','package.part'} or path.is_symlink() or not stat.S_ISREG(path.lstat().st_mode):raise ExportError('export_artifact_changed')
            for path in children:
                if path.name!='owner.json':path.unlink()
            (attempt/'owner.json').unlink();attempt.rmdir()
        sync_directory(owner)
        if not any(owner.iterdir()):owner.rmdir();sync_directory(self.root)

    @staticmethod
    def publish(partial,final):
        if final.exists():raise ExportError('export_artifact_changed')
        # Hard-link publication fails instead of replacing a previously committed artifact.
        os.link(partial,final,follow_symlinks=False);os.chmod(final,0o440);partial.unlink();sync_directory(final.parent)

    @staticmethod
    def describe(path,deadline):
        try:return digest_file(path,MAX_ARTIFACT,deadline)
        except MediaError as error:raise ExportError('export_timeout' if str(error)=='media_timeout' else 'export_artifact_changed') from None
