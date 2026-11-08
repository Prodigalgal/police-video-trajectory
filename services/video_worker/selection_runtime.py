"""Installed selection stage behind a hot catalog and leased plugin instance."""
from services.scene_analysis.specialist_selection import SpecialistSelector, POLICY_SHA256, SCHEMA
from .processing_catalog import ProcessingCatalog, PluginSlot, fingerprint


class SelectionRuntime:
    def __init__(self, mode='off', catalog_file='', models_root=''):
        if mode not in {'off', 'observe', 'select'}:
            raise ValueError('invalid specialist selection mode')
        self.mode = mode
        self.catalog = ProcessingCatalog(catalog_file) if catalog_file else None
        self.models_root=models_root
        self.slot = PluginSlot({'rules_v1': self._prepare})

    def _prepare(self,configuration):
        probe=None
        if configuration.get('plate_probe') is not None:
            if not self.models_root:raise ValueError('plate model root is not configured')
            from services.scene_analysis.plate_probe import OpenCvPlateProbe
            probe=OpenCvPlateProbe(self.models_root,configuration['plate_probe'])
        return SpecialistSelector(configuration['mode'],probe)

    def select(self, image, targets, metadata, frame_id, digest, *, detection_complete):
        snapshot = self.catalog.current() if self.catalog else None
        configuration = snapshot.module('specialist_selection') if snapshot else {
            'enabled': self.mode != 'off', 'plugin': 'rules_v1', 'mode': self.mode,
            'policy_sha256': POLICY_SHA256,
        }
        revision = snapshot.revision if snapshot else None
        if configuration is None or not configuration['enabled']:
            self.slot.disable()
            return {'schema_version': SCHEMA, 'mode': 'off', 'state': 'disabled',
                    'policy_sha256': POLICY_SHA256, 'catalog_revision': revision,
                    'frame_id': frame_id, 'jpeg_sha256': digest,
                    'modules': [], 'frame_suppression': False, 'semantic_modules_changed': False}
        if configuration['policy_sha256'] != POLICY_SHA256:
            raise ValueError('installed selection policy differs from catalog')
        key = fingerprint(configuration)
        self.slot.configure(key, configuration['plugin'], configuration)
        with self.slot.acquire(key) as plugin:
            result = plugin.select(image, targets, metadata, frame_id, digest, detection_complete=detection_complete)
        return {**result, 'state': 'complete', 'catalog_revision': revision}

    def close(self):
        self.slot.close()
