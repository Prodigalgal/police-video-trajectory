# 视频线索检索

面向中文监控检索的应用系统。使用自然语言搜索 Elasticsearch 中的视觉语义记录，查看来源画面、目标详情、候选时段与专用识别结果。Java 负责业务和持久队列，Python 负责视频、模型适配与媒体网关，React 提供搜索与管理界面。

## 能力与边界

- RTSP 与历史录像接入、活动筛选、人车检测、语义分析和独立分析模块。
- 分页检索、线索整理、来源详情、区域核验、车牌人工复核。
- 对已入库人车有效帧建立外观索引并查询相似时段；不做人脸身份识别。
- 登录后观看实时监控；存在同会话录像绑定的有效帧支持对应片段回看。
- 用户管理、任务恢复、容量控制和证据导出。

源码能力不代表所有运行环境均已配置并通过验收。模型、原始媒体、账号、地图服务和部署凭据需要独立配置。本系统不包含模型训练功能。

## 验证与发布

统一版本在 `VERSION`。执行 `python deploy/check-version-contract.py` 检查各组件版本；Java 使用 JDK 21、Gradle 9.1.0，前端使用 Node 22。运行 `gradle -p services/core_api test bootJar`、`node --test web/tests/*.test.mjs` 与 `npm ci --prefix web && npm run build --prefix web`。

公开仓库仅存放程序与无私有画面的契约测试。真实录像、截图、复核集、模型文件、运行记录、私有部署配置和密钥不入库。提交前运行 `python ci/public-source-check.py`，它检查已跟踪文件；发布者还需审核首次提交和变更集。

每个 `main` 提交先通过版本、公开信息、测试与构建门禁，再使用标准 ARM64 GitHub runner 构建两个同版本镜像，写入不可变摘要与来源 commit，并用专用 deploy key 更新单独的私有 GitOps 仓库。Argo CD 先执行有时限的数据库迁移，再更新应用。凭据仅放在 GitHub Actions Secrets 与 Kubernetes Secrets。

所需 Actions Secrets：`DOCKERHUB_USERNAME`、`DOCKERHUB_TOKEN`、`GITOPS_DEPLOY_KEY`、`GITOPS_KNOWN_HOSTS`、`GITOPS_REPOSITORY`。所需变量：`REGISTRY_NAMESPACE`。公开 PR 的测试不读取发布凭据。回滚使用私有 GitOps 中上一组镜像摘要，不覆盖已发布镜像或版本。
