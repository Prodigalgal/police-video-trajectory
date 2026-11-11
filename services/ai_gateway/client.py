import asyncio
import base64
import binascii
from contextlib import asynccontextmanager
import hashlib
import json
import re
import sqlite3
import time

import httpx
from pydantic import BaseModel, ValidationError

from .config import ModelConfig, Settings
from .errors import GatewayError, CallDiagnostics, active_diagnostics, response_error, upstream_code
from .result_cache import CacheBusy, CacheUnavailable, ResultCache
from .tool_output import read_tool_result, result_tool, RESULT_INSTRUCTIONS
from .inference_receipt import make_receipt


def validation_issue(error: ValidationError) -> str:
    # Only bounded Pydantic error types are retained; never log model output or values.
    allowed = {"json_invalid", "extra_forbidden", "literal_error", "missing", "list_type", "dict_type",
               "string_type", "string_too_long", "string_too_short", "tuple_type", "too_long", "too_short",
               "float_type", "float_parsing", "value_error", "chinese_semantics"}
    codes = {item.get("type") for item in error.errors(include_input=False, include_url=False)}
    return next((code for code in sorted(codes & allowed)), "other")


def build_body(config: ModelConfig, prompt: str, image_data_url: str | None = None, image_file_id: str | None = None, *, result_schema=None):
    if image_data_url and image_file_id:
        raise ValueError("image input must have exactly one source")
    if config.output_mode=='tool':
        if config.json_mode:raise ValueError('tool output and JSON mode cannot be combined')
        prompt += RESULT_INSTRUCTIONS
    if config.style == "responses":
        content = [{"type": "input_text", "text": prompt}]
        if image_data_url:
            content.append({"type": "input_image", "image_url": image_data_url})
        if image_file_id:
            content.append({"type": "input_image", "file_id": image_file_id})
        body = {"model": config.model, "stream": True, "input": [{"role": "user", "content": content}]}
        if config.json_mode:
            body["text"] = {"format": {"type": "json_object"}}
        if config.reasoning_effort:
            body['reasoning'] = {'effort':config.reasoning_effort}
        if config.output_mode == 'tool':
            if result_schema is None:raise ValueError('tool output requires a result schema')
            body.update(result_tool(config.style,result_schema))
        return body
    content = [{"type": "text", "text": prompt}]
    if image_data_url:
        content.append({"type": "image_url", "image_url": {"url": image_data_url}})
    if image_file_id:
        content.append({"type": "image_url", "image_url": {"file_id": image_file_id}})
    body = {"model": config.model, "stream": True, "messages": [{"role": "user", "content": content}]}
    if config.json_mode:
        body["response_format"] = {"type": "json_object"}
    if config.reasoning_effort:
        body['reasoning_effort'] = config.reasoning_effort
    if config.output_mode == 'tool':
        if result_schema is None:raise ValueError('tool output requires a result schema')
        body.update(result_tool(config.style,result_schema))
    return body


def extract_text(payload: object, style: str):
    if not isinstance(payload, dict):
        raise GatewayError("模型返回结构无效")
    if style == "responses":
        if payload.get("status") in {"failed", "incomplete", "cancelled"}:
            raise GatewayError("模型响应未完成")
        content = payload.get("output_text")
        if not content:
            parts = []
            output = payload.get("output")
            if not isinstance(output, list):
                raise GatewayError("模型返回结构无效")
            for item in output:
                if isinstance(item, dict) and item.get("type") == "message":
                    message_content = item.get("content")
                    if not isinstance(message_content, list):
                        raise GatewayError("模型返回结构无效")
                    for part in message_content:
                        if isinstance(part, dict) and part.get("type") == "output_text" and isinstance(part.get("text"), str):
                            parts.append(part["text"])
            content = "\n".join(parts)
    else:
        try:
            message = payload["choices"][0]["message"]
            if message.get("refusal"):
                raise GatewayError("模型拒绝处理该请求")
            content = message.get("content")
            if isinstance(content, list):
                content = "\n".join(p["text"] for p in content if isinstance(p, dict) and p.get("type") == "text" and isinstance(p.get("text"), str))
        except (IndexError, KeyError, TypeError, AttributeError):
            raise GatewayError("模型返回结构无效") from None
    if not isinstance(content, str) or not content.strip():
        raise GatewayError("模型未返回可解析的文字结果")
    content = content.strip()
    if content.startswith("```"):
        content = re.sub(r"^```(?:json)?\s*|\s*```$", "", content, flags=re.IGNORECASE)
    return content


class ModelClient:
    def __init__(self, settings: Settings, client: httpx.AsyncClient,admission=None):
        self.settings = settings
        self.client = client
        if not 0 <= settings.text_reserved < settings.max_inflight:
            raise ValueError("invalid reserved text capacity")
        self.semaphore = asyncio.Semaphore(settings.max_inflight)
        self.vision_semaphore = (asyncio.Semaphore(settings.max_inflight - settings.text_reserved)
                                 if settings.text_reserved else None)
        self.active_total = 0
        self.active_vision = 0
        from .admission import ModelAdmissionClient
        self.admission=admission if admission is not None else ModelAdmissionClient(settings) if settings.cluster_admission else None
        self.result_cache = (ResultCache(settings.result_cache_dir,settings.result_cache_bytes,
            settings.result_cache_entries,settings.result_cache_ttl_seconds) if settings.result_cache_dir else None)

    def capacity(self):
        return {"max_inflight": self.settings.max_inflight, "text_reserved": self.settings.text_reserved,
                "active_total": self.active_total, "active_vision": self.active_vision}

    @asynccontextmanager
    async def request_slot(self, vision: bool, max_wait_seconds: float = 3):
        """Cap vision separately so it cannot consume explicitly reserved text slots."""
        loop = asyncio.get_running_loop()
        deadline = loop.time() + min(3,max_wait_seconds)
        vision_acquired = total_acquired = False
        try:
            try:
                if vision and self.vision_semaphore is not None:
                    await asyncio.wait_for(self.vision_semaphore.acquire(), timeout=max(0, deadline - loop.time()))
                    vision_acquired = True
                await asyncio.wait_for(self.semaphore.acquire(), timeout=max(0, deadline - loop.time()))
                total_acquired = True
                self.active_total += 1
                if vision:
                    self.active_vision += 1
            except TimeoutError:
                raise GatewayError("模型网关繁忙，请稍后重试", 503) from None
            if self.admission is None:yield
            else:
                async with self.admission.slot(vision):yield
        finally:
            if total_acquired:
                self.active_total -= 1
                if vision:
                    self.active_vision -= 1
                self.semaphore.release()
            if vision_acquired:
                self.vision_semaphore.release()

    async def close(self):
        if self.admission is not None:await self.admission.close()

    async def run(self, config: ModelConfig, prompt: str, result_model: type[BaseModel], image_data_url: str | None = None,
                  *, image_bytes: bytes | None = None, media_type: str = 'image/jpeg', timeout_seconds: float | None = None,
                  validate_result=None,prompt_version=None):
        vision = image_data_url is not None or image_bytes is not None
        if not vision or self.result_cache is None:
            result,metadata = await self._run_uncached(config,prompt,result_model,image_data_url,image_bytes=image_bytes,
                                                      media_type=media_type,timeout_seconds=timeout_seconds)
            if validate_result is not None:
                validate_result(result)
            receipt=make_receipt(config,prompt,prompt_version,result,metadata,image_bytes,timeout_seconds or self.settings.timeout_seconds)
            return result,{**metadata,'inference_receipt':receipt}
        started = time.monotonic()
        budget = self.settings.timeout_seconds if timeout_seconds is None else min(self.settings.timeout_seconds,timeout_seconds)
        if budget<=0:
            raise GatewayError('模型服务超时',504)
        image_hash = hashlib.sha256(image_bytes if image_bytes is not None else image_data_url.encode()).hexdigest()
        binding = {'namespace':self.settings.result_cache_namespace,'url':config.api_url,'model':config.model,
                   'model_binding':config.binding_id,
                   'protocol':config.style,'json_mode':config.json_mode,'transport':config.image_transport,
                   'credential_revision':hashlib.sha256(config.api_key.encode()).hexdigest(),
                   'prompt':prompt,'prompt_version':prompt_version,'schema':result_model.model_json_schema(),'media_type':media_type,'image_sha256':image_hash}
        if config.output_mode != 'text':
            binding.update(output_mode=config.output_mode,output_instructions=RESULT_INSTRUCTIONS,
                           output_tool=result_tool(config.style,result_model.model_json_schema()))
        if config.reasoning_effort:binding['reasoning_effort'] = config.reasoning_effort
        key = hashlib.sha256(json.dumps(binding,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
        owner,validated = None,False
        try:
            claim = await asyncio.to_thread(self.result_cache.claim,key,self.settings.timeout_seconds+30)
            if claim['hit']:
                try:
                    stored = claim['payload']
                    result = result_model.model_validate(stored['result'])
                    metadata = stored['metadata']
                    if (not isinstance(metadata,dict) or metadata.get('configured_model')!=config.model
                            or metadata.get('protocol')!=config.style):
                        raise ValueError('cached model binding differs')
                except (KeyError,ValueError,TypeError,ValidationError):
                    await asyncio.to_thread(self.result_cache.quarantine,key)
                    raise GatewayError('模型结果缓存不可用',503) from None
                if validate_result is not None:
                    try:
                        validate_result(result)
                    except GatewayError:
                        await asyncio.to_thread(self.result_cache.quarantine,key)
                        raise
                return result,{**metadata,'cache_status':'hit','upstream_latency_ms':metadata.get('latency_ms'),
                               'latency_ms':round((time.monotonic()-started)*1000)}
            owner = claim['owner']
            remaining = budget-(time.monotonic()-started)
            result,metadata = await self._run_uncached(config,prompt,result_model,image_data_url,image_bytes=image_bytes,
                                         media_type=media_type,timeout_seconds=remaining)
            if validate_result is not None:
                validate_result(result)
            metadata={**metadata,'inference_receipt':make_receipt(config,prompt,prompt_version,result,metadata,image_bytes,budget)}
            validated = True
            # Commit before replying. A worker/backend restart can replay the
            # identical request without paying for another upstream analysis.
            await asyncio.to_thread(self.result_cache.save,key,owner,result.model_dump(mode='json'),metadata)
            return result,{**metadata,'cache_status':'miss'}
        except CacheBusy:
            raise GatewayError('相同画面正在分析，请稍后重试',503) from None
        except (CacheUnavailable,OSError,sqlite3.Error):
            raise GatewayError('模型结果缓存不可用',503) from None
        finally:
            # A valid result with an uncertain cache commit remains reserved.
            # Do not erase a possible commit after cancellation or I/O failure.
            if owner is not None and not validated:
                try:
                    await asyncio.to_thread(self.result_cache.release,key,owner)
                except (CacheUnavailable,OSError,sqlite3.Error):
                    pass

    async def _run_uncached(self, config: ModelConfig, prompt: str, result_model: type[BaseModel], image_data_url: str | None = None,
                  *, image_bytes: bytes | None = None, media_type: str = 'image/jpeg', timeout_seconds: float | None = None):
        if not config.valid or not self.settings.ca_valid:
            raise GatewayError("模型服务尚未完整配置", 503)
        if image_data_url is not None and image_bytes is not None:
            raise ValueError('image input must have exactly one source')
        started = time.monotonic()
        budget = self.settings.timeout_seconds if timeout_seconds is None else min(self.settings.timeout_seconds,timeout_seconds)
        if budget<=0:raise GatewayError('模型服务超时',504)
        async with self.request_slot(vision=image_data_url is not None or image_bytes is not None,max_wait_seconds=budget):
            diagnostics = CallDiagnostics()
            token = active_diagnostics.set(diagnostics)
            try:
                remaining=budget-(time.monotonic()-started)
                options = {'result_schema':result_model.model_json_schema()} if config.output_mode=='tool' else {}
                payload, image_metadata = await asyncio.wait_for(self._invoke(config, prompt, image_data_url,image_bytes,media_type,**options),timeout=max(0,remaining))
            except TimeoutError:
                raise diagnostics.apply(GatewayError("模型服务超时", 504)) from None
            except GatewayError as error:
                raise diagnostics.apply(error)
            finally:
                active_diagnostics.reset(token)
            text = extract_text(payload, config.style)
            try:
                result = result_model.model_validate_json(text)
            except ValidationError as error:
                issue=validation_issue(error)
                raise diagnostics.apply(GatewayError("模型识别语义必须使用中文" if issue=='chinese_semantics' else "模型结果不符合结构化契约", validation_issue=issue)) from None
            except (ValueError, TypeError):
                raise GatewayError("模型结果不符合结构化契约") from None
            returned_model = payload.get("model") if isinstance(payload, dict) else None
            return result, {"configured_model": config.model,
                            **({'model_binding':config.binding_id} if config.binding_id else {}),
                            "returned_model": returned_model if isinstance(returned_model, str) and len(returned_model) <= 200 else None,
                            "protocol": config.style, "latency_ms": round((time.monotonic() - started) * 1000),
                            **({'upstream_request_id':diagnostics.identity} if diagnostics.identity else {}), **image_metadata}

    async def _invoke(self, config, prompt, image_data_url,image_bytes=None,media_type='image/jpeg',*,result_schema=None):
        metadata = {}
        vision=image_data_url is not None or image_bytes is not None
        if vision and config.image_transport == "file":
            file_id, metadata = await self._upload_image(config,image_data_url,image_bytes,media_type)
            body = build_body(config, prompt, image_file_id=file_id,result_schema=result_schema)
        else:
            if image_bytes is not None:
                image_data_url='data:'+media_type+';base64,'+base64.b64encode(image_bytes).decode()
            body = build_body(config, prompt, image_data_url,result_schema=result_schema)
            if image_data_url:
                metadata = {"image_transport": "inline"}
        if vision:
            metadata["inference_request_bytes"] = len(json.dumps(body, ensure_ascii=False, separators=(",", ":")).encode())
        return await self._request(config, body), metadata

    async def _upload_image(self, config, data_url,image=None,media_type='image/jpeg'):
        started = time.monotonic()
        try:
            if image is None:
                prefix, encoded = data_url.split(",", 1)
                media_type = {"data:image/jpeg;base64": "image/jpeg", "data:image/png;base64": "image/png",
                              "data:image/webp;base64": "image/webp"}[prefix]
                image = base64.b64decode(encoded, validate=True)
            if media_type not in {'image/jpeg','image/png','image/webp'} or not 0 < len(image) <= 8 * 1024 * 1024:
                raise ValueError("image upload size")
        except (KeyError, ValueError, binascii.Error):
            raise GatewayError("待上传图像无效", 422) from None
        sha = hashlib.sha256(image).hexdigest()
        filename = sha + {"image/jpeg": ".jpg", "image/png": ".png", "image/webp": ".webp"}[media_type]
        for attempt in range(self.settings.retries + 1):
            try:
                diagnostics = active_diagnostics.get()
                if diagnostics is not None:diagnostics.start('upload')
                async with self.client.stream("POST", config.file_upload_url,
                        headers={"Authorization": f"Bearer {config.api_key}", "Idempotency-Key": sha},
                        data={"purpose": "vision"}, files={"file": (filename, image, media_type)}) as response:
                    status = response.status_code
                    if diagnostics is not None:diagnostics.received(response)
                    if status in {408, 429, 500, 502, 503, 504} and attempt < self.settings.retries:
                        retry = True
                    elif not 200 <= status < 300:
                        raise await response_error(response,f"图像上传服务返回 HTTP {status}",'upload')
                    else:
                        raw = bytearray()
                        async for chunk in response.aiter_bytes():
                            raw.extend(chunk)
                            if len(raw) > 16384:
                                raise GatewayError("图像上传回执无效")
                        try:
                            result = json.loads(raw)
                            if (not isinstance(result, dict) or not re.fullmatch(r"file_[0-9a-f]{32}", result.get("id", ""))
                                    or result.get("sha256") != sha or result.get("bytes") != len(image)
                                    or result.get("media_type") != media_type or result.get("purpose") != "vision"):
                                raise ValueError("upload receipt differs")
                        except (ValueError, TypeError, AttributeError):
                            raise GatewayError("图像上传回执无效") from None
                        # Same-key retries may reuse this ID concurrently. Keep it
                        # until the server's bounded TTL rather than deleting it
                        # while a concurrent request may still be resolving it.
                        return result["id"], {"image_transport": "file", "image_bytes": len(image),
                            "upload_attempts": attempt + 1,
                            "upload_request_bytes": int(response.request.headers.get("content-length", "0")),
                            "upload_latency_ms": round((time.monotonic()-started)*1000)}
                if retry:
                    await asyncio.sleep(min(.25 * (2 ** attempt), 2))
            except httpx.HTTPError:
                if attempt == self.settings.retries:
                    raise GatewayError("图像上传服务连接失败", 502) from None
                await asyncio.sleep(min(.25 * (2 ** attempt), 2))
        raise GatewayError("图像上传服务暂不可用", 503)

    async def _request(self, config: ModelConfig, body: dict):
        for attempt in range(self.settings.retries + 1):
            try:
                diagnostics = active_diagnostics.get()
                if diagnostics is not None:diagnostics.start('inference')
                async with self.client.stream("POST", config.api_url,
                                              headers={"Authorization": f"Bearer {config.api_key}"}, json=body) as response:
                    status = response.status_code
                    if diagnostics is not None:diagnostics.received(response)
                    if status in {429, 500, 502, 503, 504} and attempt < self.settings.retries:
                        retry = True
                    elif not 200 <= status < 300:
                        raise await response_error(response,f"模型服务返回 HTTP {status}",'inference')
                    else:
                        if "text/event-stream" not in response.headers.get("content-type", "").lower():
                            raise GatewayError("模型服务未返回流式响应")
                        if config.output_mode=='tool':
                            return await read_tool_result(response,config.style,self.settings.max_response_bytes)
                        return await self._read_stream(response, config.style)
                if retry:
                    await asyncio.sleep(min(.25 * (2 ** attempt), 2))
            except GatewayError:
                raise
            except httpx.TimeoutException:
                if attempt == self.settings.retries:
                    raise GatewayError("模型服务超时", 504) from None
                await asyncio.sleep(min(.25 * (2 ** attempt), 2))
            except httpx.HTTPError:
                if attempt == self.settings.retries:
                    raise GatewayError("模型服务连接失败", 502) from None
                await asyncio.sleep(min(.25 * (2 ** attempt), 2))
        raise GatewayError("模型服务暂不可用", 503)

    async def _read_stream(self, response: httpx.Response, style: str):
        """Decode SSE across arbitrary network/UTF-8 boundaries; reject partial output."""
        pending = bytearray()
        data_lines = []
        event_name = ""
        total_bytes = 0
        output = []
        final_text = None
        model = None
        completed = False

        def accept_event():
            nonlocal data_lines, event_name, final_text, model, completed
            if not data_lines:
                event_name = ""
                return
            raw = "\n".join(data_lines)
            data_lines = []
            if raw == "[DONE]":
                event_name = ""
                return
            try:
                item = json.loads(raw)
            except (ValueError, TypeError):
                raise GatewayError("模型流式响应格式无效") from None
            if not isinstance(item, dict):
                raise GatewayError("模型流式响应格式无效")
            kind = item.get("type") or event_name
            event_name = ""
            if kind in {"error", "response.failed", "response.incomplete", "response.cancelled"} or item.get("error"):
                raise GatewayError("模型流式响应失败", upstream_error_code=upstream_code(item))
            if kind in {"response.refusal.delta", "response.refusal.done"}:
                raise GatewayError("模型拒绝处理该请求")
            if isinstance(item.get("model"), str):
                model = item["model"]
            if style == "responses":
                if kind == "response.output_text.delta":
                    if not isinstance(item.get("delta"), str):
                        raise GatewayError("模型流式响应格式无效")
                    output.append(item["delta"])
                elif kind == "response.output_text.done" and isinstance(item.get("text"), str):
                    final_text = item["text"]
                elif kind == "response.completed":
                    result = item.get("response")
                    if not isinstance(result, dict) or result.get("status") not in {None, "completed"}:
                        raise GatewayError("模型响应未完成")
                    if isinstance(result.get("model"), str):
                        model = result["model"]
                    if not output and final_text is None:
                        final_text = extract_text(result, "responses")
                    completed = True
            else:
                choices = item.get("choices")
                if choices is None:
                    return
                if not isinstance(choices, list):
                    raise GatewayError("模型流式响应格式无效")
                for choice in choices:
                    if not isinstance(choice, dict) or choice.get("index", 0) != 0:
                        continue
                    delta = choice.get("delta") or {}
                    if not isinstance(delta, dict):
                        raise GatewayError("模型流式响应格式无效")
                    if delta.get("refusal"):
                        raise GatewayError("模型拒绝处理该请求")
                    content = delta.get("content")
                    if content is not None:
                        if not isinstance(content, str):
                            raise GatewayError("模型流式响应格式无效")
                        output.append(content)
                    reason = choice.get("finish_reason")
                    if reason:
                        if reason != "stop":
                            raise GatewayError("模型响应未完成")
                        completed = True

        def accept_line(line: bytes):
            nonlocal event_name
            try:
                line_text = line.rstrip(b"\r").decode("utf-8")
            except UnicodeDecodeError:
                raise GatewayError("模型流式响应编码无效") from None
            if not line_text:
                accept_event()
            elif line_text.startswith("data:"):
                data_lines.append(line_text[5:].removeprefix(" "))
            elif line_text.startswith("event:"):
                event_name = line_text[6:].strip()

        try:
            async for chunk in response.aiter_bytes():
                total_bytes += len(chunk)
                if total_bytes > self.settings.max_response_bytes:
                    raise GatewayError("模型响应超过大小限制")
                pending.extend(chunk)
                while b"\n" in pending:
                    line, _, remainder = pending.partition(b"\n")
                    pending = bytearray(remainder)
                    accept_line(line)
            if pending:
                accept_line(bytes(pending))
            accept_event()
        except httpx.HTTPError:
            raise GatewayError("模型流式响应中断") from None
        if not completed:
            raise GatewayError("模型流式响应中断")
        content = "".join(output) if output else final_text
        if not isinstance(content, str) or not content.strip():
            raise GatewayError("模型未返回可解析的文字结果")
        if style == "responses":
            return {"status": "completed", "model": model, "output_text": content}
        return {"model": model, "choices": [{"message": {"content": content}}]}
