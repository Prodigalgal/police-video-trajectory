import hashlib
import json
import re
from types import SimpleNamespace

import httpx
import pytest
from fastapi.testclient import TestClient

from services.web_gateway.app import create_app
from services.web_gateway.password_auth import password_hash, valid_work, _parse_hash

ORIGIN = 'https://video.example.invalid'


@pytest.fixture(scope='module')
def settings():
    return {'auth_mode':'password','username':'trial','password_hash':password_hash('unit-test-only-password'),
            'pow_bits':16,'session_secret':'unit-session-secret','core_token':'unit-core-secret',
            'core_url':'https://core.invalid','core_ca':'unused','origin':ORIGIN,'static_dir':'missing'}


def challenge(client):
    data = client.get('/api/auth/challenge').json()
    left, op, right = re.fullmatch(r'(\d+) ([+−×]) (\d+) = \?',data['question']).groups()
    left,right=int(left),int(right)
    answer={'+':left+right,'−':left-right,'×':left*right}[op]
    nonce=0
    while not valid_work(data['challenge_id'],str(nonce),data['pow']['bits']):nonce+=1
    return {'username':'trial','password':'unit-test-only-password','challenge_id':data['challenge_id'],
            'captcha_answer':str(answer),'pow_nonce':str(nonce)}


def client_for(settings):
    return TestClient(create_app(settings,httpx.MockTransport(lambda r:httpx.Response(200,json={'status':'ok','results':[]}))),base_url=ORIGIN)


def test_password_math_pow_session_and_legacy_token_cannot_bypass(settings):
    with client_for(settings) as client:
        assert client.get('/readyz').status_code==200
        assert client.get('/api/auth/config').json()=={'mode':'password'}
        assert client.post('/api/session',headers={'Origin':ORIGIN},json={'token':'old-token'}).status_code==401
        body=challenge(client)
        assert client.post('/api/session',headers={'Origin':'https://other.invalid'},json=body).status_code==403
        result=client.post('/api/session',headers={'Origin':ORIGIN},json=body)
        assert result.status_code==200
        assert 'Secure' in result.headers['set-cookie'] and 'HttpOnly' in result.headers['set-cookie']
        assert body['password'] not in result.text and body['pow_nonce'] not in result.text
        assert client.get('/api/session').json()=={'authenticated':True}
        assert client.post('/api/v1/search',headers={'Origin':ORIGIN},json={'query':'查找车辆'}).status_code==200
        assert client.post('/api/session',headers={'Origin':ORIGIN},json=body).status_code==401
        assert client.delete('/api/session',headers={'Origin':ORIGIN}).status_code==200
        assert client.get('/api/session').json()=={'authenticated':False}


@pytest.mark.parametrize('field,value',[('captcha_answer','9999999'),('pow_nonce','-1'),('password','wrong-password'),('username','not-a-user')])
def test_wrong_answers_proofs_and_credentials_reject_and_consume_challenge(settings,field,value):
    with client_for(settings) as client:
        body=challenge(client);good=dict(body);body[field]=value
        response=client.post('/api/session',headers={'Origin':ORIGIN},json=body)
        assert response.status_code==401 and value not in response.text
        assert client.post('/api/session',headers={'Origin':ORIGIN},json=good).status_code==401
        assert client.get('/api/session').json()=={'authenticated':False}


def test_challenge_is_browser_bound_and_failure_limits_are_enforced(settings):
    with client_for(settings) as client:
        body=challenge(client);client.cookies.clear()
        assert client.post('/api/session',headers={'Origin':ORIGIN},json=body).status_code==401
        for _ in range(4):
            assert client.post('/api/session',headers={'Origin':ORIGIN},json={'username':'trial','password':'bad'}).status_code==401
        assert client.post('/api/session',headers={'Origin':ORIGIN},json=challenge(client)).status_code==429


def test_misconfiguration_fails_closed_and_password_rotation_invalidates_sessions(settings):
    with client_for(settings) as client:
        body=challenge(client);assert client.post('/api/session',headers={'Origin':ORIGIN},json=body).status_code==200
        old=client.cookies.get('pv_session')
    with client_for({**settings,'password_hash':password_hash('another-unit-password')}) as client:
        client.cookies.set('pv_session',old)
        assert client.get('/api/session').json()=={'authenticated':False}
    for change in [{'password_hash':''},{'auth_mode':'unknown'},{'pow_bits':0}]:
        with client_for({**settings,**change}) as client:
            assert client.get('/readyz').status_code==503
            assert client.get('/api/auth/challenge').status_code==503


def test_hash_parameters_are_bounded_and_work_binds_the_exact_challenge(settings):
    with pytest.raises(ValueError):_parse_hash(settings['password_hash'].replace('$32768$','$1048576$'))
    assert not valid_work('a','1'*21,16)
    assert not valid_work('a','１２',16)
    nonce=0
    while not valid_work('one',str(nonce),16):nonce+=1
    assert hashlib.sha256(('one:'+str(nonce)).encode()).digest()[:2]==b'\0\0'


def test_proxy_peer_changes_do_not_break_the_same_browser_challenge(settings):
    with client_for({**settings,'trust_cf':True}) as client:
        client.headers['cf-connecting-ip']='192.0.2.1'
        body=challenge(client)
        client.headers['cf-connecting-ip']='192.0.2.2'
        assert client.post('/api/session',headers={'Origin':ORIGIN},json=body).status_code==200


def test_sse_search_has_terminal_result_or_sanitized_error(settings):
    for status, response in [(200,{'status':'ok','results':[]}),(502,{'code':'model_unavailable','secret':'never-echo'})]:
        transport=httpx.MockTransport(lambda r:httpx.Response(status,json=response))
        with TestClient(create_app(settings,transport),base_url=ORIGIN) as client:
            client.post('/api/session',headers={'Origin':ORIGIN},json=challenge(client))
            result=client.post('/api/v1/search',headers={'Origin':ORIGIN,'Accept':'text/event-stream'},json={'query':'查找车辆'})
            assert result.headers['content-type'].startswith('text/event-stream')
            assert 'event: pending\n' in result.text
            assert ('event: result\n' if status==200 else 'event: error\n') in result.text
            assert 'never-echo' not in result.text
