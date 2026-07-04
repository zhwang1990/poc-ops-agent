# 前后端统一打包与发布包设计

## 背景

当前项目处于 P2 受控变更试点阶段。前端操作台位于 `frontend/operator-console`，后端控制面和 Worker 位于 `backend` Maven 多模块工程。用户要求开始建设“前后端打在一起”的打包和发布脚本，并确认采用“双产物”方案：控制面 Spring Boot JAR 内置前端静态资源，同时生成包含前后端制品和清单的发布 ZIP。

本设计属于 M00、M10 和 M11 的工程发布机制，不新增目标环境部署能力，不改变发布中心 ADR 0010 的安全边界。

## 目标

1. 生成一个内置操作台静态资源的 `control-plane-bootstrap` 可运行 JAR。
2. 生成一个发布 ZIP，包含控制面 JAR、执行 Worker JAR、前端 `dist` 快照、校验和、构建清单和本地启动脚本。
3. 通过脚本测试和仓库门禁验证打包规则，避免把 `node_modules`、日志、密钥、本地运行状态或生产数据写入发布包。

## 不做事项

- 不连接任何目标服务器。
- 不触发 CI/CD 平台、Tomcat、Liberty、SSH、脚本 Profile 或发布中心执行链路。
- 不处理生产凭据或目标系统长期凭据。
- 不开放生产发布、启停、回滚或任意脚本执行能力。

## 方案

新增 `tools/release` 目录，发布脚本统一使用 Node.js ESM，不使用 PowerShell：

- `release-packaging.mjs`：提供可测试的打包函数，包括路径校验、发布目录初始化、文件复制、SHA-256 校验和生成、构建清单生成、ZIP 生成和可选发布目录复制。
- `package-release.mjs`：面向开发者和 CI 的入口脚本，负责编排前端构建、后端系统 Maven 构建、发布目录组装和 ZIP 生成；默认使用 `PATH` 中的 `mvn`，可通过 `--maven-command` 指定 Maven 可执行文件，不依赖 Maven Wrapper。
- `test-release-packaging.mjs`：使用临时目录和假制品验证模块函数，不运行真实 Maven 或 npm 构建。

后端 `backend/control-plane/bootstrap/pom.xml` 增加一个受属性控制的 Maven Profile。脚本在构建前端后，通过 `-Dops-agent.include-operator-console=true` 和 `-Dops-agent.operator-console.dist=<dist路径>` 让 Maven 在 `process-resources` 阶段把前端 `dist` 复制到 classpath 的 `static/` 下。该方式不把生成的前端文件提交到源码目录。

## 产物结构

发布目录格式：

```text
artifacts/release/ops-agent-<version>/
|-- apps/
|   |-- control-plane-bootstrap.jar
|   `-- execution-worker.jar
|-- frontend/
|   `-- operator-console-dist/
|-- scripts/
|   |-- start-control-plane.cmd
|   |-- start-control-plane.sh
|   |-- start-execution-worker.cmd
|   `-- start-execution-worker.sh
|-- checksums.sha256
`-- release-manifest.json
```

最终 ZIP：

```text
artifacts/release/ops-agent-<version>.zip
```

`release-manifest.json` 记录版本、Git commit、生成时间、产物路径、文件大小和 SHA-256。`checksums.sha256` 用于人工或 CI 校验发布包内容。

## 安全与质量

- 发布脚本默认失败关闭：缺少前端 `dist`、控制面 JAR 或 Worker JAR 时立即失败。
- 发布 ZIP 只从白名单路径复制制品，不扫描或复制整个仓库。
- 可选 `-PublishDirectory` 只把 ZIP、校验和和清单复制到指定目录，不执行部署。
- 仓库密钥扫描继续由 `tools/ci/scan-secrets.ps1` 覆盖。
- Maven Profile 仅在显式属性启用时复制前端静态资源，不影响普通后端开发构建。

## 使用方式

```bash
node tools/release/package-release.mjs --skip-tests
```

默认脚本会执行前端构建和后端系统 Maven 构建，不使用 PowerShell 或 Maven Wrapper。`--skip-tests` 仅用于本地快速打包验证，正式发布门禁仍应运行完整测试。
如果受限环境中的 Maven 不在 `PATH`，使用 `--maven-command <command>` 指定 Maven 可执行文件。

## 验收标准

1. `node tools/release/test-release-packaging.mjs` 通过。
2. `node tools/release/package-release.mjs --skip-tests` 能生成内置前端的控制面 JAR 和发布 ZIP。
3. 发布 ZIP 中存在控制面 JAR、Worker JAR、前端 `index.html`、`release-manifest.json` 和 `checksums.sha256`。
4. 发布 ZIP 中存在控制面和 Worker 的 Windows / POSIX 启动脚本。
5. 发布 ZIP 中不存在 `node_modules`、`.log`、`.env`、`.key`、`.p12`、`.pfx`、`target/surefire-reports` 等非发布内容。
6. `tools/ci/check-repository.ps1` 和 `tools/ci/scan-secrets.ps1` 通过。
