"""Frozen inference inputs and validated outputs; no credentials, endpoint or image bytes."""
from datetime import datetime,timezone
import hashlib,json,os
from uuid import uuid4
from . import __version__
from .errors import GatewayError

def canonical(value):return json.dumps(value,ensure_ascii=False,sort_keys=True,separators=(',',':'),allow_nan=False)
def sha(value):return hashlib.sha256(value.encode()).hexdigest()

def make_receipt(config,prompt,prompt_version,result,metadata,image_bytes,budget):
    schema=canonical(type(result).model_json_schema())
    request=canonical({'model':config.model,'model_binding':config.binding_id or None,'protocol':config.style,
        'stream':True,'json_mode':config.json_mode,'image_transport':config.image_transport,
        'output_mode':config.output_mode,'reasoning_effort':config.reasoning_effort or None,
        'prompt':prompt,'result_schema_sha256':sha(schema)})
    response=canonical(result.model_dump(mode='json'))
    receipt={'schema_version':'analysis-receipt-v1','receipt_id':str(uuid4()),'gateway_version':__version__,
        'source_revision':os.environ.get('VIDEO_SOURCE_REVISION'),'configured_model':config.model,
        'model_binding':config.binding_id or None,'protocol':config.style,'prompt_version':prompt_version,
        'image_sha256':hashlib.sha256(image_bytes).hexdigest() if image_bytes is not None else None,
        'prompt_sha256':sha(prompt),'schema_sha256':sha(schema),'schema_json':schema,'request_json':request,'request_sha256':sha(request),
        'response_json':response,'response_sha256':sha(response),'streaming':True,
        'timeout_budget_ms':max(1,min(360000,round(budget*1000))),'latency_ms':metadata.get('latency_ms',0),
        'created_at':datetime.now(timezone.utc).isoformat()}
    receipt['receipt_sha256']=sha(canonical(receipt))
    if len(canonical(receipt).encode())>384*1024:raise GatewayError('模型追溯回执超过大小限制',502)
    return receipt
