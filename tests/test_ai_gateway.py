import asyncio
import base64
import json

import httpx
import pytest
from fastapi.testclient import TestClient

from services.ai_gateway.app import create_app, failure_category
from services.ai_gateway.config import ModelConfig, Settings


def test_model_budget_is_shared_across_independent_gateways_and_reserves_text_capacity():
    from dataclasses import replace
    from services.ai_gateway.admission import ModelAdmissionClient
    from services.ai_gateway.client import ModelClient
    from services.ai_gateway.errors import GatewayError
    async def run():
        leases={};requests=[]
        async def control(request):
            if request.url.path.endswith('/readyz'):return httpx.Response(200,json={'ready':True})
            value=json.loads(request.content);requests.append(value);identity=value['leaseId']
            if request.url.path.endswith('/release'):
                leases.pop(identity,None);return httpx.Response(200,json={'released':True})
            if len(leases)>=2 or value['vision'] and sum(leases.values())>=1:return httpx.Response(200,json={'granted':False})
            assert value['ttlSeconds']==375;leases[identity]=value['vision'];return httpx.Response(200,json={'granted':True,'lease_id':identity})
        cfg=Settings(shared_token='coordination-test',control_url='https://core.invalid',control_ca_file='unused',cluster_admission=True,max_inflight=2)
        async with httpx.AsyncClient() as upstream:
            first=ModelClient(cfg,upstream,ModelAdmissionClient(replace(cfg,control_consumer='gateway-a'),httpx.MockTransport(control)))
            second=ModelClient(cfg,upstream,ModelAdmissionClient(replace(cfg,control_consumer='gateway-b'),httpx.MockTransport(control)))
            try:
                async with first.request_slot(True):
                    with pytest.raises(GatewayError) as full:
                        async with second.request_slot(True):raise AssertionError('vision reserve was bypassed')
                    assert full.value.status_code==429
                    async with second.request_slot(False):
                        assert len(leases)==2
                        with pytest.raises(GatewayError):
                            async with first.request_slot(False):raise AssertionError('total limit was bypassed')
                assert not leases and first.active_total==second.active_total==0
                assert {value['consumer'] for value in requests}=={'gateway-a','gateway-b'}
            finally:await first.close();await second.close()
    asyncio.run(run())


def test_cluster_admission_cancellation_unknown_ack_and_outage_release_or_fail_closed():
    from services.ai_gateway.admission import ModelAdmissionClient
    from services.ai_gateway.errors import GatewayError
    async def run():
        leases=set();acquired=asyncio.Event();release_failed=False;lose_ack=False
        async def control(request):
            nonlocal release_failed
            if request.url.path.endswith('/readyz'):return httpx.Response(503,json={'ready':False})
            body=json.loads(request.content);identity=body['leaseId']
            if request.url.path.endswith('/acquire'):
                leases.add(identity)
                if lose_ack:raise httpx.ReadTimeout('protocol acknowledgement lost',request=request)
                return httpx.Response(200,json={'granted':True,'lease_id':identity})
            if release_failed:raise httpx.ConnectError('protocol unavailable',request=request)
            leases.discard(identity);return httpx.Response(200,json={'released':True})
        cfg=Settings(shared_token='coordination-test',control_url='https://core.invalid',control_ca_file='unused',cluster_admission=True)
        client=ModelAdmissionClient(cfg,httpx.MockTransport(control))
        try:
            async def hold():
                async with client.slot(True):acquired.set();await asyncio.Event().wait()
            pending=asyncio.create_task(hold());await acquired.wait();assert len(leases)==1
            pending.cancel();await asyncio.gather(pending,return_exceptions=True);assert not leases
            lose_ack=True
            with pytest.raises(GatewayError):
                async with client.slot(False):raise AssertionError('unknown grant must not run inference')
            assert not leases
            assert await client.ready() is False
            lose_ack=False;release_failed=True
            async with client.slot(False):pass
            assert len(leases)==1 # The Core expiry lease is the recovery boundary.
        finally:await client.close()
    asyncio.run(run())


TOKEN = "test-gateway-token"
KEY = "test-model-secret"
QUERY = {"query": "昨天下午穿黑上衣背包的人", "reference_time": "2026-10-31T10:00:00+08:00"}
PLAN = {"schema_version": "1.0", "target_type": "person", "time_range": {"start": "2026-10-30T12:00:00+08:00", "end": "2026-10-30T18:00:00+08:00"},
        "attributes": {"upper_color": "黑色", "carrying": ["背包"]}, "text_terms": [], "clarifications": []}
FRAME = {"schema_version": "1.0", "objects": [{"object_type": "person", "attributes": {"upper_color": "黑色", "carrying": ["背包"]}}]}
IMAGE = base64.b64encode(b"\xff\xd8\xffsynthetic-image").decode()


def settings(style="chat_completions", retries=0, timeout=360):
    cfg = ModelConfig("https://model.invalid/v1/" + ("responses" if style == "responses" else "chat/completions"), KEY, "mock-model", style)
    return Settings(shared_token=TOKEN, vision=cfg, text=cfg, retries=retries, timeout_seconds=timeout)


def sse(events):
    return b"".join(("data: " + (e if isinstance(e, str) else json.dumps(e, ensure_ascii=False)) + "\r\n\r\n").encode() for e in events)


def response_events(result, style):
    content = json.dumps(result, ensure_ascii=False)
    split = len(content) // 2
    if style == "responses":
        return [{"type": "response.output_text.delta", "delta": content[:split]},
                {"type": "response.output_text.delta", "delta": content[split:]},
                {"type": "response.completed", "response": {"status": "completed", "model": "mock-model"}}, "[DONE]"]
    return [{"model": "mock-model", "choices": [{"index": 0, "delta": {"content": content[:split]}, "finish_reason": None}]},
            {"choices": [{"index": 0, "delta": {"content": content[split:]}, "finish_reason": None}]},
            {"choices": [{"index": 0, "delta": {}, "finish_reason": "stop"}]}, "[DONE]"]


def response(result, style):
    return httpx.Response(200, headers={"content-type": "text/event-stream"}, content=sse(response_events(result, style)))


@pytest.mark.parametrize("style", ["chat_completions", "responses"])
@pytest.mark.parametrize("kind", ["query", "frame"])
def test_protocols_stream_real_schema_and_keep_secrets_internal(style, kind):
    calls = []

    def upstream(req):
        calls.append(req)
        body = json.loads(req.content)
        assert req.headers["authorization"] == "Bearer " + KEY
        assert body["model"] == "mock-model" and body["stream"] is True
        content = body["input"][0]["content"] if style == "responses" else body["messages"][0]["content"]
        assert '所有面向用户的描述、动作、属性值和澄清问题使用简体中文' in content[0]['text']
        if kind == "frame":
            img = content[1]
            assert img["type"] == ("input_image" if style == "responses" else "image_url")
            url = img["image_url"] if style == "responses" else img["image_url"]["url"]
            assert url == "data:image/jpeg;base64," + IMAGE
        else:
            assert "2026-10-31T10:00:00+08:00" in content[0]["text"]
            assert QUERY["query"] in content[0]["text"]
        return response(FRAME if kind == "frame" else PLAN, style)

    with TestClient(create_app(settings(style), httpx.MockTransport(upstream))) as client:
        assert client.get("/readyz").status_code == 200
        path = "/internal/v1/analyze-frame" if kind == "frame" else "/internal/v1/interpret-query"
        data = {"frame_id": "frame-1", "image_base64": IMAGE} if kind == "frame" else QUERY
        result = client.post(path, json=data, headers={"Authorization": "Bearer " + TOKEN})
    assert result.status_code == 200, result.text
    assert len(calls) == 1 and result.json()["protocol"] == style
    if kind == "frame":
        assert result.json()["result"]["objects"][0]["attributes"]["upper_color"] == "黑色"
    else:
        assert result.json()["result"]["target_type"] == "person"
    assert KEY not in result.text and TOKEN not in result.text and IMAGE not in result.text


def test_rejects_missing_auth_invalid_inputs_and_unconfigured_models():
    calls = []
    transport = httpx.MockTransport(lambda req: calls.append(req) or httpx.Response(500))
    with TestClient(create_app(settings(), transport)) as client:
        assert client.post("/internal/v1/interpret-query", json=QUERY).status_code == 401
        auth = {"Authorization": "Bearer " + TOKEN}
        assert client.post("/internal/v1/interpret-query", headers=auth, json={**QUERY, "reference_time": "2026-10-31T10:00:00"}).status_code == 422
        bad_image = client.post("/internal/v1/analyze-frame", headers=auth, json={"frame_id": "a", "image_base64": IMAGE, "media_type": "image/png"})
        assert bad_image.status_code == 422 and IMAGE not in bad_image.text
        assert calls == []
    with TestClient(create_app(Settings(shared_token=TOKEN), transport)) as client:
        assert client.get("/readyz").status_code == 503
        assert client.post("/internal/v1/interpret-query", json=QUERY, headers=auth).status_code == 503


@pytest.mark.parametrize("events", [
    ["not-json"],
    [{"type": "response.output_text.delta", "delta": "{}"}],
    [{"type": "response.output_text.delta", "delta": "not-json"}, {"type": "response.completed", "response": {"status": "completed"}}],
    [{"type": "response.output_text.delta", "delta": '{"target_type":"person","unexpected_dsl":{}}'}, {"type": "response.completed", "response": {"status": "completed"}}],
    [{"type": "response.failed", "response": {"status": "failed"}}],
    [{"type": "response.refusal.delta", "delta": "no"}],
])
def test_invalid_incomplete_or_rejected_streams_fail_closed(events):
    transport = httpx.MockTransport(lambda req: httpx.Response(200, headers={"content-type": "text/event-stream"}, content=sse(events)))
    with TestClient(create_app(settings("responses"), transport)) as client:
        result = client.post("/internal/v1/interpret-query", json=QUERY, headers={"Authorization": "Bearer " + TOKEN})
    assert result.status_code == 502
    assert KEY not in result.text and "unexpected_dsl" not in result.text


def test_temporary_failure_retries_and_does_not_echo_upstream_error():
    calls = []

    def transient(req):
        calls.append(req)
        if len(calls) == 1:
            return httpx.Response(429, text=KEY)
        return response(PLAN, "chat_completions")

    with TestClient(create_app(settings(retries=1), httpx.MockTransport(transient))) as client:
        result = client.post("/internal/v1/interpret-query", json=QUERY, headers={"Authorization": "Bearer " + TOKEN})
    assert result.status_code == 200 and len(calls) == 2 and KEY not in result.text


def test_sse_network_boundaries_can_split_chinese_characters():
    class SplitBytes(httpx.AsyncByteStream):
        async def __aiter__(self):
            content = sse(response_events(PLAN, "responses"))
            for index in range(0, len(content), 2):
                yield content[index:index + 2]
    transport = httpx.MockTransport(lambda req: httpx.Response(200, headers={"content-type": "text/event-stream"}, stream=SplitBytes()))
    with TestClient(create_app(settings("responses"), transport)) as client:
        result = client.post("/internal/v1/interpret-query", json=QUERY, headers={"Authorization": "Bearer " + TOKEN})
    assert result.status_code == 200 and result.json()["result"]["attributes"]["upper_color"] == "黑色"


def test_non_stream_response_and_truncated_chat_are_rejected():
    non_stream = httpx.MockTransport(lambda req: httpx.Response(200, json={"choices": []}))
    truncated = httpx.MockTransport(lambda req: httpx.Response(200, headers={"content-type": "text/event-stream"}, content=sse([
        {"choices": [{"index": 0, "delta": {"content": "{}"}, "finish_reason": "length"}]}, "[DONE]"])))
    for transport in (non_stream, truncated):
        with TestClient(create_app(settings(), transport)) as client:
            result = client.post("/internal/v1/interpret-query", json=QUERY, headers={"Authorization": "Bearer " + TOKEN})
        assert result.status_code == 502


def test_total_timeout_includes_upstream_wait():
    async def delayed(req):
        await asyncio.sleep(0.1)
        return response(PLAN, "chat_completions")
    with TestClient(create_app(settings(timeout=0.02), httpx.MockTransport(delayed))) as client:
        result = client.post("/internal/v1/interpret-query", json=QUERY, headers={"Authorization": "Bearer " + TOKEN})
    assert result.status_code == 504
    assert Settings().timeout_seconds == 360


def test_invalid_endpoint_configuration_is_not_ready():
    assert not ModelConfig("https://[invalid", KEY, "model", "responses").valid
    assert not ModelConfig("https://user:pass@example.test/v1/responses", KEY, "model", "responses").valid


def test_model_failure_logs_contain_bounded_reason_and_no_input(caplog):
    transport = httpx.MockTransport(lambda req: httpx.Response(400, text="upstream-secret-response"))
    with TestClient(create_app(settings(), transport)) as client:
        result = client.post("/internal/v1/interpret-query", json=QUERY, headers={"Authorization": "Bearer " + TOKEN})
    assert result.status_code == 502
    assert "operation=text reason=upstream_http_400 issue=none" in caplog.text
    assert KEY not in caplog.text and TOKEN not in caplog.text and QUERY["query"] not in caplog.text
    assert "upstream-secret-response" not in caplog.text
    assert failure_category("unexpected private data") == "other"


def test_contract_error_logs_only_bounded_issue_type(caplog):
    bad = {**PLAN, "private-user-description": "do-not-log-this"}
    transport = httpx.MockTransport(lambda req: response(bad, "chat_completions"))
    with TestClient(create_app(settings(), transport)) as client:
        result = client.post("/internal/v1/interpret-query", json=QUERY, headers={"Authorization": "Bearer " + TOKEN})
    assert result.status_code == 502
    assert "reason=invalid_contract issue=extra_forbidden" in caplog.text
    assert "private-user-description" not in caplog.text and "do-not-log-this" not in caplog.text


def test_text_and_vision_can_select_distinct_provider_profiles(monkeypatch):
    values = {"MODEL_API_URL": "https://primary.invalid/v1/chat/completions", "MODEL_API_KEY": "primary-key", "MODEL_NAME": "primary",
              "TEXT_API_URL": "https://text.invalid/v1/responses", "TEXT_API_KEY": "text-key", "TEXT_MODEL": "text-model", "TEXT_API_STYLE": "responses",
              "VISION_API_URL": "https://vision.invalid/v1/chat/completions", "VISION_API_KEY": "vision-key", "VISION_MODEL": "vision-model",
              "VISION_API_STYLE": "chat_completions", "MODEL_TIMEOUT_SECONDS": "360"}
    for name, value in values.items():
        monkeypatch.setenv(name, value)
    configured = Settings.from_env()
    assert configured.timeout_seconds == 360
    assert configured.text.valid and configured.text.style == "responses" and configured.text.model == "text-model"
    assert configured.vision.valid and configured.vision.style == "chat_completions" and configured.vision.model == "vision-model"
    assert "text-key" not in repr(configured) and "vision-key" not in repr(configured)
