# Windows 本地 Demo 启动器

## 1. 用途

本目录用于明示和启动本地演示环境。它只面向开发机或演示机，不是生产部署方案。

启动器会在本机回环地址启动三项服务：

- Worker：`http://127.0.0.1:8091`
- 控制面：`http://127.0.0.1:8080`
- 操作台：`http://127.0.0.1:5173`

Worker 和控制面使用固定端口约束：Worker 只能使用 `8091`，控制面只能使用 `8080`。如果这两个端口已被监听，启动器会先终止占用端口的进程，再在原端口重启服务；任何情况下都不允许为 Worker 或控制面切换到其他端口。

## 2. 前置条件

演示机需要提前安装：

- Java 21，并确保 `java.exe` 在 `PATH` 中。
- Node.js 20+，并确保 `npm.cmd` 在 `PATH` 中。
- Windows 命令处理器 `cmd.exe`。
- 通过批准的部署密钥源向启动进程注入一次性 `OPS_AGENT_DEMO_ADMIN_PASSWORD` 和 `OPS_AGENT_SQL_DML_RECEIPT_HMAC_SECRET`；启动器不提供默认口令或签名密钥。

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

Worker 和控制面都会以 `demo` profile 启动。该 profile 只用于 `sit` H2 演示，Worker 的基础配置始终关闭 DML；不要在 `dev`、`uat` 或生产环境激活该 profile。

如果前端已经内嵌进控制面 JAR，且后端两个 JAR 已经单独拷贝出来，可以把脚本复制到这两个 JAR 所在目录后双击运行：

```text
start-backend-jars.cmd
```

这个目录里应只保留一份控制面 JAR 和一份 Worker JAR：

```text
control-plane-bootstrap-*.jar
execution-worker-*.jar
```

如果不想移动脚本，也可以把 JAR 目录作为第一个参数传入：

```text
tools\demo\start-backend-jars.cmd D:\ops-agent-jars
```

如需显式指定 JDK 21 的 `bin` 目录，第二个参数传入：

```text
tools\demo\start-backend-jars.cmd D:\ops-agent-jars "C:\path\to\jdk-21\bin"
```

该脚本不会运行 Maven、npm 或前端开发服务器，只会启动已经拷贝出来的两个 JAR。

控制面会使用 `demo` profile 启动，页面地址为：

```text
http://127.0.0.1:8080
```

登录信息：

```text
用户名：admin
密码：由启动进程注入的 OPS_AGENT_DEMO_ADMIN_PASSWORD
```

该账号只在 `demo` profile 下自动预置。口令必须是一次性受控演示值；不要把它用于生产、测试长期环境或真实系统接入。

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

这是本地 H2 内存数据源，目标环境为 `sit`，只用于演示 SQL 工作台只读查询和非生产受控 DML 链路。

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

以下语句可用于演示非生产受控 DML 手动提交：

```sql
update PUBLIC.ORDERS
set STATUS = 'DONE'
where ORDER_ID = 1
```

P2 demo 允许 `sit` 环境单条 `INSERT`、`UPDATE`、`DELETE` 通过“事务模式”和“手动提交”进入短事务。无 `WHERE` 的 `UPDATE` / `DELETE` 会要求二次确认。DDL、存储过程、多语句脚本、长期事务、交互式回滚和生产写执行不属于本 demo 的执行能力。

## 7. 常见问题

### 端口已占用

启动器会检查 `8091`、`8080` 和 `5173`。其中 `8091` 和 `8080` 是 Worker 与控制面的固定端口；如果已被监听，启动器会先终止占用进程，再使用原端口启动服务，不会切换到其他端口。`5173` 是操作台开发服务器端口，若被占用需先关闭已有前端进程。

如果占用进程是本启动器创建的，也可先运行：

```text
tools\demo\stop-demo.cmd
```

### 控制面登录失败

检查：

- `control-plane.log` 是否显示启动成功。
- 启动进程是否已注入 `OPS_AGENT_DEMO_ADMIN_PASSWORD`。
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
- 不保存管理员口令、真实数据库密码、模型 API Key 或生产连接串。
- 生产 SQL 连接只能用于查询，不开放生产写执行。
- 不开放任意脚本执行。
- 前端仍只调用控制面，不直接调用 Worker 或 H2。
- SQL 执行仍经过控制面校验、策略授权、工作流和 Worker 二次校验。
