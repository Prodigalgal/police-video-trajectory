# ARM64 离线镜像交付

本流程面向无法访问公网镜像仓库的 ARM64 Kubernetes 集群。GitHub Actions 在外网完成测试、ARM64 镜像构建和打包；运维人员下载并校验包后，手动导入节点并更新工作负载。新集群不连接 Docker Hub，也不建设内网镜像仓库或 Runner。

## 发布流程

1. Pull request 运行项目现有测试，并分别构建 Core 与 Runtime 的 `linux/arm64` 镜像，不推送镜像。
2. 合入 `main` 后，`release.yml` 执行正式测试，推送带日期、应用版本和提交 SHA 的不可变标识镜像；同时保留现有悉尼 GitOps 回写流程。
3. 两个镜像成功推送后，该次 Actions run 的 summary 会记录 `run ID`、镜像 tag 和 digest，并保存 `police-video-release-metadata` 工件 90 天。
4. 在 Actions 手动运行「离线 ARM64 镜像导出」，填写已完成的 `release.yml` run ID 和 tag。导出会核对来源分支、工作流、提交、版本以及同一运行生成的两个镜像 digest，并按 digest 拉取镜像。它依赖已成功生成的发布摘要工件，即使后续悉尼 GitOps 回写失败也可以单独导出；没有摘要工件时会拒绝。
5. 下载 `police-video-offline-<version>-<run ID>` 工件，在 Windows 上校验后再搬入内网。导出工件保留 90 天，请及时归档。

## 清单契约

`manifest.json` 的 `schema_version` 为 `offline-app-images-v1`，`platform` 为 `linux/arm64`，`images` 恰为 `core` 与 `runtime` 两项。每项含 `reference`（`命名空间/镜像:tag`）和 `registry_digest`（**裸** `sha256:<64 位十六进制>`，不带 `reference@` 前缀）——打包、Windows 校验与节点导入三处共用这一形式，改动其一必须同步其余两处。`images.tar` 内 `RepoTags` 必须与两项 `reference` 完全一致。

`tests/test_offline_image_delivery.py` 覆盖该契约：导入脚本的覆盖保护和导入后核验在 containerd 1.x（裸 tag）与 2.x（带 `@sha256:` 修饰）两种输出下都必须正确，并校验三方摘要模式一致。

## Windows 校验

解压 GitHub Actions artifact，并将解压目录命名为 `police-video-offline` 后，在 PowerShell 执行：

```powershell
powershell -NoProfile -ExecutionPolicy Bypass -File .\police-video-offline\verify-offline-image-package.ps1 -BundlePath .\police-video-offline
```

校验器核对包内 `SHA256SUMS.txt`、清单格式、Core/Runtime 镜像集合和归档可读性。校验通过后，把整个目录复制到每台可能运行该业务 Pod 的 ARM64 节点。

## 节点导入和手动更新

每台节点执行：

```bash
sudo bash import-offline-images.sh /path/to/police-video-offline
```

脚本需要节点具备 `ctr`、`python3`、`sha256sum` 和 `tar`，会复核 SHA-256、平台和镜像清单，再导入 containerd 的 `k8s.io` namespace；若同名 tag 已存在则停止，避免静默覆盖。脚本同时兼容 containerd 1.x 的裸 tag 输出与 2.x 的 `name:tag@sha256:...` 输出；后者若未剥离摘要修饰，覆盖保护会失效并可能静默覆盖同名 tag。工作负载镜像应使用包内的完整 tag，并设置 `imagePullPolicy: IfNotPresent`。更新 Deployment/StatefulSet 由运维人员手动执行，按 Pod 调度范围在每个可能承载业务 Pod 的节点导入镜像。旧 tag 留作回滚版本。

## 当前基线与启用边界

当前已成功发布并下载的基线是 `0.35.0`，tag 为 `20261110-v0.35.0-ci-4b25ecfb5cd3`；两个 ARM64 镜像已在 `E:\cache\K8s-HA-v1.34.12-ARM64\images\arm64` 离线包中。该历史运行没有生成新的 `police-video-release-metadata` 工件，因此新的严格校验导出工作流不能回溯处理它；如需交付该基线，直接使用上述离线材料和 `PROJECT-IMAGES-v0.35.0.json` 清单。

新增工作流目前只在本地工作区，未推送到 GitHub。启用时需纳入一次正常版本发布；main 发布会继续触发原有悉尼 GitOps 流程。PR 校验不会使用 Docker Hub 或 GitOps 凭据，也不会修改悉尼环境。离线导出只生成下载工件，不会连接或部署到集群。
