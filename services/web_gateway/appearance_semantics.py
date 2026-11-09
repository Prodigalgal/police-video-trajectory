"""Describe only the selected ROI through the existing authenticated vision gateway."""
import asyncio
import io
import json
import logging
import math
import ssl
from urllib.parse import urlsplit
import httpx
from PIL import Image
from services.ai_gateway.contracts import AppearanceDescription

LOG=logging.getLogger('appearance-semantics')


def crop_target(image,box):
    with Image.open(io.BytesIO(image)) as source:
        width,height=source.size
        if width>20000 or height>20000 or width*height>16777216 or len(box)!=4 or not all(math.isfinite(v) for v in box):raise ValueError('invalid ROI')
        left,top,right,bottom=box
        if min(left,top)<0 or right>width or bottom>height or right-left<12 or bottom-top<12:raise ValueError('invalid ROI')
        target=source.crop((math.floor(left),math.floor(top),math.ceil(right),math.ceil(bottom))).convert('RGB')
        target.thumbnail((1280,1280))
        output=io.BytesIO();target.save(output,format='JPEG',quality=90)
        return output.getvalue()


async def describe_target(cfg,metadata,image,budget):
    def outcome(state):return {'state':state,'attributes':{},'uncertain_fields':[],'model_id':None,'prompt_version':None}
    if not metadata.semantic_assistance:return outcome('disabled')
    address=cfg.get('appearance_gateway_url','');token=cfg.get('appearance_gateway_token','');ca=cfg.get('appearance_gateway_ca','')
    parsed=urlsplit(address)
    if parsed.scheme!='https' or not parsed.hostname or parsed.path not in {'','/'} or any((parsed.username,parsed.password,parsed.query,parsed.fragment)) or not token or not ca:return outcome('unavailable')
    try:
        async with asyncio.timeout(budget):
            cropped=await asyncio.to_thread(crop_target,image,metadata.bbox_xyxy)
            verify=ssl.create_default_context(cafile=ca)
            body={'frame_id':str(metadata.request_id),'media_type':'image/jpeg','target_type':metadata.target_type,'request_timeout_seconds':max(1,budget-5)}
            async with httpx.AsyncClient(verify=verify,trust_env=False,follow_redirects=False,timeout=httpx.Timeout(budget,connect=10)) as client:
                async with client.stream('POST',address.rstrip('/')+'/internal/v1/describe-appearance',headers={'Authorization':'Bearer '+token},
                        data={'metadata':json.dumps(body)},files={'file':('target.jpg',cropped,'image/jpeg')}) as response:
                    if response.status_code!=200:
                        LOG.warning('appearance_semantic_upstream status=%s',response.status_code)
                        return outcome('timeout' if response.status_code==504 else 'unavailable')
                    raw=bytearray()
                    async for block in response.aiter_bytes():
                        raw.extend(block)
                        if len(raw)>32768:raise ValueError('semantic response too large')
            value=json.loads(raw)
            if value.get('frame_id')!=str(metadata.request_id) or value.get('target_type')!=metadata.target_type:raise ValueError('semantic source differs')
            described=AppearanceDescription.model_validate(value['result']);attributes=described.model_dump(exclude={'uncertain_fields'})
            for field in described.uncertain_fields:attributes[field]=[] if field=='carrying' else None
            for field in (('vehicle_color','transport_type') if metadata.target_type=='person' else ('upper_color','lower_color','carrying')):attributes[field]=[] if field=='carrying' else None
            model=value.get('returned_model') or value.get('configured_model')
            if not isinstance(model,str) or not 1<=len(model)<=200 or value.get('prompt_version') not in {'appearance-description-v1','appearance-description-zh-v2'}:raise ValueError('semantic model binding differs')
            return {'state':'completed','attributes':attributes,'uncertain_fields':described.uncertain_fields,'model_id':model,'prompt_version':value['prompt_version']}
    except (TimeoutError,httpx.TimeoutException):return outcome('timeout')
    except (httpx.HTTPError,OSError,ValueError,KeyError,TypeError):
        LOG.warning('appearance_semantic_unavailable')
        return outcome('unavailable')
