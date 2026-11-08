import importlib.util
import json
from pathlib import Path

import pytest


path = Path(__file__).parents[1] / "deploy/sydney-trial/prepare-any2api-profile.py"
spec = importlib.util.spec_from_file_location("provider_profile", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


@pytest.mark.parametrize("role", ["text", "vision"])
@pytest.mark.parametrize("protocol,endpoint", [("chat_completions", "chat/completions"), ("responses", "responses")])
def test_profile_is_scoped_and_patch_contains_no_credentials(role, protocol, endpoint):
    name, secret, patch = module.build_profile("mimo", "mimo-v2.6-flash", role, protocol,
                                                {"base_url": "https://example.test/v1", "api_key": "private-key"})
    assert secret["stringData"]["api-url"].endswith(endpoint)
    assert name.startswith("any2api-" + role + "-mimo-")
    assert "private-key" not in json.dumps(patch) and "example.test" not in json.dumps(patch)
    entries = patch["spec"]["template"]["spec"]["containers"][0]["env"]
    names = {role.upper() + x for x in ("_API_URL", "_API_KEY", "_MODEL", "_API_STYLE", "_JSON_MODE")}
    names.add('MODEL_TIMEOUT_SECONDS')
    if role == 'vision':
        names.update({'VISION_IMAGE_TRANSPORT','VISION_FILE_UPLOAD_URL'})
        assert secret['stringData']['file-upload-url']=='https://example.test/v1/files'
    assert {e["name"] for e in entries} == names


def test_profile_rejects_unsafe_endpoints_and_roles():
    with pytest.raises(ValueError):
        module.build_profile("mimo", "mimo-v2.6-flash", "vision", "responses", {"base_url": "https://user:pass@example.test/v1", "api_key": "k"})
    with pytest.raises(ValueError):
        module.build_profile("mimo", "mimo-v2.6-flash", "other", "responses", {"base_url": "https://example.test/v1", "api_key": "k"})


@pytest.mark.parametrize('provider,model', [('glm','glm-5.3'),('other','GLM-5.3'),('other','zai/glm-5.3')])
def test_removed_glm_is_rejected_before_credentials_are_used(provider,model):
    with pytest.raises(ValueError,match='GLM was removed'):
        module.build_profile(provider,model,'vision','responses',{})


def test_removed_provider_cli_never_reads_cluster_or_credentials(monkeypatch):
    monkeypatch.setattr(module.sys,'argv',['prepare-any2api-profile.py','unused','glm','glm-5.3','text','responses'])
    def forbidden(*args,**kwargs):
        pytest.fail('removed provider must be rejected before invoking kubectl')
    monkeypatch.setattr(module.subprocess,'run',forbidden)
    with pytest.raises(SystemExit,match='GLM was removed'):
        module.main()
