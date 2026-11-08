"""Bounded durable selected-frame outbox on a local POSIX volume.

JPEG, immutable capture metadata and later receipts are committed atomically.
Registered receipts survive source-lease replacement; unregistered old leases
remain retained for review. Logical payload limits are not disk quotas.
"""
from contextlib import closing, contextmanager
import hashlib
import json
from pathlib import Path
import shutil
import sqlite3
import threading
import uuid


class PayloadSpool:
    def __init__(self, root: Path, byte_limit=512 * 1024 * 1024, entry_limit=2000,*,context_enabled=False,upgrade_legacy=False):
        if type(context_enabled) is not bool or type(upgrade_legacy) is not bool:raise ValueError('invalid spool context mode')
        if root.is_symlink() or not 1 <= byte_limit < 2**63 or not 1 <= entry_limit <= 10000:
            raise ValueError('invalid spool configuration')
        root.mkdir(mode=0o700, parents=True, exist_ok=True)
        self.path, self.root = root / 'selected-frames-v1.sqlite3', root
        if self.path.is_symlink():
            raise ValueError('invalid spool path')
        self.byte_limit, self.entry_limit = byte_limit, entry_limit
        self.context_enabled=context_enabled
        # A local wakeup is only a latency hint. SQLite remains the durable
        # source of truth, including after a restart or another process writes.
        self._pending_changed = threading.Event()
        self._preparation_changed = threading.Event()
        self._delivery_changed = threading.Event()
        with self.transaction() as db:
            db.execute('CREATE TABLE IF NOT EXISTS meta(id INTEGER PRIMARY KEY CHECK(id=1),version INTEGER NOT NULL,byte_limit INTEGER NOT NULL,entry_limit INTEGER NOT NULL,used INTEGER NOT NULL CHECK(used>=0),entries INTEGER NOT NULL CHECK(entries>=0))')
            db.execute('CREATE TABLE IF NOT EXISTS frames(frame_id TEXT PRIMARY KEY,task_id TEXT NOT NULL,worker TEXT NOT NULL,generation INTEGER NOT NULL,sha256 TEXT NOT NULL,size INTEGER NOT NULL,metadata TEXT NOT NULL,payload BLOB NOT NULL,plan TEXT,receipt TEXT,last_attempt INTEGER NOT NULL DEFAULT 0,created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP)')
            current = db.execute('SELECT version,byte_limit,entry_limit FROM meta WHERE id=1').fetchone()
            if current is None:
                db.execute('INSERT INTO meta VALUES(1,1,?,?,0,0)', (byte_limit, entry_limit))
            elif current[1:] != (byte_limit, entry_limit):
                raise ValueError('spool schema or limits differ')
            version=current[0] if current else 1
            if context_enabled and version==1:
                if current is not None and not upgrade_legacy:
                    raise ValueError('legacy spool requires an explicit offline context-schema upgrade')
                for declaration in ['context_payload BLOB','context_sha256 TEXT','context_size INTEGER NOT NULL DEFAULT 0','context_issue TEXT']:
                    db.execute('ALTER TABLE frames ADD COLUMN '+declaration)
                db.execute('UPDATE meta SET version=2 WHERE id=1');version=2
            if version!=(2 if context_enabled else 1):raise ValueError('spool context schema mode differs')
        self.schema_version=2 if context_enabled else 1

    def connect(self):
        if self.path.is_symlink():
            raise ValueError('invalid spool path')
        db = sqlite3.connect(self.path, timeout=5, isolation_level=None)
        db.execute('PRAGMA synchronous=FULL')
        db.execute('PRAGMA busy_timeout=5000')
        if hasattr(self,'schema_version') and db.execute('SELECT version FROM meta WHERE id=1').fetchone()!=(self.schema_version,):
            db.close();raise ValueError('spool schema changed; reopen with the matching context mode')
        if db.execute('PRAGMA journal_mode').fetchone()[0].lower() != 'delete':
            db.close()
            raise ValueError('unsupported spool journal')
        return db

    @contextmanager
    def transaction(self):
        with closing(self.connect()) as db:
            db.execute('BEGIN IMMEDIATE')
            try:
                yield db
                db.execute('COMMIT')
            except BaseException:
                if db.in_transaction:
                    db.execute('ROLLBACK')
                raise

    def put(self, frame_id, payload, metadata,*,motion_context=None):
        if str(uuid.UUID(frame_id)) != frame_id or str(uuid.UUID(metadata['task_id'])) != metadata['task_id']:
            raise ValueError('invalid frame identity')
        if not isinstance(payload, bytes) or not payload.startswith(b'\xff\xd8\xff') or not payload.endswith(b'\xff\xd9') or len(payload) > 8 * 1024 * 1024:
            raise ValueError('invalid JPEG')
        digest = hashlib.sha256(payload).hexdigest()
        context_digest=None;context_issue='not_captured' if self.context_enabled else None;context=None
        if self.context_enabled and metadata.get('motion_context_capture_issue') in {'not_encoded','binding_rejected'}:
            context_issue=metadata['motion_context_capture_issue']
        if motion_context is not None:
            if not self.context_enabled:raise ValueError('motion context needs the v2 spool')
            from .motion_context import MAX_CONTEXT_BYTES,validate_binding
            if isinstance(motion_context,bytes) and len(motion_context)<=MAX_CONTEXT_BYTES:
                context_digest=hashlib.sha256(motion_context).hexdigest()
                try:
                    validate_binding(motion_context,frame_id,digest,metadata)
                    context=motion_context;context_issue=None
                except (ValueError,TypeError,KeyError,OverflowError):context_issue='invalid_binding'
            else:context_issue='invalid_context_envelope'
        encoded = json.dumps(metadata, sort_keys=True, separators=(',', ':'), allow_nan=False)
        if len(encoded) > 16384:
            raise ValueError('capture metadata too large')
        with self.transaction() as db:
            old = db.execute('SELECT sha256,metadata FROM frames WHERE frame_id=?', (frame_id,)).fetchone()
            if old is not None:
                if old != (digest, encoded):
                    raise ValueError('conflicting capture replay')
                if self.context_enabled and motion_context is not None:
                    expected=db.execute('SELECT context_sha256 FROM frames WHERE frame_id=?',(frame_id,)).fetchone()[0]
                    if expected!=context_digest:raise ValueError('conflicting motion context replay')
            else:
                used, entries = db.execute('SELECT used,entries FROM meta WHERE id=1').fetchone()
                if used + len(payload) > self.byte_limit or entries >= self.entry_limit or shutil.disk_usage(self.root).free < 64 * 1024 * 1024 + len(payload) * 3:
                    raise OSError('selected-frame spool capacity reached')
                total=len(payload)+(len(context) if context is not None else 0)
                if context is not None and (used+total>self.byte_limit or shutil.disk_usage(self.root).free<64*1024*1024+total*3):
                    context=None;context_issue='context_capacity_unavailable';total=len(payload)
                db.execute('INSERT INTO frames(frame_id,task_id,worker,generation,sha256,size,metadata,payload) VALUES(?,?,?,?,?,?,?,?)',
                           (frame_id, metadata['task_id'], metadata['worker_id'], metadata['generation'], digest, len(payload), encoded, payload))
                if self.context_enabled:
                    db.execute('UPDATE frames SET context_payload=?,context_sha256=?,context_size=?,context_issue=? WHERE frame_id=?',
                        (context,context_digest,len(context) if context is not None else 0,context_issue,frame_id))
                db.execute('UPDATE meta SET used=used+?,entries=entries+1 WHERE id=1', (total,))
        # Signal only after the transaction committed, never before its JPEG
        # and immutable metadata are readable by the dispatch thread.
        self._pending_changed.set()
        self._preparation_changed.set()

    def _notification(self, stage):
        if stage is None:
            return self._pending_changed
        if stage == 'prepare':
            return self._preparation_changed
        if stage == 'deliver':
            return self._delivery_changed
        raise ValueError('unknown spool stage')

    def clear_pending_notification(self, stage=None):
        self._notification(stage).clear()

    def wait_for_pending_notification(self, timeout, stage=None):
        return self._notification(stage).wait(timeout)

    def pending(self, limit=24, *, retry_before=None, stage=None):
        if type(limit) is not int or not 1 <= limit <= 10000:
            raise ValueError('invalid pending batch limit')
        if retry_before is not None and (type(retry_before) is not int or retry_before < 0):
            raise ValueError('invalid retry cutoff')
        if stage not in {None, 'prepare', 'deliver'}:
            raise ValueError('unknown spool stage')
        with closing(self.connect()) as db:
            # Register fresh records before retrying a full analysis queue.
            # The insertion order breaks same-second ties; random UUID order
            # could reverse a camera's adjacent captures and break association.
            conditions = [] if retry_before is None else ['last_attempt <= ?']
            if stage == 'prepare':
                conditions.append('plan IS NULL AND receipt IS NULL')
            elif stage == 'deliver':
                conditions.append('plan IS NOT NULL')
            condition = ('WHERE '+' AND '.join(conditions)+' ') if conditions else ''
            params = (limit,) if retry_before is None else (retry_before, limit)
            return [row[0] for row in db.execute('SELECT frame_id FROM frames ' + condition +
                'ORDER BY last_attempt,receipt IS NOT NULL,rowid LIMIT ?', params)]

    def attempted(self, frame_id):
        with self.transaction() as db:
            db.execute("UPDATE frames SET last_attempt=cast(strftime('%s','now') AS INTEGER) WHERE frame_id=?", (frame_id,))

    def unregistered(self, task_id, generation):
        with closing(self.connect()) as db:
            return db.execute('SELECT count(*) FROM frames WHERE task_id=? AND generation=? AND receipt IS NULL', (task_id, generation)).fetchone()[0]

    def managed_pending(self,task_id,after=None):
        with closing(self.connect()) as db:
            return [row[0] for row in db.execute('SELECT frame_id FROM frames WHERE task_id=? AND receipt IS NULL AND (? IS NULL OR frame_id>?) ORDER BY frame_id LIMIT 512',(task_id,after,after))]

    def resume_managed(self,task_id,input_sha256,worker,generation,captures,frame_ids=None):
        """Reconcile lost receipts first; only then rebind unregistered bytes of the same frozen input."""
        uuid.UUID(task_id)
        known={value['frame_id']:value for value in captures}
        if len(known)!=len(captures) or len(known)>512 or type(generation) is not int or generation<1:raise ValueError('invalid managed resume')
        if frame_ids is not None and (not frame_ids or len(frame_ids)>512 or len(set(frame_ids))!=len(frame_ids) or set(known)-set(frame_ids)):raise ValueError('invalid managed receipt window')
        recovered=adopted=0
        with self.transaction() as db:
            clause='' if frame_ids is None else ' AND frame_id IN ('+','.join('?' for _ in frame_ids)+')'
            args=(task_id,*(frame_ids or []))
            for frame,digest,metadata in db.execute('SELECT frame_id,sha256,metadata FROM frames WHERE task_id=? AND receipt IS NULL'+clause,args).fetchall():
                value=json.loads(metadata)
                if value.get('source_input_sha256')!=input_sha256:raise ValueError('managed spool source changed')
                if frame in known:
                    capture=known[frame]
                    if capture['sha256']!=digest or capture['offset_ms']!=value['media_offset_ms']:raise ValueError('managed capture differs')
                    uuid.UUID(capture['receipt'])
                    receipt={'frameId':frame,'payloadSha256':digest,'receipt':capture['receipt']}
                    db.execute('UPDATE frames SET receipt=?,last_attempt=0 WHERE frame_id=?',(json.dumps(receipt,sort_keys=True,separators=(',',':')),frame));recovered+=1
                else:
                    value.update(worker_id=worker,generation=generation)
                    db.execute('UPDATE frames SET worker=?,generation=?,metadata=?,last_attempt=0 WHERE frame_id=?',(worker,generation,json.dumps(value,sort_keys=True,separators=(',',':'),allow_nan=False),frame));adopted+=1
        self._notification('deliver').set();return {'recovered':recovered,'adopted':adopted}

    def get(self, frame_id):
        with closing(self.connect()) as db:
            db.execute('BEGIN')
            row = db.execute('SELECT sha256,size,metadata,payload,plan,receipt FROM frames WHERE frame_id=?', (frame_id,)).fetchone()
            context_row=db.execute('SELECT context_payload,context_sha256,context_size,context_issue FROM frames WHERE frame_id=?',(frame_id,)).fetchone() if self.context_enabled and row is not None else None
        if row is None:
            return None
        digest, size, metadata, payload, plan, receipt = row
        if len(payload) != size or hashlib.sha256(payload).hexdigest() != digest:
            raise ValueError('spool JPEG integrity failure')
        result={'frame_id': frame_id, 'sha256': digest, 'metadata': json.loads(metadata), 'payload': payload,
                'plan': json.loads(plan) if plan else None, 'receipt': json.loads(receipt) if receipt else None}
        if self.context_enabled:
            context,expected,size,issue=context_row
            if context is not None:
                from .motion_context import validate_binding
                try:
                    if len(context)!=size or hashlib.sha256(context).hexdigest()!=expected:raise ValueError('integrity')
                    validate_binding(context,frame_id,digest,result['metadata'])
                except (ValueError,TypeError,KeyError,OverflowError):context=None;issue='context_integrity_failed'
            result.update(motion_context=context,motion_context_sha256=expected,
                          motion_context_issue=issue or ('not_captured' if context is None else None))
        return result

    def attach(self, frame_id, digest, field, value):
        if field not in {'plan', 'receipt'}:
            raise ValueError('unknown spool stage')
        encoded = json.dumps(value, sort_keys=True, separators=(',', ':'), allow_nan=False)
        if len(encoded) > 256000:
            raise ValueError('spool stage too large')
        prepared_now = False
        with self.transaction() as db:
            row = db.execute(f'SELECT sha256,{field} FROM frames WHERE frame_id=?', (frame_id,)).fetchone()
            if row is None or row[0] != digest or row[1] not in (None, encoded):
                raise ValueError('spool stage conflict')
            if row[1] is None:
                if field == 'plan':
                    # Successful preparation starts a separate delivery stage;
                    # a previous preparation retry must not delay registration.
                    db.execute('UPDATE frames SET plan=?,last_attempt=0 WHERE frame_id=?', (encoded, frame_id))
                    prepared_now = True
                else:
                    db.execute('UPDATE frames SET receipt=? WHERE frame_id=?', (encoded, frame_id))
        if prepared_now:
            self._pending_changed.set()
            self._delivery_changed.set()

    def acknowledge(self, frame_id, digest):
        with self.transaction() as db:
            row = db.execute('SELECT sha256,size,receipt FROM frames WHERE frame_id=?', (frame_id,)).fetchone()
            if row is None:
                return
            if row[0] != digest or row[2] is None:
                raise ValueError('unregistered or conflicting acknowledgement')
            context_size=db.execute('SELECT context_size FROM frames WHERE frame_id=?',(frame_id,)).fetchone()[0] if self.context_enabled else 0
            db.execute('DELETE FROM frames WHERE frame_id=?', (frame_id,))
            db.execute('UPDATE meta SET used=used-?,entries=entries-1 WHERE id=1', (row[1]+context_size,))

    def status(self):
        with closing(self.connect()) as db:
            # Preparation, delivery and capture commit independently. Read all
            # accounting from one snapshot rather than report a false mismatch
            # when a writer commits between the count queries.
            db.execute('BEGIN')
            used, entries = db.execute('SELECT used,entries FROM meta WHERE id=1').fetchone()
            charge='size+context_size' if self.context_enabled else 'size'
            actual = db.execute('SELECT coalesce(sum('+charge+'),0),count(*) FROM frames').fetchone()
            if actual != (used, entries):
                raise ValueError('spool accounting mismatch')
            stages = db.execute('''SELECT
                count(*) FILTER (WHERE plan IS NULL AND receipt IS NULL),
                count(*) FILTER (WHERE plan IS NOT NULL AND receipt IS NULL),
                count(*) FILTER (WHERE plan IS NOT NULL AND receipt IS NOT NULL),
                count(*) FILTER (WHERE plan IS NULL AND receipt IS NOT NULL) FROM frames''').fetchone()
            result={'payload_bytes': used, 'entries': entries, 'registered': db.execute('SELECT count(*) FROM frames WHERE receipt IS NOT NULL').fetchone()[0],
                    'unprepared': stages[0], 'prepared_waiting_registration': stages[1],
                    'registered_waiting_enqueue': stages[2], 'inconsistent_stage_records': stages[3],
                    'database_bytes': db.execute('PRAGMA page_count').fetchone()[0] * db.execute('PRAGMA page_size').fetchone()[0],
                    'payload_limit_bytes': self.byte_limit, 'entry_limit': self.entry_limit}
            if self.context_enabled:
                context_bytes,context_entries,missing=db.execute('SELECT coalesce(sum(context_size),0),count(*) FILTER(WHERE context_payload IS NOT NULL),count(*) FILTER(WHERE context_payload IS NULL) FROM frames').fetchone()
                result.update(schema_version=2,context_bytes=context_bytes,context_entries=context_entries,context_unavailable=missing,
                              jpeg_bytes=used-context_bytes,context_issues=dict(db.execute("SELECT coalesce(context_issue,'captured'),count(*) FROM frames GROUP BY context_issue").fetchall()))
            return result
