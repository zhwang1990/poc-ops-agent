# Windows Demo 环境启动脚本设计

## 1. 背景与目标

本设计用于支持 2026-07-01 的本地演示。目标是在没有 PowerShell 执行权限的 Windows 机器上，通过双击 `.cmd` 文件启动 P1 演示环境，并让操作台、控制面、Worker 和 SQL 工作台 H2 演示数据源可用。

演示环境仍属于本地开发和测试用途，不是生产部署方案。它必须遵守 P1 只读诊断 MVP 边界：不开放生产写执行，不启用任意脚本执行，不绕过身份、策略、审计、工作流和 Worker 隔离链路。

## 2. 非目标

- 不提供生产部署、服务注册、Windows Service 安装或高可用方案。
- 不调用 PowerShell、WSL、Docker、kubectl 或额外运维平台。
- 不自动杀死未知 Java、Node 或端口占用进程。
- 不保存真实数据库密码、模型 API Key、生产连接串或长期目标系统凭据。
- 不开放生产 SQL 连接，不执行 DML、DDL、存储过程、事务提交或回滚。
- 不把 demo 账号、demo 密码或 H2 数据源视为生产配置。
- 不新增多租户、外部客户接入、计费或产品边界外能力。

## 3. 交付内容

新增 `tools/demo` 目录，包含：

- `start-demo.cmd`：双击启动入口。
- `stop-demo.cmd`：双击停止由 demo 启动器创建的进程。
- `README.md`：中文 demo 操作手册、账号信息、常见问题和安全提醒。

脚本运行时创建本地状态目录：

- `.demo/logs`：Worker、控制面、前端和启动器日志。
- `.demo/pids`：启动器记录的进程 ID。
- `.demo/runtime`：demo profile 所需的临时运行标记。

这些目录属于本地运行状态，不应提交到 Git。

## 4. 启动流程

`start-demo.cmd` 使用纯 Windows Batch 实现，所有命令从仓库根目录推导路径。脚本必须能从资源管理器双击运行，不要求用户先打开终端。

启动步骤：

1. 切换到仓库根目录。
2. 检查必需命令：
   - `java.exe`
   - `cmd.exe`
   - `backend\mvnw.cmd`
   - `npm.cmd`
3. 检查端口：
   - Worker：`127.0.0.1:8091`
   - 控制面：`127.0.0.1:8080`
   - 操作台：`127.0.0.1:5173`
4. 如端口被占用，提示用户先关闭相关进程或执行 `stop-demo.cmd`，脚本不自动杀未知进程。
5. 创建 `.demo` 运行目录。
6. 启动 Worker：
   - 工作目录：`backend`
   - 命令：`mvnw.cmd -f execution-worker\pom.xml spring-boot:run`
   - 日志：`.demo\logs\worker.log`
7. 启动控制面：
   - 工作目录：`backend`
   - 命令：`mvnw.cmd -f control-plane\bootstrap\pom.xml spring-boot:run -Dspring-boot.run.profiles=demo`
   - 日志：`.demo\logs\control-plane.log`
8. 启动操作台：
   - 工作目录：`frontend\operator-console`
   - 首次缺少 `node_modules` 时执行 `npm install`。
   - 命令：`npm run dev -- --host 127.0.0.1 --port 5173`
   - 日志：`.demo\logs\operator-console.log`
9. 轮询可用性：
   - 优先使用 `curl.exe` 检查 `http://127.0.0.1:8080/actuator/health` 和 `http://127.0.0.1:5173/`。
   - 没有 `curl.exe` 时退化为固定等待，并提示用户查看日志。
10. 成功后打开 `http://127.0.0.1:5173`。
11. 在窗口中显示 demo 登录信息和 H2 查询样例。

## 5. 停止流程

`stop-demo.cmd` 只停止 demo 启动器记录的进程，不扫描并终止所有 Java 或 Node 进程。

停止步骤：

1. 读取 `.demo/pids` 中的进程 ID。
2. 对每个 PID 使用 `taskkill /PID <pid> /T` 停止进程树。
3. 如果 PID 已不存在，记录为已停止。
4. 不删除 `.demo/logs`，便于现场排障。
5. 不删除控制面本地 H2 工作流库，避免演示过程中误清数据。

如端口仍被占用，停止脚本只提示用户手动检查，不自动扩大杀进程范围。

## 6. Demo Profile

新增控制面 `demo` profile，仅用于本地演示。普通开发 profile 和生产配置不启用 demo 自动开户。

demo profile 行为：

- 使用内建身份模式。
- 启用浏览器账号密码登录。
- 使用本地回环 Worker 地址 `http://127.0.0.1:8091`。
- 保持 Worker 传输认证关闭，仅限回环开发演示。
- 自动预置 demo 管理员账号：
  - 用户名：`admin`
  - 密码：由 `OPS_AGENT_DEMO_ADMIN_PASSWORD` 在启动时注入
  - 角色：`ROLE_ops-admin`、`ROLE_ops-reader`
  - 不强制首次改密。

`OPS_AGENT_DEMO_ADMIN_PASSWORD` 是 demo profile 的一次性受控注入变量；其值不得出现在配置、运行手册、日志、测试数据或长期环境配置中。

## 7. Demo 账号种子化

账号种子化应通过 M01 身份模块仓储和密码哈希服务完成，不直接拼接未哈希密码写表。实现应满足：

- 仅在 `demo` profile 下装配。
- 启动时检查用户名 `admin` 是否存在。
- 不存在时创建账号、角色授予和密码凭据。
- 存在时不覆盖账号状态、角色和密码，避免破坏用户现场调试。
- 记录结构化启动日志，但不输出密码哈希。

种子化逻辑属于演示工程机制，不改变正式内建身份运行手册中“生产首个管理员账号由受控数据库变更预置”的规则。

## 8. H2 演示数据源

复用现有 SQL 工作台连接目录和 Worker 绑定：

- `connectionId`：`h2-local-test`
- `targetEnvironment`：`test`
- `platformType`：`H2`
- `host`：`localhost`
- `port`：`9092`
- `credentialAlias`：`h2-local-readonly`
- `schema`：`PUBLIC`

控制面继续通过启动迁移脚本预置连接目录；Worker 继续通过本地 SQL 出口 allowlist 和连接目录二次校验。H2 连接不读取真实数据库密码。

扩展 Worker 内存 H2 假数据，保留既有 `PUBLIC.ORDERS`，并增加适合演示的只读业务表：

- `PUBLIC.CUSTOMERS`：客户名称、等级、区域、状态。
- `PUBLIC.ORDERS`：订单状态、金额、客户、创建时间。
- `PUBLIC.INCIDENTS`：故障单、影响服务、严重级别、状态、恢复时间。
- `PUBLIC.SERVICE_HEALTH`：服务名、环境、健康状态、错误率、延迟、更新时间。

种子数据应使用 `CREATE TABLE IF NOT EXISTS` 和 `MERGE INTO`，确保重复启动不会重复插入。数据量控制在几十行以内，方便页面演示排序、过滤、聚合和关联查询。

建议在启动成功提示中展示可直接复制的查询：

```sql
select ORDER_ID, STATUS, AMOUNT from PUBLIC.ORDERS order by ORDER_ID
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

## 9. 安全边界

- demo 脚本只监听回环地址或使用现有默认回环配置。
- 前端仍只调用控制面，不直接调用 Worker 或 H2。
- SQL 执行仍必须通过控制面校验、M02 授权、M05 工作流和 M07 Worker 二次校验。
- Worker H2 仍只允许 `RUN_READ_ONLY` 查询。
- DML、DDL、多语句脚本、存储过程和事务控制仍必须被拒绝或只进入静态预检。
- demo 启动器不降低生产安全基线，不写生产配置。
- 日志中不得输出真实密钥、密码哈希、Cookie 或模型 API Key。

## 10. 错误处理

启动器必须用中文提示常见失败：

- 找不到 Java：提示安装 Java 21 并配置 `PATH` 或 `JAVA_HOME`。
- 找不到 npm：提示安装 Node.js 20+。
- 端口占用：提示端口、可能的服务和停止脚本路径。
- Worker 启动失败：提示查看 `.demo\logs\worker.log`。
- 控制面启动失败：提示查看 `.demo\logs\control-plane.log`。
- 前端启动失败：提示查看 `.demo\logs\operator-console.log`。
- health 检查超时：输出已启动窗口和日志路径，不宣称启动成功。

脚本结尾保留窗口，避免双击后错误信息一闪而过。

## 11. 测试与验收

最小自动化验证：

- 后端编译和相关测试：

```cmd
backend\mvnw.cmd -f backend\pom.xml -pl contracts,control-plane/modules/identity,control-plane/modules/sqlworkbench,control-plane/bootstrap,execution-worker-sqlworkbench,execution-worker -am test
```

- 前端验证：

```cmd
cd frontend\operator-console
npm run build
```

手工验收：

1. 双击 `tools\demo\start-demo.cmd`。
2. 等待浏览器打开操作台。
3. 使用 `admin` 和由 `OPS_AGENT_DEMO_ADMIN_PASSWORD` 注入的值登录。
4. 进入 SQL 工作台，确认存在 `h2-local-test`。
5. 执行建议的 `SELECT` 查询，确认能返回 H2 假数据。
6. 尝试 `update PUBLIC.ORDERS set STATUS = 'DONE' where ORDER_ID = 1`，确认不会真实执行。
7. 进入 Agent 工作台，确认只读候选能力仍可访问；如模型供应方未配置，应失败关闭而不是伪造成功。
8. 双击 `tools\demo\stop-demo.cmd`，确认 demo 进程被停止。

## 12. 发布与回滚

发布影响：

- 新增本地 demo 启停脚本、demo profile、demo-only 账号种子化和 H2 假数据。
- 不改变普通开发启动方式。
- 不改变生产部署方式。

回滚方式：

- 删除 `tools/demo` 目录。
- 移除控制面 demo profile 和 demo 账号种子化配置。
- 回退 H2 演示数据扩展，保留或恢复既有 `PUBLIC.ORDERS` smoke 数据。
- 停止 demo 进程并删除 `.demo` 本地状态目录。

## 13. 已知限制

- 纯 Batch 的进程管理能力有限，因此停止脚本只处理记录的 PID。
- 第一次 `npm install` 和 Maven 依赖下载会较慢，建议 demo 前提前运行一次。
- 如果公司电脑禁止 `start` 打开新窗口，脚本需要在同一窗口串行启动并会影响交互体验。
- 如果没有 `curl.exe`，启动器只能做固定等待和日志提示，不能精确判断服务健康。
