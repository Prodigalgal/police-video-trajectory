"""Bounded session-to-Core adapter shared by the new application modules."""
import json
import httpx
from fastapi import HTTPException
from fastapi.responses import JSONResponse


class BusinessProxy:
    def __init__(self,app,cfg,authenticated,origin_ok,feature='investigation'):
        self.app=app;self.cfg=cfg;self.authenticated=authenticated;self.origin_ok=origin_ok;self.feature=feature

    def authorize(self,request,*,write=False,admin=False):
        if write:self.origin_ok(request)
        if not self.authenticated(request):raise HTTPException(401,'请先登录')
        if not all(self.cfg.get(key) for key in (self.feature,'account_directory','admin_token')):raise HTTPException(404,'该业务模块尚未启用')
        account=request.state.account
        if admin and not account.get('is_admin'):raise HTTPException(403,'需要管理员权限')
        return account

    async def call(self,request,method,path,*,body=None,params=None,admin=False,timeout=60):
        account=self.authorize(request,write=method!='GET',admin=admin)
        try:
            async with self.app.state.http.stream(method,self.cfg['core_url'].rstrip('/')+path,json=body,params=params,
                headers={'Authorization':'Bearer '+self.cfg['admin_token'],'X-Actor-Id':account['username'],'X-Actor-Revision':str(account['credential_revision'])},timeout=timeout) as response:
                raw=bytearray()
                async for chunk in response.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw)>2*1024*1024:raise ValueError('bounded business response required')
                value=json.loads(raw)
                if not isinstance(value,dict):raise ValueError('business object required')
                status=response.status_code if response.status_code in {200,400,401,403,404,409,410,422,429} else 503
                if status!=200:
                    code=value.get('code','business_unavailable')
                    if not isinstance(code,str) or len(code)>80 or not all(c.islower() or c=='_' for c in code):code='business_unavailable'
                    value={'code':code}
                return JSONResponse(value,status_code=status,headers={'Cache-Control':'no-store'})
        except (httpx.HTTPError,ValueError,TypeError):raise HTTPException(503,'业务服务暂不可用，请保留原请求重试') from None
