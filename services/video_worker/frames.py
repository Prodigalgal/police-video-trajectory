import asyncio
import math
import json
from pathlib import Path
import re
from urllib.parse import quote

from .config import Settings
from .diagnostics import DecoderDiagnostics, drain_decoder_diagnostics
from .evidence import MAX_IMAGE_BYTES


def simulator_url(settings: Settings, reference: str) -> str:
    match = re.fullmatch(r"simulator:(recording-0[1-6])", reference or "")
    if not match or not re.fullmatch(r"[A-Za-z0-9.-]+", settings.stream_host) or not 1 <= settings.stream_port <= 65535:
        raise ValueError("unknown simulator source")
    if not settings.stream_username or not settings.stream_password or not Path(settings.stream_ca).is_file():
        raise ValueError("stream credentials incomplete")
    return ("rtsps://" + quote(settings.stream_username, safe="") + ":" + quote(settings.stream_password, safe="") +
            "@" + settings.stream_host + ":" + str(settings.stream_port) + "/" + match.group(1))


def archive_path(settings: Settings, reference: str) -> Path:
    match = re.fullmatch(r"archive:(recording-0[1-6])", reference or "")
    if not match:
        raise ValueError("unknown archive source")
    root = settings.archive_dir.resolve()
    target = (root / (match.group(1) + ".mp4")).resolve()
    if target.parent != root or not target.is_file():
        raise ValueError("archive unavailable")
    return target


async def jpeg_stream(reader: asyncio.StreamReader, idle_timeout: int = 120):
    pending = bytearray()
    while True:
        chunk = await asyncio.wait_for(reader.read(65536), timeout=idle_timeout)
        if not chunk:
            if pending:
                raise ValueError("truncated JPEG stream")
            return
        pending.extend(chunk)
        while pending:
            start = pending.find(b"\xff\xd8\xff")
            if start < 0:
                if len(pending) > 2:
                    del pending[:-2]
                break
            if start:
                del pending[:start]
            end = pending.find(b"\xff\xd9", 3)
            if end < 0:
                if len(pending) > MAX_IMAGE_BYTES:
                    raise ValueError("JPEG frame exceeds limit")
                break
            frame = bytes(pending[:end + 2])
            del pending[:end + 2]
            yield frame


async def stop_process(process):
    if process.returncode is None:
        try:
            process.terminate()
        except ProcessLookupError:
            return
        try:
            await asyncio.wait_for(process.wait(), timeout=5)
        except TimeoutError:
            process.kill()
            await process.wait()


async def rtsp_frames(settings: Settings, reference: str):
    url = simulator_url(settings, reference)
    # Raw stderr must never reach logs: the URL can contain credentials. Drain
    # continuously into bounded, fixed-category counters instead.
    report = DecoderDiagnostics(reference.removeprefix('simulator:'))
    process = await asyncio.create_subprocess_exec(
        "ffmpeg", "-hide_banner", "-loglevel", "warning", "-nostats", "-tls_verify", "1", "-ca_file", settings.stream_ca,
        "-rtsp_transport", "tcp", "-timeout", "15000000", "-i", url, "-map", "0:v:0", "-an",
        "-vf", f"fps=1/{settings.sample_seconds},scale=1280:-2", "-q:v", "5", "-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1",
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.PIPE)
    diagnostics = asyncio.create_task(drain_decoder_diagnostics(process.stderr, report))
    emitted_frames = 0
    stop_reason = 'consumer_closed'
    try:
        async for frame in jpeg_stream(process.stdout, max(120, settings.sample_seconds + 60)):
            emitted_frames += 1
            yield frame
        if await process.wait() != 0:
            stop_reason = 'decoder_failed'
            raise RuntimeError("stream decoder stopped")
        stop_reason = 'decoder_ended'
    except TimeoutError:
        stop_reason = 'jpeg_idle_timeout'
        raise
    except ValueError:
        stop_reason = 'jpeg_protocol_error'
        raise
    except asyncio.CancelledError:
        stop_reason = 'cancelled'
        raise
    finally:
        termination_requested = process.returncode is None
        await stop_process(process)
        try:
            await asyncio.wait_for(diagnostics, timeout=5)
        except TimeoutError:
            report.counts['diagnostic_drain_timeout'] += 1
        finally:
            if not diagnostics.done():
                diagnostics.cancel()
            await asyncio.gather(diagnostics, return_exceptions=True)
            print(json.dumps({'event': 'decoder_stopped', 'exit_code': process.returncode,
                              'stop_reason': stop_reason, 'termination_requested': termination_requested,
                              'output_frames': emitted_frames, **report.snapshot()}), flush=True)


async def archive_frames(settings: Settings, reference: str):
    path = archive_path(settings, reference)
    probe = await asyncio.create_subprocess_exec(
        "ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1", str(path),
        stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
    try:
        output, _ = await asyncio.wait_for(probe.communicate(), timeout=30)
        duration = float(output.strip())
        if probe.returncode != 0 or not 0 < duration <= 7 * 24 * 3600:
            raise ValueError("archive duration unavailable")
    finally:
        await stop_process(probe)
    for seconds in range(0, math.ceil(duration), settings.sample_seconds):
        process = await asyncio.create_subprocess_exec(
            "ffmpeg", "-hide_banner", "-loglevel", "error", "-ss", str(seconds), "-i", str(path),
            "-map", "0:v:0", "-an", "-frames:v", "1", "-vf", "scale=1280:-2", "-q:v", "5",
            "-f", "image2pipe", "-vcodec", "mjpeg", "pipe:1", stdout=asyncio.subprocess.PIPE, stderr=asyncio.subprocess.DEVNULL)
        try:
            output, _ = await asyncio.wait_for(process.communicate(), timeout=60)
            if process.returncode != 0 or not output.startswith(b"\xff\xd8\xff") or len(output) > MAX_IMAGE_BYTES:
                raise ValueError("archive frame decode failed")
            # This is a seek offset in the uploaded file, not a verified source-clock time.
            yield seconds * 1000, output
        finally:
            await stop_process(process)
