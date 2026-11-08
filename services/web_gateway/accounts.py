"""Small account directory: PostgreSQL remains authoritative for each session."""
import ssl
import httpx
from fastapi import HTTPException
from .password_auth import _parse_hash


class AccountDirectory:
    def __init__(self,cfg,transport=None):
        self.cfg,self.transport=cfg,transport

    def _headers(self): return {'Authorization':'Bearer '+self.cfg['admin_token']}

    def credential(self,username):
        try:
            verify=ssl.create_default_context(cafile=self.cfg['core_ca']) if self.transport is None else True
            with httpx.Client(transport=self.transport,verify=verify,trust_env=False,follow_redirects=False,timeout=10) as client:
                response=client.post(self.cfg['core_url'].rstrip('/')+'/internal/auth/credential',headers=self._headers(),json={'username':username})
                if response.status_code in {400,404}: return None
                if response.status_code!=200 or len(response.content)>16384: raise ValueError()
                value=response.json()
                if (not isinstance(value,dict) or value.get('username')!=(username or '').strip().lower()
                        or type(value.get('credential_revision')) is not int or value['credential_revision']<1
                        or type(value.get('is_admin')) is not bool): raise ValueError()
                _parse_hash(value.get('password_hash'))
                return value
        except (httpx.HTTPError,ValueError,TypeError,KeyError):
            raise HTTPException(503,'账号服务暂不可用，请稍后重试') from None

    async def status(self,http,session):
        try:
            response=await http.post(self.cfg['core_url'].rstrip('/')+'/internal/auth/status',headers=self._headers(),
                json={'username':session['sub'],'revision':session['credential_revision']},timeout=10)
            if response.status_code!=200 or len(response.content)>16384: raise ValueError()
            value=response.json()
            if not isinstance(value,dict) or type(value.get('valid')) is not bool: raise ValueError()
            if not value['valid']: return None
            if (value.get('username')!=session['sub'] or value.get('credential_revision')!=session['credential_revision']
                    or type(value.get('credential_revision')) is not int or type(value.get('is_admin')) is not bool): raise ValueError()
            return {key:value[key] for key in ('username','display_name','is_admin','credential_revision')}
        except (httpx.HTTPError,ValueError,TypeError,KeyError):
            raise HTTPException(503,'账号验证服务暂不可用，请稍后重试') from None
