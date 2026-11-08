"""Recording registration is independent of capture/VLM delivery and survives restarts."""
import asyncio
import json
import re
import sqlite3
from pathlib import Path

from .client import ServiceError
from .events import log
from .live_recording_store import LiveStore


def pin_frame(settings,record):
    try:
        descriptor=record['metadata']['recording']
        store=LiveStore(settings.live_recording_dir,descriptor['namespace'])
        pinned=store.pin(record['frame_id'],record['sha256'],descriptor)
        if not pinned:log(json.dumps({'event':'recording_reference_unavailable','frame_id':record['frame_id']}))
        return pinned
    except (OSError,ValueError,TypeError,KeyError,sqlite3.Error):
        log(json.dumps({'event':'recording_reference_unconfirmed','frame_id':record.get('frame_id')}));return False


def pending(settings):
    root=Path(settings.live_recording_dir)
    if root.is_symlink():raise ValueError('recording root is not safe')
    if not root.is_dir():return []
    result=[]
    namespaces=[p for p in root.iterdir() if re.fullmatch('[a-f0-9]{32}',p.name)]
    if len(namespaces)>128:raise ValueError('recording namespace limit exceeded')
    for directory in namespaces:
        try:
            if directory.is_symlink():raise ValueError('recording namespace is not safe')
            store=LiveStore(root,directory.name)
            result.extend((directory.name,row) for row in store.pending())
        except (OSError,ValueError,sqlite3.Error):log(json.dumps({'event':'recording_namespace_unavailable','namespace':directory.name}))
    return result


async def publish_recordings(settings,core):
    while True:
        try:
            rows=await asyncio.to_thread(pending,settings)
            for namespace,row in rows:
                frame,segment,sha,pts,packet,digest=row;store=LiveStore(settings.live_recording_dir,namespace)
                body={'frameId':frame,'payloadSha256':sha,'namespace':namespace,'segmentId':segment,
                      'manifestSha256':digest,'sourcePtsUs':pts,'packetSha256':packet}
                try:
                    reply=await core.post('/internal/v1/frame-recordings/register',body)
                    if reply.get('recorded') is not True or reply.get('frame_id')!=frame or reply.get('segment_id')!=segment or reply.get('manifest_sha256')!=digest:raise ServiceError(502)
                    await asyncio.to_thread(store.attempted,frame,done=True)
                except ServiceError as error:
                    await asyncio.to_thread(store.attempted,frame,'recording_registration_'+str(error.status),failed=error.status in {400,409,410,422})
                    log(json.dumps({'event':'recording_registration_deferred','frame_id':frame,'http_status':error.status}))
                    if error.status in {401,403,408,429,500,502,503,504}:break
        except (OSError,ValueError,TypeError,KeyError,sqlite3.Error):log(json.dumps({'event':'recording_outbox_unavailable'}))
        await asyncio.sleep(1)
