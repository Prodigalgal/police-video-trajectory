"""Independent, bounded pre-filter samples. These never enter analysis queues."""
from contextlib import closing, contextmanager
import hashlib
import json
import math
from pathlib import Path
import shutil
import sqlite3
import time


class AuditUnavailable(OSError):pass


class AuditSchedule:
    def __init__(self, seconds, seed):
        if not math.isfinite(seconds) or not 10<=seconds<=3600 or not isinstance(seed,str) or not seed:
            raise ValueError('invalid audit sampling clock')
        self.seconds=seconds;self.seed=seed;self.epoch=None;self.last=None;self.next=None;self.phase=None

    def observe(self, now, epoch):
        if not math.isfinite(now) or type(epoch) is not int or epoch<0 or self.epoch is not None and epoch<self.epoch:
            raise ValueError('invalid audit media clock')
        if epoch!=self.epoch:
            self.epoch=epoch;self.last=None
            self.phase=int.from_bytes(hashlib.sha256((self.seed+':'+str(epoch)).encode()).digest()[:8],'big')/2**64*self.seconds
            self.next=now+self.phase
        if self.last is not None and now<=self.last:raise ValueError('audit media time did not increase')
        self.last=now
        if now+.001<self.next:return False,0
        skipped=max(0,math.floor((now+.001-self.next)/self.seconds))
        self.next+=(skipped+1)*self.seconds
        return True,skipped


class AuditStore:
    def __init__(self,root,byte_limit=64*1024*1024,entry_limit=4096,retention_seconds=86400,*,clock=time.time):
        root=Path(root).absolute()
        if (root.is_symlink() or root.resolve()!=root or type(byte_limit) is not int or not 1<=byte_limit<=512*1024*1024
                or type(entry_limit) is not int or not 1<=entry_limit<=100000
                or not math.isfinite(retention_seconds) or not 10<=retention_seconds<=30*86400):
            raise ValueError('invalid audit storage configuration')
        root.mkdir(parents=True,exist_ok=True,mode=0o700)
        self.root=root;self.path=root/'prefilter-audit-v1.sqlite3'
        self.byte_limit=byte_limit;self.entry_limit=entry_limit;self.retention=retention_seconds;self.clock=clock
        self.file_limit=2*byte_limit+4*1024*1024
        with self.transaction() as db:
            db.execute('CREATE TABLE IF NOT EXISTS meta(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL,byte_limit INTEGER NOT NULL,entry_limit INTEGER NOT NULL,retention REAL NOT NULL)')
            db.execute('CREATE TABLE IF NOT EXISTS samples(sample_id TEXT PRIMARY KEY,payload_sha256 TEXT NOT NULL,metadata TEXT NOT NULL,payload BLOB NOT NULL,charge INTEGER NOT NULL CHECK(charge>0),created_at REAL NOT NULL,expires_at REAL NOT NULL)')
            db.execute('CREATE INDEX IF NOT EXISTS audit_expiry_idx ON samples(expires_at)')
            expected=(1,byte_limit,entry_limit,retention_seconds)
            prior=db.execute('SELECT version,byte_limit,entry_limit,retention FROM meta WHERE id=1').fetchone()
            if prior is None:db.execute('INSERT INTO meta VALUES(1,?,?,?,?)',expected)
            elif prior!=expected:raise ValueError('audit schema or retention limits differ')

    @contextmanager
    def transaction(self):
        if self.path.is_symlink():raise AuditUnavailable('unsafe audit storage')
        with closing(sqlite3.connect(self.path,timeout=1,isolation_level=None)) as db:
            db.execute('PRAGMA synchronous=FULL');db.execute('PRAGMA busy_timeout=1000')
            if db.execute('PRAGMA journal_mode').fetchone()[0].lower()!='delete':raise AuditUnavailable('unsupported audit journal')
            page=db.execute('PRAGMA page_size').fetchone()[0]
            if db.execute('PRAGMA max_page_count='+str(self.file_limit//page)).fetchone()[0]*page>self.file_limit:
                raise AuditUnavailable('audit file exceeds its physical bound')
            db.execute('BEGIN IMMEDIATE')
            try:
                yield db
                db.execute('COMMIT')
            except BaseException:
                if db.in_transaction:db.execute('ROLLBACK')
                raise

    def put(self,sample_id,payload,metadata):
        if (not isinstance(sample_id,str) or not len(sample_id)==64 or any(c not in '0123456789abcdef' for c in sample_id)
                or not isinstance(payload,bytes) or not payload.startswith(b'\xff\xd8\xff') or len(payload)>256*1024):
            raise ValueError('invalid audit image')
        encoded=json.dumps(metadata,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)
        if len(encoded.encode())>16*1024:raise ValueError('audit metadata too large')
        digest=hashlib.sha256(payload).hexdigest();charge=len(payload)+len(encoded.encode())+512
        now=self.clock()
        with self.transaction() as db:
            prior=db.execute('SELECT payload_sha256,metadata FROM samples WHERE sample_id=?',(sample_id,)).fetchone()
            if prior is not None:
                if prior!=(digest,encoded):raise ValueError('conflicting audit sample')
                return False
            db.execute('DELETE FROM samples WHERE expires_at<=?',(now,))
            count,used=db.execute('SELECT count(*),coalesce(sum(charge),0) FROM samples').fetchone()
            if count>=self.entry_limit or used+charge>self.byte_limit:raise AuditUnavailable('audit capacity reached')
            if shutil.disk_usage(self.root).free<8*1024*1024+charge:raise AuditUnavailable('audit disk headroom low')
            db.execute('INSERT INTO samples VALUES(?,?,?,?,?,?,?)',(sample_id,digest,encoded,payload,charge,now,now+self.retention))
        return True

    def get(self,sample_id):
        with self.transaction() as db:
            row=db.execute('SELECT payload_sha256,metadata,payload FROM samples WHERE sample_id=? AND expires_at>?',(sample_id,self.clock())).fetchone()
            if row is None:return None
            if hashlib.sha256(row[2]).hexdigest()!=row[0]:raise AuditUnavailable('audit payload integrity differs')
            return {'sample_id':sample_id,'payload_sha256':row[0],'metadata':json.loads(row[1]),'payload':row[2]}

    def status(self):
        with self.transaction() as db:
            count,used,expired=db.execute('SELECT count(*),coalesce(sum(charge),0),coalesce(sum(expires_at<=?),0) FROM samples',(self.clock(),)).fetchone()
        return {'entries':count,'charged_bytes':used,'expired_entries':expired,'byte_limit':self.byte_limit,
                'entry_limit':self.entry_limit,'retention_seconds':self.retention,'sqlite_file_limit':self.file_limit}
