"""Bounded analysis execution, renewable leases and graceful consumer shutdown."""
import asyncio
from datetime import datetime, timedelta, timezone
import json
import signal

from .client import ServiceError
from .events import log


RETRYABLE_RENEWAL_STATUS = {408, 425, 429, 500, 502, 503, 504}
FAILURE_REPORT_SECONDS = 5
RENEWAL_RETRY_SECONDS = 5


def job_event(event, worker, job, **details):
    log(json.dumps({'event': event, 'worker_id': worker, 'job_id': job.get('jobId'),
                    'frame_id': job.get('frameId'), 'generation': job.get('generation'),
                    'task_kind':'media' if job.get('profileVersion') else 'module' if job.get('moduleKey') else 'frame',
                    **({'module_key':job['moduleKey']} if job.get('moduleKey') else {}), **details}))


async def record_failure(core, worker, job, error_code, *, timeout=FAILURE_REPORT_SECONDS,route='/internal/v1/analysis'):
    """Never repeat an ambiguous failure write; lease fencing controls recovery."""
    try:
        receipt = await asyncio.wait_for(core.post(route+'/' + job['jobId'] + '/fail', {
            'workerId': worker, 'generation': job['generation'], 'errorCode': error_code}), timeout)
        if not isinstance(receipt, dict) or receipt.get('recorded') is not True:
            raise ServiceError(502)
    except (ServiceError, TimeoutError, KeyError) as error:
        job_event('analysis_failure_unconfirmed', worker, job, error_code=error_code,
                  http_status=getattr(error, 'status', None), recovery='durable_queue_and_lease_expiry')
        return False
    job_event('analysis_failure_recorded', worker, job, error_code=error_code)
    return True


async def renew_analysis(settings, core, worker, job, deadline,route='/internal/v1/analysis'):
    loop = asyncio.get_running_loop()
    safety = min(10, settings.analysis_lease_seconds / 10)
    while True:
        remaining = deadline[0] - loop.time()
        if remaining <= 0:
            raise ServiceError(409)
        await asyncio.sleep(min(settings.analysis_heartbeat_seconds, remaining))
        while True:
            started = loop.time()
            remaining = deadline[0] - started
            if remaining <= 0:
                raise ServiceError(409)
            conservative_expiry = datetime.now(timezone.utc) + timedelta(seconds=settings.analysis_lease_seconds - safety)
            try:
                receipt = await asyncio.wait_for(core.post(route+'/' + job['jobId'] + '/renew', {
                    'workerId': worker, 'generation': job['generation'],
                    'leaseSeconds': settings.analysis_lease_seconds}), remaining)
                if not isinstance(receipt, dict) or receipt.get('renewed') is not True:
                    raise ServiceError(502)
            except TimeoutError:
                raise ServiceError(504) from None
            except ServiceError as error:
                if error.status not in RETRYABLE_RENEWAL_STATUS:
                    raise
                job_event('analysis_renewal_deferred', worker, job, http_status=error.status)
                await asyncio.sleep(min(RENEWAL_RETRY_SECONDS, max(0, deadline[0] - loop.time())))
            else:
                deadline[0] = started + settings.analysis_lease_seconds - safety
                # Completion retries must see the renewed lease, rather than
                # stopping at the original claim's wall-clock expiry.
                job['expiresAt'] = conservative_expiry.isoformat()
                break


async def execute_owned(settings, core, worker, job, claimed_at, execute,*,route='/internal/v1/analysis'):
    loop = asyncio.get_running_loop()
    safety = min(10, settings.analysis_lease_seconds / 10)
    deadline = [claimed_at + settings.analysis_lease_seconds - safety]
    if deadline[0] <= loop.time():
        job_event('analysis_lease_lost', worker, job, recovery='claim_response_arrived_after_local_deadline')
        return False
    work = asyncio.create_task(execute(), name='analysis-work-' + worker)
    renewal = asyncio.create_task(renew_analysis(settings, core, worker, job, deadline,route), name='analysis-renewal-' + worker)
    failure_code = None
    job_event('analysis_started', worker, job, job_timeout_seconds=settings.analysis_job_timeout_seconds)
    try:
        done, _ = await asyncio.wait({work, renewal}, timeout=settings.analysis_job_timeout_seconds,
                                     return_when=asyncio.FIRST_COMPLETED)
        if work in done:
            result = work.result()
            job_event('analysis_finished', worker, job, completed=result is True)
            return result
        if renewal in done:
            try:
                renewal.result()
            except ServiceError as error:
                job_event('analysis_lease_lost', worker, job, http_status=error.status,
                          recovery='cancel_work_and_allow_fenced_recovery')
                return False
            raise RuntimeError('analysis renewal stopped unexpectedly')
        failure_code = 'job_timeout'
        return False
    except asyncio.CancelledError:
        failure_code = 'consumer_interrupted'
        raise
    finally:
        for task in (work, renewal):
            if not task.done():
                task.cancel()
        await asyncio.gather(work, renewal, return_exceptions=True)
        if failure_code:
            remaining = deadline[0] - loop.time()
            if remaining > 0:
                await record_failure(core, worker, job, failure_code, timeout=min(FAILURE_REPORT_SECONDS, remaining),route=route)
            else:
                job_event('analysis_failure_unconfirmed', worker, job, error_code=failure_code,
                          recovery='lease_expiry')


async def wait_for_poll(stop, seconds=5):
    try:
        await asyncio.wait_for(stop.wait(), seconds)
    except TimeoutError:
        pass


async def supervise(settings, consume_slot,*,extra_consumers=()):
    """Stop claiming on SIGTERM, drain briefly, then release unfinished work."""
    loop = asyncio.get_running_loop()
    stop = asyncio.Event()
    signal_installed = False
    try:
        loop.add_signal_handler(signal.SIGTERM, stop.set)
        signal_installed = True
    except (NotImplementedError, RuntimeError):
        # Windows/embedded loops still use normal coroutine cancellation.
        pass
    slots = [asyncio.create_task(consume_slot(slot + 1, stop), name=f'analysis-slot-{slot + 1}')
             for slot in range(settings.analysis_slots)]
    slots.extend(asyncio.create_task(factory(stop),name=name) for name,factory in extra_consumers)
    stopping = asyncio.create_task(stop.wait())
    try:
        done, _ = await asyncio.wait({*slots, stopping}, return_when=asyncio.FIRST_COMPLETED)
        if stopping not in done:
            for task in done:
                task.result()
            raise RuntimeError('analysis consumer stopped unexpectedly')
        log(json.dumps({'event': 'analysis_draining', 'grace_seconds': settings.analysis_shutdown_grace_seconds}))
        _, pending = await asyncio.wait(slots, timeout=settings.analysis_shutdown_grace_seconds)
        for task in pending:
            task.cancel()
        outcomes = await asyncio.gather(*slots, return_exceptions=True)
        for outcome in outcomes:
            if isinstance(outcome, BaseException) and not isinstance(outcome, asyncio.CancelledError):
                raise outcome
    finally:
        stop.set()
        stopping.cancel()
        for task in slots:
            if not task.done():
                task.cancel()
        await asyncio.gather(stopping, *slots, return_exceptions=True)
        if signal_installed:
            loop.remove_signal_handler(signal.SIGTERM)
