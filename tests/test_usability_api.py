import httpx
import hashlib
import json
from pathlib import Path
import pytest
from uuid import uuid4
from fastapi.testclient import TestClient
from services.web_gateway.app import create_app
from test_clue_api import settings
from test_simple_accounts import Directory,login,ORIGIN
from test_live_media import settings as live_settings


@pytest.mark.parametrize('style',['chat_completions','responses'])
def test_appearance_description_uses_streaming_and_omits_uncertain_attributes(style):
    from test_ai_gateway import settings as model_settings,response,TOKEN,IMAGE
    from services.ai_gateway.app import create_app as gateway_app
    import base64
    calls=[]
    def model(request):
        body=json.loads(request.content);calls.append(body);assert body['stream'] is True
        return response({'vehicle_color':'白色','transport_type':'轿车','uncertain_fields':['vehicle_color']},style)
    app=gateway_app(model_settings(style),httpx.MockTransport(model))
    with TestClient(app) as client:
        metadata={'frame_id':'query-1','media_type':'image/jpeg','target_type':'vehicle','request_timeout_seconds':285}
        form={'metadata':json.dumps(metadata)};file={'file':('target.jpg',base64.b64decode(IMAGE),'image/jpeg')}
        assert client.post('/internal/v1/describe-appearance',data=form,files=file).status_code==401
        reply=client.post('/internal/v1/describe-appearance',headers={'Authorization':'Bearer '+TOKEN},data=form,files=file)
        assert reply.status_code==200
        assert reply.json()['result']['vehicle_color'] is None
        assert reply.json()['result']['transport_type']=='轿车' and len(calls)==1


def test_hybrid_upload_keeps_semantics_bound_to_the_same_image_and_query(monkeypatch):
    from services.appearance.catalog import Catalog
    cfg=settings();cfg.update(appearance=True,appearance_catalog_file=str(Path(__file__).resolve().parents[1]/'deploy/appearance-catalog.json'))
    definition=Catalog(cfg['appearance_catalog_file']).definition()
    # Transport contract fixture; it is not an accuracy or visual-quality case.
    image=b'\xff\xd8\xffprotocol-fixture';sha=hashlib.sha256(image).hexdigest();identity=str(uuid4());calls=[]
    class Runner:
        def __init__(self,*args,**kwargs):pass
        async def recognize(self,*args):return {'result':{'profile_sha256':definition.sha256,'image_sha256':sha,'image_width':100,'image_height':80,'vector':[1.0]+[0.0]*383}}
        async def close(self):pass
    async def describe(cfg,metadata,image,budget):
        assert metadata.request_id==__import__('uuid').UUID(identity) and metadata.semantic_assistance
        assert metadata.bbox_xyxy==[1,2,40,50] and 0<budget<=285
        return {'state':'completed','attributes':{'vehicle_color':'白色'},'uncertain_fields':[],'model_id':'vision','prompt_version':'appearance-description-v1'}
    monkeypatch.setattr('services.specialist_service.runner.NativeRunner',Runner)
    monkeypatch.setattr('services.web_gateway.appearance_semantics.describe_target',describe)
    directory=Directory(cfg)
    def core(request):
        if request.url.path!='/internal/appearance/queries':return directory.handle(request)
        body=json.loads(request.content);calls.append(body)
        assert body['requestId']==identity and body['imageSha256']==sha and body['bbox']==[1,2,40,50]
        assert body['semantic']['modelId']=='vision' and body['semantic']['attributes']=={'vehicle_color':'白色'}
        assert 'image_base64' not in body and len(body['vector'])==384
        return httpx.Response(200,json={'query_id':identity})
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        login(client,'reader')
        body={'request_id':identity,'media_type':'image/jpeg','bbox_xyxy':[1,2,40,50],'target_type':'vehicle','profile':definition.profile,'profile_sha256':definition.sha256,'semantic_assistance':True}
        reply=client.post('/api/v1/appearance/queries',headers={'Origin':ORIGIN},data={'metadata':json.dumps(body)},files={'file':('input.jpg',image,'image/jpeg')})
        assert reply.status_code==200 and 'event: result' in reply.text and len(calls)==1


def test_live_preview_uses_a_small_variant_and_caches_only_camera_authorization():
    cfg=live_settings();cfg['live_preview_paths']='{"camera-1":"preview-stream-1"}';calls=[]
    def upstream(request):
        calls.append(request.url.path)
        if request.url.host=='core.invalid':return httpx.Response(200,json={'items':[{'camera_id':'camera-1','display_name':'一号点位'}]})
        assert request.url.path=='/preview-stream-1/index.m3u8'
        return httpx.Response(200,content=b'#EXTM3U\nseg1.mp4\n')
    with TestClient(create_app(cfg,httpx.MockTransport(upstream)),base_url=ORIGIN) as client:
        path='/api/v1/live/cameras/camera-1/preview/index.m3u8'
        assert client.get(path).status_code==401
        assert client.post('/api/session',headers={'Origin':ORIGIN},json={'token':'test-browser'}).status_code==200
        assert client.get('/api/v1/live/cameras').json()['items'][0]['preview_available'] is True
        for _ in range(2):
            reply=client.get(path);assert reply.status_code==200 and '/camera-1/preview/seg1.mp4' in reply.text
        assert len([path for path in calls if path=='/api/v1/live/cameras'])==1
        assert client.get('/api/v1/live/cameras/camera-2/preview/index.m3u8').status_code==404
        assert client.get(path+'?source=untrusted').status_code==422


def test_nearby_records_route_is_removed_while_context_input_survives():
    cfg=settings();cfg['analysis_inputs']=True;directory=Directory(cfg);frame=str(uuid4());calls=[]
    def core(request):
        if not request.url.path.endswith('/context'):return directory.handle(request)
        calls.append(request)
        assert request.headers['authorization']=='Bearer test-core-secret'
        return httpx.Response(200,json={'frames':[]})
    with TestClient(create_app(cfg,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        login(client,'reader')
        assert client.get('/api/v1/observations/'+frame+'/nearby-records').status_code==404
        assert not calls
        assert client.get('/api/v1/observations/'+frame+'/context').status_code==200
        assert len(calls)==1
