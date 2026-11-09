from collections import defaultdict, deque
from contextlib import asynccontextmanager
import asyncio
import base64
import hashlib
import hmac
import json
import os
from pathlib import Path
import re
import secrets
import ssl
import threading
import time
from urllib.parse import urlsplit
from uuid import UUID

import httpx
from fastapi import FastAPI, HTTPException, Request, Response
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse, StreamingResponse, FileResponse
from fastapi.staticfiles import StaticFiles
from pydantic import BaseModel, ConfigDict, Field
from .password_auth import PasswordAuth
from .search_contracts import Search,CloseSearch
from . import __version__


COOKIE = "pv_session"
SESSION_SECONDS = 8 * 3600


class Login(BaseModel):
    model_config = ConfigDict(extra="forbid")
    token: str | None = Field(default=None, min_length=1, max_length=512, repr=False)
    username: str | None = Field(default=None, min_length=1, max_length=80)
    password: str | None = Field(default=None, min_length=1, max_length=128, repr=False)
    challenge_id: str | None = Field(default=None, min_length=32, max_length=64, repr=False)
    captcha_answer: str | None = Field(default=None, min_length=1, max_length=8, repr=False)
    pow_nonce: str | None = Field(default=None, min_length=1, max_length=20, repr=False)


def _b64(data):
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode("ascii")


def create_app(config=None, transport=None):
    cfg = config or {
        "access_token": os.getenv("WEB_ACCESS_TOKEN", ""),
        "auth_mode": os.getenv("WEB_AUTH_MODE", "token"),
        "username": os.getenv("WEB_USERNAME", ""),
        "password_hash": os.getenv("WEB_PASSWORD_HASH", ""),
        "pow_bits": int(os.getenv("WEB_POW_BITS", "18")),
        "trust_cf": os.getenv("WEB_TRUST_CF_HEADERS", "false") == "true",
        "session_secret": os.getenv("WEB_SESSION_SECRET", ""),
        "core_token": os.getenv("CORE_SEARCH_TOKEN", ""),
        "admin_token":os.getenv("CORE_ADMIN_TOKEN",""),
        "account_directory":os.getenv("WEB_ACCOUNT_DIRECTORY","false")=="true",
        "clue_collections":os.getenv("WEB_CLUE_COLLECTIONS_ENABLED","false")=="true",
        "analysis_inputs":os.getenv("WEB_ANALYSIS_INPUTS","false")=="true",
        "media_clips":os.getenv("WEB_MEDIA_CLIPS","false")=="true",
        "media_jobs":os.getenv("WEB_MEDIA_JOBS","false")=="true",
        "specialist_jobs":os.getenv("WEB_SPECIALIST_JOBS","false")=="true",
        "investigation":os.getenv("WEB_INVESTIGATION_ENABLED","false")=="true",
        "ingestion_jobs":os.getenv("WEB_INGEST_JOBS_ENABLED","false")=="true",
        "exports":os.getenv("WEB_EXPORTS_ENABLED","false")=="true",
        "appearance":os.getenv("WEB_APPEARANCE_ENABLED","false")=="true",
        "live_view":os.getenv("WEB_LIVE_VIEW_ENABLED","false")=="true",
        "recording_review":os.getenv("WEB_RECORDING_REVIEW_ENABLED","false")=="true",
        "live_media_url":os.getenv("WEB_LIVE_MEDIA_URL",""),
        "live_media_ca":os.getenv("WEB_LIVE_MEDIA_CA_FILE",""),
        "live_media_user":os.getenv("WEB_LIVE_MEDIA_USERNAME",""),
        "live_media_password":os.getenv("WEB_LIVE_MEDIA_PASSWORD",""),
        "live_media_bearer":os.getenv("WEB_LIVE_MEDIA_BEARER",""),
        "live_camera_paths":os.getenv("WEB_LIVE_CAMERA_PATHS_JSON","{}"),
        "appearance_catalog_file":os.getenv("APPEARANCE_CATALOG_FILE","/appearance-catalog/catalog.json"),
        "appearance_model_dir":os.getenv("APPEARANCE_MODEL_DIR","/appearance-models"),
        "amap_config_file":os.getenv("WEB_AMAP_CONFIG_FILE","/map-provider/amap.json"),
        "model_catalog_dir":os.getenv("MODEL_CATALOG_DIR","/model-catalog"),
        "active_models":os.getenv("ACTIVE_MODELS_JSON","{}"),
        "core_url": os.getenv("CORE_URL", ""),
        "core_ca": os.getenv("CORE_CA_FILE", ""),
        "origin": os.getenv("WEB_ALLOWED_ORIGIN", "https://localhost:8445"),
        "static_dir": os.getenv("WEB_STATIC_DIR", "/app/web/dist"),
        "evaluation_root":os.getenv('EVALUATION_DATASET_ROOT',''),
        "annotations_db":os.getenv('EVALUATION_ANNOTATIONS_DB','/evaluation-data/annotations/labels-v1.sqlite3'),
        "recordings_dir":os.getenv('EVALUATION_RECORDINGS_DIR','/recordings'),
        "region_root":os.getenv('EVALUATION_REGION_ROOT',''),
        "region_reference":os.getenv('EVALUATION_REGION_REFERENCE',''),
        "gate_comparison_file":os.getenv('EVALUATION_GATE_COMPARISON_FILE',''),
    }
    failures = defaultdict(deque)
    failure_lock = threading.Lock()
    auth_mode = cfg.get('auth_mode', 'token')
    multi_accounts=cfg.get('account_directory',False)
    from .accounts import AccountDirectory
    account_directory=AccountDirectory(cfg,transport) if multi_accounts else None
    password_auth = None
    if auth_mode == 'password':
        try:
            password_auth = PasswordAuth(cfg.get('username'), cfg.get('password_hash'), bits=cfg.get('pow_bits',18), trust_cf=cfg.get('trust_cf',False))
        except ValueError:
            pass

    def configured():
        try:
            parsed = urlsplit(cfg["core_url"])
            credentials_ok = password_auth is not None if auth_mode == 'password' else auth_mode == 'token' and bool(cfg.get('access_token'))
            if multi_accounts: credentials_ok=credentials_ok and auth_mode=='password' and bool(cfg.get('admin_token'))
            return bool(credentials_ok and cfg["session_secret"] and cfg["core_token"] and
                        parsed.scheme == "https" and parsed.hostname and not any((parsed.username, parsed.password, parsed.query, parsed.fragment)) and
                        (transport is not None or Path(cfg["core_ca"]).is_file()))
        except (KeyError, ValueError):
            return False

    @asynccontextmanager
    async def lifespan(app):
        from .live_api import LiveMedia
        app.state.live_media=LiveMedia(cfg,transport)
        verify = ssl.create_default_context(cafile=cfg["core_ca"]) if configured() and transport is None else True
        async with httpx.AsyncClient(transport=transport, timeout=370, verify=verify, trust_env=False, follow_redirects=False) as client, httpx.AsyncClient(timeout=15,trust_env=False,follow_redirects=False) as map_client, app.state.live_media.lifespan():
            app.state.http = client
            app.state.map_http = map_client
            try:yield
            finally:
                runner=getattr(app.state,'appearance_runner',None)
                if runner is not None:await runner.close()

    app = FastAPI(title="Trial Search", version=__version__, docs_url=None, redoc_url=None, openapi_url=None, lifespan=lifespan)

    @app.exception_handler(RequestValidationError)
    async def validation_error(_request: Request, _exc: RequestValidationError):
        return JSONResponse({"detail": "请求参数无效"}, status_code=422)

    @app.middleware("http")
    async def headers(request, call_next):
        request.state.account=None
        try:
            exempt=request.url.path in {'/api/auth/config','/api/auth/challenge'} or request.url.path=='/api/session' and request.method in {'POST','DELETE'}
            if multi_accounts and not exempt and (request.url.path.startswith(('/api/','/_AMapService/')) or request.url.path=='/_maps/frame') and configured():
                session_data=decoded_session(request)
                if session_data is not None:
                    request.state.account=await account_directory.status(request.app.state.http,session_data)
            response = await call_next(request)
        except HTTPException as error:
            response=JSONResponse({'detail':str(error.detail)},status_code=error.status_code)
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["X-Frame-Options"] = "DENY"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Content-Security-Policy"] = "default-src 'self'; img-src 'self' data: blob:; media-src 'self' blob:; style-src 'self'; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        if cfg.get('investigation') and request.url.path.rstrip('/') in {'/clues','/admin'}:
            response.headers['Content-Security-Policy']="default-src 'self'; script-src 'self' https://webapi.amap.com https://*.amap.com; img-src 'self' data: blob: https://*.amap.com https://*.autonavi.com https://*.amap.cn; connect-src 'self' https://*.amap.com https://*.autonavi.com https://*.amap.cn; style-src 'self' 'unsafe-inline'; worker-src 'self' blob:; object-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'"
        if cfg.get('investigation') and request.url.path=='/_maps/frame':
            response.headers['X-Frame-Options']='SAMEORIGIN'
            response.headers['Referrer-Policy']='strict-origin-when-cross-origin'
            response.headers['Content-Security-Policy']="default-src 'self'; script-src 'self' 'unsafe-eval' https://webapi.amap.com https://*.amap.com; img-src 'self' data: blob: https://*.amap.com https://*.autonavi.com https://*.amap.cn; connect-src 'self' https://*.amap.com https://*.autonavi.com https://*.amap.cn; style-src 'self' 'unsafe-inline'; worker-src 'self' blob:; object-src 'none'; frame-ancestors 'self'; base-uri 'none'; form-action 'none'"
        response.headers.setdefault("Cache-Control", "no-store")
        return response

    def origin_ok(request: Request):
        if request.headers.get("origin") != cfg["origin"]:
            raise HTTPException(403, "请求来源无效")

    def signed_session(account=None):
        data = {"exp": int(time.time()) + SESSION_SECONDS, "nonce": secrets.token_hex(16), 'auth_mode':auth_mode}
        if password_auth is not None:data['revision']=password_auth.revision
        if account is not None:
            data.update(sub=account['username'],credential_revision=account['credential_revision'])
        payload = _b64(json.dumps(data, separators=(",", ":")).encode())
        signature = _b64(hmac.new(cfg["session_secret"].encode(), payload.encode(), hashlib.sha256).digest())
        return payload + "." + signature

    def decoded_session(request: Request):
        value = request.cookies.get(COOKIE, "")
        if not configured() or len(value) > 1024 or value.count(".") != 1:
            return None
        payload, signature = value.split(".", 1)
        expected = _b64(hmac.new(cfg["session_secret"].encode(), payload.encode(), hashlib.sha256).digest())
        if not hmac.compare_digest(signature.encode(), expected.encode()):
            return None
        try:
            data = json.loads(base64.urlsafe_b64decode(payload + "=" * (-len(payload) % 4)))
            correct_mode = data.get('auth_mode','token') == auth_mode
            correct_revision = multi_accounts or password_auth is None or data.get('revision') == password_auth.revision
            valid=correct_mode and correct_revision and isinstance(data.get("exp"), int) and int(time.time()) < data["exp"] <= int(time.time()) + SESSION_SECONDS
            if multi_accounts:
                valid=valid and isinstance(data.get('sub'),str) and bool(re.fullmatch(r'[a-z0-9_.@-]{3,80}',data['sub'])) and type(data.get('credential_revision')) is int and data['credential_revision']>0
            return data if valid else None
        except (ValueError, TypeError, AttributeError):
            return None

    def authenticated(request:Request):
        return bool(getattr(request.state,'account',None)) if multi_accounts else decoded_session(request) is not None

    @app.get("/healthz")
    def health():
        return {"status": "ok"}

    @app.get("/readyz")
    def ready():
        return JSONResponse({"ready": configured()}, status_code=200 if configured() else 503)

    @app.get("/api/session")
    def session(request: Request):
        if not authenticated(request):return {'authenticated':False}
        if multi_accounts:
            account=request.state.account
            return {'authenticated':True,'username':account['username'],'display_name':account['display_name'],'is_admin':account['is_admin']}
        return {'authenticated':True}

    @app.get('/api/auth/config')
    def auth_config():
        return {'mode': auth_mode}

    @app.get('/api/auth/challenge')
    def auth_challenge(request: Request, response: Response):
        if not configured() or password_auth is None:raise HTTPException(503,'登录服务暂不可用')
        return password_auth.issue(request,response)

    @app.post("/api/session")
    def login(body: Login, request: Request, response: Response):
        origin_ok(request)
        if not configured():
            raise HTTPException(503, "服务暂不可用")
        if password_auth is not None:
            account=password_auth.login(body, request, account_directory.credential if multi_accounts else None)
            response.set_cookie(COOKIE, signed_session(account if multi_accounts else None), max_age=SESSION_SECONDS, secure=True, httponly=True, samesite="strict", path="/")
            return {'authenticated':True,'username':account['username'],'is_admin':account['is_admin']} if multi_accounts else {'authenticated':True}
        peer = request.client.host if request.client else "unknown"
        now = time.monotonic()
        with failure_lock:
            if len(failures) >= 2048 and peer not in failures:
                for key in list(failures):
                    if not failures[key] or failures[key][-1] <= now - 60:
                        failures.pop(key, None)
                if len(failures) >= 2048:
                    raise HTTPException(429, "尝试次数过多，请稍后再试")
            history = failures[peer]
            while history and history[0] <= now - 60:
                history.popleft()
            if len(history) >= 5:
                raise HTTPException(429, "尝试次数过多，请稍后再试")
            if not hmac.compare_digest((body.token or '').encode(), cfg["access_token"].encode()):
                history.append(now)
                raise HTTPException(401, "访问令牌无效")
            failures.pop(peer, None)
        response.set_cookie(COOKIE, signed_session(), max_age=SESSION_SECONDS, secure=True, httponly=True, samesite="strict", path="/")
        return {"authenticated": True}

    @app.delete("/api/session")
    def logout(request: Request, response: Response):
        origin_ok(request)
        response.delete_cookie(COOKIE, secure=True, httponly=True, samesite="strict", path="/")
        return {"authenticated": False}

    @app.post("/api/v1/search")
    async def search(body: Search, request: Request):
        origin_ok(request)
        if not authenticated(request):
            raise HTTPException(401, "请先登录")
        query = body.query.strip()
        if not query:
            raise HTTPException(422, "请输入搜索内容")
        async def forward():
            payload={"query":query,"pageSize":body.page_size,"cursor":body.cursor}
            if body.mode=='keyword':payload['mode']='keyword'
            if body.adjustment is not None:
                payload['adjustment']={'context':body.adjustment.context,'action':body.adjustment.action,'condition':body.adjustment.condition,'cameraIds':body.adjustment.camera_ids}
            async with request.app.state.http.stream("POST", cfg["core_url"].rstrip("/") + "/api/v1/search",
                    headers={"Authorization": "Bearer " + cfg["core_token"]}, json=payload) as upstream:
                raw = bytearray()
                async for chunk in upstream.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw) > 2 * 1024 * 1024:
                        raise ValueError("response too large")
                result = json.loads(raw)
                if not isinstance(result, dict):
                    raise ValueError("invalid result")
                if not 200 <= upstream.status_code < 300:
                    code = result.get("code")
                    safe = {"detail": "检索依赖暂不可用"}
                    recoverable={"search_cursor_expired","invalid_search_cursor","search_context_expired","invalid_search_context","invalid_search_adjustment","search_scope_changed"}
                    if isinstance(code, str) and code in {"model_unavailable", "invalid_model_plan", "invalid_search_scope", "search_backend_unavailable"}|recoverable:
                        safe["code"] = code
                    return JSONResponse(safe, status_code=upstream.status_code if code in recoverable else 502)
                return JSONResponse(result, status_code=upstream.status_code)
        async def bounded():
            try:
                return await asyncio.wait_for(forward(), timeout=370)
            except (httpx.HTTPError, ValueError, TypeError, TimeoutError):
                return JSONResponse({'detail':'搜索服务暂不可用，请稍后重试'},status_code=503)
        if 'text/event-stream' in request.headers.get('accept',''):
            async def events():
                task=asyncio.create_task(bounded())
                try:
                    yield 'event: pending\ndata: {}\n\n'
                    while not task.done():
                        done,_=await asyncio.wait({task},timeout=15)
                        if not done:yield ': waiting\n\n'
                    result=task.result()
                    event='result' if result.status_code<300 else 'error'
                    yield 'event: '+event+'\ndata: '+result.body.decode()+'\n\n'
                finally:
                    if not task.done():task.cancel()
                    await asyncio.gather(task,return_exceptions=True)
            return StreamingResponse(events(),media_type='text/event-stream',headers={'Cache-Control':'no-store, no-transform','X-Accel-Buffering':'no'})
        return await bounded()

    @app.post("/api/v1/search/close")
    async def close_search(body: CloseSearch, request: Request):
        origin_ok(request)
        if not authenticated(request):
            raise HTTPException(401, "请先登录")
        try:
            upstream=await request.app.state.http.post(cfg["core_url"].rstrip("/")+"/api/v1/search/close",
                headers={"Authorization":"Bearer "+cfg["core_token"]},json={"query":body.query.strip(),"cursor":body.cursor},timeout=15)
            if upstream.status_code==200:
                return {"closed":True}
            return JSONResponse({"detail":"搜索会话已结束"},status_code=upstream.status_code if upstream.status_code in {400,410} else 502)
        except httpx.HTTPError:
            raise HTTPException(503,"搜索会话释放暂不可用") from None

    @app.get("/api/v1/coverage")
    async def coverage(request: Request):
        if not authenticated(request):
            raise HTTPException(401, "请先登录")
        try:
            async with request.app.state.http.stream("GET", cfg["core_url"].rstrip("/") + "/api/v1/coverage",
                    headers={"Authorization": "Bearer " + cfg["core_token"]}, timeout=30) as upstream:
                if upstream.status_code != 200:
                    raise HTTPException(502, "状态暂不可用")
                raw = bytearray()
                async for chunk in upstream.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw) > 128 * 1024:
                        raise ValueError("status response too large")
                data = json.loads(raw)
                if not isinstance(data, dict):
                    raise ValueError("invalid status response")
                return JSONResponse(data)
        except (httpx.HTTPError, ValueError, TypeError):
            raise HTTPException(503, "状态暂不可用") from None

    @app.get("/api/v1/observations/{observation_id}")
    async def observation(observation_id: UUID, request: Request):
        if not authenticated(request):
            raise HTTPException(401,"请先登录")
        try:
            async with request.app.state.http.stream("GET",cfg["core_url"].rstrip("/")+f"/api/v1/observations/{observation_id}",
                    headers={"Authorization":"Bearer "+cfg["core_token"]},timeout=30) as upstream:
                if upstream.status_code==404:
                    raise HTTPException(404,"该线索已不可用，请重新搜索")
                if upstream.status_code!=200:
                    raise HTTPException(502,"线索详情暂不可用")
                raw=bytearray()
                async for chunk in upstream.aiter_bytes():
                    raw.extend(chunk)
                    if len(raw)>128*1024:
                        raise ValueError("evidence details exceed bound")
                data=json.loads(raw)
                if not isinstance(data,dict) or data.get('observation_id')!=str(observation_id):
                    raise ValueError("evidence identity mismatch")
                return JSONResponse(data,headers={'Cache-Control':'no-store'})
        except (httpx.HTTPError,ValueError,TypeError):
            raise HTTPException(503,"线索详情暂不可用") from None

    @app.get("/api/v1/frames/{frame_id}/image")
    async def image(frame_id: UUID, request: Request):
        if not authenticated(request):
            raise HTTPException(401, "请先登录")
        try:
            async with request.app.state.http.stream("GET", cfg["core_url"].rstrip("/") + f"/api/v1/frames/{frame_id}/image",
                    headers={"Authorization": "Bearer " + cfg["core_token"]}, timeout=30) as upstream:
                if upstream.status_code == 404:
                    raise HTTPException(404, "关键帧不可用")
                if upstream.status_code != 200:
                    raise HTTPException(502, "关键帧服务暂不可用")
                data = bytearray()
                async for chunk in upstream.aiter_bytes():
                    data.extend(chunk)
                    if len(data) > 8 * 1024 * 1024:
                        raise HTTPException(502, "关键帧服务暂不可用")
                if not data.startswith(b"\xff\xd8\xff"):
                    raise HTTPException(502, "关键帧服务暂不可用")
                return Response(bytes(data), media_type="image/jpeg")
        except httpx.HTTPError:
            raise HTTPException(503, "关键帧服务暂不可用") from None

    @app.get("/api/v1/frames/{frame_id}/archive")
    async def archive(frame_id: UUID, request: Request):
        if not authenticated(request):
            raise HTTPException(401, "请先登录")
        requested_range = request.headers.get("range")
        if requested_range and (len(requested_range) > 100 or not re.fullmatch(r"bytes=\d*-\d*", requested_range)):
            raise HTTPException(416, "录像范围无效")
        headers = {"Authorization": "Bearer " + cfg["core_token"]}
        if requested_range:
            headers["Range"] = requested_range
        try:
            upstream_request = request.app.state.http.build_request("GET", cfg["core_url"].rstrip("/") + f"/api/v1/frames/{frame_id}/archive", headers=headers)
            upstream = await request.app.state.http.send(upstream_request, stream=True)
            if upstream.status_code not in (200, 206):
                status = upstream.status_code
                await upstream.aclose()
                if status in (404, 416):
                    raise HTTPException(status, "录像不可用")
                raise HTTPException(502, "录像服务暂不可用")
            response_headers = {"Accept-Ranges": "bytes"}
            for name in ("content-length", "content-range"):
                if name in upstream.headers:
                    response_headers[name] = upstream.headers[name]
            async def chunks():
                try:
                    async for chunk in upstream.aiter_bytes():
                        yield chunk
                finally:
                    await upstream.aclose()
            return StreamingResponse(chunks(), status_code=upstream.status_code, headers=response_headers, media_type="video/mp4")
        except httpx.HTTPError:
            raise HTTPException(503, "录像服务暂不可用") from None

    from .admin_api import mount_admin
    mount_admin(app,cfg,authenticated,origin_ok)
    from .clue_api import mount_clues
    mount_clues(app,cfg,authenticated,origin_ok)
    from .input_api import mount_inputs
    mount_inputs(app,cfg,authenticated,origin_ok)
    from .media_api import mount_media
    mount_media(app,cfg,authenticated)
    from .media_jobs_api import mount_media_jobs
    mount_media_jobs(app,cfg,authenticated,origin_ok)
    from .specialist_api import mount_specialists
    mount_specialists(app,cfg,authenticated,origin_ok)
    from .investigation_api import mount_investigation
    mount_investigation(app,cfg,authenticated,origin_ok)
    from .ingestion_api import mount_ingestion
    mount_ingestion(app,cfg,authenticated,origin_ok)
    from .exports_api import mount_exports
    mount_exports(app,cfg,authenticated,origin_ok)
    from .amap_provider import mount_amap
    mount_amap(app,cfg,authenticated,origin_ok)
    from .evidence_package_api import mount_evidence_packages
    mount_evidence_packages(app,cfg,authenticated,origin_ok)
    from .appearance_api import mount_appearance
    mount_appearance(app,cfg,authenticated,origin_ok)
    from .live_api import mount_live
    mount_live(app,cfg,authenticated)
    from .recording_review_api import mount_recording_review
    mount_recording_review(app,cfg,authenticated,origin_ok)

    @app.get('/api/v1/features')
    async def features(request:Request):
        if not authenticated(request):raise HTTPException(401,'请先登录')
        directory=bool(cfg.get('account_directory') and cfg.get('admin_token'))
        result={'clue_collections':bool(directory and cfg.get('clue_collections')),
                'analysis_inputs':bool(directory and cfg.get('analysis_inputs'))}
        if cfg.get('media_jobs') and cfg.get('media_clips'):result['media_jobs']=True
        if directory and cfg.get('analysis_inputs') and cfg.get('specialist_jobs'):result['specialist_jobs']=True
        if directory and cfg.get('investigation'):result['investigation']=True
        if directory and cfg.get('ingestion_jobs'):result['ingestion_jobs']=True
        if directory and cfg.get('exports'):result['exports']=True
        if directory and cfg.get('appearance'):result['appearance']=True
        if cfg.get('live_view'):result['live_view']=True
        if directory and cfg.get('recording_review'):result['recording_review']=True
        return result

    if cfg.get('evaluation_root'):
        from services.evaluation.api import mount_evaluation
        mount_evaluation(app,cfg,lambda request:authenticated(request) and (not multi_accounts or request.state.account['is_admin']),origin_ok)
    static = Path(cfg["static_dir"])
    if static.is_dir():
        @app.get('/_maps/frame')
        def map_document(request:Request):
            if not cfg.get('investigation'):raise HTTPException(404,'地图模块尚未启用')
            if not authenticated(request):raise HTTPException(401,'请先登录')
            if not (static/'map-frame.html').is_file():raise HTTPException(503,'地图页面尚未完整部署')
            return FileResponse(static/'map-frame.html',media_type='text/html')
        @app.get('/evaluation')
        def evaluation_page():return FileResponse(static/'index.html',media_type='text/html')
        @app.get('/admin')
        @app.get('/admin/')
        def admin_page():return FileResponse(static/'index.html',media_type='text/html')
        @app.get('/clues')
        @app.get('/clues/')
        def clues_page():return FileResponse(static/'index.html',media_type='text/html')
        @app.get('/image-search')
        @app.get('/image-search/')
        def appearance_page():return FileResponse(static/'index.html',media_type='text/html')
        @app.get('/live')
        @app.get('/live/')
        def live_page():return FileResponse(static/'index.html',media_type='text/html')
        app.mount("/", StaticFiles(directory=static, html=True), name="frontend")
    return app


app = create_app()
