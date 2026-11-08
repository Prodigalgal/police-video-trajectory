"""Confirm an idempotent Core transaction without repeating model inference."""
import asyncio
from datetime import datetime, timezone

from .client import ServiceError


class AnalysisFailure(ValueError):
    def __init__(self, error_code):
        self.error_code=error_code
        super().__init__('analysis result submission was not confirmed')


async def submit_completion(core, path, body, expected_objects, job, *, retry_delays=(.25,.5)):
    if type(expected_objects) is not int or not 0<=expected_objects<=100:
        raise ValueError('invalid expected object count')
    expires=None
    if job.get('expiresAt') is not None:
        try:
            expires=datetime.fromisoformat(job['expiresAt'].replace('Z','+00:00'))
            if expires.tzinfo is None:raise ValueError('lease clock is unverified')
        except (ValueError,TypeError,AttributeError):
            raise AnalysisFailure('result_commit_unavailable') from None
    for attempt in range(len(retry_delays)+1):
        if expires is not None and expires<=datetime.now(timezone.utc):
            raise AnalysisFailure('result_commit_unavailable')
        try:
            receipt=await core.post(path,body)
            if isinstance(receipt,dict) and type(receipt.get('objects')) is int and receipt['objects']==expected_objects:
                return receipt
        except ServiceError as error:
            if error.status not in {408,425,429,500,502,503,504}:
                raise AnalysisFailure('result_commit_unavailable') from None
        if attempt<len(retry_delays):
            delay=retry_delays[attempt]
            if expires is not None and (expires-datetime.now(timezone.utc)).total_seconds()<=delay:
                break
            await asyncio.sleep(delay)
    raise AnalysisFailure('result_commit_unavailable')
