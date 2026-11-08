"""Session-scoped management facade; Core owns leases, quotas and command receipts."""
from typing import Literal
from uuid import UUID

from fastapi import Query, Request
from pydantic import BaseModel, ConfigDict, Field, model_validator

from .business_proxy import BusinessProxy


class Command(BaseModel):
    model_config = ConfigDict(extra='forbid')
    request_id: UUID


class RegisterSource(Command):
    camera_id: str = Field(pattern=r'^recording-0[1-4]$')
    source_type: Literal['rtsp', 'archive']


class RevisionCommand(Command):
    expected_revision: str = Field(pattern=r'^[a-f0-9]{64}$')
    enabled: bool = Field(strict=True)
    reason: str = Field(min_length=1, max_length=300)


class BatchItem(BaseModel):
    model_config = ConfigDict(extra='forbid')
    source_id: UUID
    start_ms: int = Field(default=0, ge=0, le=604800000, strict=True)
    end_ms: int | None = Field(default=None, ge=1, le=604800000, strict=True)
    priority: int = Field(default=0, ge=0, le=9, strict=True)

    @model_validator(mode='after')
    def valid_window(self):
        if self.end_ms is not None and self.end_ms <= self.start_ms:
            raise ValueError('Archive end must follow start')
        return self


class CreateBatch(Command):
    title: str = Field(min_length=1, max_length=160)
    items: list[BatchItem] = Field(min_length=1, max_length=12)


class ControlTask(Command):
    expected_revision: int = Field(ge=1, strict=True)
    action: Literal['pause', 'resume', 'retry']
    reason: str = Field(min_length=1, max_length=300)


def revision_body(body):
    return {'requestId': str(body.request_id), 'expectedRevision': body.expected_revision,
            'enabled': body.enabled, 'reason': body.reason}


def mount_ingestion(app, cfg, authenticated, origin_ok):
    proxy = BusinessProxy(app, cfg, authenticated, origin_ok, feature='ingestion_jobs')
    public = '/api/admin/ingestion'
    internal = '/internal/ingestion'

    @app.get(public + '/sources')
    async def sources(request: Request, limit: int = Query(default=50, ge=1, le=50), after: UUID | None = None):
        return await proxy.call(request, 'GET', internal + '/sources', admin=True,
                                params={'limit': limit, **({'after': str(after)} if after else {})})

    @app.post(public + '/sources')
    async def register(body: RegisterSource, request: Request):
        reference = ('archive:' if body.source_type == 'archive' else 'simulator:') + body.camera_id
        return await proxy.call(request, 'POST', internal + '/sources', admin=True,
                                body={'requestId': str(body.request_id), 'cameraId': body.camera_id,
                                      'sourceType': body.source_type, 'sourceRef': reference})

    @app.put(public + '/sources/{identity}')
    async def toggle(identity: UUID, body: RevisionCommand, request: Request):
        return await proxy.call(request, 'PUT', internal + f'/sources/{identity}', admin=True, body=revision_body(body))

    @app.post(public + '/batches')
    async def batch(body: CreateBatch, request: Request):
        return await proxy.call(request, 'POST', internal + '/batches', admin=True, timeout=120,
                                body={'requestId': str(body.request_id), 'title': body.title,
                                      'items': [{'sourceId': str(item.source_id), 'startMs': item.start_ms,
                                                 'endMs': item.end_ms, 'priority': item.priority} for item in body.items]})

    @app.get(public + '/tasks')
    async def tasks(request: Request, state: Literal['all', 'queued', 'active', 'failed', 'completed', 'disabled'] = 'all',
                    camera_id: str | None = Query(default=None, pattern=r'^[A-Za-z0-9_.-]{1,100}$'),
                    limit: int = Query(default=20, ge=1, le=50), after: UUID | None = None, batch_id: UUID | None = None):
        params = {'state': state, 'limit': limit}
        params.update({key: str(value) for key, value in {'camera_id': camera_id, 'after': after, 'batch_id': batch_id}.items() if value is not None})
        return await proxy.call(request, 'GET', internal + '/tasks', admin=True, params=params)

    @app.post(public + '/tasks/{identity}/control')
    async def control(identity: UUID, body: ControlTask, request: Request):
        return await proxy.call(request, 'POST', internal + f'/tasks/{identity}/control', admin=True,
                                body={'requestId': str(body.request_id), 'expectedRevision': body.expected_revision,
                                      'action': body.action, 'reason': body.reason})

    @app.get(public + '/control')
    async def global_control(request: Request):
        return await proxy.call(request, 'GET', internal + '/control', admin=True)

    @app.post(public + '/control')
    async def update_global(body: RevisionCommand, request: Request):
        return await proxy.call(request, 'POST', internal + '/control', admin=True, body=revision_body(body))
