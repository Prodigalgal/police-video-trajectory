import hashlib
from uuid import uuid4
import httpx
from fastapi.testclient import TestClient
from services.web_gateway.app import create_app
from services.web_gateway.media_api import valid_video_headers
from test_clue_api import settings
from test_simple_accounts import Directory,login,ORIGIN


def test_media_reads_require_login_feature_and_valid_sample_number():
    config=settings();directory=Directory(config)
    with TestClient(create_app(config,httpx.MockTransport(directory.handle)),base_url=ORIGIN) as client:
        assert client.get('/api/v1/media-clips?camera_id=recording-01').status_code==401
        login(client,'reader')
        assert client.get('/api/v1/media-clips?camera_id=recording-01').status_code==404
        config['media_clips']=True
        assert client.get(f'/api/v1/media-clips/{uuid4()}/samples/31').status_code==422
        assert client.get('/api/v1/media-clips?camera_id=recording-01&limit=51').status_code==422


def test_metadata_and_sample_digests_use_service_token_and_safe_errors():
    config=settings();config['media_clips']=True;directory=Directory(config);raw=b'protocol payload';mode='ok';calls=[]
    def upstream(request):
        if '/api/v1/media-clips' not in request.url.path:return directory.handle(request)
        calls.append(request)
        if mode=='error':return httpx.Response(409,json={'code':'media_source_changed','source_ref':'never-copy'})
        return httpx.Response(200,content=raw,headers={'x-content-sha256':hashlib.sha256(raw).hexdigest() if mode=='ok' else 'bad'})
    with TestClient(create_app(config,httpx.MockTransport(upstream)),base_url=ORIGIN) as client:
        login(client,'reader');url=f'/api/v1/media-clips/{uuid4()}/samples/0'
        assert client.get(url).content==raw and calls[-1].headers['authorization']=='Bearer test-core-secret'
        mode='bad';assert client.get(url).status_code==503
        mode='error';response=client.get(url);assert response.status_code==409 and response.json()=={'code':'media_source_changed'}


def test_video_range_is_streamed_and_inconsistent_headers_fail_closed():
    config=settings();config['media_clips']=True;directory=Directory(config);mode='ok';calls=[]
    def upstream(request):
        if not request.url.path.endswith('/video'):return directory.handle(request)
        calls.append(request)
        return httpx.Response(206,content=b'1234',headers={'content-type':'video/mp4','content-range':'bytes 4-7/10' if mode=='ok' else 'bytes 4-8/10','accept-ranges':'bytes'})
    with TestClient(create_app(config,httpx.MockTransport(upstream)),base_url=ORIGIN) as client:
        login(client,'reader');url=f'/api/v1/media-clips/{uuid4()}/video';response=client.get(url,headers={'Range':'bytes=4-7'})
        assert response.status_code==206 and response.content==b'1234' and response.headers['content-range']=='bytes 4-7/10'
        assert calls[-1].headers['range']=='bytes=4-7'
        assert client.get(url,headers={'Range':'bytes=1-2,4-5'}).status_code==416
        mode='bad';assert client.get(url).status_code==503
    assert not valid_video_headers(206,{'content-type':'text/html','content-length':'4','content-range':'bytes 4-7/10'})
