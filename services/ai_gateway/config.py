from dataclasses import dataclass, field
from pathlib import Path
import os
from urllib.parse import urlsplit


@dataclass(frozen=True)
class ModelConfig:
    api_url: str = ""
    api_key: str = field(default="", repr=False)
    model: str = ""
    style: str = "chat_completions"
    json_mode: bool = False
    image_transport: str = "inline"
    file_upload_url: str = ""
    output_mode: str = 'text'
    reasoning_effort: str = ''
    binding_id: str = ''

    @property
    def valid(self):
        try:
            parsed = urlsplit(self.api_url)
            upload = urlsplit(self.file_upload_url)
            file_valid = self.image_transport == "inline" or (self.image_transport == "file"
                and upload.scheme == parsed.scheme and upload.hostname == parsed.hostname and upload.port == parsed.port
                and not any((upload.username,upload.password,upload.query,upload.fragment)) and bool(upload.path))
            return bool(self.api_key and self.model and self.style in {"chat_completions", "responses"}
                        and self.output_mode in {'text','tool'} and not (self.output_mode=='tool' and self.json_mode)
                        and self.reasoning_effort in {'','none','minimal','low','medium','high','xhigh','max','auto'}
                        and parsed.scheme in {"http", "https"} and parsed.hostname
                        and not parsed.username and not parsed.password and not parsed.query and not parsed.fragment and file_valid)
        except ValueError:
            return False


@dataclass(frozen=True)
class Settings:
    shared_token: str = field(default="", repr=False)
    vision: ModelConfig = field(default_factory=ModelConfig)
    text: ModelConfig = field(default_factory=ModelConfig)
    ca_file: str = ""
    max_inflight: int = 2
    timeout_seconds: float = 360
    retries: int = 2
    max_response_bytes: int = 2 * 1024 * 1024
    text_reserved: int = 0
    result_cache_dir: str = ''
    result_cache_namespace: str = 'vision-results-v1'
    result_cache_bytes: int = 512*1024*1024
    result_cache_entries: int = 100000
    result_cache_ttl_seconds: int = 86400
    control_url: str = ''
    control_ca_file: str = ''
    credentials_root: str = ''
    providers_file: str = ''
    control_consumer: str = 'ai-gateway'

    @classmethod
    def from_env(cls):
        def get(name, default=""):
            return os.environ.get(name, default).strip()

        def boolean(name, default="false"):
            value = get(name, default).lower()
            if value not in {"true", "false"}:
                raise ValueError("JSON mode must be true or false")
            return value == "true"

        common = ModelConfig(get("MODEL_API_URL"), get("MODEL_API_KEY"), get("MODEL_NAME"), get("MODEL_API_STYLE", "chat_completions").lower(), boolean("MODEL_JSON_MODE"),
                             get("MODEL_IMAGE_TRANSPORT","inline"),get("MODEL_FILE_UPLOAD_URL"),
                             get('MODEL_OUTPUT_MODE','text'),get('MODEL_REASONING_EFFORT'))
        vision = ModelConfig(get("VISION_API_URL", common.api_url), get("VISION_API_KEY", common.api_key),
                             get("VISION_MODEL", common.model), get("VISION_API_STYLE", common.style).lower(), boolean("VISION_JSON_MODE", str(common.json_mode).lower()),
                             get("VISION_IMAGE_TRANSPORT",common.image_transport),get("VISION_FILE_UPLOAD_URL",common.file_upload_url),
                             get('VISION_OUTPUT_MODE',common.output_mode),get('VISION_REASONING_EFFORT',common.reasoning_effort))
        text = ModelConfig(get("TEXT_API_URL", common.api_url), get("TEXT_API_KEY", common.api_key),
                           get("TEXT_MODEL", common.model), get("TEXT_API_STYLE", common.style).lower(), boolean("TEXT_JSON_MODE", str(common.json_mode).lower()),
                           output_mode=get('TEXT_OUTPUT_MODE',common.output_mode),reasoning_effort=get('TEXT_REASONING_EFFORT',common.reasoning_effort))
        max_inflight = max(1, min(32, int(get("MODEL_MAX_INFLIGHT", "2"))))
        text_reserved = int(get("MODEL_TEXT_RESERVED", "0"))
        if not 0 <= text_reserved < max_inflight:
            raise ValueError("invalid reserved text capacity")
        return cls(shared_token=get("GATEWAY_SHARED_TOKEN"), vision=vision, text=text,
                   ca_file=get("MODEL_CA_FILE"), max_inflight=max_inflight, text_reserved=text_reserved,
                   timeout_seconds=max(1, min(600, float(get("MODEL_TIMEOUT_SECONDS", "360")))),
                   retries=max(0, min(5, int(get("MODEL_RETRIES", "2")))),
                   result_cache_dir=get('MODEL_RESULT_CACHE_DIR'),
                   result_cache_namespace=get('MODEL_RESULT_CACHE_NAMESPACE','vision-results-v1'),
                   result_cache_bytes=int(get('MODEL_RESULT_CACHE_BYTES',str(512*1024*1024))),
                   result_cache_entries=int(get('MODEL_RESULT_CACHE_ENTRIES','100000')),
                   result_cache_ttl_seconds=int(get('MODEL_RESULT_CACHE_TTL_SECONDS','86400')),
                   control_url=get('MODEL_CONTROL_URL'),control_ca_file=get('MODEL_CONTROL_CA_FILE'),
                   credentials_root=get('MODEL_CREDENTIALS_ROOT'),providers_file=get('MODEL_PROVIDERS_FILE'),control_consumer=get('MODEL_CONTROL_CONSUMER','ai-gateway'))

    @property
    def ca_valid(self):
        return not self.ca_file or Path(self.ca_file).is_file()
