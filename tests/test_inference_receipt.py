"""Protocol consistency tests; these fixtures never measure visual accuracy."""
import hashlib
import json
from pathlib import Path
from dataclasses import replace
import httpx
import pytest
from fastapi.testclient import TestClient
from services.ai_gateway.app import create_app
from services.ai_gateway.contracts import FrameResult, VisibleObject, AppearanceDescription
from services.ai_gateway.inference_receipt import canonical, sha
from test_ai_gateway import settings, response, FRAME, IMAGE, KEY, TOKEN


@pytest.mark.parametrize('style',['chat_completions','responses'])
def test_frozen_receipt_is_bound_to_source_prompt_schema_and_cache(style,tmp_path):
    calls=[]
    def upstream(request):
        calls.append(request)
        assert json.loads(request.content)['stream'] is True
        return response(FRAME,style)
    cfg=replace(settings(style),result_cache_dir=str(tmp_path))
    with TestClient(create_app(cfg,httpx.MockTransport(upstream))) as client:
        results=[client.post('/internal/v1/analyze-frame',json={'frame_id':'contract','image_base64':IMAGE},headers={'Authorization':'Bearer '+TOKEN}) for _ in range(2)]
    assert [r.status_code for r in results]==[200,200]
    first,second=[r.json() for r in results];receipt=first['inference_receipt']
    assert receipt==second['inference_receipt'] and len(calls)==1
    assert receipt['streaming'] is True and 0<receipt['timeout_budget_ms']<=360000
    import base64
    assert receipt['image_sha256']==hashlib.sha256(base64.b64decode(IMAGE)).hexdigest()
    unsigned={k:v for k,v in receipt.items() if k!='receipt_sha256'}
    assert receipt['receipt_sha256']==sha(canonical(unsigned))
    for kind in ('request','response','schema'):assert receipt[kind+'_sha256']==sha(receipt[kind+'_json'])
    assert json.loads(receipt['response_json'])==first['result']
    manifest=json.loads(receipt['request_json'])
    assert receipt['prompt_sha256']==sha(manifest['prompt']) and manifest['result_schema_sha256']==receipt['schema_sha256']
    assert not any(secret in canonical(receipt) for secret in (KEY,TOKEN,IMAGE,'https://model.invalid'))


def test_language_gate_rejects_english_descriptions_and_attributes_without_changing_protocol_fields():
    from pydantic import ValidationError
    for value in ({'description':'a white vehicle'},{'attributes':{'vehicle_color':'white'}},{'attributes':{'carrying':['backpack']}}):
        with pytest.raises(ValidationError):VisibleObject.model_validate({'object_type':'vehicle',**value})
    valid=VisibleObject.model_validate({'object_type':'vehicle','description':'白色 SUV 驶过路口','attributes':{'vehicle_color':'白色','transport_type':'SUV 车型'}})
    assert valid.object_type=='vehicle' and valid.attributes.transport_type=='SUV 车型'
    with pytest.raises(ValidationError):AppearanceDescription(vehicle_color='white')
    assert FrameResult(objects=[]).schema_version=='1.0'


def test_java_shared_receipt_fixture_uses_the_same_canonical_hash():
    fixture=Path('services/core_api/src/test/resources/inference-receipt-contract.json')
    receipt=json.loads(fixture.read_text(encoding='utf-8'))
    assert receipt['receipt_sha256']==sha(canonical({k:v for k,v in receipt.items() if k!='receipt_sha256'}))
    assert receipt['response_sha256']==sha(receipt['response_json'])
