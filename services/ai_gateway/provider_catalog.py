"""Operator-owned provider discovery, independent of inference and frozen bindings."""
import asyncio,copy,hashlib,json,re,time
from datetime import datetime,timezone
from pathlib import Path
from urllib.parse import urlsplit
import httpx
from .credentials import read_credential
from .errors import upstream_code

MAX_RESPONSE=2*1024*1024
MAX_MODELS=512
PROTOCOLS={'chat_completions','responses'}
ROLES={'text','vision'}
REASONING={'auto','none','minimal','low','medium','high','xhigh','max'}


class CatalogRejected(Exception):
    def __init__(self,status,code):self.status,self.code=status,code


def encoded(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False).encode()


def configuration_sha(provider):
    return hashlib.sha256(encoded({key:sorted(provider[key]) if key in {'roles','protocols'} else provider[key]
        for key in ('id','base_url','credential_file','protocols','roles','activation_allowed')})).hexdigest()


def normalize_models(value,provider):
    if not isinstance(value,dict) or value.get('object')!='list' or not isinstance(value.get('data'),list) or len(value['data'])>MAX_MODELS:raise ValueError('invalid catalog list')
    models=[];seen=set()
    for row in value['data']:
        if not isinstance(row,dict) or not isinstance(row.get('id'),str) or not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9._:/+@() -]{0,149}',row['id']) or row['id'] in seen:raise ValueError('invalid catalog model')
        if row.get('owned_by',provider['id'])!=provider['id']:raise ValueError('catalog provider differs')
        seen.add(row['id']);cap=row.get('capabilities',{})
        if not isinstance(cap,dict):raise ValueError('invalid capabilities')
        media=cap.get('multimodal',row.get('multimodal',{}));parameters=cap.get('supported_parameters',row.get('supported_parameters',{}))
        if not isinstance(media,dict) or not isinstance(parameters,dict):raise ValueError('invalid capability shape')
        inputs=media.get('input',[])
        if not isinstance(inputs,list) or len(inputs)>16 or any(not isinstance(entry,str) for entry in inputs):raise ValueError('invalid media declaration')
        inputs=sorted(set(inputs)&{'text','image','audio','video','file'})
        streaming=cap.get('streaming',row.get('streaming'))
        if streaming is not None and type(streaming) is not bool:raise ValueError('invalid streaming declaration')
        protocols=sorted(set(parameters)&set(provider['protocols'])&PROTOCOLS)
        roles=sorted(({'text'} if 'text' in inputs else set())|({'vision'} if 'image' in inputs else set()))
        # Any2API routes these Grok models through media-generation endpoints.
        media_only=provider['id']=='grok_web' and row['id'].startswith('grok-imagine-')
        reasoning=cap.get('reasoning',{});tools=cap.get('tools',{})
        if not isinstance(reasoning,dict) or not isinstance(tools,dict):raise ValueError('invalid optional capability')
        levels=reasoning.get('levels',[])
        if not isinstance(levels,list) or any(not isinstance(level,str) for level in levels):raise ValueError('invalid reasoning declaration')
        name=row.get('name',row['id'])
        if not isinstance(name,str) or not name.strip() or len(name)>200 or any(ord(char)<32 for char in name):name=row['id']
        models.append({'id':row['id'],'name':name[:100],'input_media':inputs,'declared_roles':roles,
            'allowed_roles':sorted(set(roles)&set(provider['roles'])) if streaming is True and protocols and not media_only else [],
            'protocols':protocols,'streaming':streaming,'reasoning_levels':sorted(set(levels)&REASONING),
            'tools':tools.get('supported') if type(tools.get('supported')) is bool else None,'analysis_reason':'media_endpoint_required' if media_only else None,'inference_verified':False})
    return sorted(models,key=lambda row:row['id'])


class ProviderCatalog:
    cache_seconds=60
    def __init__(self,settings,http):
        self.settings=settings;self.http=http;self.cache={};self.active=set();self.slots=asyncio.Semaphore(2)

    def _input(self,identity):
        if not self.settings.providers_file:raise CatalogRejected(404,'model_catalog_not_configured')
        with Path(self.settings.providers_file).open('rb') as source:raw=source.read(65537)
        if len(raw)>65536:raise ValueError('registry exceeds bound')
        registry=json.loads(raw)
        if not isinstance(registry,dict) or registry.keys()!={'schema_version','providers'} or registry['schema_version']!='model-providers-v1' or not isinstance(registry['providers'],list) or len(registry['providers'])>16:raise ValueError('invalid registry')
        matches=[value for value in registry['providers'] if isinstance(value,dict) and value.get('id')==identity]
        if len(matches)!=1:raise CatalogRejected(404,'model_provider_unavailable')
        provider=matches[0]
        if provider.keys()!={'id','label','base_url','credential_file','protocols','roles','activation_allowed'} or type(provider['activation_allowed']) is not bool:raise ValueError('invalid provider')
        if not provider['activation_allowed']:raise CatalogRejected(409,'model_activation_forbidden')
        for field,allowed in (('protocols',PROTOCOLS),('roles',ROLES)):
            if not isinstance(provider[field],list) or not provider[field] or any(not isinstance(v,str) for v in provider[field]) or not set(provider[field])<=allowed or len(provider[field])!=len(set(provider[field])):raise ValueError('invalid provider policy')
        uri=urlsplit(provider['base_url'])
        if uri.scheme!='https' or not uri.hostname or any((uri.username,uri.password,uri.query,uri.fragment)) or provider['base_url'].endswith('/') or uri.port is not None and not 1<=uri.port<=65535:raise ValueError('invalid provider endpoint')
        key=read_credential(self.settings.credentials_root,provider['credential_file'])
        fingerprint=configuration_sha(provider)+':'+hashlib.sha256(key.encode()).hexdigest()
        return provider,key,fingerprint

    @staticmethod
    def _result(provider,state,models,diagnostics=None):
        return {'schema_version':'provider-model-catalog-v1','provider':provider['id'],'configuration_sha256':configuration_sha(provider),
            'state':state,'checked_at':datetime.now(timezone.utc).isoformat(),'cached':False,'models':models,
            'catalog_sha256':hashlib.sha256(encoded(models)).hexdigest(),'diagnostics':diagnostics,'inference_verified':False}

    async def read(self,identity,refresh=False):
        if not isinstance(identity,str) or not re.fullmatch('[a-z][a-z0-9_-]{0,79}',identity):raise CatalogRejected(422,'invalid_model_provider')
        try:provider,key,fingerprint=await asyncio.to_thread(self._input,identity)
        except CatalogRejected:raise
        except (OSError,ValueError,TypeError,KeyError):raise CatalogRejected(503,'model_catalog_configuration_unavailable') from None
        saved=self.cache.get(identity)
        if not refresh and saved and saved['fingerprint']==fingerprint and time.monotonic()-saved['time']<self.cache_seconds:
            result=copy.deepcopy(saved['value']);result['cached']=True;return result
        if identity in self.active or self.slots.locked():return self._result(provider,'unavailable',[],{'category':'catalog_busy'})
        self.active.add(identity)
        try:
            async with self.slots,asyncio.timeout(22):
                async with self.http.stream('GET',provider['base_url']+'/models',headers={'Authorization':'Bearer '+key},timeout=20) as response:
                    raw=bytearray();diagnostics={'category':'catalog_unavailable','upstream_status':response.status_code}
                    request_id=response.headers.get('x-request-id','')
                    if re.fullmatch('[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}',request_id):diagnostics['upstream_request_id']=request_id
                    async for chunk in response.aiter_bytes():
                        raw.extend(chunk)
                        if len(raw)>MAX_RESPONSE:raise ValueError('catalog exceeds bound')
                    if response.status_code!=200:
                        try:value=json.loads(raw)
                        except ValueError:value=None
                        diagnostics['category']='credential_rejected' if response.status_code in {401,403} else 'catalog_rate_limited' if response.status_code==429 else 'catalog_not_found' if response.status_code==404 else 'catalog_unavailable'
                        code=upstream_code(value)
                        if code:diagnostics['upstream_error_code']=code
                        _,_,after=await asyncio.to_thread(self._input,identity)
                        if after!=fingerprint:return self._result(provider,'unavailable',[],{'category':'configuration_changed'})
                        return self._result(provider,'unavailable',[],diagnostics)
                    value=json.loads(raw)
                    models=normalize_models(value,provider)
                _,_,after=await asyncio.to_thread(self._input,identity)
                if after!=fingerprint:return self._result(provider,'unavailable',[],{'category':'configuration_changed'})
                result=self._result(provider,'ready',models)
                self.cache[identity]={'fingerprint':fingerprint,'time':time.monotonic(),'value':copy.deepcopy(result)}
                return result
        except CatalogRejected:return self._result(provider,'unavailable',[],{'category':'configuration_changed'})
        except (httpx.TimeoutException,TimeoutError):return self._result(provider,'unavailable',[],{'category':'catalog_timeout'})
        except httpx.HTTPError:return self._result(provider,'unavailable',[],{'category':'catalog_network_unavailable'})
        except (ValueError,KeyError,TypeError):return self._result(provider,'unavailable',[],{'category':'catalog_invalid'})
        except OSError:return self._result(provider,'unavailable',[],{'category':'credential_unavailable'})
        finally:self.active.discard(identity)
