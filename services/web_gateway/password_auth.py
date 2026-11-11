"""Single-account trial login with one-use, browser-bound math and SHA-256 work.

Passwords are stored as bounded scrypt hashes. Challenges and throttles are
bounded in memory; a restart invalidates pending challenges, never accepts them.
"""
import base64
from collections import defaultdict, deque
import hashlib
import hmac
import ipaddress
import secrets
import threading
import time

from fastapi import HTTPException

CHALLENGE_COOKIE = 'pv_login'
CHALLENGE_SECONDS = 180


def _encode(value):
    return base64.urlsafe_b64encode(value).decode().rstrip('=')


def password_hash(password):
    if not isinstance(password, str) or not 8 <= len(password) <= 128:
        raise ValueError('password must contain 8 to 128 characters')
    salt = secrets.token_bytes(16)
    digest = hashlib.scrypt(password.encode(), salt=salt, n=32768, r=8, p=1,
                            dklen=32, maxmem=128*1024*1024)
    return 'scrypt$32768$8$1$'+_encode(salt)+'$'+_encode(digest)


def _parse_hash(value):
    try:
        algorithm, n, r, p, salt, digest = value.split('$')
        if (algorithm, n, r, p) != ('scrypt', '32768', '8', '1'):
            raise ValueError()
        salt, digest = (base64.b64decode(v+'='*(-len(v)%4), altchars=b'-_', validate=True) for v in (salt, digest))
        if len(salt) != 16 or len(digest) != 32:
            raise ValueError()
        return salt, digest
    except (ValueError, TypeError, AttributeError):
        raise ValueError('invalid password hash configuration') from None


def valid_work(challenge_id, nonce, bits):
    if not isinstance(nonce, str) or not 1 <= len(nonce) <= 20 or not nonce.isascii() or not nonce.isdigit():
        return False
    digest = hashlib.sha256((challenge_id+':'+nonce).encode()).digest()
    return int.from_bytes(digest, 'big') < 1 << (256-bits)


class PasswordAuth:
    def __init__(self, username, encoded_hash, *, bits=18, trust_cf=False,state_store=None):
        if not isinstance(username, str) or not 1 <= len(username) <= 80 or not 16 <= bits <= 22:
            raise ValueError('invalid login configuration')
        self.username = username
        self.salt, self.digest = _parse_hash(encoded_hash)
        self.revision = hashlib.sha256((username+':'+encoded_hash).encode()).hexdigest()
        self.bits, self.trust_cf = bits, trust_cf
        self.challenges = {}
        self.failures = defaultdict(deque)
        self.issues = defaultdict(deque)
        self.lock = threading.Lock()
        self.hash_slots = threading.BoundedSemaphore(2)
        self.state_store=state_store

    def peer(self, request):
        raw = request.headers.get('cf-connecting-ip') if self.trust_cf else None
        if not raw:
            raw = request.client.host if request.client else 'unknown'
        try:
            ip = ipaddress.ip_address(raw)
            return str(ipaddress.ip_network(str(ip)+'/64', strict=False)) if ip.version == 6 else str(ip)
        except ValueError:
            return 'unknown'

    def _limit(self, buckets, peer, maximum, now):
        for key in list(buckets):
            while buckets[key] and buckets[key][0] <= now-60:
                buckets[key].popleft()
            if not buckets[key]:
                buckets.pop(key, None)
        if peer not in buckets and len(buckets) >= 2048:
            raise HTTPException(429, '尝试次数过多，请稍后再试')
        history = buckets[peer]
        if len(history) >= maximum:
            raise HTTPException(429, '尝试次数过多，请稍后再试')
        return history

    def issue(self, request, response):
        now, peer = time.monotonic(), self.peer(request)
        cookie = request.cookies.get(CHALLENGE_COOKIE, '')
        if not cookie.isascii() or not 24 <= len(cookie) <= 64:
            cookie = secrets.token_urlsafe(24)
        left, right = secrets.randbelow(35)+5, secrets.randbelow(18)+2
        operator = secrets.choice(['+', '−', '×'])
        if operator == '−':
            left, right = max(left, right), min(left, right)
        answer = {'+': left+right, '−': left-right, '×': left*right}[operator]
        challenge_id = secrets.token_urlsafe(32)
        if self.state_store is not None:
            self.state_store.issue(challenge_id,peer,{'answer_digest':self.state_store.digest('answer',challenge_id+':'+str(answer)),
                'cookie':hashlib.sha256(cookie.encode()).hexdigest(),'revision':self.revision,'bits':self.bits})
        else:
            with self.lock:
                self._limit(self.issues, peer, 30, now).append(now)
                self.challenges = {key: value for key, value in self.challenges.items() if value['expires'] > now}
                if len(self.challenges) >= 2048:
                    raise HTTPException(429, '验证请求过多，请稍后再试')
                self.challenges[challenge_id] = {'answer': str(answer), 'peer': peer, 'cookie': hashlib.sha256(cookie.encode()).digest(),
                                                 'expires': now+CHALLENGE_SECONDS}
        response.set_cookie(CHALLENGE_COOKIE, cookie, max_age=CHALLENGE_SECONDS, secure=True, httponly=True, samesite='strict', path='/api')
        return {'challenge_id': challenge_id, 'question': f'{left} {operator} {right} = ?',
                'pow': {'algorithm': 'SHA-256', 'bits': self.bits}, 'expires_in': CHALLENGE_SECONDS}

    def login(self, body, request, lookup=None):
        now, peer = time.monotonic(), self.peer(request)
        failure_key=peer+'|'+(body.username or '').strip().casefold()
        if self.state_store is not None:
            challenge=self.state_store.consume(body.challenge_id,failure_key)
        else:
            with self.lock:
                history = self._limit(self.failures, failure_key, 5, now)
                challenge = self.challenges.pop(body.challenge_id, None)
                history.append(now)
        cookie = request.cookies.get(CHALLENGE_COOKIE, '')
        # Bind proof to the browser cookie. Reverse-proxy replicas and mobile
        # network changes must not invalidate a legitimate pending challenge.
        if self.state_store is not None:
            valid=bool(challenge and isinstance(cookie,str) and hmac.compare_digest(challenge['cookie'],hashlib.sha256(cookie.encode()).hexdigest())
                and hmac.compare_digest(challenge['revision'],self.revision) and challenge['bits']==self.bits
                and hmac.compare_digest(challenge['answer_digest'],self.state_store.digest('answer',str(body.challenge_id)+':'+str(body.captcha_answer))))
        else:
            valid=bool(challenge and challenge['expires']>now and isinstance(cookie,str)
                and hmac.compare_digest(challenge['cookie'],hashlib.sha256(cookie.encode()).digest())
                and hmac.compare_digest(str(body.captcha_answer).encode(),challenge['answer'].encode()))
        if not valid or not valid_work(body.challenge_id, body.pow_nonce, self.bits):
            raise HTTPException(401, '验证未通过，请刷新验证码后重试')
        if not self.hash_slots.acquire(blocking=False):
            raise HTTPException(429, '登录验证繁忙，请稍后重试')
        try:
            record=lookup(body.username) if lookup else None
            salt,digest=_parse_hash(record['password_hash']) if record else (self.salt,self.digest)
            expected_name=record['username'] if record else self.username
            # The same expensive check runs for unknown usernames.
            actual = hashlib.scrypt((body.password or '').encode(), salt=salt, n=32768, r=8, p=1,
                                     dklen=32, maxmem=128*1024*1024)
            name=(body.username or '').strip().lower() if lookup else body.username or ''
            good = hmac.compare_digest(actual, digest) & hmac.compare_digest(name.encode(), expected_name.encode())
            if lookup and record is None: good=False
        finally:
            self.hash_slots.release()
        if not good:
            raise HTTPException(401, '用户名或密码错误')
        if self.state_store is not None:self.state_store.clear(failure_key)
        else:
            with self.lock:self.failures.pop(failure_key, None)
        return record
