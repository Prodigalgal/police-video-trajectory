import asyncio
import json
import ssl

import httpx


class ServiceError(Exception):
    def __init__(self, status=503):
        self.status = status
        super().__init__("internal service unavailable or rejected request")


class JsonService:
    def __init__(self, base_url: str, ca_file: str, token: str, timeout: int = 30, transport=None,max_response_bytes=2*1024*1024):
        self.base_url = base_url.rstrip("/")
        self.token = token
        self.timeout = timeout
        if type(max_response_bytes) is not int or not 1024<=max_response_bytes<=12*1024*1024:raise ValueError('invalid service response bound')
        self.max_response_bytes=max_response_bytes
        verify = ssl.create_default_context(cafile=ca_file) if transport is None else True
        self.client = httpx.AsyncClient(timeout=timeout, verify=verify, transport=transport, trust_env=False, follow_redirects=False)

    async def close(self):
        await self.client.aclose()

    async def post(self, path: str, body: dict):
        return await self._post(path,json=body)

    async def post_image(self,path:str,metadata:dict,image:bytes):
        return await self._post(path,data={'metadata':json.dumps(metadata,ensure_ascii=False)},
            files={'file':(metadata['frame_id']+'.jpg',image,metadata.get('media_type','image/jpeg'))})

    async def _post(self,path:str,**payload):
        return await self._request('POST',path,**payload)

    async def get(self,path:str,params=None):
        return await self._request('GET',path,params=params)

    async def _request(self,method:str,path:str,**payload):
        async def run():
            try:
                async with self.client.stream(method, self.base_url + path,
                        headers={"Authorization": "Bearer " + self.token}, **payload) as response:
                    if response.status_code < 200 or response.status_code >= 300:
                        raise ServiceError(response.status_code)
                    result = bytearray()
                    async for chunk in response.aiter_bytes():
                        result.extend(chunk)
                        if len(result) > self.max_response_bytes:
                            raise ServiceError(502)
                    value = json.loads(result)
                    if not isinstance(value, dict):
                        raise ServiceError(502)
                    return value
            except (httpx.HTTPError, ValueError, TypeError):
                raise ServiceError(502) from None
        try:
            return await asyncio.wait_for(run(), timeout=self.timeout)
        except TimeoutError:
            raise ServiceError(504) from None
