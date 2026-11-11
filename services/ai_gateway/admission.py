"""Cluster-wide bounded inference leases, independent of per-process limits."""
import asyncio
from contextlib import asynccontextmanager
import json
import logging
import math
import re
import ssl
from urllib.parse import urlsplit
from uuid import uuid4
import httpx
from .errors import GatewayError

LOG=logging.getLogger('video_model_admission')


class ModelAdmissionClient:
    def __init__(self,settings,transport=None):
        parsed=urlsplit(settings.control_url)
        if (parsed.scheme!='https' or not parsed.hostname or any((parsed.username,parsed.password,parsed.query,parsed.fragment))
                or not settings.shared_token or not re.fullmatch(r'[A-Za-z0-9_.-]{1,100}',settings.control_consumer)
                or not 0<settings.timeout_seconds<=600):raise ValueError('Invalid shared model admission configuration')
        self.base=settings.control_url.rstrip('/')+'/internal/cluster/models/'
        self.consumer=settings.control_consumer;self.ttl=math.ceil(settings.timeout_seconds)+15
        self.headers={'Authorization':'Bearer '+settings.shared_token}
        verify=ssl.create_default_context(cafile=settings.control_ca_file) if transport is None else True
        self.http=httpx.AsyncClient(verify=verify,transport=transport,timeout=3,trust_env=False,follow_redirects=False)

    async def close(self):await self.http.aclose()

    async def ready(self):
        try:
            response=await self.http.get(self.base.split('/internal/cluster/')[0]+'/internal/cluster/readyz')
            return response.status_code==200 and len(response.content)<=2048 and response.json().get('ready') is True
        except (httpx.HTTPError,ValueError,TypeError,AttributeError):return False

    async def call(self,operation,body):
        async with self.http.stream('POST',self.base+operation,headers=self.headers,json=body) as response:
            if response.status_code!=200:raise ValueError('Coordination rejected')
            raw=bytearray()
            async for chunk in response.aiter_bytes():
                raw.extend(chunk)
                if len(raw)>2048:raise ValueError('Coordination response too large')
            value=json.loads(raw)
            if not isinstance(value,dict):raise ValueError('Coordination response differs')
            return value

    async def release(self,identity):
        try:
            result=await self.call('release',{'leaseId':identity,'consumer':self.consumer})
            if result.get('released') is not True:raise ValueError('Release not acknowledged')
        except (httpx.HTTPError,ValueError,TypeError,TimeoutError):
            # Expiration recovers capacity; never turn a valid inference into a retry.
            LOG.warning('Model admission release deferred until lease expiry')

    @asynccontextmanager
    async def slot(self,vision):
        identity=str(uuid4())
        try:
            try:
                value=await self.call('acquire',{'leaseId':identity,'consumer':self.consumer,'vision':vision,'ttlSeconds':self.ttl})
                if value.get('granted') is False:raise GatewayError('模型服务额度已占用，请稍后重试',429)
                if value.get('granted') is not True or value.get('lease_id')!=identity:raise ValueError('Lease identity differs')
            except (httpx.HTTPError,ValueError,TypeError,TimeoutError):
                raise GatewayError('模型额度协调服务暂不可用，请稍后重试',503) from None
            yield
        finally:
            # Also release an ambiguous acquisition after a lost acknowledgement.
            cleanup=asyncio.create_task(self.release(identity))
            try:await asyncio.shield(cleanup)
            except asyncio.CancelledError:
                await cleanup
                raise
