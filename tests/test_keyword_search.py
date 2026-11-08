import json

import httpx
from fastapi.testclient import TestClient

from services.web_gateway.app import create_app


ORIGIN='https://localhost:8445'
SETTINGS={'access_token':'browser-token','session_secret':'session-secret','core_token':'core-secret',
          'core_url':'https://core.invalid','core_ca':'unused','origin':ORIGIN,'static_dir':'missing'}


def test_keyword_selection_is_explicit_authenticated_and_streamed():
    calls=[]
    def core(request):
        calls.append(json.loads(request.content))
        assert request.headers['authorization']=='Bearer core-secret'
        return httpx.Response(200,json={'status':'ok','total':1,'results':[]})
    with TestClient(create_app(SETTINGS,httpx.MockTransport(core)),base_url=ORIGIN) as client:
        body={'query':'白色汽车','mode':'keyword'}
        assert client.post('/api/v1/search',headers={'Origin':ORIGIN},json=body).status_code==401
        assert not calls
        client.post('/api/session',headers={'Origin':ORIGIN},json={'token':'browser-token'})
        assert client.post('/api/v1/search',headers={'Origin':'https://foreign.invalid'},json=body).status_code==403
        response=client.post('/api/v1/search',headers={'Origin':ORIGIN,'Accept':'text/event-stream'},json=body)
        assert response.status_code==200 and 'event: result' in response.text
        assert calls==[{'query':'白色汽车','pageSize':20,'cursor':None,'mode':'keyword'}]
        client.post('/api/v1/search',headers={'Origin':ORIGIN},json={'query':'车辆'})
        assert calls[-1]=={'query':'车辆','pageSize':20,'cursor':None}


def test_invalid_modes_and_continuations_are_rejected_before_core():
    calls=[]
    with TestClient(create_app(SETTINGS,httpx.MockTransport(lambda request:calls.append(request))),base_url=ORIGIN) as client:
        client.post('/api/session',headers={'Origin':ORIGIN},json={'token':'browser-token'})
        for body in [
            {'query':'车','mode':'dsl'}, {'query':'车','mode':True}, {'query':' ','mode':'keyword'},
            {'query':'车'*201,'mode':'keyword'}, {'query':'🚗'*101,'mode':'keyword'},
            {'query':'车','mode':'keyword','cursor':'x'*20},
            {'query':'车','mode':'keyword','adjustment':{'context':'a'*20+'.'+'b'*20,'action':'choose_cameras','camera_ids':['recording-01']}},
        ]:
            assert client.post('/api/v1/search',headers={'Origin':ORIGIN},json=body).status_code==422
        assert not calls
