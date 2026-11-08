"""Bounded durable retry results. No text-query cache and no inferred facts."""
from contextlib import contextmanager
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import time
import uuid


class CacheUnavailable(Exception):
    pass


class CacheBusy(CacheUnavailable):
    pass


class ResultCache:
    def __init__(self,directory,max_bytes=512*1024*1024,max_entries=100000,ttl_seconds=86400,
                 max_result_bytes=1024*1024):
        self.directory = Path(directory)
        if (not self.directory.is_absolute() or self.directory.is_symlink() or max_entries<1 or max_result_bytes<1024 or max_bytes<max_result_bytes
                or not 600<=ttl_seconds<=7*86400):
            raise ValueError('invalid durable model result cache')
        self.directory.mkdir(parents=True,exist_ok=True,mode=0o700)
        self.path = self.directory/'results.sqlite3'
        if self.path.is_symlink():
            raise ValueError('unsafe result cache database')
        self.max_bytes,self.max_entries,self.ttl_seconds,self.max_result_bytes = max_bytes,max_entries,ttl_seconds,max_result_bytes
        with self.connection() as db:
            db.execute('PRAGMA journal_mode=WAL')
            db.execute('''CREATE TABLE IF NOT EXISTS results(
              cache_key TEXT PRIMARY KEY, owner TEXT NOT NULL, reserved_until REAL NOT NULL,
              expires_at REAL NOT NULL, charge INTEGER NOT NULL CHECK(charge>0),
              payload TEXT, payload_sha256 TEXT, quarantined INTEGER NOT NULL DEFAULT 0)''')
            db.execute('CREATE INDEX IF NOT EXISTS results_expiry ON results(expires_at)')
        os.chmod(self.path,0o600)

    @contextmanager
    def connection(self):
        db = sqlite3.connect(self.path,timeout=1,isolation_level=None)
        db.row_factory = sqlite3.Row
        try:
            db.execute('PRAGMA synchronous=FULL')
            yield db
        finally:
            db.close()

    def claim(self,key,lease_seconds):
        if not re.fullmatch(r'[0-9a-f]{64}',key) or not 0<lease_seconds<=630:
            raise ValueError('invalid cache reservation')
        now = time.time()
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            try:
                # Expiration is explicit retry retention, never silent LRU eviction.
                db.execute('DELETE FROM results WHERE expires_at<=? AND quarantined=0',(now,))
                row = db.execute('SELECT * FROM results WHERE cache_key=?',(key,)).fetchone()
                if row:
                    if row['quarantined']:
                        raise CacheUnavailable('quarantined')
                    if row['payload'] is not None:
                        if hashlib.sha256(row['payload'].encode()).hexdigest()!=row['payload_sha256']:
                            db.execute('UPDATE results SET quarantined=1 WHERE cache_key=?',(key,))
                            db.commit()
                            raise CacheUnavailable('corrupt')
                        try:
                            payload = json.loads(row['payload'])
                        except ValueError:
                            db.execute('UPDATE results SET quarantined=1 WHERE cache_key=?',(key,))
                            db.commit()
                            raise CacheUnavailable('corrupt') from None
                        db.commit()
                        return {'hit':True,'payload':payload}
                    if row['reserved_until']>now:
                        raise CacheBusy('inflight')
                    db.execute('DELETE FROM results WHERE cache_key=?',(key,))
                count,charged = db.execute('SELECT count(*),coalesce(sum(charge),0) FROM results').fetchone()
                if count>=self.max_entries or charged+self.max_result_bytes>self.max_bytes:
                    raise CacheUnavailable('full')
                owner = uuid.uuid4().hex
                db.execute('INSERT INTO results(cache_key,owner,reserved_until,expires_at,charge) VALUES(?,?,?,?,?)',
                           (key,owner,now+lease_seconds,now+self.ttl_seconds,self.max_result_bytes))
                db.commit()
                return {'hit':False,'owner':owner}
            except BaseException:
                if db.in_transaction:
                    db.rollback()
                raise

    def save(self,key,owner,result,metadata):
        payload = json.dumps({'result':result,'metadata':metadata},ensure_ascii=False,sort_keys=True,separators=(',',':'))
        raw = payload.encode()
        if len(raw)>self.max_result_bytes:
            raise CacheUnavailable('oversized')
        digest = hashlib.sha256(raw).hexdigest()
        with self.connection() as db:
            db.execute('BEGIN IMMEDIATE')
            try:
                changed = db.execute('''UPDATE results SET payload=?,payload_sha256=?,charge=?,expires_at=?
                  WHERE cache_key=? AND owner=? AND payload IS NULL AND quarantined=0 AND reserved_until>?''',
                  (payload,digest,len(raw),time.time()+self.ttl_seconds,key,owner,time.time())).rowcount
                if changed!=1:
                    raise CacheUnavailable('reservation_replaced')
                db.commit()
            except BaseException:
                db.rollback()
                raise

    def release(self,key,owner):
        with self.connection() as db:
            db.execute('DELETE FROM results WHERE cache_key=? AND owner=? AND payload IS NULL AND quarantined=0',(key,owner))

    def quarantine(self,key):
        with self.connection() as db:
            db.execute('UPDATE results SET quarantined=1 WHERE cache_key=?',(key,))

    def status(self):
        with self.connection() as db:
            row = db.execute('''SELECT count(*) entries,coalesce(sum(charge),0) charged_bytes,
              coalesce(sum(payload IS NOT NULL),0) ready,coalesce(sum(payload IS NULL),0) reserved,
              coalesce(sum(quarantined),0) quarantined FROM results''').fetchone()
            return {**dict(row),'max_bytes':self.max_bytes,'max_entries':self.max_entries,'ttl_seconds':self.ttl_seconds}
