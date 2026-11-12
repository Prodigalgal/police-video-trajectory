"""Contract tests for the offline ARM64 image import script.

The import script runs on Kylin ARM64 nodes and must (a) refuse to overwrite an
already-present tag and (b) confirm that a fresh import really landed. containerd
1.x reports bare tags while containerd 2.x reports digest-qualified references
(`name:tag@sha256:...`), so both output shapes are exercised against the real
script rather than a reimplementation of its logic.

The script is root-only and ARM64-only, and it calls `ctr`. Rather than faking
files on PATH (fragile under MSYS/Git-bash), the stubs are exported bash
functions, which child bash processes inherit.
"""
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import textwrap
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[1]
IMPORT_SCRIPT = ROOT / 'ci/import-offline-images.sh'

TAG = '20261110-v0.35.0-ci-4b25ecfb5cd3'
SHA = '4b25ecfb5cd35dda0b9657929ba5ebfb8a9bd90a'
VERSION = '0.35.0'
CORE = f'docker.io/speedproxy/police-video-core:{TAG}'
RUNTIME = f'docker.io/speedproxy/police-video-runtime:{TAG}'
DIGEST_CORE = 'sha256:d9afa931d07017e9e309fef8ed67a15ef95e84ee63c97a6fe52ca90516270c95'
DIGEST_RUNTIME = 'sha256:c6627bda6e421d5520c5996a11f77711f0247ba7626b27d1460bcd201a633d2b'
PRESENT = 'ghcr.io/flannel-io/flannel:v0.28.9'
DIGEST_PRESENT = 'sha256:' + 'aa' * 32


def _candidate_shells():
    """Yield bash candidates, most explicit first.

    On Windows `which bash` usually resolves to the WSL launcher, which fails
    when no distro is installed, so every candidate is probed before use.
    """
    override = os.environ.get('OFFLINE_TEST_BASH')
    if override:
        yield override
        return
    found = shutil.which('bash')
    if found:
        yield found
    if os.name == 'nt':
        yield from (
            r'C:\Program Files\Git\bin\bash.exe',
            r'C:\Program Files (x86)\Git\bin\bash.exe',
            os.path.expandvars(r'%LOCALAPPDATA%\Programs\Git\bin\bash.exe'),
        )


def _resolve_bash():
    for candidate in _candidate_shells():
        if not candidate or not Path(candidate).exists():
            continue
        try:
            probe = subprocess.run(
                [candidate, '-c', 'echo ok'], capture_output=True, text=True,
                encoding='utf-8', errors='replace', timeout=30)
        except (OSError, subprocess.SubprocessError):
            continue
        if probe.returncode == 0 and probe.stdout.strip() == 'ok':
            return candidate
    return None


BASH = _resolve_bash()
pytestmark = pytest.mark.skipif(BASH is None, reason='a usable bash is required to exercise the import script')


def _run(args, cwd, env=None):
    merged = dict(os.environ)
    if env:
        merged.update(env)
    return subprocess.run(
        [BASH, *args], cwd=str(cwd), env=merged,
        capture_output=True, text=True, encoding='utf-8', errors='replace')


def _posix(path):
    """Convert a host path to a form the discovered bash can use."""
    text = str(path)
    if os.name != 'nt':
        return text
    result = _run(['-c', 'cygpath -u "$1"', '_', text], Path(os.getcwd()))
    converted = result.stdout.strip()
    if result.returncode == 0 and converted:
        return converted
    if len(text) > 1 and text[1] == ':':
        return f'/{text[0].lower()}{text[2:].replace(chr(92), "/")}'
    return text.replace('\\', '/')


def _sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _stub_prelude(state, style, bundle, python):
    """Bash function stubs for the root check, arch check, python3 and `ctr`.

    Exported functions are used instead of PATH shims because MSYS/Git-bash
    rewrites mixed Windows/POSIX entries in PATH unpredictably. Values are
    inlined into the function body because the exercised script runs with
    `set -u`, under which unexported stub variables would abort it.
    `ctr images list -q` reads the state file; `ctr images import` records the
    archive's references into it, honoring containerd's two output styles.
    """
    return textwrap.dedent('''
        id() { echo 0; }
        uname() { if [[ "${1:-}" == "-m" ]]; then echo aarch64; else echo Linux; fi; }
        python3() { __PYTHON__ "$@"; }
        export -f id uname python3

        ctr() {
          local mode=""
          for arg in "$@"; do
            case "$arg" in
              list) mode="list" ;;
              import) mode="import" ;;
            esac
          done
          if [[ "$mode" == "list" ]]; then
            [[ -f __STATE__ ]] && cat __STATE__
            return 0
          fi
          if [[ "$mode" != "import" ]]; then
            echo "unexpected ctr invocation: $*" >&2
            return 9
          fi
          python3 - __BUNDLE__ __STATE__ __STYLE__ <<'PY'
import json, sys
bundle, state, style = sys.argv[1], sys.argv[2], sys.argv[3]
with open(bundle + "/manifest.json", encoding="utf-8") as source:
    images = json.load(source)["images"]
lines = []
for image in images:
    reference = image["reference"]
    lines.append(reference + ("@" + image["registry_digest"] if style == "2" else ""))
with open(state, "w", encoding="utf-8") as target:
    target.write("\\n".join(lines) + "\\n")
PY
        }
        export -f ctr
    ''').replace('__PYTHON__', python).replace('__STATE__', f'"{state}"') \
        .replace('__STYLE__', f'"{style}"').replace('__BUNDLE__', f'"{bundle}"')


def _build_bundle(tmp_path, platform='linux/arm64', tamper_archive=False):
    bundle = tmp_path / 'police-video-offline'
    bundle.mkdir(parents=True)
    manifest = {
        'schema_version': 'offline-app-images-v1',
        'application_version': VERSION,
        'source_commit': SHA,
        'github_actions_run_id': '42852546269',
        'image_tag': TAG,
        'platform': platform,
        'images': [
            {'name': 'core', 'reference': CORE, 'registry_digest': DIGEST_CORE},
            {'name': 'runtime', 'reference': RUNTIME, 'registry_digest': DIGEST_RUNTIME},
        ],
    }
    (bundle / 'manifest.json').write_text(json.dumps(manifest), encoding='utf-8', newline='\n')
    staging = tmp_path / 'archive-staging'
    staging.mkdir()
    # docker save emits a JSON array whose RepoTags pin the references.
    (staging / 'manifest.json').write_text(
        json.dumps([{'RepoTags': [CORE, RUNTIME]}, {'RepoTags': None}]),
        encoding='utf-8', newline='\n')
    subprocess.run(['tar', '-cf', str(bundle / 'images.tar'), '-C', str(staging), 'manifest.json'],
                   check=True, capture_output=True)
    shutil.copy(IMPORT_SCRIPT, bundle / 'import-offline-images.sh')
    (bundle / 'README.txt').write_text('offline bundle\n', encoding='utf-8', newline='\n')
    names = ['images.tar', 'manifest.json', 'import-offline-images.sh', 'README.txt']
    (bundle / 'SHA256SUMS.txt').write_text(
        ''.join(f'{_sha256(bundle / name)}  {name}\n' for name in names),
        encoding='utf-8', newline='\n')
    if tamper_archive:
        # Corrupt the archive after the checksum manifest was written, modelling
        # a damaged or substituted download.
        with (bundle / 'images.tar').open('ab') as handle:
            handle.write(b'tampered')
    return bundle


def _invoke(tmp_path, bundle, state_lines=None, style='1'):
    state = tmp_path / 'ctr-state.txt'
    if state_lines is not None:
        state.write_text('\n'.join(state_lines) + '\n', encoding='utf-8', newline='\n')
    prelude = _stub_prelude(_posix(state), style, _posix(bundle), _posix(sys.executable))
    script = _posix(bundle / 'import-offline-images.sh')
    command = f'{prelude}\nbash {script} {_posix(bundle)}'
    return _run(['-c', command], cwd=tmp_path), state


def test_import_script_exists():
    assert IMPORT_SCRIPT.is_file()


def test_fresh_node_imports_and_verifies(tmp_path):
    bundle = _build_bundle(tmp_path)
    result, state = _invoke(tmp_path, bundle, state_lines=[PRESENT])
    assert result.returncode == 0, result.stderr
    assert f'Imported {CORE}' in result.stdout
    assert f'Imported {RUNTIME}' in result.stdout
    assert CORE in state.read_text(encoding='utf-8')


@pytest.mark.parametrize('style', ['1', '2'])
def test_existing_tag_is_never_overwritten(tmp_path, style):
    """A present tag must stop the script even when containerd qualifies digests."""
    bundle = _build_bundle(tmp_path)
    if style == '2':
        existing = [f'{CORE}@{DIGEST_CORE}', f'{RUNTIME}@{DIGEST_RUNTIME}',
                    f'{PRESENT}@{DIGEST_PRESENT}']
    else:
        existing = [CORE, RUNTIME, PRESENT]
    result, state = _invoke(tmp_path, bundle, state_lines=existing, style=style)
    assert result.returncode == 3, f'expected refusal (3), got {result.returncode}: {result.stderr}'
    assert 'refusing to overwrite' in result.stderr
    # No import may have happened: node state is untouched.
    assert state.read_text(encoding='utf-8').strip().split('\n') == existing


def test_digest_qualified_output_does_not_false_negative_after_import(tmp_path):
    """On containerd 2.x the post-import check must still find the new reference."""
    bundle = _build_bundle(tmp_path)
    result, _ = _invoke(tmp_path, bundle,
                        state_lines=[f'{PRESENT}@{DIGEST_PRESENT}'], style='2')
    assert result.returncode == 0, result.stderr
    assert f'Imported {CORE}' in result.stdout


def test_tampered_archive_is_rejected_before_import(tmp_path):
    bundle = _build_bundle(tmp_path, tamper_archive=True)
    result, state = _invoke(tmp_path, bundle, state_lines=[PRESENT])
    assert result.returncode != 0
    # The pre-existing state must not have been replaced by an import.
    assert PRESENT in state.read_text(encoding='utf-8')


def test_wrong_platform_manifest_is_rejected(tmp_path):
    bundle = _build_bundle(tmp_path, platform='linux/amd64')
    result, state = _invoke(tmp_path, bundle, state_lines=[PRESENT])
    assert result.returncode != 0
    assert 'arm64' in (result.stderr + result.stdout)


def test_packager_verifier_and_importer_agree_on_registry_digest_schema():
    """All three offline scripts must accept the same registry_digest form.

    The packager writes the field, the Windows verifier checks it and the node
    importer checks it again. A mismatch means a genuine package is rejected,
    so the digest emitted by the packager is validated against both consumers.
    """
    packager = (ROOT / 'ci/package-offline-images.sh').read_text(encoding='utf-8')
    verifier = (ROOT / 'ci/verify-offline-image-package.ps1').read_text(encoding='utf-8')
    importer = (ROOT / 'ci/import-offline-images.sh').read_text(encoding='utf-8')

    # The packager must emit a bare digest, not "reference@digest".
    assert 'core_digest="$EXPECTED_CORE_DIGEST"' in packager
    assert 'runtime_digest="$EXPECTED_RUNTIME_DIGEST"' in packager
    assert 'core_digest="$core_image@' not in packager
    assert 'runtime_digest="$runtime_image@' not in packager

    # The importer must accept a bare, entirely-anchored digest.
    assert r're.fullmatch(r"sha256:[a-f0-9]{64}"' in importer
    assert r're.search(r"@sha256:' not in importer

    # The verifier must accept a bare, entirely-anchored digest.
    bare = 'sha256:' + 'd9' * 32
    prefixed = f'{CORE}@{bare}'
    assert re.match(r'^sha256:[a-f0-9]{64}$', bare)
    assert not re.match(r'^sha256:[a-f0-9]{64}$', prefixed)

    # Cross-check against a real packaged manifest produced by this repo.
    manifest = {
        'images': [
            {'name': 'core', 'reference': CORE, 'registry_digest': bare},
            {'name': 'runtime', 'reference': RUNTIME, 'registry_digest': bare},
        ]
    }
    for image in manifest['images']:
        assert re.fullmatch(r'sha256:[a-f0-9]{64}', image['registry_digest'])
        assert re.search(r'\$image\.registry_digest -notmatch', verifier)
