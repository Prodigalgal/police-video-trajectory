"""Versioned Core bindings. A request holds its immutable config until completion."""
import asyncio
import hashlib
import json
import logging
import re
import ssl
import time
from urllib.parse import urlsplit
from uuid import UUID

import httpx
from .client import GatewayError
from .config import ModelConfig
from .credentials import read_credential

LOG=logging.getLogger('video_model_runtime')


class ModelRuntime:
    refresh_seconds=2
    stale_seconds=30

    def __init__(self,settings,transport=None):
        self.settings=settings
        self.configs={'text':settings.text,'vision':settings.vision}
        self.managed=False
        self.revision=-1
        self.sha256=''
        self.refreshed_at=None
        self.acknowledged_at=None
        self.failure=False
        self.http=None
        if settings.control_url:
            uri=urlsplit(settings.control_url)
            if uri.scheme!='https' or not uri.hostname or any((uri.username,uri.password,uri.query,uri.fragment)) or not settings.shared_token or not re.fullmatch(r'[A-Za-z0-9_.-]{1,80}',settings.control_consumer):
                raise ValueError('invalid model control configuration')
            verify=ssl.create_default_context(cafile=settings.control_ca_file) if transport is None else True
            self.http=httpx.AsyncClient(verify=verify,transport=transport,timeout=5,follow_redirects=False,trust_env=False)

    def _credential(self,filename):
        return read_credential(self.settings.credentials_root,filename)

    def _binding(self,raw,role):
        if raw is None:return None
        if not isinstance(raw,dict) or raw.keys()!={'profile_id','version','definition'} or str(UUID(raw['profile_id']))!=raw['profile_id'] or type(raw['version']) is not int or raw['version']<1:
            raise ValueError('invalid model binding')
        value=raw['definition']
        fields={'display_name','provider','model','protocol','roles','image_transport','output_mode','json_mode','reasoning_effort','base_url','credential_file'}
        if not isinstance(value,dict) or value.keys()!=fields or not isinstance(value['roles'],list) or role not in value['roles'] or not set(value['roles'])<={'text','vision'}:
            raise ValueError('invalid model definition')
        if any(not isinstance(value[key],str) for key in fields-{'roles','json_mode'}) or type(value['json_mode']) is not bool or not 1<=len(value['model'])<=150:
            raise ValueError('invalid model fields')
        base=value['base_url'];uri=urlsplit(base)
        if uri.scheme!='https' or not uri.hostname or any((uri.username,uri.password,uri.query,uri.fragment)) or base.endswith('/'):
            raise ValueError('invalid model endpoint')
        config=ModelConfig(api_url=base+('/responses' if value['protocol']=='responses' else '/chat/completions'),
            api_key=self._credential(value['credential_file']),model=value['model'],style=value['protocol'],
            json_mode=value['json_mode'],image_transport=value['image_transport'] if role=='vision' else 'inline',
            file_upload_url=base+'/files' if value['image_transport']=='file' and role=='vision' else '',
            output_mode=value['output_mode'],reasoning_effort=value['reasoning_effort'],binding_id=f"{raw['profile_id']}:{raw['version']}")
        if not config.valid:raise ValueError('invalid model config')
        return config

    def apply(self,value):
        if not isinstance(value,dict) or value.keys()!={'schema_version','revision','managed','bindings','sha256'} or value['schema_version']!='model-bindings-v1' or type(value['revision']) is not int or value['revision']<0 or type(value['managed']) is not bool or value['managed']!=(value['revision']>0) or not isinstance(value['bindings'],dict) or value['bindings'].keys()!={'text','vision'}:
            raise ValueError('invalid binding snapshot')
        payload=json.dumps({'revision':value['revision'],'bindings':value['bindings']},ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()
        if hashlib.sha256(payload).hexdigest()!=value['sha256'] or value['revision']<self.revision or value['revision']==self.revision and self.sha256!=value['sha256']:
            raise ValueError('binding revision conflict')
        configs={role:self._binding(raw,role) for role,raw in value['bindings'].items()} if value['managed'] else {'text':self.settings.text,'vision':self.settings.vision}
        self.configs=configs;self.managed=value['managed'];self.revision=value['revision'];self.sha256=value['sha256'];self.refreshed_at=time.monotonic()

    async def refresh(self):
        if self.http is None:return
        headers={'Authorization':'Bearer '+self.settings.shared_token}
        try:
            async with self.http.stream('GET',self.settings.control_url.rstrip('/')+'/internal/v1/model-bindings',headers=headers) as response:
                response.raise_for_status();raw=bytearray()
                async for chunk in response.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw)>131072:raise ValueError('binding snapshot exceeds bound')
            self.apply(json.loads(raw))
            if self.acknowledged_at is None or time.monotonic()-self.acknowledged_at>=10 or getattr(self,'acknowledged_revision',-1)!=self.revision:
                response=await self.http.post(self.settings.control_url.rstrip('/')+'/internal/v1/model-bindings/acknowledge',headers=headers,
                    json={'consumer_id':self.settings.control_consumer,'revision':self.revision,'sha256':self.sha256})
                response.raise_for_status();self.acknowledged_at=time.monotonic();self.acknowledged_revision=self.revision
            if self.failure:LOG.info('model_bindings_recovered revision=%s',self.revision)
            self.failure=False
        except (httpx.HTTPError,ValueError,TypeError,KeyError,OSError):
            if not self.failure:LOG.warning('model_bindings_refresh_failed revision=%s',self.revision)
            self.failure=True

    async def watch(self):
        while True:
            await asyncio.sleep(self.refresh_seconds)
            await self.refresh()

    @property
    def fresh(self):return self.http is None or self.refreshed_at is not None and time.monotonic()-self.refreshed_at<=self.stale_seconds

    def resolve(self,role):
        if not self.fresh:raise GatewayError('模型配置同步暂不可用',503)
        config=self.configs[role]
        if config is None:raise GatewayError('该用途的模型已停用',503)
        return config

    def status(self):return {'managed':self.managed,'revision':self.revision,'sha256':self.sha256,'fresh':self.fresh,'roles':{role:config is not None for role,config in self.configs.items()}}

    async def close(self):
        if self.http is not None:await self.http.aclose()
