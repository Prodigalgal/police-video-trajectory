import hashlib
import httpx
from uuid import uuid4
from fastapi.testclient import TestClient
from services.web_gateway.app import create_app
from test_clue_api import settings
from test_simple_accounts import Directory,login,ORIGIN


def test_recording_review_is_admin_only_and_uses_authoritative_actor():
    cfg=settings();directory=Directory(cfg);calls=[]
    def core(request):
        if '/internal/recording-review/' not in request.url.path:return directory.handle(request)
        calls.append(request)
        assert request.headers['authorization']=='Bearer test-admin-secret'
        assert request.headers['x-actor-id']=='admin' and request.headers['x-actor-revision']=='1'
        return httpx.Response(200,json={'items':[]})
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        url='/api/admin/recording-review/frames'
        assert client.get(url).status_code==401
        login(client,'admin');assert client.get(url).status_code==404
        cfg['recording_review']=True
        assert client.get(url+'?limit=51').status_code==422
        assert client.get(url,headers={'X-Actor-Id':'reader'}).status_code==200 and len(calls)==1
        client.delete('/api/session',headers={'Origin':ORIGIN});login(client,'reader')
        assert client.get(url).status_code==403
        assert client.get(url+'/'+str(uuid4())+'/video').status_code==403
        assert len(calls)==1


def test_recording_review_images_reject_changed_bytes_and_video_preserves_ranges():
    cfg=settings();cfg['recording_review']=True;directory=Directory(cfg);image=b'nonvisual transport contract';bad=False;closed=[]
    class Stream(httpx.AsyncByteStream):
        async def __aiter__(self):yield b'1234'
        async def aclose(self):closed.append(True)
    def core(request):
        if '/internal/recording-review/' not in request.url.path:return directory.handle(request)
        assert request.headers['x-actor-id']=='admin'
        if request.url.path.endswith('/image'):
            return httpx.Response(200,content=image,headers={'Content-Type':'image/jpeg','X-Content-SHA256':'0'*64 if bad else hashlib.sha256(image).hexdigest()})
        assert request.headers['range']=='bytes=0-3'
        return httpx.Response(206,stream=Stream(),headers={'Content-Type':'video/mp4','Content-Length':'4','Content-Range':'bytes 0-3/100','Accept-Ranges':'bytes'})
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        login(client,'admin');prefix='/api/admin/recording-review/frames/'+str(uuid4())
        assert client.get(prefix+'/image').content==image
        bad=True;assert client.get(prefix+'/image').status_code==503
        response=client.get(prefix+'/video',headers={'Range':'bytes=0-3'})
        assert response.status_code==206 and response.content==b'1234' and closed
        assert response.headers['content-range']=='bytes 0-3/100' and response.headers['cache-control']=='no-store'
        assert client.get(prefix+'/video',headers={'Range':'bytes=0-2,6-8'}).status_code==416
