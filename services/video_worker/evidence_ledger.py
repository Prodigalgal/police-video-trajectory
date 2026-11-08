"""Experimental local-volume evidence quota ledger.

Only a candidate for a future Worker release: all writers to a directory must
use this implementation. The limit counts committed and reserved JPEG bytes,
not SQLite, temporary files, filesystem overhead or a physical disk quota.
"""
import hashlib
import os
import re
import stat
from contextlib import closing, contextmanager
from pathlib import Path
import sqlite3
import threading
import uuid

from .evidence import EvidenceStore, MAX_IMAGE_BYTES

try:
    import fcntl
except ImportError:  # The production candidate is for local Linux volumes only.
    fcntl = None


class LedgerEvidenceStore(EvidenceStore):
    def __init__(self, root: Path, limit: int):
        if not isinstance(limit, int) or not 0 < limit < 2**63:
            raise ValueError("invalid evidence capacity")
        if fcntl is None:
            raise RuntimeError("evidence ledger requires local POSIX file locks")
        super().__init__(root, limit)
        self._ledger = self.root / ".evidence-ledger-v1.sqlite3"
        self._locks = self.root / ".evidence-locks-v1"
        self._initialization_lock = threading.Lock()
        self._initialized = False

    def _connect(self):
        if self._ledger.is_symlink():
            raise ValueError("invalid evidence ledger location")
        connection = sqlite3.connect(self._ledger, timeout=30, isolation_level=None)
        try:
            connection.execute("PRAGMA busy_timeout=30000")
            connection.execute("PRAGMA synchronous=FULL")
        except BaseException:
            connection.close()
            raise
        return connection

    def _sync_directory(self):
        if hasattr(os, "O_DIRECTORY"):
            directory = os.open(self.root, os.O_RDONLY | os.O_DIRECTORY)
            try:
                os.fsync(directory)
            finally:
                os.close(directory)

    @contextmanager
    def _frame_lock(self, target: Path, *, blocking: bool = True):
        if target.parent != self.root or not re.fullmatch(r"[0-9a-f]{32}\.jpg", target.name):
            raise ValueError("invalid evidence lock target")
        if self._locks.is_symlink():
            raise ValueError("invalid evidence lock directory")
        self._locks.mkdir(mode=0o700, exist_ok=True)
        if not self._locks.is_dir():
            raise ValueError("invalid evidence lock directory")
        # A fixed set of stable shard files avoids deleting a lock inode while
        # another process is waiting for it.
        lock_path = self._locks / (target.name[:2] + ".lock")
        flags = os.O_CREAT | os.O_RDWR | getattr(os, "O_NOFOLLOW", 0)
        fd = os.open(lock_path, flags, 0o600)
        acquired = False
        try:
            if not stat.S_ISREG(os.fstat(fd).st_mode):
                raise ValueError("invalid evidence lock file")
            fcntl.flock(fd, fcntl.LOCK_EX | (0 if blocking else fcntl.LOCK_NB))
            acquired = True
            yield
        finally:
            if acquired:
                fcntl.flock(fd, fcntl.LOCK_UN)
            os.close(fd)

    def _ensure_initialized(self):
        if self._initialized:
            return
        with self._initialization_lock:
            if self._initialized:
                return
            if self._ledger.is_symlink():
                raise ValueError("invalid evidence ledger location")
            try:
                fd = os.open(self._ledger, os.O_CREAT | os.O_EXCL | os.O_WRONLY, 0o600)
            except FileExistsError:
                pass
            else:
                os.close(fd)
            with closing(self._connect()) as connection:
                if connection.execute("PRAGMA journal_mode").fetchone()[0].lower() != "delete":
                    raise ValueError("unsupported evidence ledger journal mode")
                connection.execute("BEGIN IMMEDIATE")
                try:
                    connection.execute("""CREATE TABLE IF NOT EXISTS ledger_meta (
                        id INTEGER PRIMARY KEY CHECK(id=1), version INTEGER NOT NULL,
                        byte_limit INTEGER NOT NULL CHECK(byte_limit>0), bytes_used INTEGER NOT NULL CHECK(bytes_used>=0))""")
                    connection.execute("""CREATE TABLE IF NOT EXISTS ledger_files (
                        name TEXT PRIMARY KEY, byte_size INTEGER NOT NULL CHECK(byte_size>=0), sha256 TEXT,
                        state TEXT NOT NULL CHECK(state IN ('reserved','ready')))""")
                    meta = connection.execute("SELECT version,byte_limit FROM ledger_meta WHERE id=1").fetchone()
                    if meta is None:
                        if connection.execute("SELECT count(*) FROM ledger_files").fetchone()[0]:
                            raise ValueError("incomplete evidence ledger")
                        used = 0
                        # A one-time migration of existing JPEGs, under the same
                        # SQLite write lock as every new reservation.
                        for file in self.root.glob("*.jpg"):
                            if file.is_symlink():
                                raise ValueError("invalid evidence location")
                            if file.is_file():
                                size = file.stat().st_size
                                used += size
                                connection.execute("INSERT INTO ledger_files(name,byte_size,state) VALUES (?,?, 'ready')",
                                                   (file.name, size))
                        connection.execute("INSERT INTO ledger_meta(id,version,byte_limit,bytes_used) VALUES (1,1,?,?)",
                                           (self.limit, used))
                    elif meta != (1, self.limit):
                        raise ValueError("inconsistent evidence ledger capacity or version")
                    connection.execute("COMMIT")
                except BaseException:
                    if connection.in_transaction:
                        connection.execute("ROLLBACK")
                    raise
            self._initialized = True

    def _reserve(self, target: Path, image: bytes, digest: str):
        """Reserve capacity before file I/O; return True for an existing replay."""
        self._ensure_initialized()
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                row = connection.execute("SELECT byte_size,sha256,state FROM ledger_files WHERE name=?", (target.name,)).fetchone()
                if target.is_symlink():
                    raise ValueError("invalid evidence location")
                if row is None:
                    if target.exists():
                        raise ValueError("untracked evidence file")
                    meta = connection.execute("SELECT byte_limit,bytes_used FROM ledger_meta WHERE id=1 AND version=1").fetchone()
                    if meta is None or meta[0] != self.limit:
                        raise ValueError("inconsistent evidence ledger")
                    if meta[1] + len(image) > self.limit:
                        raise OSError("evidence capacity reached")
                    connection.execute("INSERT INTO ledger_files(name,byte_size,sha256,state) VALUES (?,?,?,'reserved')",
                                       (target.name, len(image), digest))
                    connection.execute("UPDATE ledger_meta SET bytes_used=bytes_used+? WHERE id=1", (len(image),))
                    exists = False
                else:
                    size, old_digest, state = row
                    if size != len(image) or (old_digest is not None and old_digest != digest):
                        raise ValueError("conflicting evidence replay")
                    exists = target.exists()
                    if exists:
                        if not target.is_file() or target.read_bytes() != image:
                            raise ValueError("conflicting evidence replay")
                        if state == "reserved":
                            self._sync_directory()
                        connection.execute("UPDATE ledger_files SET sha256=?,state='ready' WHERE name=?", (digest, target.name))
                    elif old_digest is None:
                        # A legacy file disappeared before its original bytes could be verified.
                        raise ValueError("legacy evidence unavailable")
                    elif state == "ready":
                        connection.execute("UPDATE ledger_files SET state='reserved' WHERE name=?", (target.name,))
                connection.execute("COMMIT")
                return exists
            except BaseException:
                if connection.in_transaction:
                    connection.execute("ROLLBACK")
                raise

    def _finalize(self, target: Path, size: int, digest: str):
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                updated = connection.execute("""UPDATE ledger_files SET state='ready'
                    WHERE name=? AND byte_size=? AND sha256=? AND state IN ('reserved','ready')""",
                                             (target.name, size, digest)).rowcount
                if updated != 1:
                    raise ValueError("evidence reservation unavailable")
                connection.execute("COMMIT")
            except BaseException:
                if connection.in_transaction:
                    connection.execute("ROLLBACK")
                raise

    def write(self, frame_id: uuid.UUID, image: bytes) -> str:
        if not image.startswith(b"\xff\xd8\xff") or len(image) > MAX_IMAGE_BYTES:
            raise ValueError("invalid JPEG")
        reference = "evidence:" + frame_id.hex
        target = self.path_for(reference)
        digest = hashlib.sha256(image).hexdigest()
        with self._frame_lock(target):
            if self._reserve(target, image, digest):
                return reference
            temporary = self.root / ("." + frame_id.hex + "." + uuid.uuid4().hex + ".tmp")
            try:
                fd = os.open(temporary, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
                with os.fdopen(fd, "wb") as handle:
                    handle.write(image)
                    handle.flush()
                    os.fsync(handle.fileno())
                try:
                    os.link(temporary, target)
                except FileExistsError:
                    if target.is_symlink() or not target.is_file() or target.read_bytes() != image:
                        raise ValueError("conflicting evidence replay") from None
                # Durably link the data before changing a reserved entry to ready.
                self._sync_directory()
                self._finalize(target, len(image), digest)
            finally:
                temporary.unlink(missing_ok=True)
        return reference

    def recover(self, *, max_items: int = 1000):
        """Reconcile interrupted writers without waiting for active shard locks.

        An unlinked reservation is released only after taking the same lock as
        its writer. A linked file is verified before it is marked ready. The
        caller may invoke this periodically; it never deletes original video.
        """
        if not 1 <= max_items <= 10000:
            raise ValueError("invalid recovery batch size")
        self._ensure_initialized()
        with closing(self._connect()) as connection:
            names = [row[0] for row in connection.execute(
                "SELECT name FROM ledger_files WHERE state='reserved' ORDER BY name LIMIT ?", (max_items,))]
        report = {"released": 0, "completed": 0, "busy": 0, "invalid": 0, "temporary_removed": 0}
        for name in names:
            if not re.fullmatch(r"[0-9a-f]{32}\.jpg", name):
                report["invalid"] += 1
                continue
            target = self.root / name
            try:
                with self._frame_lock(target, blocking=False):
                    with closing(self._connect()) as connection:
                        connection.execute("BEGIN IMMEDIATE")
                        try:
                            row = connection.execute("SELECT byte_size,sha256,state FROM ledger_files WHERE name=?", (name,)).fetchone()
                            if row is None or row[2] != "reserved":
                                connection.execute("COMMIT")
                                continue
                            size, digest, _ = row
                            if target.is_symlink() or not digest or not re.fullmatch(r"[0-9a-f]{64}", digest):
                                report["invalid"] += 1
                            elif target.exists():
                                if (not target.is_file() or target.stat().st_size != size or
                                        hashlib.sha256(target.read_bytes()).hexdigest() != digest):
                                    report["invalid"] += 1
                                else:
                                    self._sync_directory()
                                    connection.execute("UPDATE ledger_files SET state='ready' WHERE name=?", (name,))
                                    report["completed"] += 1
                            else:
                                changed = connection.execute("UPDATE ledger_meta SET bytes_used=bytes_used-? WHERE id=1 AND bytes_used>=?",
                                                             (size, size)).rowcount
                                if changed != 1:
                                    raise ValueError("inconsistent evidence ledger")
                                connection.execute("DELETE FROM ledger_files WHERE name=? AND state='reserved'", (name,))
                                report["released"] += 1
                            connection.execute("COMMIT")
                        except BaseException:
                            if connection.in_transaction:
                                connection.execute("ROLLBACK")
                            raise
            except BlockingIOError:
                report["busy"] += 1

        # Temporary files are never original recordings. Inspect only our exact
        # filename pattern, under the same shard lock held by the writer.
        examined = 0
        for temporary in self.root.glob(".*.tmp"):
            match = re.fullmatch(r"\.([0-9a-f]{32})\.[0-9a-f]{32}\.tmp", temporary.name)
            if not match:
                continue
            examined += 1
            if examined > max_items:
                break
            target = self.root / (match.group(1) + ".jpg")
            try:
                with self._frame_lock(target, blocking=False):
                    try:
                        if stat.S_ISREG(temporary.lstat().st_mode):
                            temporary.unlink()
                            report["temporary_removed"] += 1
                    except FileNotFoundError:
                        pass
            except BlockingIOError:
                report["busy"] += 1
        return report

    def audit_files(self):
        """Reconcile untracked legacy JPEGs after all old writers are stopped.

        Existing entries are never removed here: missing evidence stays charged
        until an explicit retention/recovery procedure resolves it. This is a
        startup operation, not part of the per-frame write path.
        """
        self._ensure_initialized()
        discovered = discovered_bytes = 0
        with closing(self._connect()) as connection:
            connection.execute("BEGIN IMMEDIATE")
            try:
                meta = connection.execute("SELECT byte_limit,bytes_used FROM ledger_meta WHERE id=1 AND version=1").fetchone()
                recorded = connection.execute("SELECT coalesce(sum(byte_size),0) FROM ledger_files").fetchone()[0]
                if meta is None or meta[0] != self.limit or meta[1] != recorded:
                    raise ValueError("inconsistent evidence ledger")
                present = set()
                for file in self.root.glob("*.jpg"):
                    if file.is_symlink():
                        raise ValueError("invalid evidence location")
                    if not file.is_file():
                        continue
                    present.add(file.name)
                    size = file.stat().st_size
                    row = connection.execute("SELECT byte_size FROM ledger_files WHERE name=?", (file.name,)).fetchone()
                    if row is None:
                        connection.execute("INSERT INTO ledger_files(name,byte_size,state) VALUES (?,?,'ready')", (file.name, size))
                        discovered += 1
                        discovered_bytes += size
                    elif row[0] != size:
                        raise ValueError("tracked evidence size changed")
                missing = sum(1 for (name,) in connection.execute("SELECT name FROM ledger_files WHERE state='ready'") if name not in present)
                if discovered_bytes:
                    connection.execute("UPDATE ledger_meta SET bytes_used=bytes_used+? WHERE id=1", (discovered_bytes,))
                connection.execute("COMMIT")
            except BaseException:
                if connection.in_transaction:
                    connection.execute("ROLLBACK")
                raise
        return {"discovered_files": discovered, "discovered_bytes": discovered_bytes, "missing_ready_files": missing}

    def status(self):
        """Operational snapshot; reserved bytes remain charged until replay."""
        self._ensure_initialized()
        with closing(self._connect()) as connection:
            meta = connection.execute("SELECT byte_limit,bytes_used FROM ledger_meta WHERE id=1 AND version=1").fetchone()
            if meta is None or meta[0] != self.limit:
                raise ValueError("inconsistent evidence ledger")
            reserved = connection.execute("SELECT count(*),coalesce(sum(byte_size),0) FROM ledger_files WHERE state='reserved'").fetchone()
        return {"limit_bytes": meta[0], "used_bytes": meta[1], "reserved_files": reserved[0], "reserved_bytes": reserved[1]}
