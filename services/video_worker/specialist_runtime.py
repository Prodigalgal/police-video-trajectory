"""Contract-specific model adapters, immutable route leases and bounded pooling."""
import asyncio
from dataclasses import replace
import hashlib
from pathlib import Path
import re
import ssl

from .client import ServiceError
from .processing_catalog import ADAPTER, fingerprint, validate_profile
from .specialist_consumer import SpecialistClient, validate_job


def validated_route(job):
    descriptor = validate_job(job)
    route = job.get('routing')
    if (not isinstance(route, dict) or route.keys() != {'schema_version', 'catalog_revision', 'catalog_sha256', 'definition'}
            or route['schema_version'] != 'specialist-route-v1' or type(route['catalog_revision']) is not int
            or not 1 <= route['catalog_revision'] <= 9007199254740991
            or not isinstance(route['catalog_sha256'], str) or not re.fullmatch('[a-f0-9]{64}', route['catalog_sha256'])
            or fingerprint(route) != job.get('routingSha256')):
        raise ValueError('invalid frozen specialist route')
    definition = validate_profile(route['definition'])
    if definition['profile'] != descriptor['profile'] or descriptor['operation'] not in definition['capabilities']:
        raise ValueError('specialist route does not match input')
    return definition


class SpecialistRuntimeClient:
    """Factories are installed implementations, not arbitrary import locations."""
    def __init__(self, settings, *, factories=None, max_entries=8):
        if not 1 <= max_entries <= 32:
            raise ValueError('invalid adapter pool bound')
        self.settings = settings
        self.factories = dict({ADAPTER: SpecialistClient} if factories is None else factories)
        self.legacy_settings = replace(settings, accept_routing=False) if settings.url else None
        self.entries = {}
        self.lock = asyncio.Lock()
        self.limit = max_entries
        self.closed = False
        self.counter = 0
        self.latest_bindings = {}

    def _credentials(self, definition):
        try:
            root = Path(self.settings.secrets_root).resolve(strict=True)
            values = []
            for name, bound in [('token_file', 4096), ('ca_file', 512 * 1024)]:
                path = (root / definition[name]).resolve(strict=True)
                if not path.is_relative_to(root) or not path.is_file():
                    raise ValueError('credential reference escaped its root')
                with path.open('rb') as stream:
                    raw = stream.read(bound + 1)
                if not raw or len(raw) > bound:
                    raise ValueError('credential exceeds bound')
                values.append((path, raw))
            token = values[0][1].decode('utf-8').strip()
            if not token or not token.isascii() or any(ord(c) < 32 or ord(c) == 127 for c in token):
                raise ValueError('invalid credential content')
            configuration = replace(self.settings, url=definition['base_url'], profile=definition['profile'],
                operations=tuple(definition['capabilities']), token=token, ca_file=str(values[1][0]), accept_routing=False,
                expected_model_id=definition['model_id'])
            configuration.validate()
            key = (fingerprint(definition), hashlib.sha256(token.encode()).hexdigest(), hashlib.sha256(values[1][1]).hexdigest())
            context = ssl.create_default_context(cadata=values[1][1].decode('ascii'))
            return key, configuration, context
        except (OSError, UnicodeError, ValueError):
            raise ServiceError(503) from None

    async def recognize(self, job, image):
        if 'routing' not in job:
            if job.get('routingSha256') is not None or self.legacy_settings is None:
                raise ValueError('legacy specialist adapter unavailable')
            definition = {'profile': self.legacy_settings.profile, 'adapter': ADAPTER}
            configuration = self.legacy_settings
            tls_context = None
            key = ('legacy', fingerprint({'url': configuration.url, 'profile': configuration.profile,
                    'token': configuration.token, 'ca_file': configuration.ca_file}))
            revision = 0
        else:
            if not self.settings.accept_routing:
                raise ValueError('consumer does not support frozen routes')
            definition = validated_route(job)
            key, configuration, tls_context = self._credentials(definition)
            revision = job['routing']['catalog_revision']
        factory = self.factories.get(definition['adapter'])
        if factory is None:
            raise ValueError('model adapter is not installed')
        closing = []
        async with self.lock:
            if self.closed:
                raise ServiceError(503)
            operation = job['descriptor']['operation']
            bindings = dict(self.latest_bindings)
            previous = bindings.get(operation)
            if previous is None or revision >= previous[0]:
                bindings[operation] = (revision, key)
            desired = {value[1] for value in bindings.values()}
            entry = self.entries.get(key)
            if entry is None:
                removals = []
                for old_key, old in list(self.entries.items()):
                    if old_key not in desired and old_key != key:
                        if not old['users']:
                            removals.append(old_key)
                while len(self.entries) - len(removals) >= self.limit:
                    idle = [(value['used'], candidate) for candidate, value in self.entries.items()
                            if not value['users'] and candidate not in removals]
                    if not idle:
                        raise ServiceError(503)
                    removals.append(min(idle)[1])
                try:
                    client = factory(configuration, **({'tls_context': tls_context} if tls_context is not None else {}))
                except (ValueError, OSError):
                    raise ServiceError(503) from None
                for old_key in removals:
                    closing.append(self.entries.pop(old_key)['client'])
                entry = {'client': client, 'users': 0, 'retired': False,
                         'profile': definition['profile'], 'used': 0}
                self.entries[key] = entry
            self.latest_bindings = bindings
            for candidate, cached in self.entries.items():
                cached['retired'] = candidate not in desired
            self.counter += 1
            entry['used'] = self.counter
            entry['users'] += 1
            for candidate, cached in list(self.entries.items()):
                if cached['retired'] and not cached['users']:
                    closing.append(self.entries.pop(candidate)['client'])
        try:
            results = await asyncio.gather(*(client.close() for client in closing), return_exceptions=True)
            if any(isinstance(value, BaseException) for value in results):
                raise ServiceError(503)
            return await entry['client'].recognize(job, image)
        finally:
            dispose = None
            async with self.lock:
                entry['users'] -= 1
                if entry['retired'] and not entry['users']:
                    self.entries.pop(key, None)
                    dispose = entry['client']
            if dispose is not None:
                await dispose.close()

    async def retire(self, profile):
        closing = []
        async with self.lock:
            for key, entry in list(self.entries.items()):
                if entry['profile'] == profile:
                    entry['retired'] = True
                    if not entry['users']:
                        closing.append(self.entries.pop(key)['client'])
        for client in closing:
            await client.close()

    async def close(self):
        async with self.lock:
            self.closed = True
            closing = []
            for key, entry in list(self.entries.items()):
                entry['retired'] = True
                if not entry['users']:
                    closing.append(self.entries.pop(key)['client'])
        for client in closing:
            await client.close()
