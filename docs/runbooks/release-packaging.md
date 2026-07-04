# 统一发布包打包使用手册

## 适用范围

本文说明如何使用 `tools/release` 目录下的发布打包脚本生成统一发布包。

统一发布包用于本地交付、测试交付或后续受控发布流程的输入。它只做本地构建和制品组装，不连接目标服务器，不触发发布中心执行链路，不执行生产发布、启停、回滚或任意脚本。

如果只想把前端 `dist` 内嵌到控制面 JAR 并手工启动验证，可以参考 `docs/runbooks/backend-only-packaging.md`。如果需要包含清单、校验和、前端快照、启动脚本和 ZIP 文件，应使用本文的统一发布包流程。

## 发布脚本入口

主入口：

```cmd
node tools\release\package-release.mjs [options]
```

脚本特性：

- 使用 Node.js 编排前端、后端和发布包组装。
- 不依赖 PowerShell。
- 不依赖 Maven Wrapper。
- 默认调用系统 `mvn`。
- 可以通过 `--maven-command` 指定 Maven 可执行文件。
- 默认生成 `artifacts/release/ops-agent-<version>/` 和 `artifacts/release/ops-agent-<version>.zip`。

## 前置条件

打包机器需要提前安装并能执行以下工具：

```cmd
java -version
jar --version
node -v
npm -v
mvn -v
```

要求：

- Java 21 JDK。必须包含 `java` 和 `jar`，只安装 JRE 不够。
- Node.js 20 或兼容版本。
- npm，版本以当前前端 `package-lock.json` 能正常执行 `npm ci` 为准。
- Maven 3.9 或兼容版本，并确保 `mvn` 在 `PATH` 中。

如果受限环境不能把 Maven 放到 `PATH`，可以直接指定 Maven 可执行文件：

```cmd
node tools\release\package-release.mjs --maven-command C:\tools\apache-maven\bin\mvn.cmd
```

路径包含空格时使用引号：

```cmd
node tools\release\package-release.mjs --maven-command "C:\Program Files\Apache Maven\bin\mvn.cmd"
```

## 推荐目录位置

先进入仓库根目录。下面的命令都假设当前目录是仓库根目录：

```cmd
cd /d C:\path\to\poc-ops-agent
```

确认目录中存在：

```text
backend\pom.xml
frontend\operator-console\package.json
tools\release\package-release.mjs
```

## 最常用命令

### 正式本地打包

该命令会安装前端依赖、执行前端正式构建，并运行后端 Maven `verify`：

```cmd
node tools\release\package-release.mjs
```

适用于正式交付前的本地验证。它会运行后端测试，耗时更长，但风险最低。

### 快速打包

该命令跳过后端测试，Maven 使用 `package -DskipTests`：

```cmd
node tools\release\package-release.mjs --skip-tests
```

适用于本地快速验证打包流程。正式发布门禁不应只依赖该命令。

### 已经安装前端依赖时跳过 `npm ci`

如果 `frontend/operator-console/node_modules` 已经准备好，可以跳过依赖安装：

```cmd
node tools\release\package-release.mjs --skip-frontend-install
```

受限网络环境常用组合：

```cmd
node tools\release\package-release.mjs --skip-tests --skip-frontend-install
```

这要求当前 `node_modules` 已经和 `package-lock.json` 匹配，否则前端构建可能失败或产物不可复现。

### 只运行 Vite 构建

默认前端步骤是 `npm run build`。如果只想临时生成 Vite `dist`，可以使用：

```cmd
node tools\release\package-release.mjs --skip-frontend-tests
```

该参数会改用：

```cmd
npm exec vite -- build
```

它适合本地临时验证，不适合作为正式发布门禁。

### 指定版本号

默认版本号来自 `backend/pom.xml` 的项目版本。需要覆盖时使用：

```cmd
node tools\release\package-release.mjs --version 0.1.0-rc1
```

版本号只能包含字母、数字、点、下划线和短横线，并且必须以字母或数字开头。

### 指定输出目录

默认输出到 `artifacts/release`。需要改到其他目录时使用：

```cmd
node tools\release\package-release.mjs --artifact-root C:\release-output
```

生成结果示例：

```text
C:\release-output\ops-agent-0.1.0\
C:\release-output\ops-agent-0.1.0.zip
```

### 同步复制到发布目录

如果要把 ZIP、清单和校验和复制到另一个目录：

```cmd
node tools\release\package-release.mjs --publish-dir \\fileserver\ops-agent\release-candidates
```

该参数只复制以下文件，不执行部署：

- `ops-agent-<version>.zip`
- `ops-agent-<version>-manifest.json`
- `ops-agent-<version>-checksums.sha256`

## 参数说明

| 参数 | 默认值 | 说明 |
|---|---|---|
| `--version <value>` | `backend/pom.xml` 版本 | 覆盖发布包版本号。 |
| `--artifact-root <path>` | `artifacts/release` | 指定发布包输出根目录。 |
| `--publish-dir <path>` | 不复制 | 把 ZIP、manifest 和 checksum 复制到外部目录。 |
| `--maven-command <command>` | `mvn` | 指定 Maven 可执行文件，适合 `mvn` 不在 `PATH` 的受限环境。 |
| `--skip-tests` | `false` | 后端使用 `package -DskipTests`，跳过 Maven 测试。 |
| `--skip-frontend-install` | `false` | 跳过 `npm ci`。 |
| `--skip-frontend-tests` | `false` | 前端使用 `npm exec vite -- build`，不走完整 `npm run build`。 |
| `-h` / `--help` | 无 | 显示命令帮助。 |

查看当前脚本支持的参数：

```cmd
node tools\release\package-release.mjs --help
```

## 脚本内部执行顺序

默认执行顺序：

1. 读取参数。
2. 从 `backend/pom.xml` 读取版本号。
3. 在 `frontend/operator-console` 执行 `npm ci`。
4. 执行前端构建，默认是 `npm run build`。
5. 检查 `frontend/operator-console/dist` 是否存在。
6. 在 `backend` 目录执行系统 Maven 构建：

   ```cmd
   mvn -f pom.xml -B -ntp -Dops-agent.include-operator-console=true "-Dops-agent.operator-console.dist=<frontend-dist>" verify
   ```

7. 定位控制面 JAR 和执行 Worker JAR。
8. 复制前端 `dist`、Skill 契约包和后端 JAR 到发布目录。
9. 生成启动脚本、配置模板、`release-manifest.json` 和 `checksums.sha256`。
10. 使用 JDK `jar` 工具生成 ZIP。
11. 如设置了 `--publish-dir`，复制 ZIP、清单和校验和到发布目录。

使用 `--skip-tests` 时，后端 Maven 命令改为：

```cmd
mvn -f pom.xml -B -ntp -Dops-agent.include-operator-console=true "-Dops-agent.operator-console.dist=<frontend-dist>" -DskipTests package
```

## 产物结构

默认输出目录：

```text
artifacts/release/ops-agent-<version>/
```

目录结构：

```text
ops-agent-<version>/
|-- apps/
|   |-- control-plane-bootstrap.jar
|   `-- execution-worker.jar
|-- config/
|   `-- start-ops-agent.cmd
|-- contracts/
|   `-- skills/
|       `-- packages/
|-- frontend/
|   `-- operator-console-dist/
|-- scripts/
|   |-- start-control-plane.cmd
|   |-- start-control-plane.sh
|   |-- start-execution-worker.cmd
|   |-- start-execution-worker.sh
|   `-- start-ops-agent.cmd
|-- checksums.sha256
`-- release-manifest.json
```

ZIP 文件：

```text
artifacts/release/ops-agent-<version>.zip
```

## 产物内容说明

- `apps/control-plane-bootstrap.jar`：控制面 Spring Boot 可运行 JAR，包含前端静态资源。
- `apps/execution-worker.jar`：执行 Worker Spring Boot 可运行 JAR。
- `frontend/operator-console-dist/`：前端构建结果快照，便于人工检查。
- `contracts/skills/packages/`：Skill 注册和契约包快照。
- `config/start-ops-agent.cmd`：Windows 一键启动脚本的可编辑配置模板。
- `scripts/start-ops-agent.cmd`：Windows 下同时启动 Worker 和控制面的脚本。
- `scripts/start-control-plane.cmd`：Windows 下单独启动控制面。
- `scripts/start-execution-worker.cmd`：Windows 下单独启动 Worker。
- `scripts/start-control-plane.sh`：POSIX 下单独启动控制面。
- `scripts/start-execution-worker.sh`：POSIX 下单独启动 Worker。
- `release-manifest.json`：发布清单，记录版本、Git commit、生成时间、文件列表、文件大小和 SHA-256。
- `checksums.sha256`：发布包内文件校验和。

## 打包后检查

### 检查命令输出

成功时脚本会输出类似内容：

```text
Release directory: C:\path\to\poc-ops-agent\artifacts\release\ops-agent-0.1.0
Release zip: C:\path\to\poc-ops-agent\artifacts\release\ops-agent-0.1.0.zip
Release manifest: C:\path\to\poc-ops-agent\artifacts\release\ops-agent-0.1.0\release-manifest.json
Release checksums: C:\path\to\poc-ops-agent\artifacts\release\ops-agent-0.1.0\checksums.sha256
```

### 检查文件是否存在

```cmd
dir artifacts\release
dir artifacts\release\ops-agent-<version>\apps
dir artifacts\release\ops-agent-<version>\frontend\operator-console-dist\index.html
dir artifacts\release\ops-agent-<version>\release-manifest.json
dir artifacts\release\ops-agent-<version>\checksums.sha256
```

### 检查前端是否已进入控制面 JAR

```cmd
jar tf artifacts\release\ops-agent-<version>\apps\control-plane-bootstrap.jar | findstr /c:"BOOT-INF/classes/static/index.html"
```

看到下面路径表示前端入口已经内嵌到控制面 JAR：

```text
BOOT-INF/classes/static/index.html
```

### 检查清单

```cmd
type artifacts\release\ops-agent-<version>\release-manifest.json
```

重点确认：

- `version` 是预期版本。
- `gitCommit` 是当前提交或本地 fallback。
- `files` 中包含控制面 JAR、Worker JAR、前端 `index.html`、启动脚本和 Skill 契约包。

### 检查校验和

`checksums.sha256` 的每一行格式是：

```text
<sha256>  <relative-path>
```

可人工抽查某个文件的 SHA-256 是否一致。Windows 可使用：

```cmd
certutil -hashfile artifacts\release\ops-agent-<version>\apps\control-plane-bootstrap.jar SHA256
```

## 本地启动发布包

生成发布包后，可以在发布目录中做本地启动验证。

进入发布目录：

```cmd
cd /d artifacts\release\ops-agent-<version>
```

如需调整端口、Java 路径或 profile，先编辑：

```text
config\start-ops-agent.cmd
```

常用配置项：

```cmd
set "OPS_AGENT_JAVA_HOME=C:\Program Files\Java\jdk-21"
set "OPS_AGENT_JAVA_EXE=C:\Program Files\Java\jdk-21\bin\java.exe"
set "OPS_AGENT_CONTROL_PLANE_ADDRESS=0.0.0.0"
set "OPS_AGENT_CONTROL_PLANE_PORT=8080"
set "OPS_AGENT_WORKER_ADDRESS=127.0.0.1"
set "OPS_AGENT_WORKER_PORT=8091"
set "OPS_AGENT_SPRING_PROFILES=demo"
```

启动 Worker 和控制面：

```cmd
scripts\start-ops-agent.cmd
```

日志目录：

```text
runtime-logs\
```

常见日志文件：

```text
runtime-logs\worker.out.log
runtime-logs\worker.err.log
runtime-logs\control-plane.out.log
runtime-logs\control-plane.err.log
```

如果只想单独启动某个组件：

```cmd
scripts\start-execution-worker.cmd
scripts\start-control-plane.cmd
```

POSIX 环境下当前提供单组件启动脚本：

```sh
./scripts/start-execution-worker.sh
./scripts/start-control-plane.sh
```

## 清理和重复打包

同一版本重复打包时，脚本会删除并重建：

```text
artifacts/release/ops-agent-<version>/
artifacts/release/ops-agent-<version>.zip
```

如果你需要保留旧包，应先改版本号或复制到其他目录。

推荐每次正式候选包使用明确版本：

```cmd
node tools\release\package-release.mjs --version 0.1.0-rc1
node tools\release\package-release.mjs --version 0.1.0-rc2
```

## 离线或受限网络环境

受限网络环境通常有两类问题：

1. `npm ci` 不能访问 npm registry。
2. Maven 不能下载依赖。

处理建议：

- 提前准备 npm 缓存或预装匹配的 `node_modules`，然后使用 `--skip-frontend-install`。
- 提前准备 Maven 本地仓库，确保 `mvn -o` 场景下依赖完整。
- 如果 Maven 不在 `PATH`，使用 `--maven-command` 指向固定路径。
- 不要改用 Maven Wrapper。发布脚本设计为使用系统 Maven。

示例：

```cmd
node tools\release\package-release.mjs --skip-tests --skip-frontend-install --maven-command D:\maven\bin\mvn.cmd
```

## 常见故障处理

### `mvn` 不是内部或外部命令

原因：系统 Maven 不在 `PATH`。

处理：

```cmd
node tools\release\package-release.mjs --maven-command C:\tools\apache-maven\bin\mvn.cmd
```

或者修复 `PATH` 后重新运行：

```cmd
mvn -v
```

### `jar` 不是内部或外部命令

原因：未安装 JDK，或 `JAVA_HOME\bin` 不在 `PATH`。

处理：

- 安装 Java 21 JDK。
- 确保 `jar --version` 能执行。

### `frontend dist not found`

原因：前端构建未成功生成 `frontend/operator-console/dist`。

处理：

```cmd
cd frontend\operator-console
npm ci
npm run build
```

修复前端构建问题后回到仓库根目录重新运行 release 脚本。

### `Expected exactly one ... jar`

原因：目标目录中没有找到 JAR，或存在多个同名前缀 JAR。

处理：

1. 查看 Maven 是否 `BUILD SUCCESS`。
2. 清理对应 target 目录后重跑。

```cmd
rmdir /s /q backend\control-plane\bootstrap\target
rmdir /s /q backend\execution-worker\target
node tools\release\package-release.mjs --skip-tests
```

### `Forbidden file type in release package`

原因：发布包复制范围内出现 `.env`、`.key`、`.pem`、`.p12`、`.pfx`、`.log` 等禁止文件。

处理：

- 不要把密钥、日志、本地状态或生产数据放进前端 `dist` 或 Skill 契约包目录。
- 清理相关文件后重新打包。

### `npm ci` 失败

常见原因：

- npm registry 不可达。
- `package-lock.json` 与 `package.json` 不一致。
- Node.js 版本不兼容。

处理：

```cmd
cd frontend\operator-console
npm ci
npm run build
```

如果环境完全离线，需要先准备 npm 缓存或预装依赖。仅在确认依赖已准备好时使用：

```cmd
node tools\release\package-release.mjs --skip-frontend-install
```

### Maven 测试失败

处理原则：

- 正式候选包必须优先修复测试失败。
- `--skip-tests` 只能用于本地快速验证打包链路，不应用作正式发布依据。

临时验证命令：

```cmd
node tools\release\package-release.mjs --skip-tests
```

## 安全边界

统一发布包脚本只做构建和制品组装：

- 不连接目标服务器。
- 不读取生产凭据。
- 不执行目标环境脚本。
- 不触发发布中心工作流。
- 不开放生产写执行。
- 不绕过策略、审批、幂等、审计或 Worker 隔离。

发布包中不得包含：

- `.env` 或 `.env.*`
- `.key`
- `.pem`
- `.p12`
- `.pfx`
- `.log`
- `node_modules`
- `target`
- `test-results`
- 生产数据或密钥

正式交付前仍应运行仓库质量门禁、密钥扫描和必要测试。

## 推荐验证清单

正式交付前至少确认：

- `node tools\release\package-release.mjs --help` 输出参数符合预期。
- `node tools\release\test-package-release-options.mjs` 通过。
- `node tools\release\test-release-packaging.mjs` 通过。
- `node tools\release\package-release.mjs` 能完整生成发布目录和 ZIP。
- `release-manifest.json` 中包含预期文件。
- `checksums.sha256` 已生成。
- 控制面 JAR 中存在 `BOOT-INF/classes/static/index.html`。
- 发布 ZIP 不包含密钥、日志、本地运行状态或构建临时目录。
