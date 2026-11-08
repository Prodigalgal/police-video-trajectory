"""Bounded model failures. Upstream messages and credentials never cross this boundary."""
import json
import re
from contextvars import ContextVar
from dataclasses import dataclass


UPSTREAM_CODES = frozenset({'provider_upstream_error', 'credential_rejected', 'upstream_error',
    'provider_unavailable', 'provider_rate_limited', 'rate_limit_exceeded', 'invalid_api_key',
    'insufficient_quota', 'model_not_found', 'unsupported_parameter', 'invalid_request_error',
    'no_available_accounts', 'upstream_timeout', 'invalid_tool_arguments', 'tool_validation_failed'})


def upstream_code(payload):
    if not isinstance(payload, dict):
        return None
    error = payload.get('error')
    if not isinstance(error, dict):
        response = payload.get('response')
        error = response.get('error') if isinstance(response, dict) else None
    if not isinstance(error, dict):
        return None
    code = error.get('code')
    return code if isinstance(code, str) and code in UPSTREAM_CODES else 'other'


class GatewayError(Exception):
    def __init__(self, message: str, status_code: int = 502, validation_issue: str = 'none', *, upstream_error_code=None):
        self.message = message
        self.status_code = status_code
        self.validation_issue = validation_issue
        self.upstream_error_code = upstream_error_code if isinstance(upstream_error_code, str) and upstream_error_code in UPSTREAM_CODES | {'other'} else None
        self.upstream_request_id = None
        self.upstream_status = None
        self.stage = None
        super().__init__(message)

    def bind_response(self, response, stage):
        identity = response.headers.get('x-request-id', '')
        if re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', identity):
            self.upstream_request_id = identity
        self.upstream_status = response.status_code
        self.stage = stage if stage in {'upload', 'inference'} else None
        return self

    def diagnostics(self):
        return {key: value for key, value in {
            'stage': self.stage, 'upstream_status': self.upstream_status,
            'upstream_request_id': self.upstream_request_id, 'upstream_error_code': self.upstream_error_code,
        }.items() if value is not None}


async def response_error(response, message, stage):
    raw = bytearray()
    async for chunk in response.aiter_bytes():
        raw.extend(chunk[:max(0, 16385 - len(raw))])
        if len(raw) > 16384:
            break
    try:
        code = upstream_code(json.loads(raw)) if len(raw) <= 16384 else None
    except (ValueError, TypeError):
        code = None
    return GatewayError(message, upstream_error_code=code).bind_response(response, stage)


@dataclass
class CallDiagnostics:
    stage: str | None = None
    identity: str | None = None
    status: int | None = None

    def start(self, stage):
        self.stage, self.identity, self.status = stage, None, None

    def received(self, response):
        identity = response.headers.get('x-request-id', '')
        self.identity = identity if re.fullmatch(r'[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}', identity) else None
        self.status = response.status_code

    def apply(self, error):
        error.stage = error.stage or self.stage
        error.upstream_request_id = error.upstream_request_id or self.identity
        error.upstream_status = error.upstream_status or self.status
        return error


active_diagnostics = ContextVar('model_call_diagnostics', default=None)
