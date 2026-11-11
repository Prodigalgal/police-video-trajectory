# 内网集群基础配置

此目录提供可选共享协调模式的三类服务：Core、Web、AI Gateway，各两个副本。它不包含数据库/ES集群、CSI、入口控制器、生产秘密或完整媒体写入拓扑，不会自动连接现场或应用资源。

将 `config.example.json` 复制到私有目录，替换命名空间、同版不可变 ARM64 镜像、域名、Secret/ConfigMap/PVC引用；所有候选Worker先导入同一镜像包。准备服务时使用 `video.platform/worker=true` 标签，保留控制节点的隔离。镜像交付仍由外网CI构建、人工搬入内网；不修改悉尼GitOps的目标。

```powershell
python deploy/cluster/render.py --config private/cluster.json --check
python deploy/cluster/render.py --config private/cluster.json --output private/cluster-manifests.json
```

输出为 Kubernetes List，供检查与后续人工部署；工具本身没有 apply/sync/扩容动作。`--check` 明确列出尚需现场确认的前置条件，不将纯渲染通过报告成生产就绪。

可以追加 `--check --inventory private/cluster-inventory.json` 校验现场元数据。该文件只含 `nodes`、`claims`、`volumes` 的 Kubernetes 对象列表，以及 `secrets`、`configmaps`（资源名对应键名列表）、`images`（节点名对应镜像名列表）三张映射；不放入 Secret 数据。检查三个可调度的 ARM64 Worker、业务标签、同版镜像、已绑定RWX卷及非本地PV、证书与服务秘密的键名和目录引用。即使元数据通过，仍返回 `ready_for_production=false`，因为实际存储一致性、依赖可达及故障接管尚需验证。

依赖契约：

| 引用 | 必需内容 |
|---|---|
| database_secret | `url`、`user`、`password`、`ca.crt`；JDBC URL启用 verify-full，CA路径 `/database-ca/ca.crt` |
| elasticsearch_secret | `url`、`user`、`password`、`ca.crt`；HTTPS端点 |
| service_secret | `session-secret`（所有Web一致、至少32字符）、`bootstrap-username`、`bootstrap-password-hash`（scrypt）、`admin-token`、`search-token`、`worker-token`、`gateway-token` |
| core_tls_secret | `keystore.p12`、`keystore-password`、`ca.crt`，证书覆盖 Core Service DNS |
| web_tls_secret / gateway_tls_secret | `tls.crt`、`tls.key`、`ca.crt`，覆盖浏览器域名/相应Service DNS |
| provider_registry / provider_credentials | 提供商 `providers.json` 与匹配凭据文件名；仅服务端挂载，模型用途绑定由后台登记 |
| media_claim | 经过验证的 RWX 共享媒体目录，包含 `evidence`、`archives`、`clips`、`live`；Core只读挂载 |

启动前人工执行包括033在内的版本化数据库迁移，并初始化首个管理员。配置记录、队列、权限和索引契约保持现有实现。读就绪探针会检查共享协调表与全局模型策略；Web/Gateway也检查协调服务，不仅检查端口。新模式关闭时旧单实例行为保留；启用时不能混入仍按本地验证码或本地额度工作的旧副本。

模型总额度由 Core 的 `VIDEO_CLUSTER_MODEL_TOTAL`、`VIDEO_CLUSTER_MODEL_TEXT_RESERVED` 初始化到 PG，全副本必须一致。错误配置的副本会拒绝就绪和新授权，避免滚动期间总额度漂移。策略修改须经显式运维变更，不会由任一新Pod静默覆盖。每次推理持有覆盖有界调用预算的租约，正常/取消均释放；失联释放失败在到期后恢复。默认360秒总超时、流式协议继续。

安全默认值：采集派发、索引生产、录像及视频导出关闭；本配置未启动媒体写入者。持久推理结果缓存暂不跨Gateway共享，禁止把现有SQLite/WAL文件直接移到共享盘并多节点写入。完整采集、分布式媒体配额/账本与缓存去重、节点接管需要后续独立工作包和实际故障验证；不要直接复制悉尼本地PV或放大采集副本。

回滚前先停用共享模式并将Web/Gateway恢复单副本，再回切上一不可变镜像；新增033表可保留。PodDisruptionBudget只约束主动中断，不证明故障切换已经通过。PG/ES HA、存储、内部模型/高德网络、入口TLS及资源容量，均须在现场单独核验。
