import json
import re

import httpx
import pytest
from fastapi.testclient import TestClient

from services.web_gateway.app import create_app
from services.web_gateway.password_auth import password_hash, valid_work, _parse_hash

ORIGIN='https://video.example.invalid'
PASSWORD='test-account-password-only'


@pytest.fixture(scope='module')
def config():
    return dict(auth_mode='password',username='admin',password_hash=password_hash(PASSWORD),pow_bits=16,
        session_secret='test-session-secret',core_token='test-core-secret',admin_token='test-admin-secret',
        account_directory=True,core_url='https://core.invalid',core_ca='unused',origin=ORIGIN,static_dir='missing')


def login(client,name='admin',password=PASSWORD):
    value=client.get('/api/auth/challenge').json()
    left,op,right=re.fullmatch(r'(\d+) ([+−×]) (\d+) = \?',value['question']).groups()
    left,right=int(left),int(right)
    answer={'+':left+right,'−':left-right,'×':left*right}[op]
    nonce=0
    while not valid_work(value['challenge_id'],str(nonce),value['pow']['bits']):nonce+=1
    return client.post('/api/session',headers={'Origin':ORIGIN},json=dict(username=name,password=password,
        challenge_id=value['challenge_id'],captcha_answer=str(answer),pow_nonce=str(nonce)))


class Directory:
    def __init__(self,config):
        self.accounts={name:dict(username=name,display_name=name,is_admin=name=='admin',credential_revision=1,
            password_hash=config['password_hash'],enabled=True) for name in ('admin','reader')}
        self.calls=[];self.outage=False;self.wrong_subject=False

    def handle(self,request):
        path=request.url.path;body=json.loads(request.content) if request.content else {}
        self.calls.append((request.method,path,dict(request.headers),body))
        if path.startswith('/internal/'):
            assert request.headers['authorization']=='Bearer test-admin-secret'
        if path=='/internal/auth/credential':
            account=self.accounts.get(body['username'].strip().lower())
            return httpx.Response(200,json={k:v for k,v in account.items() if k!='enabled'}) if account and account['enabled'] else httpx.Response(404,json={})
        if path=='/internal/auth/status':
            if self.outage:return httpx.Response(503,json={})
            account=self.accounts.get(body['username'])
            valid=bool(account and account['enabled'] and account['credential_revision']==body['revision'])
            return httpx.Response(200,json={**{k:v for k,v in account.items() if k not in {'password_hash','enabled'}},'username':'another' if self.wrong_subject else account['username'],'valid':True}) if valid else httpx.Response(200,json={'valid':False})
        if path=='/internal/admin/users':
            user=dict(username=body.get('username','reader'),display_name='Test',is_admin=False,enabled=True,version=1,
                password_hash='must-never-leak',credential_revision=7)
            return httpx.Response(200,json={'items':[user],'has_more':False,'next_cursor':None}) if request.method=='GET' else httpx.Response(200,json={'user':user})
        return httpx.Response(200,json={'status':'ok','results':[]})


def client(config,directory):
    return TestClient(create_app(config,httpx.MockTransport(directory.handle)),base_url=ORIGIN)


def test_user_management_checks_session_csrf_and_server_actor_and_hides_hash(config):
    directory=Directory(config)
    with client(config,directory) as browser:
        assert browser.get('/api/admin/users').status_code==401
        assert login(browser).status_code==200
        assert browser.get('/api/session').json()['is_admin'] is True
        assert browser.post('/api/admin/users',json={'username':'newuser','display_name':'New','password':PASSWORD}).status_code==403
        response=browser.post('/api/admin/users',headers={'Origin':ORIGIN,'X-Actor-Id':'attacker'},json={'username':'newuser','display_name':'New','password':PASSWORD})
        assert response.status_code==200 and 'must-never-leak' not in response.text and PASSWORD not in response.text
        call=next(call for call in directory.calls if call[:2]==('POST','/internal/admin/users'))
        assert call[2]['x-actor-id']=='admin' and 'password' not in call[3] and call[3]['admin'] is False
        _parse_hash(call[3]['passwordHash'])
        assert PASSWORD not in json.dumps(call)
        listed=browser.get('/api/admin/users')
        assert listed.status_code==200 and 'password_hash' not in listed.text and 'credential_revision' not in listed.text
        assert 'blob:' in listed.headers['content-security-policy']


def test_ordinary_user_can_search_but_cannot_use_admin_or_evaluation(config):
    directory=Directory(config)
    with client(config,directory) as browser:
        assert login(browser,'reader').status_code==200
        assert browser.get('/api/session').json()['is_admin'] is False
        assert browser.post('/api/v1/search',headers={'Origin':ORIGIN},json={'query':'车辆'}).status_code==200
        for path in ('users','config','models','cameras','overview','jobs'):
            assert browser.get('/api/admin/'+path).status_code==403
        assert not any('/internal/admin/' in call[1] for call in directory.calls)


@pytest.mark.parametrize('change',['disable','password_revision','role_revision','delete'])
def test_account_changes_revoke_existing_cookie_on_next_request(config,change):
    directory=Directory(config)
    with client(config,directory) as browser:
        assert login(browser,'reader').status_code==200
        if change=='disable':directory.accounts['reader']['enabled']=False
        elif change=='delete':directory.accounts.pop('reader')
        else:directory.accounts['reader']['credential_revision']+=1
        assert browser.get('/api/session').json()=={'authenticated':False}
        assert browser.post('/api/v1/search',headers={'Origin':ORIGIN},json={'query':'车辆'}).status_code==401


def test_directory_outage_preserves_session_and_recovers_without_relogin(config):
    directory=Directory(config)
    with client(config,directory) as browser:
        assert login(browser).status_code==200
        cookie=browser.cookies.get('pv_session');directory.outage=True
        response=browser.get('/api/session')
        assert response.status_code==503 and browser.cookies.get('pv_session')==cookie
        directory.outage=False
        assert browser.get('/api/session').json()['authenticated'] is True
        directory.wrong_subject=True
        assert browser.get('/api/session').status_code==503


@pytest.mark.parametrize('name',['unknown','admin'])
def test_unknown_and_disabled_users_never_fall_back_to_bootstrap_hash(config,name):
    directory=Directory(config);directory.accounts['admin']['enabled']=False
    with client(config,directory) as browser:
        assert login(browser,name).status_code==401
        assert browser.get('/api/session').json()=={'authenticated':False}


def test_validation_never_echoes_password_input(config):
    directory=Directory(config)
    with client(config,directory) as browser:
        assert login(browser).status_code==200
        response=browser.post('/api/admin/users',headers={'Origin':ORIGIN},json={'username':'invalid user','display_name':'Test','password':PASSWORD})
        assert response.status_code==422 and PASSWORD not in response.text
