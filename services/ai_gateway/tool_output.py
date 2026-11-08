"""A single forced function transports a result; no tools are executed."""
from copy import deepcopy
from dataclasses import dataclass, field
import json

import httpx

from .errors import GatewayError, upstream_code


RESULT_FUNCTION = 'submit_video_analysis'
RESULT_INSTRUCTIONS = '\n结果返回方式：前文的 JSON 结构就是 submit_video_analysis 的参数结构。通过唯一结果函数提交，不输出正文、Markdown 或其他工具调用。所有可见事实及未知值规则保持有效。'


def function_schema(schema):
    """Map homogeneous tuples to the provider subset; semantic validation stays local."""
    def convert(value):
        if isinstance(value, list):
            return [convert(child) for child in value]
        if not isinstance(value, dict):
            return value
        result = {}
        for key, child in value.items():
            if key in {'default', 'title', 'format'}:
                continue
            if key in {'properties', '$defs', 'definitions'}:
                result[key] = {name: convert(definition) for name, definition in child.items()}
            else:
                result[key] = convert(child)
        if 'prefixItems' in result:
            items = result.pop('prefixItems')
            if not items or any(child != items[0] for child in items):
                raise ValueError('heterogeneous result tuples are unsupported')
            result['items'] = items[0]
        if result.get('type') == 'object':
            result['additionalProperties'] = False
            result['required'] = list(result.get('properties', {}))
        return result
    return convert(deepcopy(schema))


def result_tool(style, schema):
    definition = {'name': RESULT_FUNCTION, 'description': '提交完整的视频线索分析结果。仅返回给定结构，不执行任何操作。',
                  'strict': True, 'parameters': function_schema(schema)}
    if style == 'responses':
        return {'tools': [{'type': 'function', **definition}],
                'tool_choice': {'type': 'function', 'name': RESULT_FUNCTION}, 'parallel_tool_calls': False}
    return {'tools': [{'type': 'function', 'function': definition}],
            'tool_choice': {'type': 'function', 'function': {'name': RESULT_FUNCTION}}, 'parallel_tool_calls': False}


async def sse_events(response, maximum_bytes):
    pending = bytearray()
    data, event_name = [], ''
    size = 0

    def line(raw):
        nonlocal data, event_name
        try:
            value = raw.rstrip(b'\r').decode('utf-8')
        except UnicodeDecodeError:
            raise GatewayError('模型流式响应编码无效') from None
        if not value:
            result = (event_name, '\n'.join(data)) if data else None
            data, event_name = [], ''
            return result
        if value.startswith('data:'):
            data.append(value[5:].removeprefix(' '))
        elif value.startswith('event:'):
            event_name = value[6:].strip()
        return None

    try:
        async for chunk in response.aiter_bytes():
            size += len(chunk)
            if size > maximum_bytes:
                raise GatewayError('模型响应超过大小限制')
            pending.extend(chunk)
            while b'\n' in pending:
                raw, _, remainder = pending.partition(b'\n')
                pending = bytearray(remainder)
                event = line(raw)
                if event is not None:
                    yield event
        if pending:
            event = line(bytes(pending))
            if event is not None:
                yield event
        event = line(b'')
        if event is not None:
            yield event
    except httpx.HTTPError:
        raise GatewayError('模型流式响应中断') from None


def object_arguments(arguments):
    def pairs(entries):
        result = {}
        for key, value in entries:
            if key in result:
                raise ValueError('duplicate result property')
            result[key] = value
        return result
    def constant(_):
        raise ValueError('nonfinite result number')
    try:
        value = json.loads(arguments, object_pairs_hook=pairs, parse_constant=constant)
        if not isinstance(value, dict):
            raise ValueError('result is not an object')
    except (ValueError, TypeError, RecursionError):
        raise GatewayError('模型结果不符合结构化契约', validation_issue='json_invalid') from None
    return arguments


@dataclass
class ToolResultStream:
    identity: str | None = None
    call_id: str | None = None
    name: str = ''
    arguments: list[str] = field(default_factory=list)
    final_arguments: str | None = None
    completed: bool = False
    model: str | None = None

    def bind(self, identity, call_id=None):
        if not isinstance(identity, str) or not identity or len(identity) > 200:
            raise GatewayError('模型返回结构无效')
        if self.identity is not None and identity != self.identity:
            raise GatewayError('模型结果不符合结构化契约', validation_issue='value_error')
        if call_id is not None:
            if not isinstance(call_id, str) or not call_id or len(call_id) > 200 or self.call_id not in {None, call_id}:
                raise GatewayError('模型返回结构无效')
            self.call_id = call_id
        self.identity = identity

    def set_name(self, name):
        if not isinstance(name, str) or name != RESULT_FUNCTION or self.name not in {'', name}:
            raise GatewayError('模型结果不符合结构化契约', validation_issue='value_error')
        self.name = name

    def append_name(self, name):
        if not isinstance(name, str):
            raise GatewayError('模型返回结构无效')
        if self.name == name == RESULT_FUNCTION:
            return
        combined = self.name + name
        if not RESULT_FUNCTION.startswith(combined):
            raise GatewayError('模型结果不符合结构化契约', validation_issue='value_error')
        self.name = combined

    def append(self, arguments):
        if not isinstance(arguments, str) or self.final_arguments is not None or self.completed:
            raise GatewayError('模型返回结构无效')
        self.arguments.append(arguments)

    def finish_arguments(self, arguments):
        if not isinstance(arguments, str) or self.final_arguments not in {None, arguments}:
            raise GatewayError('模型返回结构无效')
        if self.arguments and ''.join(self.arguments) != arguments:
            raise GatewayError('模型结果不符合结构化契约', validation_issue='value_error')
        self.final_arguments = arguments

    def response_item(self, item):
        if not isinstance(item, dict) or item.get('type') != 'function_call':
            raise GatewayError('模型返回结构无效')
        self.bind(item.get('id'), item.get('call_id'))
        self.set_name(item.get('name'))
        return item

    def responses(self, kind, payload):
        if kind == 'response.output_item.added':
            item = payload.get('item')
            if isinstance(item, dict) and item.get('type') == 'reasoning':
                return
            self.response_item(item)
        elif kind == 'response.function_call_arguments.delta':
            self.bind(payload.get('item_id'))
            self.append(payload.get('delta'))
        elif kind == 'response.function_call_arguments.done':
            self.bind(payload.get('item_id'))
            self.finish_arguments(payload.get('arguments'))
        elif kind == 'response.output_item.done':
            item = payload.get('item')
            if isinstance(item, dict) and item.get('type') == 'reasoning':
                return
            if isinstance(item, dict) and item.get('status') not in {None, 'completed'}:
                raise GatewayError('模型响应未完成')
            self.finish_arguments(self.response_item(item).get('arguments'))
        elif kind == 'response.completed':
            result = payload.get('response')
            if not isinstance(result, dict) or result.get('status') != 'completed':
                raise GatewayError('模型响应未完成')
            output = result.get('output')
            if not isinstance(output, list) or any(not isinstance(item, dict) for item in output):
                raise GatewayError('模型返回结构无效')
            calls = [item for item in output if isinstance(item, dict) and item.get('type') == 'function_call']
            if len(calls) != 1 or any(item.get('type') not in {'function_call', 'reasoning'} for item in output if isinstance(item, dict)):
                raise GatewayError('模型结果不符合结构化契约', validation_issue='value_error')
            if calls[0].get('status') not in {None, 'completed'}:
                raise GatewayError('模型响应未完成')
            self.finish_arguments(self.response_item(calls[0]).get('arguments'))
            self.model = result.get('model')
            self.completed = True
        elif kind in {'response.output_text.delta', 'response.output_text.done', 'response.refusal.delta', 'response.refusal.done'}:
            raise GatewayError('模型结果不符合结构化契约', validation_issue='value_error')

    def chat(self, payload):
        choices = payload.get('choices', [])
        if not isinstance(choices, list):
            raise GatewayError('模型返回结构无效')
        for choice in choices:
            if not isinstance(choice, dict) or choice.get('index', 0) != 0:
                raise GatewayError('模型返回结构无效')
            delta = choice.get('delta') or {}
            if not isinstance(delta, dict) or delta.get('content') or delta.get('refusal'):
                raise GatewayError('模型结果不符合结构化契约', validation_issue='value_error')
            calls = delta.get('tool_calls', [])
            if not isinstance(calls, list):
                raise GatewayError('模型返回结构无效')
            for call in calls:
                if not isinstance(call, dict) or type(call.get('index')) is not int or call['index'] != 0 or call.get('type', 'function') != 'function':
                    raise GatewayError('模型结果不符合结构化契约', validation_issue='value_error')
                if 'id' in call:
                    self.bind(call['id'], call['id'])
                if self.identity is None:
                    raise GatewayError('模型返回结构无效')
                function = call.get('function')
                if not isinstance(function, dict):
                    raise GatewayError('模型返回结构无效')
                if 'name' in function:
                    self.append_name(function['name'])
                if 'arguments' in function:
                    self.append(function['arguments'])
            reason = choice.get('finish_reason')
            if reason is not None:
                if reason != 'tool_calls' or self.completed:
                    raise GatewayError('模型响应未完成')
                self.completed = True


async def read_tool_result(response, style, maximum_bytes):
    state = ToolResultStream()
    done_marker = False
    async for event_name, raw in sse_events(response, maximum_bytes):
        if raw == '[DONE]':
            if not state.completed or done_marker:
                raise GatewayError('模型流式响应中断')
            done_marker = True
            continue
        if done_marker:
            raise GatewayError('模型响应未完成')
        try:
            payload = json.loads(raw)
        except (ValueError, TypeError):
            raise GatewayError('模型流式响应格式无效') from None
        if not isinstance(payload, dict):
            raise GatewayError('模型流式响应格式无效')
        kind = payload.get('type') or event_name
        if payload.get('error') or kind in {'error', 'response.failed', 'response.incomplete', 'response.cancelled'}:
            raise GatewayError('模型流式响应失败', upstream_error_code=upstream_code(payload))
        if state.completed:
            if style == 'chat_completions' and payload.get('choices') == []:
                continue
            raise GatewayError('模型响应未完成')
        if isinstance(payload.get('model'), str):
            state.model = payload['model']
        state.responses(kind, payload) if style == 'responses' else state.chat(payload)
    if not state.completed:
        raise GatewayError('模型流式响应中断')
    if state.name != RESULT_FUNCTION or not state.identity or not state.call_id:
        raise GatewayError('模型结果不符合结构化契约', validation_issue='value_error')
    arguments = object_arguments(state.final_arguments if state.final_arguments is not None else ''.join(state.arguments))
    if style == 'responses':
        return {'status': 'completed', 'model': state.model, 'output_text': arguments}
    return {'model': state.model, 'choices': [{'message': {'content': arguments}}]}
