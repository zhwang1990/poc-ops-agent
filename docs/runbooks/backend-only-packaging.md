# 后端单独打包运行手册

## 适用范围

本手册用于在本地或演示机上只打包后端控制面和执行 Worker，不构建前端操作台，也不生成统一发布 ZIP。

本流程只做本地 Maven 构建和 JAR 启动验证，不连接目标服务器，不触发发布中心执行链路，不处理生产凭据。

## 前置条件

执行机器需要提前安装：

- Java 21，并确保 `java` 在 `PATH` 中。
- Maven 3.9 或兼容版本，并确保 `mvn` 在 `PATH` 中。
- Windows 命令提示符 `cmd.exe`。

先确认工具可用：

```cmd
java -version
mvn -v
```

如果 `mvn -v` 不能运行，需要先安装 Maven 或修复 `PATH`。本手册只使用 `cmd.exe` 和系统 `mvn`，不依赖 `mvnw.cmd`。

## 快速打包

进入后端目录。请把 `C:\path\to\poc-ops-agent` 替换成实际仓库路径：

```cmd
cd /d C:\path\to\poc-ops-agent\backend
```

执行后端单独打包：

```cmd
mvn -f pom.xml -B -ntp -pl control-plane/bootstrap,execution-worker -am -DskipTests package
```

看到 `BUILD SUCCESS` 后，后端 JAR 会生成在：

```text
control-plane\bootstrap\target\control-plane-bootstrap-*.jar
execution-worker\target\execution-worker-*.jar
```

## 完整校验打包

如果需要在打包时运行后端测试，去掉 `-DskipTests`：

```cmd
mvn -f pom.xml -B -ntp -pl control-plane/bootstrap,execution-worker -am package
```

日常快速演示可以使用 `-DskipTests package`，正式提交或发布前仍应运行与变更范围匹配的测试和仓库检查。

## 启动 Worker

另开一个命令提示符窗口，进入后端目录：

```cmd
cd /d C:\path\to\poc-ops-agent\backend
for /f "delims=" %J in ('dir /b execution-worker\target\execution-worker-*.jar') do set WORKER_JAR=execution-worker\target\%J
java -jar "%WORKER_JAR%"
```

默认本地 Worker 监听 `127.0.0.1:8091`。

## 启动控制面并启用 demo 账号

再开一个命令提示符窗口，进入后端目录：

```cmd
cd /d C:\path\to\poc-ops-agent\backend
for /f "delims=" %J in ('dir /b control-plane\bootstrap\target\control-plane-bootstrap-*.jar') do set CONTROL_PLANE_JAR=control-plane\bootstrap\target\%J
java -jar "%CONTROL_PLANE_JAR%" --spring.profiles.active=demo
```

`demo` profile 会启用本地演示用内建账号：

```text
用户名：admin
密码：Admin#2026Demo
```

`demo` 账号是运行时 profile 行为，不是打包参数。打包命令不需要增加 demo 参数。

## 常见误用

`-Dspring-boot.run.profiles=demo` 只适用于 `mvn spring-boot:run`：

```cmd
mvn -f control-plane\bootstrap\pom.xml spring-boot:run -Dspring-boot.run.profiles=demo
```

已经打好的 JAR 必须使用：

```cmd
java -jar <control-plane-jar> --spring.profiles.active=demo
```

## 写入 cmd 文件时的差异

本手册中的 `for /f` 命令用于直接粘贴到 `cmd.exe` 交互窗口。如果把命令写进 `.cmd` 文件，需要把循环变量从 `%J` 改成 `%%J`：

```cmd
for /f "delims=" %%J in ('dir /b execution-worker\target\execution-worker-*.jar') do set WORKER_JAR=execution-worker\target\%%J
```

## 安全提醒

- `admin / Admin#2026Demo` 只允许用于本地演示 profile。
- 不得把 demo profile、demo 密码或本地 H2 数据源当成生产配置。
- 后端单独打包不会内嵌前端页面；需要前后端统一发布包时，应改用 `tools/release/package-release.mjs`。
- 本流程不开放生产发布、生产写执行、任意脚本执行或目标系统长期凭据。
