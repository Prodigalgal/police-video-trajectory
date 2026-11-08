import pytest

from services.ai_gateway.client import build_body
from services.ai_gateway.config import ModelConfig, Settings


@pytest.mark.parametrize("style", ["chat_completions", "responses"])
def test_optional_json_mode_uses_protocol_specific_field_and_keeps_streaming(style):
    config = ModelConfig("https://example.invalid/v1", "fake-key", "fake-model", style, True)
    body = build_body(config, "Return JSON")
    assert body["stream"] is True
    if style == "responses":
        assert body["text"]["format"] == {"type": "json_object"} and "response_format" not in body
    else:
        assert body["response_format"] == {"type": "json_object"} and "text" not in body


def test_json_mode_supports_shared_and_per_modality_configuration(monkeypatch):
    monkeypatch.setenv("MODEL_JSON_MODE", "true")
    monkeypatch.setenv("VISION_JSON_MODE", "false")
    settings = Settings.from_env()
    assert settings.text.json_mode is True and settings.vision.json_mode is False
