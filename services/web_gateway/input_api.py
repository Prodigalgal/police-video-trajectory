"""Verified analysis-input snapshots, distinct from inference submission."""
import hashlib
import json
from typing import Literal
from uuid import UUID

import httpx
from fastapi import HTTPException, Query, Request
from fastapi.responses import JSONResponse, Response
from pydantic import BaseModel, ConfigDict, Field


class Window(BaseModel):
    model_config = ConfigDict(extra='forbid')
    before_ms: int = Field(default=60000, ge=0, le=120000, strict=True)
    after_ms: int = Field(default=60000, ge=0, le=120000, strict=True)
    max_frames: int = Field(default=16, ge=1, le=24, strict=True)
    max_gap_ms: int = Field(default=6000, ge=1, le=10000, strict=True)

    def core(self):
        return dict(beforeMs=self.before_ms, afterMs=self.after_ms,
                    maxFrames=self.max_frames, maxGapMs=self.max_gap_ms)


class Create(BaseModel):
    model_config = ConfigDict(extra='forbid')
    request_id: UUID
    observation_id: UUID
    observation_version: int = Field(ge=1, strict=True)
    evidence_sha256: str = Field(pattern=r'^[a-f0-9]{64}$')
    purpose: Literal['single_frame', 'frame_sequence']
    window: Window | None = None


def mount_inputs(app, cfg, authenticated, origin_ok):
    allowed = {'observation_unavailable', 'observation_revision_conflict', 'input_source_unavailable',
               'input_source_changed', 'input_sequence_unavailable', 'input_too_large', 'input_evidence_changed',
               'input_evidence_unavailable', 'input_request_conflict', 'input_unavailable',
               'input_manifest_changed', 'input_frame_unavailable', 'invalid_input_request'}

    async def forward(request, method, path, *, body=None, params=None, context=False, download=None):
        if method != 'GET':
            origin_ok(request)
        if not authenticated(request):
            raise HTTPException(401, '请先登录')
        if not (cfg.get('analysis_inputs') and cfg.get('account_directory') and cfg.get('admin_token')):
            raise HTTPException(404, '分析输入功能尚未启用')
        account = request.state.account
        headers = {'Authorization': 'Bearer ' + (cfg['core_token'] if context else cfg['admin_token'])}
        if not context:
            headers.update({'X-Actor-Id': account['username'], 'X-Actor-Revision': str(account['credential_revision'])})
        try:
            async with request.app.state.http.stream(method, cfg['core_url'].rstrip('/') + path,
                                                      json=body, params=params, headers=headers, timeout=90) as upstream:
                raw = bytearray()
                async for chunk in upstream.aiter_bytes():
                    if len(raw) + len(chunk) > 2 * 1024 * 1024:
                        raise ValueError('input response exceeds bound')
                    raw.extend(chunk)
                if upstream.status_code != 200:
                    value = json.loads(raw)
                    code = value.get('code') if isinstance(value, dict) else None
                    return JSONResponse({'code': code if code in allowed else 'analysis_inputs_unavailable'},
                                        status_code=upstream.status_code if upstream.status_code in {400, 401, 403, 404, 409, 422, 429} else 503,
                                        headers={'Cache-Control': 'no-store'})
                if download:
                    digest = hashlib.sha256(raw).hexdigest()
                    if upstream.headers.get('x-content-sha256') != digest:
                        raise ValueError('manifest digest differs')
                    return Response(bytes(raw), media_type='application/json', headers={
                        'Content-Disposition': f'attachment; filename="analysis-input-{download}.json"',
                        'X-Content-SHA256': digest, 'Cache-Control': 'no-store'})
                value = json.loads(raw)
                if not isinstance(value, dict):
                    raise ValueError('invalid input response')
                return JSONResponse(value, headers={'Cache-Control': 'no-store'})
        except (httpx.HTTPError, ValueError, TypeError):
            raise HTTPException(503, '分析输入服务暂不可用') from None

    @app.get('/api/v1/observations/{observation}/context')
    async def context(observation: UUID, request: Request, expected_version: int | None = Query(default=None, ge=1),
                      before_ms: int = Query(default=60000, ge=0, le=120000),
                      after_ms: int = Query(default=60000, ge=0, le=120000),
                      max_frames: int = Query(default=16, ge=1, le=24),
                      max_gap_ms: int = Query(default=6000, ge=1, le=10000)):
        params = dict(before_ms=before_ms, after_ms=after_ms, max_frames=max_frames, max_gap_ms=max_gap_ms)
        if expected_version is not None:
            params['expected_version'] = expected_version
        return await forward(request, 'GET', f'/api/v1/observations/{observation}/context', params=params, context=True)

    @app.get('/api/v1/observations/{observation}/nearby-records')
    async def nearby(observation:UUID,request:Request,expected_version:int|None=Query(default=None,ge=1),limit:int=Query(default=9,ge=1,le=17)):
        params={'limit':limit}
        if expected_version is not None:params['expected_version']=expected_version
        return await forward(request,'GET',f'/api/v1/observations/{observation}/nearby-records',params=params,context=True)

    @app.post('/api/v1/analysis-inputs')
    async def create(body: Create, request: Request):
        return await forward(request, 'POST', '/internal/analysis-inputs', body={
            'requestId': str(body.request_id), 'observationId': str(body.observation_id),
            'observationVersion': body.observation_version, 'evidenceSha256': body.evidence_sha256,
            'purpose': body.purpose, 'window': body.window.core() if body.window else None})

    @app.get('/api/v1/analysis-inputs')
    async def list_inputs(request: Request, observation_id: UUID, purpose: Literal['single_frame', 'frame_sequence'] | None = None,
                          limit: int = Query(default=20, ge=1, le=50), after: UUID | None = None):
        params = {'observation_id': str(observation_id), 'limit': limit}
        if purpose:
            params['purpose'] = purpose
        if after:
            params['after'] = str(after)
        return await forward(request, 'GET', '/internal/analysis-inputs', params=params)

    @app.get('/api/v1/analysis-inputs/{identity}')
    async def read(identity: UUID, request: Request):
        return await forward(request, 'GET', f'/internal/analysis-inputs/{identity}')

    @app.get('/api/v1/analysis-inputs/{identity}/manifest')
    async def manifest(identity: UUID, request: Request):
        return await forward(request, 'GET', f'/internal/analysis-inputs/{identity}/manifest', download=identity)
