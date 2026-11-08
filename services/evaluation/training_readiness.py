"""Read-only data qualification, not label verification or a training exporter."""
from collections import Counter
import hashlib
import re


SCOPE = [f'recording-0{i}' for i in range(1, 5)]


def digest(value):
    if not isinstance(value, str) or not re.fullmatch(r'[a-f0-9]{64}', value):
        raise ValueError('invalid evidence digest')
    return value


def provenance(label):
    """Never upgrade missing/AI provenance through the legacy verified status."""
    if label.get('annotation_source') == 'ai_visual_review' or label.get('review_source') == 'ai_visual_review':
        return 'ai_provisional'
    if label.get('status') == 'verified':
        if label.get('annotation_source') == 'human' and label.get('review_source') == 'human':
            return 'explicit_human_state_identity_unverified'
        if label.get('annotation_source') in {None, 'human'} and label.get('review_source') in {None, 'human'}:
            return 'legacy_human_state_provenance_unverified'
    return 'unreviewed_or_unknown'


def inventory(dataset, ledger, ai_reference, *, previously_used_for_development):
    if not isinstance(previously_used_for_development, bool):
        raise ValueError('development exposure must be explicit')
    if ledger.get('dataset_id') != dataset.identity or ledger.get('source_sha256') != dataset.source_hashes:
        raise ValueError('ledger dataset or source differs')
    if sorted(dataset.source_hashes) != SCOPE or not 1 <= len(dataset.frames) <= 500:
        raise ValueError('only bounded four-point dataset permitted')
    for value in dataset.source_hashes.values():
        digest(value)
    ledger_rows = ledger.get('annotations')
    ai_rows = ai_reference.get('frames')
    if not isinstance(ledger_rows, dict) or not isinstance(ai_rows, dict):
        raise ValueError('missing reference rows')
    if set(ledger_rows) != set(dataset.frames) or set(ai_rows) != set(dataset.frames):
        raise ValueError('reference coverage differs from dataset')
    if ai_reference.get('annotation_source') != 'ai_visual_review':
        raise ValueError('independent reference must retain AI provenance')
    groups = {name: {'frames': 0, 'ledger_provenance': Counter(), 'ledger_complete': 0,
                     'ai_complete': 0, 'ai_partial': 0, 'ai_boxes': 0, 'ai_night_frames': 0,
                     'ai_night_complete': 0, 'ai_empty_frames': 0,
                     'source_sha256': dataset.source_hashes[name]} for name in SCOPE}
    samples = []
    for identity, frame in sorted(dataset.frames.items()):
        camera = frame['camera']
        if camera not in groups or frame.get('id') != identity or not re.fullmatch(r'recording-0[1-4]-\d{6}', identity):
            raise ValueError('frame identity or scope differs')
        if not identity.startswith(camera + '-'):
            raise ValueError('frame camera differs')
        digest(frame['sha256'])
        # Dataset.image rechecks the actual JPEG rather than trusting metadata.
        if hashlib.sha256(dataset.image(identity).read_bytes()).hexdigest() != frame['sha256']:
            raise ValueError('actual JPEG differs')
        label, ai = ledger_rows[identity], ai_rows[identity]
        for row in (label, ai):
            if row.get('image_sha256') != frame['sha256'] or type(row.get('boxes_complete')) is not bool:
                raise ValueError('reference image or completeness differs')
            if not isinstance(row.get('objects'), list) or len(row['objects']) > 100:
                raise ValueError('invalid bounded object list')
            conditions = row.get('conditions')
            if not isinstance(conditions, list) or any(not isinstance(v, str) for v in conditions):
                raise ValueError('invalid condition tags')
            if 'day' in conditions and 'night' in conditions:
                raise ValueError('conflicting day/night tags')
        if ai.get('annotation_source') != 'ai_visual_review':
            raise ValueError('AI row provenance differs')
        tier = provenance(label)
        group = groups[camera]
        group['frames'] += 1
        group['ledger_provenance'][tier] += 1
        group['ledger_complete'] += int(label['boxes_complete'])
        group['ai_complete'] += int(ai['boxes_complete'])
        group['ai_partial'] += int(not ai['boxes_complete'])
        group['ai_boxes'] += len(ai['objects'])
        group['ai_night_frames'] += int('night' in ai['conditions'])
        group['ai_night_complete'] += int('night' in ai['conditions'] and ai['boxes_complete'])
        group['ai_empty_frames'] += int(ai.get('scene') == 'empty')
        samples.append({'frame_id': identity, 'image_sha256': frame['sha256'],
                        'source_group_sha256': dataset.source_hashes[camera],
                        'ledger_provenance': tier, 'ledger_boxes_complete': label['boxes_complete'],
                        'ai_boxes_complete': ai['boxes_complete'],
                        'development_exposed': previously_used_for_development,
                        'formal_split': None, 'training_eligible': False,
                        'exclusion_reasons': ['independent_review_and_authorized_snapshot_not_established']
                            + (['development_exposed_not_pristine_holdout'] if previously_used_for_development else [])})
    for group in groups.values():
        group['ledger_provenance'] = dict(group['ledger_provenance'])
    return {'schema': 'model-training-readiness-v1', 'status': 'insufficient_reference',
            'dataset_id': dataset.identity, 'scope': SCOPE, 'frame_count': len(samples),
            'distinct_source_hash_groups': len(set(dataset.source_hashes.values())),
            'source_groups_are_independent_sessions': None,
            'by_camera': groups, 'ledger_provenance': dict(Counter(v['ledger_provenance'] for v in samples)),
            'ai_reference': {'frames': len(ai_rows), 'boxes': sum(g['ai_boxes'] for g in groups.values()),
                             'complete_frames': sum(g['ai_complete'] for g in groups.values()),
                             'partial_frames': sum(g['ai_partial'] for g in groups.values()),
                             'night_complete_frames': sum(g['ai_night_complete'] for g in groups.values()),
                             'empty_frames': sum(g['ai_empty_frames'] for g in groups.values())},
            'training_eligible_frames': 0, 'pristine_holdout_frames': None if not previously_used_for_development else 0,
            'training_export_created': False, 'label_correctness_verified': False,
            'production_drift_verdict': 'not_evaluable', 'samples': samples,
            'limitations': ['Metadata and pixel binding do not establish label correctness or human identity.',
                            'Existing development data cannot establish production drift or independent accuracy.',
                            'Source SHA groups do not prove independent recording sessions.',
                            'Partial boxes must not turn unannotated targets into background.',
                            'No formal split, human confirmation, training or deployment is performed.']}
