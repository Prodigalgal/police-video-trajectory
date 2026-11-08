import httpx
import pytest
from fastapi.testclient import TestClient
from services.web_gateway.app import create_app
from services.web_gateway.live_api import rewrite_playlist

ORIGIN = 'https://video.example.invalid'


def settings():
    return dict(access_token='test-browser',session_secret='test-session',core_token='test-core',
                core_url='https://core.invalid',core_ca='unused',origin=ORIGIN,static_dir='missing',
                live_view=True,live_media_url='https://media.invalid:8888',live_media_user='reader',
                live_media_password='test-media',live_camera_paths='{"camera-1":"stream-1"}')


def test_live_requires_session_camera_scope_and_private_authenticated_transport():
    calls=[]
    def upstream(request):
        calls.append(request)
        if request.url.host=='core.invalid':
            assert request.headers['authorization']=='Bearer test-core'
            return httpx.Response(200,json={'items':[{'camera_id':'camera-1','display_name':'一号点位'},
                                                    {'camera_id':'camera-2','display_name':'二号点位'}]})
        assert request.url.host=='media.invalid' and request.url.path=='/stream-1/index.m3u8'
        assert request.headers['authorization'].startswith('Basic ')
        return httpx.Response(200,content=b'#EXTM3U\n#EXT-X-MAP:URI="init.mp4"\nseg1.mp4\n')
    with TestClient(create_app(settings(),httpx.MockTransport(upstream)),base_url=ORIGIN) as client:
        assert client.get('/api/v1/live/cameras').status_code==401 and not calls
        assert client.post('/api/session',headers={'Origin':ORIGIN},json={'token':'test-browser'}).status_code==200
        response=client.get('/api/v1/live/cameras')
        assert response.json()=={'items':[{'camera_id':'camera-1','display_name':'一号点位'}]}
        assert client.get('/api/v1/live/cameras/camera-2/hls/index.m3u8').status_code==404
        response=client.get('/api/v1/live/cameras/camera-1/hls/index.m3u8')
        assert response.status_code==200 and '/api/v1/live/cameras/camera-1/hls/init.mp4' in response.text
        assert 'media.invalid' not in response.text and 'test-media' not in response.text
        assert response.headers['cache-control']=='no-store'
        assert client.get('/api/v1/live/cameras/camera-1/hls/index.m3u8?source=https://other.invalid').status_code==422


@pytest.mark.parametrize('reference',['https://outside.invalid/x.mp4','../x.mp4','/x.mp4','x.mp4?key=private','data:abc','x.mp4#fragment'])
def test_playlist_never_forwards_arbitrary_references(reference):
    with pytest.raises(ValueError):rewrite_playlist(('#EXTM3U\n'+reference+'\n').encode(),'camera-1')
    with pytest.raises(ValueError):rewrite_playlist(('#EXTM3U\n#EXT-X-MAP:URI="'+reference+'"\n').encode(),'camera-1')


def test_live_rejects_redirects_without_exposing_location_and_recovers_next_request():
    mode='redirect'
    def upstream(request):
        if request.url.host=='core.invalid':return httpx.Response(200,json={'items':[{'camera_id':'camera-1','display_name':'一号点位'}]})
        if mode=='redirect':return httpx.Response(302,headers={'Location':'https://private.invalid'})
        return httpx.Response(200,content=b'segment bytes')
    with TestClient(create_app(settings(),httpx.MockTransport(upstream)),base_url=ORIGIN) as client:
        client.post('/api/session',headers={'Origin':ORIGIN},json={'token':'test-browser'})
        response=client.get('/api/v1/live/cameras/camera-1/hls/seg1.mp4')
        assert response.status_code==503 and 'private.invalid' not in response.text
        mode='ok'
        assert client.get('/api/v1/live/cameras/camera-1/hls/seg1.mp4').content==b'segment bytes'
        client.delete('/api/session',headers={'Origin':ORIGIN})
        assert client.get('/api/v1/live/cameras/camera-1/hls/seg1.mp4').status_code==401


def test_proxy_token_stays_server_side_and_does_not_share_media_sessions_with_browser():
    config=settings();config['live_media_bearer']='test-live-proxy-token'
    def upstream(request):
        if request.url.host=='core.invalid':return httpx.Response(200,json={'items':[{'camera_id':'camera-1','display_name':'一号点位'}]})
        assert request.headers['authorization']=='Bearer test-live-proxy-token'
        assert 'cookie' not in request.headers
        return httpx.Response(200,content=b'#EXTM3U\nseg1.mp4\n')
    with TestClient(create_app(config,httpx.MockTransport(upstream)),base_url=ORIGIN) as client:
        client.post('/api/session',headers={'Origin':ORIGIN},json={'token':'test-browser'})
        response=client.get('/api/v1/live/cameras/camera-1/hls/index.m3u8')
        assert response.status_code==200 and 'test-live-proxy-token' not in response.text
        assert 'set-cookie' not in response.headers


def test_high_bitrate_segments_stream_and_oversized_headers_fail_before_body():
    class Segment(httpx.AsyncByteStream):
        def __init__(self):self.closed=False
        async def __aiter__(self):
            for _ in range(208):yield b'x'*65536
        async def aclose(self):self.closed=True
    streams=[];oversized=False
    def upstream(request):
        if request.url.host=='core.invalid':return httpx.Response(200,json={'items':[{'camera_id':'camera-1','display_name':'一号点位'}]})
        stream=Segment();streams.append(stream)
        return httpx.Response(200,stream=stream,headers={'content-length':str(64*1024*1024+1 if oversized else 208*65536)})
    with TestClient(create_app(settings(),httpx.MockTransport(upstream)),base_url=ORIGIN) as client:
        client.post('/api/session',headers={'Origin':ORIGIN},json={'token':'test-browser'})
        url='/api/v1/live/cameras/camera-1/hls/segment.mp4'
        response=client.get(url)
        assert response.status_code==200 and len(response.content)==208*65536 and streams[-1].closed
        oversized=True
        assert client.get(url).status_code==503 and streams[-1].closed
        oversized=False
        assert client.get(url).status_code==200 and streams[-1].closed
