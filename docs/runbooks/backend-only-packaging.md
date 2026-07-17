# 前端内嵌控制面 JAR 打包运行手册

## 适用范围

本手册用于在不能运行 PowerShell 的 Windows 环境中，通过 `cmd.exe` 完成以下本地打包流程：

1. 构建前端操作台 `dist` 静态文件。
2. 使用系统 Maven 打包后端控制面和执行 Worker。
3. 把前端 `dist` 内嵌到控制面 Spring Boot JAR 的 `static/` classpath 中。
4. 使用 `demo` profile 启动控制面做本地验证。

本流程只做本地构建和 JAR 启动验证，不生成统一发布 ZIP，不连接目标服务器，不触发发布中心执行链路，不处理生产凭据。

## 前置条件

执行机器需要提前安装：

- Java 21，并确保 `java` 和 `jar` 在 `PATH` 中。
- Maven 3.9 或兼容版本，并确保 `mvn` 在 `PATH` 中。
- Node.js 20 或兼容版本，并确保 `node` 和 `npm` 在 `PATH` 中。
- Windows 命令提示符 `cmd.exe`。

先打开 `cmd.exe`，确认工具可用：

```cmd
java -version
jar --version
mvn -v
node -v
npm -v
```

如果 `mvn -v` 不能运行，需要先安装 Maven 或修复 `PATH`。本手册只使用 `cmd.exe`、系统 `mvn` 和 `npm`，不依赖 `mvnw.cmd`。

## 设置仓库路径

请把下面的路径替换成实际仓库路径：

```cmd
set "REPO=C:\path\to\poc-ops-agent"
```

后续命令都依赖这个变量。可以用下面命令确认目录存在：

```cmd
dir "%REPO%"
```

## 构建前端 dist

进入前端目录：

```cmd
cd /d "%REPO%\frontend\operator-console"
```

第一次构建或依赖变化后，安装依赖：

```cmd
npm ci
```

执行前端正式构建：

```cmd
npm run build
```

构建成功后，确认前端入口文件存在：

```cmd
dir "%REPO%\frontend\operator-console\dist\index.html"
```

如果只做临时本地验证，并且明确接受跳过前端检查、lint 和测试，可以直接生成 Vite `dist`：

```cmd
npx vite build
```

正式提交或发包前仍应使用 `npm run build`。

## 打包后端并内嵌前端

进入后端目录：

```cmd
cd /d "%REPO%\backend"
```

快速打包控制面和 Worker，并把前端 `dist` 打进控制面 JAR：

```cmd
mvn -f pom.xml -B -ntp -pl control-plane/bootstrap,execution-worker -am -DskipTests -Dops-agent.include-operator-console=true "-Dops-agent.operator-console.dist=%REPO%\frontend\operator-console\dist" package
```

看到 `BUILD SUCCESS` 后，后端 JAR 会生成在：

```text
backend\control-plane\bootstrap\target\control-plane-bootstrap-*.jar
backend\execution-worker\target\execution-worker-*.jar
```

如果需要在打包时运行后端测试，去掉 `-DskipTests`：

```cmd
mvn -f pom.xml -B -ntp -pl control-plane/bootstrap,execution-worker -am -Dops-agent.include-operator-console=true "-Dops-agent.operator-console.dist=%REPO%\frontend\operator-console\dist" package
```

`demo` 账号是运行时 profile 行为，不是打包参数。打包命令不需要增加 demo 参数。

## 验证前端已进入 JAR

在后端目录中执行：

```cmd
cd /d "%REPO%\backend"
for /f "delims=" %J in ('dir /b control-plane\bootstrap\target\control-plane-bootstrap-*.jar') do set CONTROL_PLANE_JAR=control-plane\bootstrap\target\%J
jar tf "%CONTROL_PLANE_JAR%" | findstr /c:"BOOT-INF/classes/static/index.html"
```

如果输出包含下面路径，说明前端入口已经进入控制面 JAR：

```text
BOOT-INF/classes/static/index.html
```

## 启动 Worker

另开一个 `cmd.exe` 窗口：

启动前必须通过批准的部署密钥源向两个启动进程注入一次性 `OPS_AGENT_DEMO_ADMIN_PASSWORD` 和 `OPS_AGENT_SQL_DML_RECEIPT_HMAC_SECRET`。这两个变量没有默认值，不得写入命令历史、脚本或本手册。

```cmd
set "REPO=C:\path\to\poc-ops-agent"
cd /d "%REPO%\backend"
for /f "delims=" %J in ('dir /b execution-worker\target\execution-worker-*.jar') do set WORKER_JAR=execution-worker\target\%J
java -jar "%WORKER_JAR%" --spring.profiles.active=demo
```

默认本地 Worker 监听 `127.0.0.1:8091`。只有 `demo` profile 的 `sit` H2 连接允许受控 DML；基础 Worker 配置保持关闭。

## 启动控制面并启用 demo 账号

再开一个 `cmd.exe` 窗口：

```cmd
set "REPO=C:\path\to\poc-ops-agent"
cd /d "%REPO%\backend"
for /f "delims=" %J in ('dir /b control-plane\bootstrap\target\control-plane-bootstrap-*.jar') do set CONTROL_PLANE_JAR=control-plane\bootstrap\target\%J
rem 以下变量仅由批准的部署密钥源注入，示例不提供任何值。
set "OPS_AGENT_DEMO_ADMIN_PASSWORD=<由安全密钥源注入>"
set "OPS_AGENT_SQL_DML_RECEIPT_HMAC_SECRET=<由安全密钥源注入>"
set "OPS_AGENT_SKILL_REGISTRY_SIGNING_SECRET=<由安全密钥源注入>"
java -jar "%CONTROL_PLANE_JAR%" --spring.profiles.active=demo
```

`demo` profile 会启用本地演示用内建账号：

```text
用户名：admin
密码：由启动进程注入的 OPS_AGENT_DEMO_ADMIN_PASSWORD
```

启动后访问控制面地址。前端页面会由控制面 JAR 内的静态资源提供。

## 常见误用

`-Dspring-boot.run.profiles=demo` 只适用于 `mvn spring-boot:run`：

```cmd
mvn -f control-plane\bootstrap\pom.xml spring-boot:run -Dspring-boot.run.profiles=demo
```

已经打好的 JAR 必须使用：

```cmd
java -jar <control-plane-jar> --spring.profiles.active=demo
```

只执行下面命令不会把前端打进 JAR，因为缺少前端 `dist` 路径和内嵌 profile：

```cmd
mvn -f pom.xml -B -ntp -pl control-plane/bootstrap,execution-worker -am -DskipTests package
```

必须带上：

```cmd
-Dops-agent.include-operator-console=true "-Dops-agent.operator-console.dist=%REPO%\frontend\operator-console\dist"
```

## 写入 cmd 文件时的差异

本手册中的 `for /f` 命令用于直接粘贴到 `cmd.exe` 交互窗口。如果把命令写进 `.cmd` 文件，需要把循环变量从 `%J` 改成 `%%J`：

```cmd
for /f "delims=" %%J in ('dir /b control-plane\bootstrap\target\control-plane-bootstrap-*.jar') do set CONTROL_PLANE_JAR=control-plane\bootstrap\target\%%J
```

## 安全提醒

- `admin` 账号只允许用于本地 `demo` profile，口令必须由 `OPS_AGENT_DEMO_ADMIN_PASSWORD` 一次性注入。
- 不得把 demo profile、演示口令或本地 H2 数据源当成生产配置。
- 本流程不开放生产发布、生产写执行、任意脚本执行或目标系统长期凭据。
- 如需生成包含清单、校验和、前端快照和启动脚本的统一发布 ZIP，应改用 `tools\release\package-release.mjs`；该脚本同样默认使用系统 `mvn`，不依赖 PowerShell 或 Maven Wrapper，必要时可通过 `--maven-command <command>` 指定 Maven 可执行文件。
