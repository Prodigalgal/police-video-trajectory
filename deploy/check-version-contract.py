"""Verify one SemVer across source and optional immutable candidate artifacts."""
import argparse
import ast
import json
from pathlib import Path
import re
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def verify(root=ROOT, *, web_dist=False, jar=None):
    version = (root / 'VERSION').read_text().strip()
    if not re.fullmatch(r'(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)', version):
        raise ValueError('a formal x.y.z application version is required')
    found = {}
    for service in ('ai_gateway', 'web_gateway', 'video_worker', 'specialist_service'):
        tree = ast.parse((root / 'services' / service / '__init__.py').read_text(encoding='utf-8'))
        values = [ast.literal_eval(statement.value) for statement in tree.body if isinstance(statement, ast.Assign)
                  and any(isinstance(target, ast.Name) and target.id == '__version__' for target in statement.targets)]
        if len(values) != 1:
            raise ValueError('one runtime version per Python service is required')
        found[service] = values[0]
    gradle = (root / 'services/core_api/build.gradle').read_text(encoding='utf-8')
    found['core_api'] = re.search(r"(?m)^version = '([^']+)'$", gradle).group(1)
    package = json.loads((root / 'web/package.json').read_text())
    lock = json.loads((root / 'web/package-lock.json').read_text())
    found.update(web=package['version'], npm_lock=lock['version'], npm_root=lock['packages']['']['version'])
    migration = (root / 'services/core_api/src/main/resources/db/009-clue-collections.sql').read_text()
    found['migration_009'] = re.search(r'Application version: ([0-9.]+)\.', migration).group(1)
    module_migration=(root/'services/core_api/src/main/resources/db/010-independent-analysis-modules.sql').read_text()
    found['migration_010']=re.search(r'Application version: ([0-9.]+)\.',module_migration).group(1)
    input_migration=(root/'services/core_api/src/main/resources/db/011-temporal-analysis-inputs.sql').read_text()
    found['migration_011']=re.search(r'Application version: ([0-9.]+)\.',input_migration).group(1)
    media_migration=(root/'services/core_api/src/main/resources/db/012-recorded-media-clips.sql').read_text()
    found['migration_012']=re.search(r'Application version: ([0-9.]+)\.',media_migration).group(1)
    recording_migration=(root/'services/core_api/src/main/resources/db/013-same-session-recordings.sql').read_text()
    found['migration_013']=re.search(r'Application version: ([0-9.]+)\.',recording_migration).group(1)
    jobs_migration=(root/'services/core_api/src/main/resources/db/014-media-production-jobs.sql').read_text()
    found['migration_014']=re.search(r'Application version: ([0-9.]+)\.',jobs_migration).group(1)
    session_migration=(root/'services/core_api/src/main/resources/db/015-session-media-inputs.sql').read_text()
    found['migration_015']=re.search(r'Application version: ([0-9.]+)\.',session_migration).group(1)
    specialist_migration=(root/'services/core_api/src/main/resources/db/016-specialist-analysis-jobs.sql').read_text()
    found['migration_016']=re.search(r'Application version: ([0-9.]+)\.',specialist_migration).group(1)
    routing_migration=(root/'services/core_api/src/main/resources/db/017-specialist-selection-and-routes.sql').read_text()
    found['migration_017']=re.search(r'Application version: ([0-9.]+)\.',routing_migration).group(1)
    candidate_migration=(root/'services/core_api/src/main/resources/db/018-specialist-candidate-production.sql').read_text()
    found['migration_018']=re.search(r'Application version: ([0-9.]+)\.',candidate_migration).group(1)
    investigation_migration=(root/'services/core_api/src/main/resources/db/019-investigation-and-geography.sql').read_text()
    found['migration_019']=re.search(r'Application version: ([0-9.]+)\.',investigation_migration).group(1)
    ingestion_migration=(root/'services/core_api/src/main/resources/db/020-managed-ingestion-jobs.sql').read_text()
    found['migration_020']=re.search(r'Application version: ([0-9.]+)\.',ingestion_migration).group(1)
    export_migration=(root/'services/core_api/src/main/resources/db/021-evidence-exports.sql').read_text()
    found['migration_021']=re.search(r'Application version: ([0-9.]+)\.',export_migration).group(1)
    appearance_migration=(root/'services/core_api/src/main/resources/db/022-appearance-retrieval.sql').read_text()
    found['migration_022']=re.search(r'Application version: ([0-9.]+)\.',appearance_migration).group(1)
    review_migration=(root/'services/core_api/src/main/resources/db/023-recording-review.sql').read_text()
    found['migration_023']=re.search(r'Application version: ([0-9.]+)\.',review_migration).group(1)
    nearby_migration=(root/'services/core_api/src/main/resources/db/024-nearby-records.sql').read_text()
    found['migration_024']=re.search(r'Application version: ([0-9.]+)\.',nearby_migration).group(1)
    models_migration=(root/'services/core_api/src/main/resources/db/025-managed-models.sql').read_text()
    found['migration_025']=re.search(r'Application version: ([0-9.]+)\.',models_migration).group(1)
    specialist_control=(root/'services/core_api/src/main/resources/db/026-specialist-independent-control.sql').read_text()
    found['migration_026']=re.search(r'Application version: ([0-9.]+)\.',specialist_control).group(1)
    plate_projection=(root/'services/core_api/src/main/resources/db/027-reviewed-plate-index.sql').read_text()
    found['migration_027']=re.search(r'Application version: ([0-9.]+)\.',plate_projection).group(1)
    inference_receipts=(root/'services/core_api/src/main/resources/db/028-analysis-receipts.sql').read_text()
    found['migration_028']=re.search(r'Application version: ([0-9.]+)\.',inference_receipts).group(1)
    context_window=(root/'services/core_api/src/main/resources/db/029-context-window-media.sql').read_text()
    found['migration_029']=re.search(r'Application version: ([0-9.]+)\.',context_window).group(1)
    optional_features=(root/'services/core_api/src/main/resources/db/030-optional-pipeline-features.sql').read_text()
    found['migration_030']=re.search(r'Application version: ([0-9.]+)\.',optional_features).group(1)
    if web_dist:
        found['web_artifact'] = json.loads((root / 'web/dist/version.json').read_text())['version']
    if jar:
        with zipfile.ZipFile(jar) as archive:
            manifest = archive.read('META-INF/MANIFEST.MF').decode()
            found['core_artifact'] = re.search(r'(?m)^Implementation-Version: ([^\r\n]+)', manifest).group(1)
            build_entries=[name for name in ('META-INF/build-info.properties','BOOT-INF/classes/META-INF/build-info.properties') if name in archive.namelist()]
            if len(build_entries)!=1:raise ValueError('one packaged Core build-info is required')
            build = archive.read(build_entries[0]).decode()
            found['core_build_info'] = re.search(r'(?m)^build.version=(.+)$', build).group(1).strip()
    if any(value != version for value in found.values()):
        raise ValueError('application version contract mismatch: ' + json.dumps(found, sort_keys=True))
    return {'result': 'passed', 'application_version': version, 'components': found}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--web-dist', action='store_true')
    parser.add_argument('--jar', type=Path)
    args = parser.parse_args()
    print(json.dumps(verify(web_dist=args.web_dist, jar=args.jar)))


if __name__ == '__main__':
    main()
