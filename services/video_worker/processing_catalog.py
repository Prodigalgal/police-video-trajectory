"""Versioned, non-secret processing bindings shared with Core.

Catalog publication affects new work only. Persisted job routes remain usable
while their module is disabled or its active binding changes.
"""
from copy import deepcopy
from dataclasses import dataclass
import hashlib
import json
from pathlib import Path
import re
import threading
from urllib.parse import urlsplit


SCHEMA = 'processing-catalog-v1'
ADAPTER = 'specialist_sse_v1'
OPERATIONS = frozenset({'text_ocr', 'license_plate'})
TOKEN = re.compile(r'[A-Za-z0-9_.-]{1,80}')
MAX_CATALOG_BYTES = 128 * 1024


def canonical(value):
    return json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False).encode()


def fingerprint(value):
    return hashlib.sha256(canonical(value)).hexdigest()


def validate_profile(value):
    fields = {'profile', 'adapter', 'capabilities', 'base_url', 'token_file', 'ca_file',
              'model_id', 'contract', 'streaming', 'timeout_seconds'}
    if not isinstance(value, dict) or value.keys() != fields:
        raise ValueError('invalid model profile fields')
    if (not isinstance(value['profile'], str) or not TOKEN.fullmatch(value['profile'])
            or value['adapter'] != ADAPTER or value['contract'] != 'specialist-result-v1'
            or value['streaming'] is not True or type(value['timeout_seconds']) is not int
            or value['timeout_seconds'] != 360):
        raise ValueError('unsupported model profile contract')
    capabilities = value['capabilities']
    if (not isinstance(capabilities, list) or not 1 <= len(capabilities) <= 2
            or any(not isinstance(v, str) or v not in OPERATIONS for v in capabilities)
            or len(capabilities) != len(set(capabilities))):
        raise ValueError('invalid model capabilities')
    for key in ('token_file', 'ca_file'):
        if not isinstance(value[key], str) or not TOKEN.fullmatch(value[key]) or value[key] in {'.', '..'}:
            raise ValueError('invalid credential file reference')
    if (not isinstance(value['model_id'], str) or not value['model_id'].strip()
            or len(value['model_id']) > 100 or any(ord(c) < 32 for c in value['model_id'])):
        raise ValueError('invalid declared model identifier')
    if not isinstance(value['base_url'], str) or len(value['base_url']) > 512:
        raise ValueError('invalid specialist HTTPS endpoint')
    try:
        address = urlsplit(value['base_url'])
        if (not isinstance(value['base_url'], str) or len(value['base_url']) > 512
                or address.scheme != 'https' or not address.hostname or address.username
                or address.password or address.query or address.fragment):
            raise ValueError('invalid specialist HTTPS endpoint')
        address.port
    except (ValueError, TypeError):
        raise ValueError('invalid specialist HTTPS endpoint') from None
    return deepcopy(value)


@dataclass(frozen=True)
class CatalogSnapshot:
    revision: int
    sha256: str
    payload: bytes

    def module(self, name):
        return json.loads(self.payload)['modules'].get(name)

    def profiles(self):
        return json.loads(self.payload)['profiles']


def validate_catalog(value):
    if (not isinstance(value, dict) or value.keys() != {'schema_version', 'revision', 'modules', 'profiles'}
            or value['schema_version'] not in {SCHEMA,'processing-catalog-v2'} or type(value['revision']) is not int
            or not 1 <= value['revision'] <= 9007199254740991
            or not isinstance(value['modules'], dict) or len(value['modules']) > (16 if value['schema_version']=='processing-catalog-v2' else 4)
            or not isinstance(value['profiles'], list) or len(value['profiles']) > 32):
        raise ValueError('invalid processing catalog')
    profiles = {}
    for raw in value['profiles']:
        profile = validate_profile(raw)
        if profile['profile'] in profiles:
            raise ValueError('duplicate immutable model profile')
        profiles[profile['profile']] = profile
    for name, module in value['modules'].items():
        strategy_modules={'temporal_aggregation','candidate_association'} if value['schema_version']=='processing-catalog-v2' else set()
        if name not in OPERATIONS | {'specialist_selection', 'face_quality'} | strategy_modules or not isinstance(module, dict):
            raise ValueError('unknown processing module')
        if type(module.get('enabled')) is not bool:
            raise ValueError('module enabled flag must be boolean')
        if name in strategy_modules:
            if module.keys()!={'enabled','plugin'} or not isinstance(module.get('plugin'),str) or not TOKEN.fullmatch(module['plugin']):
                raise ValueError('invalid installed strategy binding')
        elif name == 'specialist_selection':
            if (module.keys() not in ({'enabled', 'plugin', 'mode', 'policy_sha256'}, {'enabled', 'plugin', 'mode', 'policy_sha256','plate_probe'})
                    or module['plugin'] != 'rules_v1' or module['mode'] not in {'observe', 'select'}
                    or not isinstance(module['policy_sha256'], str)
                    or not re.fullmatch('[a-f0-9]{64}', module['policy_sha256'])):
                raise ValueError('unsupported selection plugin')
            probe=module.get('plate_probe')
            if probe is not None:
                if (not isinstance(probe,dict) or probe.keys()!={'adapter','model_file','model_sha256'}
                    or probe['adapter']!='opencv_plate_v1' or not isinstance(probe['model_file'],str)
                    or not TOKEN.fullmatch(probe['model_file']) or probe['model_file'] in {'.','..'}
                    or not isinstance(probe['model_sha256'],str) or not re.fullmatch('[a-f0-9]{64}',probe['model_sha256'])):
                    raise ValueError('invalid installed plate probe')
        elif name == 'face_quality':
            if module.keys() != {'enabled'} or module['enabled']:
                raise ValueError('face quality plugin is not installed')
        elif module.keys() != {'enabled', 'profile'}:
            raise ValueError('invalid specialist module binding')
        elif module['profile'] is not None and (not isinstance(module['profile'], str) or not TOKEN.fullmatch(module['profile'])):
            raise ValueError('invalid specialist module profile')
        elif module['enabled']:
            definition = profiles.get(module['profile'])
            if definition is None or name not in definition['capabilities']:
                raise ValueError('module capability is not supplied by profile')
        elif module['profile'] is not None and module['profile'] not in profiles:
            raise ValueError('unknown disabled module profile')
    payload = canonical(value)
    if len(payload) > MAX_CATALOG_BYTES:
        raise ValueError('processing catalog exceeds bound')
    return CatalogSnapshot(value['revision'], hashlib.sha256(payload).hexdigest(), payload)


class ProcessingCatalog:
    def __init__(self, path):
        self.path = Path(path)
        self.lock = threading.RLock()
        self.snapshot = None
        self.definitions = {}

    def current(self):
        with self.path.open('rb') as stream:
            raw = stream.read(MAX_CATALOG_BYTES + 1)
        if not raw or len(raw) > MAX_CATALOG_BYTES:
            raise ValueError('processing catalog exceeds bound')
        candidate = validate_catalog(json.loads(raw))
        with self.lock:
            previous = self.snapshot
            if previous is not None:
                if candidate.revision < previous.revision or (candidate.revision == previous.revision and candidate.sha256 != previous.sha256):
                    raise ValueError('catalog revision must increase')
            definitions = {p['profile']: fingerprint(p) for p in candidate.profiles()}
            if any(name in self.definitions and self.definitions[name] != sha for name, sha in definitions.items()):
                raise ValueError('model profile cannot change in place')
            self.definitions.update(definitions)
            self.snapshot = candidate
            return candidate


class PluginSlot:
    """Lease one installed synchronous stage; retire it after its last user."""
    def __init__(self, factories):
        self.factories = dict(factories)
        self.lock = threading.RLock()
        self.current = None
        self.entries = {}
        self.sequence = 0
        self.closed = False

    def configure(self, key, plugin, configuration):
        with self.lock:
            factory = self.factories.get(plugin)
            if factory is None or self.closed:
                raise ValueError('plugin is not installed')
            if self.current is not None and key == self.current[0]:
                return
            self.sequence += 1
            ticket = self.sequence
        instance = factory(deepcopy(configuration))
        with self.lock:
            if ticket != self.sequence:
                instance.close()
                raise ValueError('plugin configuration was superseded')
            old = self.current
            generation = (key, ticket)
            self.entries[generation] = {'instance': instance, 'users': 0, 'retired': False}
            self.current = generation
            self._retire(old)

    def _retire(self, key):
        if key is None:
            return
        entry = self.entries[key]
        entry['retired'] = True
        if not entry['users']:
            self.entries.pop(key)['instance'].close()

    def acquire(self, expected_key=None):
        with self.lock:
            key = self.current
            if key is None or expected_key is not None and key[0] != expected_key:
                raise ValueError('processing stage is disabled')
            self.entries[key]['users'] += 1
            return PluginLease(self, key, self.entries[key]['instance'])

    def disable(self):
        with self.lock:
            self.sequence += 1
            old, self.current = self.current, None
            self._retire(old)

    def close(self):
        with self.lock:
            self.closed = True
            self.disable()


class PluginLease:
    def __init__(self, owner, key, instance):
        self.owner, self.key, self.instance = owner, key, instance
        self.released = False

    def __enter__(self):
        return self.instance

    def __exit__(self, *exception):
        with self.owner.lock:
            if self.released:
                return
            self.released = True
            entry = self.owner.entries[self.key]
            entry['users'] -= 1
            if entry['retired'] and not entry['users']:
                self.owner.entries.pop(self.key)['instance'].close()
