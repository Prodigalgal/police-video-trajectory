"""Kill and reap native work before cancellation releases its admission slot."""
import asyncio
import json
import os
from pathlib import Path
import struct
import subprocess
import sys

from .catalog import canonical


class NativeFailure(RuntimeError):
    pass


class NativeTimeout(NativeFailure):
    pass


async def owned_result(task):
    cancelled=False
    while not task.done():
        try:await asyncio.shield(task)
        except asyncio.CancelledError:cancelled=True
    value=task.result()
    return value,cancelled


async def bounded_read(reader,limit):
    chunks=[];size=0
    while True:
        chunk=await reader.read(min(65536,limit-size+1))
        if not chunk:return b''.join(chunks)
        size+=len(chunk)
        if size>limit:raise NativeFailure('native output exceeded bound')
        chunks.append(chunk)


class NativeRunner:
    def __init__(self,root,*,module='services.specialist_service.process'):
        if module not in {'services.specialist_service.process','services.appearance.process'}:raise ValueError('unsupported native module')
        self.module=module
        self.root=str(Path(root).resolve(strict=True));self.running=set();self.creating=set();self.calls=set();self.closed=False

    async def recognize(self,definition,metadata,image,timeout):
        if self.closed:raise NativeFailure('native runner closed')
        header=canonical({'root':self.root,'definition':definition.model_dump(),'request':metadata.model_dump()})
        if len(header)>65536 or timeout<=2:raise NativeTimeout('native budget exhausted')
        payload=struct.pack('>I',len(header))+header+image
        environment={key:value for key,value in os.environ.items() if key.upper() in {
            'SYSTEMROOT','WINDIR','PATH','PATHEXT','TEMP','TMP','PYTHONPATH','PYTHONHOME','LD_LIBRARY_PATH',
            'LANG','LC_ALL','TZ','PYTHONDONTWRITEBYTECODE','PYTHONUNBUFFERED'}}
        environment.update(OMP_NUM_THREADS='1',OPENBLAS_NUM_THREADS='1')
        process=None;readers=[];creation=None
        invocation=asyncio.current_task();self.calls.add(invocation)
        try:
            async with asyncio.timeout(timeout-2):
                creation=asyncio.create_task(asyncio.create_subprocess_exec(sys.executable,'-m',self.module,
                    stdin=asyncio.subprocess.PIPE,stdout=asyncio.subprocess.PIPE,stderr=asyncio.subprocess.PIPE,
                    creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0),env=environment))
                self.creating.add(creation)
                process=await asyncio.shield(creation);self.running.add(process)
                if self.closed:raise NativeFailure('native runner closed')
                readers=[asyncio.create_task(bounded_read(process.stdout,192*1024)),asyncio.create_task(bounded_read(process.stderr,16384))]
                process.stdin.write(payload);await process.stdin.drain();process.stdin.close()
                output,_=await asyncio.gather(*readers);status=await process.wait()
                if status:raise NativeFailure('native process failed')
                value=json.loads(output)
                if not isinstance(value,dict) or set(value)!={'result','diagnostics'}:raise NativeFailure('native process result invalid')
                return value
        except TimeoutError:raise NativeTimeout('native inference timed out') from None
        except (OSError,ValueError,RuntimeError,ConnectionError):raise NativeFailure('native process unavailable') from None
        finally:
            if creation is not None:
                try:
                    if process is None:
                        try:process,_=await owned_result(creation)
                        except (OSError,ValueError,RuntimeError):process=None
                finally:self.creating.discard(creation)
            if process is not None:
                cleanup=asyncio.create_task(self._stop(process,readers))
                _,cancelled=await owned_result(cleanup)
                self.running.discard(process)
            else:cancelled=False
            self.calls.discard(invocation)
            if cancelled:raise asyncio.CancelledError

    @staticmethod
    async def _stop(process,readers):
        if process.stdin is not None:process.stdin.close()
        if process.returncode is None:
            try:process.kill()
            except ProcessLookupError:pass
        for reader in readers:
            if not reader.done():reader.cancel()
        await asyncio.gather(*readers,return_exceptions=True)
        async def drain(reader):
            while await reader.read(65536):pass
        # Reap only after draining closed pipes; a bounded-reader rejection must
        # not leave wait() blocked behind the subprocess transport's buffers.
        await asyncio.gather(drain(process.stdout),drain(process.stderr))
        await process.wait()

    async def close(self):
        self.closed=True
        pending=[task for task in self.calls if task is not asyncio.current_task()]
        for task in pending:task.cancel()
        await asyncio.gather(*pending,return_exceptions=True)
