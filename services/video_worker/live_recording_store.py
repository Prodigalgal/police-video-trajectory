"""Local single-writer recording ledger; referenced evidence is never evicted."""
from contextlib import closing,contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import sqlite3
import time
import uuid

from .media_clips import canonical,digest_file,sync_directory,writer_lock,MediaError

SEGMENT_BYTES=16*1024*1024
MANIFEST_BYTES=256*1024


def namespace_for(worker):return hashlib.sha256(worker.encode()).hexdigest()[:32]


class LiveStore:
    def __init__(self,base,namespace,*,writer=False,limit=512*1024*1024,entries=256,retention=60):
        if not re.fullmatch('[a-f0-9]{32}',namespace):raise MediaError('recording_invalid_namespace')
        requested=Path(base).absolute()
        if requested.is_symlink():raise MediaError('recording_unsafe_path')
        self.base=requested.resolve();self.root=self.base/namespace;self.namespace=namespace
        if self.root.is_symlink():raise MediaError('recording_unsafe_path')
        if writer:self.root.mkdir(parents=True,exist_ok=True,mode=0o700)
        if not self.root.is_dir():raise MediaError('recording_store_unavailable')
        if not 2*SEGMENT_BYTES<=limit<=8*1024*1024*1024 or not 4<=entries<=4096 or not 10<=retention<=3600:raise MediaError('recording_invalid_capacity')
        self.limit,self.entries,self.retention=limit,entries,retention;self.db_path=self.root/'index.sqlite3';self.lock=None
        if self.db_path.is_symlink():raise MediaError('recording_unsafe_path')
        if writer:
            self.lock=writer_lock(self.root);self.lock.__enter__()
            try:
                with closing(self.connect()) as db:db.executescript('''
CREATE TABLE IF NOT EXISTS segments(segment_id TEXT PRIMARY KEY,state TEXT NOT NULL,reserved INTEGER NOT NULL,
  context TEXT NOT NULL,manifest_sha256 TEXT,created REAL NOT NULL);
CREATE TABLE IF NOT EXISTS refs(frame_id TEXT PRIMARY KEY,segment_id TEXT NOT NULL,payload_sha256 TEXT NOT NULL,
  source_pts_us INTEGER NOT NULL,packet_sha256 TEXT NOT NULL,state TEXT NOT NULL DEFAULT 'pending',
  last_attempt REAL NOT NULL DEFAULT 0,error_code TEXT,created REAL NOT NULL);
CREATE INDEX IF NOT EXISTS refs_pending ON refs(state,last_attempt,created);
CREATE INDEX IF NOT EXISTS refs_segment ON refs(segment_id,state);
''')
                self.recover()
            except BaseException:self.close();raise
        elif not self.db_path.is_file():raise MediaError('recording_store_unavailable')

    def connect(self):
        if self.db_path.is_symlink():raise MediaError('recording_unsafe_path')
        db=sqlite3.connect(self.db_path,timeout=3,isolation_level=None);db.execute('PRAGMA busy_timeout=3000');db.execute('PRAGMA synchronous=FULL');return db

    @contextmanager
    def transaction(self):
        with closing(self.connect()) as db:
            db.execute('BEGIN IMMEDIATE')
            try:yield db;db.commit()
            except BaseException:db.rollback();raise

    def close(self):
        if self.lock is not None:self.lock.__exit__(None,None,None);self.lock=None

    def path(self,identity,staging=False):
        key=uuid.UUID(identity).hex
        return self.root/(('.building-' if staging else '')+key)

    def verify_owned(self,path,identity):
        if path.is_symlink() or path.resolve().parent!=self.root or path.name not in {uuid.UUID(identity).hex,'.building-'+uuid.UUID(identity).hex}:raise MediaError('recording_unsafe_path')
        marker=path/'owner.json'
        if marker.is_symlink() or not marker.is_file() or marker.stat().st_size>200 or json.loads(marker.read_bytes())!={'schema':'live-segment-owner-v1','segment_id':identity}:raise MediaError('recording_unknown_files')
        for child in path.iterdir():
            if child.is_symlink() or not child.is_file() or child.name not in {'owner.json','source.mp4','manifest.json'}:raise MediaError('recording_unknown_files')

    def remove_owned(self,path,identity):
        self.verify_owned(path,identity)
        shutil.rmtree(path)

    def recover(self):
        # A crash between marking and removing a segment must not leak a slot.
        with closing(self.connect()) as db:deleting=db.execute("SELECT segment_id FROM segments WHERE state='deleting'").fetchall()
        for (identity,) in deleting:
            for path in (self.path(identity),self.path(identity,True)):
                if path.exists():self.remove_owned(path,identity)
            with self.transaction() as db:db.execute('DELETE FROM refs WHERE segment_id=?',(identity,));db.execute('DELETE FROM segments WHERE segment_id=?',(identity,))
        with closing(self.connect()) as db:rows=db.execute("SELECT segment_id FROM segments WHERE state='writing'").fetchall()
        for (identity,) in rows:
            final=self.path(identity)
            if final.is_dir() and not final.is_symlink():
                try:
                    self.verify_owned(final,identity)
                    manifest_path=final/'manifest.json'
                    if manifest_path.is_symlink() or manifest_path.stat().st_size>MANIFEST_BYTES:raise MediaError('recording_recovery_invalid')
                    raw=manifest_path.read_bytes();value=json.loads(raw);actual=digest_file(final/'source.mp4',SEGMENT_BYTES)
                    if value.get('segment_id')!=identity or actual!=value.get('video'):raise MediaError('recording_recovery_invalid')
                    size=sum(p.stat().st_size for p in final.iterdir());sha=hashlib.sha256(canonical(value)).hexdigest()
                    if size>SEGMENT_BYTES:raise MediaError('recording_recovery_invalid')
                    with self.transaction() as db:db.execute("UPDATE segments SET state='ready',reserved=?,manifest_sha256=? WHERE segment_id=?",(size,sha,identity))
                    continue
                except (OSError,ValueError,TypeError,KeyError):
                    # Unknown committed files remain charged, never discarded.
                    with self.transaction() as db:db.execute("UPDATE segments SET state='failed' WHERE segment_id=?",(identity,));db.execute("UPDATE refs SET state='failed',error_code='recording_recovery_invalid' WHERE segment_id=?",(identity,))
                    continue
            staging=self.path(identity,True)
            if staging.exists():self.remove_owned(staging,identity)
            with self.transaction() as db:db.execute("UPDATE segments SET state='failed',reserved=0 WHERE segment_id=?",(identity,));db.execute("UPDATE refs SET state='failed',error_code='recording_interrupted' WHERE segment_id=?",(identity,))

    def reserve(self,identity,context):
        self.prune()
        with self.transaction() as db:
            used,count=db.execute('SELECT COALESCE(sum(reserved),0),count(*) FROM segments').fetchone()
            if used+SEGMENT_BYTES>self.limit or count>=self.entries:raise MediaError('recording_capacity_reached')
            db.execute("INSERT INTO segments(segment_id,state,reserved,context,created) VALUES(?,'writing',?,?,?)",(identity,SEGMENT_BYTES,canonical(context).decode(),time.time()))
        path=self.path(identity,True);path.mkdir(mode=0o700)
        with (path/'owner.json').open('xb') as f:f.write(canonical({'schema':'live-segment-owner-v1','segment_id':identity}));f.flush();os.fsync(f.fileno())
        sync_directory(path);return path

    def commit(self,identity,manifest):
        staging=self.path(identity,True);final=self.path(identity);raw=canonical(manifest)
        if len(raw)>MANIFEST_BYTES:raise MediaError('recording_manifest_too_large')
        with (staging/'manifest.json').open('xb') as f:f.write(raw);f.flush();os.fsync(f.fileno())
        with (staging/'source.mp4').open('r+b') as f:os.fsync(f.fileno())
        size=sum(p.stat().st_size for p in staging.iterdir())
        if size>SEGMENT_BYTES:raise MediaError('recording_segment_too_large')
        sync_directory(staging)
        if final.exists():raise MediaError('recording_conflicting_commit')
        staging.rename(final);sync_directory(self.root);digest=hashlib.sha256(raw).hexdigest()
        with self.transaction() as db:db.execute("UPDATE segments SET state='ready',reserved=?,manifest_sha256=? WHERE segment_id=? AND state='writing'",(size,digest,identity))

    def abort(self,identity,code):
        staging=self.path(identity,True)
        if staging.exists():self.remove_owned(staging,identity)
        committed=self.path(identity).exists()
        with self.transaction() as db:
            # A rename may have succeeded before the ledger commit failed.
            # Keep that final asset charged until owned cleanup can verify it.
            db.execute("UPDATE segments SET state='failed',reserved=CASE WHEN ? THEN reserved ELSE 0 END WHERE segment_id=? AND state='writing'",(committed,identity))
            db.execute("UPDATE refs SET state='failed',error_code=? WHERE segment_id=? AND state='pending'",(code,identity))

    def pin(self,frame_id,payload_sha,descriptor):
        identity=descriptor['segment_id'];uuid.UUID(frame_id)
        uuid.UUID(identity)
        if descriptor['namespace']!=self.namespace or not re.fullmatch('[a-f0-9]{64}',payload_sha) or type(descriptor.get('source_pts_us')) is not int or not -(2**63)<=descriptor['source_pts_us']<2**63 or not re.fullmatch('[a-f0-9]{64}',descriptor.get('packet_sha256','')):raise MediaError('recording_invalid_reference')
        fields=(identity,payload_sha,descriptor['source_pts_us'],descriptor['packet_sha256'])
        with self.transaction() as db:
            segment=db.execute('SELECT state FROM segments WHERE segment_id=?',(identity,)).fetchone()
            # The decoder can select a JPEG before the writer reserves its GOP.
            # Keep this bounded reference first; the publisher joins ready assets.
            if segment and segment[0] not in {'writing','ready','registered'}:return False
            previous=db.execute('SELECT segment_id,payload_sha256,source_pts_us,packet_sha256 FROM refs WHERE frame_id=?',(frame_id,)).fetchone()
            if previous:
                if previous!=fields:raise MediaError('recording_reference_conflict')
                return True
            if db.execute('SELECT count(*) FROM refs').fetchone()[0]>=4096:raise MediaError('recording_reference_capacity')
            db.execute('INSERT INTO refs(frame_id,segment_id,payload_sha256,source_pts_us,packet_sha256,created) VALUES(?,?,?,?,?,?)',(frame_id,*fields,time.time()));return True

    def pending(self):
        with closing(self.connect()) as db:
            return db.execute("SELECT r.frame_id,r.segment_id,r.payload_sha256,r.source_pts_us,r.packet_sha256,s.manifest_sha256 FROM refs r JOIN segments s USING(segment_id) WHERE r.state='pending' AND s.state IN ('ready','registered') AND r.last_attempt<? ORDER BY r.created LIMIT 10",(time.time()-5,)).fetchall()

    def attempted(self,frame_id,code=None,done=False,failed=False):
        with self.transaction() as db:
            changed=db.execute("UPDATE refs SET last_attempt=?,error_code=?,state=? WHERE frame_id=? AND state='pending'",(time.time(),code,'published' if done else 'failed' if failed else 'pending',frame_id)).rowcount
            if done and changed:db.execute("UPDATE segments SET state='registered' WHERE segment_id=(SELECT segment_id FROM refs WHERE frame_id=?)",(frame_id,))

    def prune(self):
        with self.transaction() as db:
            db.execute("DELETE FROM refs WHERE state='pending' AND created<? AND NOT EXISTS(SELECT 1 FROM segments s WHERE s.segment_id=refs.segment_id)",(time.time()-600,))
        with closing(self.connect()) as db:rows=db.execute("SELECT segment_id FROM segments s WHERE state IN ('ready','failed') AND created<? AND NOT EXISTS(SELECT 1 FROM refs r WHERE r.segment_id=s.segment_id AND r.state!='failed') ORDER BY created LIMIT 20",(time.time()-self.retention,)).fetchall()
        for (identity,) in rows:
            with self.transaction() as db:
                changed=db.execute("UPDATE segments SET state='deleting' WHERE segment_id=? AND state IN ('ready','failed') AND NOT EXISTS(SELECT 1 FROM refs WHERE segment_id=? AND state!='failed')",(identity,identity)).rowcount
            if not changed:continue
            try:
                for path in (self.path(identity),self.path(identity,True)):
                    if path.exists():self.remove_owned(path,identity)
            except BaseException:
                with self.transaction() as db:db.execute("UPDATE segments SET state='failed' WHERE segment_id=?",(identity,))
                raise
            with self.transaction() as db:db.execute('DELETE FROM refs WHERE segment_id=?',(identity,));db.execute('DELETE FROM segments WHERE segment_id=?',(identity,))
