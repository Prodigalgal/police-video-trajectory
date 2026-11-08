"""Bounded FFmpeg diagnostics: retain counts, never source text or URLs."""
from collections import Counter
import json
import re
import time


class DecoderDiagnostics:
    MAX_LINE_BYTES = 4096

    def __init__(self, camera_id):
        if not re.fullmatch(r'recording-0[1-6]', camera_id):
            raise ValueError('unknown diagnostic camera')
        self.camera_id = camera_id
        self.counts = Counter()
        self.total_bytes = 0
        self.truncated_lines = 0
        self._pending = bytearray()
        self._dropping = False

    def _line(self, line):
        if not line.strip():
            return
        text = line.lower()
        categories = (
            ('rtp_sequence_gap', (b'bad cseq', b'rtp: missed', b'rtp: dropping old packet')),
            ('tls_error', (b'certificate verify failed', b'certificate verification failed', b'hostname mismatch', b'tls handshake')),
            ('authentication_error', (b'401 unauthorized', b'403 forbidden')),
            ('input_timeout', (b'timed out', b'connection timeout')),
            ('empty_output', (b'output file is empty', b'nothing was written into output')),
            ('probe_warning', (b'not enough frames to estimate rate', b'could not find codec parameters')),
            ('decode_error', (b'error while decoding', b'error decoding', b'invalid nal', b'decode_slice_header error',
                              b'corrupt decoded frame', b'no frame!', b'error concealing', b'bytestream')),
            ('decode_warning', (b'co located pocs unavailable', b'reference picture missing', b'mmco:')),
        )
        category = next((name for name, patterns in categories if any(value in text for value in patterns)), 'other_message')
        self.counts[category] += 1

    def feed(self, chunk):
        self.total_bytes += len(chunk)
        # The caller reads at most 4096 bytes at once. Keep a prefix of each
        # line and discard its tail until a newline, bounding even malformed logs.
        for part in chunk.splitlines(keepends=True):
            complete = part.endswith((b'\n', b'\r'))
            if not self._dropping:
                remaining = self.MAX_LINE_BYTES - len(self._pending)
                self._pending.extend(part[:remaining])
                if len(part) > remaining:
                    self._dropping = True
                    self.truncated_lines += 1
            if complete:
                self._line(bytes(self._pending))
                self._pending.clear()
                self._dropping = False

    def finish(self):
        if self._pending:
            self._line(bytes(self._pending))
            self._pending.clear()
        self._dropping = False

    def snapshot(self):
        return {'camera_id': self.camera_id, 'message_counts': dict(sorted(self.counts.items())),
                'stderr_bytes': self.total_bytes, 'truncated_lines': self.truncated_lines}


async def drain_decoder_diagnostics(reader, report):
    last_report = time.monotonic()
    try:
        while chunk := await reader.read(4096):
            report.feed(chunk)
            if time.monotonic() - last_report >= 60:
                print(json.dumps({'event': 'decoder_diagnostics', **report.snapshot()}), flush=True)
                last_report = time.monotonic()
    except (OSError, ValueError):
        report.counts['diagnostic_read_error'] += 1
    finally:
        report.finish()
