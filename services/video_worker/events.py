"""Atomic per-process log records shared by decoder and asyncio threads."""
import threading
import sys

_lock = threading.Lock()


def log(message):
    with _lock:
        sys.stdout.write(str(message) + '\n')
        sys.stdout.flush()
