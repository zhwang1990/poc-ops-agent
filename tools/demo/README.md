# Windows 本地 Demo 启动器

## 1. 用途

本目录用于明示和启动本地演示环境。它只面向开发机或演示机，不是生产部署方案。

启动器会在本机回环地址启动三项服务：

- Worker：`http://127.0.0.1:8091`
- 控制面：`http://127.0.0.1:8080`
- 操作台：`http://127.0.0.1:5173`

## 2. 前置条件

演示机需要提前安装：

- Java 21，并确保 `java.exe` 在 `PATH` 中。
- Node.js 20+，并确保 `npm.cmd` 在 `PATH` 中。
- Windows 命令处理器 `cmd.exe`。

脚本不调用 PowerShell、Docker、WSL 或 Kubernetes。

第一次启动会下载 Maven 和 npm 依赖，耗时会比较长。正式 demo 前建议先运行一次。

## 3. 启动

从资源管理器双击：

```text
tools\demo\start-demo.cmd
```

如果演示机同时安装了多个 Java 版本，可以显式指定 JDK 21 的 `bin` 目录：

```text
tools\demo\start-demo.cmd "C:\path\to\jdk-21\bin"
```

启动成功后脚本会打开：

```text
http://127.0.0.1:5173
```

登录信息：

```text
用户名：admin
密码：Admin#2026Demo
```

该账号只在 `demo` profile 下自动预置，是公开 demo 凭据。不要把它用于生产、测试长期环境或真实系统接入。

## 4. 停止

从资源管理器双击：

```text
tools\demo\stop-demo.cmd
```

停止脚本只处理 `start-demo.cmd` 记录的 PID 和固定 demo 窗口标题，不会扫描并终止所有 Java 或 Node 进程。

## 5. 日志

日志保存在：

```text
.demo\logs
```

主要文件：

- `worker.log`
- `control-plane.log`
- `operator-console.log`
- `launcher.log`

`.demo` 是本地运行状态目录，不应提交到 Git。

## 6. SQL 工作台演示

进入 SQL 工作台后选择连接：

```text
h2-local-test
```

这是本地 H2 内存数据源，目标环境为 `test`，只用于演示 SQL 工作台只读查询链路。

可演示的查询：

```sql
select ORDER_ID, STATUS, AMOUNT
from PUBLIC.ORDERS
order by ORDER_ID
```

```sql
select c.REGION, count(*) as ORDER_COUNT, sum(o.AMOUNT) as TOTAL_AMOUNT
from PUBLIC.ORDERS o
join PUBLIC.CUSTOMERS c on c.CUSTOMER_ID = o.CUSTOMER_ID
group by c.REGION
order by TOTAL_AMOUNT desc
```

```sql
select SERVICE_NAME, ENVIRONMENT, HEALTH_STATUS, ERROR_RATE_PERCENT, P95_LATENCY_MS
from PUBLIC.SERVICE_HEALTH
where ENVIRONMENT = 'test'
order by P95_LATENCY_MS desc
```

以下语句可用于演示 DML 不会真实执行：

```sql
update PUBLIC.ORDERS
set STATUS = 'DONE'
where ORDER_ID = 1
```

P1 仍只允许受控单条 `SELECT` 执行。`INSERT`、`UPDATE`、`DELETE`、DDL、存储过程、多语句脚本、提交和回滚不属于本 demo 的执行能力。

## 7. 常见问题

### 端口已占用

启动器会检查 `8091`、`8080` 和 `5173`。如果端口已占用，先关闭已有进程；如果是本启动器创建的进程，可运行：

```text
tools\demo\stop-demo.cmd
```

### 控制面登录失败

检查：

- `control-plane.log` 是否显示启动成功。
- 是否使用了 `admin / Admin#2026Demo`。
- 是否以 `demo` profile 启动。

### SQL 查询没有结果

检查：

- 是否选择 `h2-local-test`。
- SQL 是否为单条 `SELECT`。
- `worker.log` 是否显示 Worker 启动成功。

### 页面打不开

检查：

- `operator-console.log` 是否显示 Vite 已监听 `127.0.0.1:5173`。
- 第一次启动时 `npm install` 是否还在执行。

## 8. 安全提醒

- 本启动器不是生产部署方案。
- 不保存真实数据库密码、模型 API Key 或生产连接串。
- 不开放生产 SQL 连接。
- 不开放任意脚本执行。
- 前端仍只调用控制面，不直接调用 Worker 或 H2。
- SQL 执行仍经过控制面校验、策略授权、工作流和 Worker 二次校验。
