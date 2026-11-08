from pathlib import Path
import os
import re
import uuid


MAX_IMAGE_BYTES = 8 * 1024 * 1024


class EvidenceStore:
    def __init__(self, root: Path, limit: int):
        self.root = root.resolve()
        self.limit = limit
        self.root.mkdir(parents=True, exist_ok=True, mode=0o700)

    def path_for(self, reference: str) -> Path:
        if not re.fullmatch(r"evidence:[0-9a-f]{32}", reference or ""):
            raise ValueError("invalid evidence reference")
        return self.root / (reference.split(":", 1)[1] + ".jpg")

    def write(self, frame_id: uuid.UUID, image: bytes) -> str:
        if not image.startswith(b"\xff\xd8\xff") or len(image) > MAX_IMAGE_BYTES:
            raise ValueError("invalid JPEG")
        reference = "evidence:" + frame_id.hex
        target = self.path_for(reference)

        def existing_matches():
            if target.is_symlink():
                raise ValueError("invalid evidence location")
            if not target.exists():
                return False
            if target.read_bytes() != image:
                raise ValueError("conflicting evidence replay")
            return True

        # A retry does not consume additional capacity, even when the store is full.
        if existing_matches():
            return reference
        # Avoid deleting or modifying original recordings; only files created in this directory are considered.
        size = sum(p.stat().st_size for p in self.root.glob("*.jpg") if p.is_file())
        if size + len(image) > self.limit:
            if existing_matches():
                return reference
            raise OSError("evidence capacity reached")
        temp = self.root / ("." + frame_id.hex + "." + uuid.uuid4().hex + ".tmp")
        try:
            fd = os.open(temp, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as handle:
                handle.write(image)
                handle.flush()
                os.fsync(handle.fileno())
            # Same-directory hard link does not overwrite an already committed frame.
            try:
                os.link(temp, target)
            except FileExistsError:
                # A concurrent replay may have committed the same immutable frame.
                if not existing_matches():
                    raise
        finally:
            temp.unlink(missing_ok=True)
        return reference

    def read(self, reference: str) -> bytes:
        path = self.path_for(reference)
        if path.is_symlink() or not path.is_file() or path.stat().st_size > MAX_IMAGE_BYTES:
            raise ValueError("evidence unavailable")
        data = path.read_bytes()
        if not data.startswith(b"\xff\xd8\xff"):
            raise ValueError("invalid evidence")
        return data
