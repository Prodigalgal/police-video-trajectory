"""Pre-login state shared through the existing authenticated Core service."""
import hashlib
import hmac
import re
import ssl
from urllib.parse import urlsplit
import httpx
from fastapi import HTTPException


class SharedLoginStore:
    def __init__(self,cfg,transport=None):
        endpoint=urlsplit(cfg.get('core_url',''))
        if (endpoint.scheme!='https' or not endpoint.hostname or any((endpoint.username,endpoint.password,endpoint.query,endpoint.fragment))
                or not cfg.get('admin_token') or len(cfg.get('session_secret',''))<32):
            raise ValueError('Shared login requires Core and a common signing secret')
        self.cfg,self.transport=cfg,transport
        self.secret=cfg['session_secret'].encode()

    def digest(self,kind,value):
        return hmac.new(self.secret,('cluster-login-v1\0'+kind+'\0'+value).encode(),hashlib.sha256).hexdigest()

    def call(self,operation,body):
        try:
            verify=ssl.create_default_context(cafile=self.cfg['core_ca']) if self.transport is None else True
            with httpx.Client(transport=self.transport,verify=verify,trust_env=False,follow_redirects=False,timeout=5) as client:
                response=client.post(self.cfg['core_url'].rstrip('/')+'/internal/cluster/login/'+operation,
                    headers={'Authorization':'Bearer '+self.cfg['admin_token']},json=body)
                if response.status_code==429:raise HTTPException(429,'尝试次数过多，请稍后再试')
                if response.status_code!=200 or len(response.content)>4096:raise ValueError('Invalid coordination response')
                result=response.json()
                if not isinstance(result,dict):raise ValueError('Invalid coordination response')
                return result
        except (httpx.HTTPError,ValueError,TypeError,KeyError,OSError):
            raise HTTPException(503,'登录验证服务暂不可用，请稍后重试') from None

    def issue(self,identity,peer,payload):
        if self.call('issue',{'challengeId':identity,'peerKey':self.digest('issue',peer),'payload':payload}).get('accepted') is not True:
            raise HTTPException(503,'登录验证服务暂不可用，请稍后重试')

    def consume(self,identity,failure_key):
        value=self.call('consume',{'challengeId':identity,'failureKey':self.digest('login',failure_key)})
        if value.get('available') is False:return None
        payload=value.get('payload')
        if (value.get('available') is not True or not isinstance(payload,dict)
                or payload.keys()!={'answer_digest','cookie','revision','bits'} or type(payload['bits']) is not int or not 16<=payload['bits']<=22
                or any(not isinstance(payload[key],str) or not re.fullmatch('[a-f0-9]{64}',payload[key]) for key in ('answer_digest','cookie','revision'))):
            raise HTTPException(503,'登录验证服务返回异常，请稍后重试')
        return payload

    def clear(self,failure_key):
        if self.call('clear',{'failureKey':self.digest('login',failure_key)}).get('cleared') is not True:
            raise HTTPException(503,'登录验证服务暂不可用，请稍后重试')

    def ready(self):
        try:
            verify=ssl.create_default_context(cafile=self.cfg['core_ca']) if self.transport is None else True
            with httpx.Client(transport=self.transport,verify=verify,trust_env=False,follow_redirects=False,timeout=3) as client:
                response=client.get(self.cfg['core_url'].rstrip('/')+'/internal/cluster/readyz')
                return response.status_code==200 and len(response.content)<=2048 and response.json().get('ready') is True
        except (httpx.HTTPError,ValueError,TypeError,AttributeError,OSError):return False
