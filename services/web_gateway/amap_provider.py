"""AMap JS API adapter. Only the public JS key reaches the browser; jscode stays server-side."""
import json
from pathlib import Path
import re
import httpx
from fastapi import Request,HTTPException
from fastapi.responses import JSONResponse,Response


class AmapConfiguration:
    def __init__(self,cfg):self.path=cfg.get('amap_config_file','');self.enabled=bool(cfg.get('investigation'))
    def current(self):
        if not self.enabled or not self.path:return None
        try:
            with Path(self.path).open('rb') as stream:raw=stream.read(8193)
            if not raw or len(raw)>8192:return None
            value=json.loads(raw)
            if value.get('provider')!='amap' or value.get('version')!=1:return None
            if not all(isinstance(value.get(key),str) and re.fullmatch('[A-Za-z0-9_-]{16,128}',value[key]) for key in ('js_api_key','security_js_code')):return None
            return value
        except (OSError,ValueError,TypeError,AttributeError):return None


def mount_amap(app,cfg,authenticated,origin_ok):
    configuration=AmapConfiguration(cfg)

    def authorize(request,admin=False):
        if not authenticated(request):raise HTTPException(401,'请先登录')
        if admin and not getattr(request.state,'account',{}).get('is_admin'):raise HTTPException(403,'需要管理员权限')

    def public_config():
        current=configuration.current()
        return {'provider':'amap','configured':current is not None,'js_api_key':current['js_api_key'] if current else None,'sdk_version':'2.0','service_host':'/_AMapService','coordinate_system':'GCJ-02'}

    @app.get('/api/v1/maps/config')
    async def config(request:Request):
        authorize(request)
        return JSONResponse(public_config(),headers={'Cache-Control':'no-store'})

    @app.get('/api/admin/map-provider')
    async def status(request:Request):
        authorize(request,True);value=public_config();value.pop('js_api_key')
        return JSONResponse(value,headers={'Cache-Control':'no-store'})

    @app.get('/_AMapService/{path:path}')
    async def proxy(path:str,request:Request):
        authorize(request);current=configuration.current()
        if current is None:raise HTTPException(503,'地图服务尚未配置')
        if len(path)>200 or '..' in path or not re.fullmatch(r'(v4/map/styles(?:/[A-Za-z0-9_-]+)?|v3/(?:geocode/(?:geo|regeo)|assistant/coordinate/convert|config/district|direction/(?:driving|walking|bicycling)|place/(?:text|around|polygon|detail)|assistant/inputtips))',path):raise HTTPException(404,'地图服务接口不可用')
        if len(request.url.query)>4096 or len(request.query_params)>40:raise HTTPException(400,'地图请求过大')
        params=list(request.query_params.multi_items())
        if any(name.lower()=='jscode' for name,_ in params):raise HTTPException(400,'地图安全参数不可覆盖')
        supplied=[value for key,value in params if key=='key']
        if supplied and supplied!=[current['js_api_key']]:raise HTTPException(400,'地图配置已变化，请刷新页面')
        params=[(key,value) for key,value in params if key!='key']+[('key',current['js_api_key']),('jscode',current['security_js_code'])]
        host='https://webapi.amap.com/' if path.startswith('v4/') else 'https://restapi.amap.com/'
        try:
            async with app.state.map_http.stream('GET',host+path,params=params,headers={'Referer':cfg['origin'].rstrip('/')+'/'}) as upstream:
                raw=bytearray()
                async for chunk in upstream.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw)>4*1024*1024:raise ValueError('bounded map reply required')
                if upstream.status_code!=200:raise HTTPException(503,'地图服务暂不可用')
                if current['security_js_code'].encode() in raw:raise ValueError('map response echoed security material')
                content_type=upstream.headers.get('content-type','application/json')
                if not any(kind in content_type for kind in ('json','javascript','text/plain','octet-stream')):raise ValueError('unexpected map content')
                return Response(bytes(raw),headers={'Content-Type':content_type,'Cache-Control':'private, max-age=60'})
        except (httpx.HTTPError,ValueError):raise HTTPException(503,'地图服务暂不可用') from None
