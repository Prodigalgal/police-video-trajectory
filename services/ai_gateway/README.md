# 内部 AI 网关

此目录是独立的 Python 服务；目前提供自然语言条件解析和单帧目标描述，兼容 OpenAI 风格的 Chat Completions 与 Responses。服务代码及模拟测试已在工作机运行，容器构建与部署状态见悉尼试验记录，不能将本目录等同于完整后端。

`POST /internal/v1/interpret-query` 只接受自然语言、服务端生成的参考时间和 IANA 时区；`POST /internal/v1/analyze-frame` 接受单张 JPEG/PNG。两个接口使用 `Authorization: Bearer` 内部令牌，返回经 Pydantic 校验的固定结构。业务 API 必须自行获取可信参考时间、解析地点和权限、白名单构造 ES 查询；不得直接执行模型输出。未知和不能确认的字段保留空值。

`POST /internal/v3/analyze-frame` 与 `/internal/v3/analyze-targets` 接受二进制 multipart：一个 `file` 文件和一个 JSON 字符串 `metadata`，后者与旧请求元数据相同但不含 `image_base64`。目标接口继续校验当前帧的尺寸、目标 ID、类型和框，不能混用帧或捏造目标。先鉴权再解析，文件最多 8 MiB、元数据最多 128 KiB，multipart 请求总量最多两者之和；接收许可为单进程有界并发。旧 JSON/Base64 接口保留，内部和外部传输方式可分别设置。

环境变量：

- `GATEWAY_SHARED_TOKEN`：独立内部访问令牌，保存在 Secret 中。
- `MODEL_API_URL`、`MODEL_API_KEY`、`MODEL_NAME`、`MODEL_API_STYLE`：模型完整端点、密钥、模型名及 `chat_completions` / `responses`。地址包含对应接口路径，服务不会推测或拼接路径。
- 可按视觉与文本分别配置 `VISION_API_URL`、`VISION_API_KEY`、`VISION_MODEL`、`VISION_API_STYLE` 和对应的 `TEXT_*`，覆盖公共配置。
- `MODEL_IMAGE_TRANSPORT` 默认为 `inline`，`VISION_IMAGE_TRANSPORT=file` 可启用 Any2API 1.8.0 文件引用；需显式配置 `VISION_FILE_UPLOAD_URL`（或公共 `MODEL_FILE_UPLOAD_URL`），必须与推理地址同源。先原样 multipart 上传，验证文件 ID、摘要、长度和类型，再提交流式推理；Responses 使用 `input_image.file_id`，Chat 使用 Any2API 扩展 `image_url: {file_id: ...}`，不假定所有兼容服务都支持。当前每个任务上传一次，HTTP 模型重试复用 ID，服务器 TTL 回收；没有跨任务客户端上传缓存。失败不自动降级或换提供商。上游内部桥仍可能转 Base64，不能称全链路零编码。
- `MODEL_JSON_MODE=true` 可启用兼容协议的 JSON 模式，也可用 `VISION_JSON_MODE` / `TEXT_JSON_MODE` 分别设置；兼容服务可能不支持该参数，须先实测。JSON 模式不代替输出结构和内容校验。
- 两种模型协议都发送 `stream: true` 并增量读取 SSE；等待完整结束事件后再验证结构化结果。模型调用的总超时默认 360 秒（包含并发等待、文件上传、模型推理和重试），二进制接收已耗时会从剩余预算扣除；可通过 `MODEL_TIMEOUT_SECONDS` 调整。其他可选项为 `MODEL_CA_FILE`、`MODEL_MAX_INFLIGHT`、`MODEL_TEXT_RESERVED`、`MODEL_RETRIES`。`MODEL_TEXT_RESERVED` 默认 0，启用时限制视觉请求占用的并发，使设定数量的总槽位不被视觉请求占满；它是单进程保护，不保证多个文本请求均有空位。默认不继承宿主机代理，不跟随 HTTP 重定向，不在响应里返回密钥或图像。

本服务在进程内限制并发；多个副本的总并发需由上层统一限制。带内部令牌的 `/internal/v1/capacity` 返回该进程当前容量和占用，不代表多副本全局状态。`/healthz` 表示进程存活；`/readyz` 只检查配置是否齐全，不证明外部模型可达或模型输出正确。实际部署需提供证书并以 TLS 运行，例如配置 uvicorn 的 `--ssl-keyfile`、`--ssl-certfile`，不能把 Kubernetes 的 ClusterIP 当作网络隔离保证。

从仓库根目录构建，示例：`docker build -f services/ai_gateway/Dockerfile .`。使用 `python -m pytest tests/test_ai_gateway.py -q` 运行模拟服务测试。模型服务的图像能力、协议实现及目标网络连通性需要分别实测；不把合成测试结果当作真实录像识别验证。

二进制接收依赖固定版本 `python-multipart==0.0.33`，列于本目录 requirements。悉尼当前采用内部 multipart + 外部文件引用，三视觉/一文字保留槽、360 秒；部署、24 项六点真实对照、失败及回退见[记录](../../docs/25-binary-image-upload.md)。文件上传减少传输体积，不代表结构可靠性、精度或吞吐已满足生产要求。
