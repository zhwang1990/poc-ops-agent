# P1 SQL 工作台运行手册

## 当前能力

- 控制面提供开发和测试环境 SQL 连接目录、连接创建、连接探测、SQL 校验、只读查询执行和结果读取 API。
- P1 只允许 `development` 与 `test` 环境的单条 `SELECT` 进入真实执行链路。
- P1 连接契约允许的 `platformType` 为 `DB2_FOR_I`、`H2` 和 `MYSQL`；新增平台仍必须通过连接目录、出口 allowlist、凭据别名和 Worker 二次校验。
- `INSERT`、`UPDATE`、`DELETE`、DDL、`CALL`、存储过程、`MERGE` 和多语句脚本只能进入静态预检或被拒绝，不得真实执行。
- 控制面生成短期 SQL 执行信封并通过 Worker 传输认证调用 SQL Worker。
- Worker 在本地再次校验请求过期时间、只读 SQL、连接目录、目标环境、出口 allowlist 和 `credentialAlias`，然后才创建 JDBC DataSource。
- 查询结果由 Worker 短期留存并分页读取，结果页包含过期时间。

## 禁止能力

- 不得配置、展示或调用生产 SQL 连接。
- 不得在浏览器、控制面配置、日志、测试数据、Prompt 或源码中保存数据库密码、完整 JDBC 凭据 URL 或目标系统长期密钥。
- 不得绕过控制面直接从前端调用 Worker。
- 不得在 P1 开放 DML 写执行、手动 `Commit`、手动 `Rollback`、长事务或任意脚本执行。
- 未配置 KeyStore 或连接目录时，Worker 必须失败关闭，不得模拟成功。

## 控制面配置

控制面只保存连接元数据和 `credentialAlias`，不保存真实数据库凭据。Worker 传输认证在非回环部署中必须启用。

```yaml
ops-agent:
  worker:
    base-url: http://127.0.0.1:8091
    transport-auth:
      enabled: true
      key-id: ${OPS_AGENT_WORKER_KEY_ID}
      shared-secret: ${OPS_AGENT_WORKER_SHARED_SECRET}
```

## Worker SQL 出口配置

部署环境中的 Worker SQL 出口安全基线是空连接目录和空 allowlist，表示拒绝所有 SQL 目标。仓库内置 `backend/execution-worker/src/main/resources/application.yaml` 仅为本地 H2 smoke 预置 `h2-local-test` 的 `localhost:9092` 测试绑定，不得复制为生产配置。启用真实开发或测试查询前，必须同时配置连接目录、出口 allowlist 和 KeyStore 凭据源。

```yaml
ops-agent:
  worker:
    transport-auth:
      enabled: true
      key-id: ${OPS_AGENT_WORKER_KEY_ID}
      shared-secret: ${OPS_AGENT_WORKER_SHARED_SECRET}
      max-clock-skew: 30s
    sql-egress:
      allowed-targets:
        - host: as400-dev.internal
          port: 446
      connections:
        - connection-id: as400-development
          target-environment: development
          host: as400-dev.internal
          port: 446
          credential-alias: as400-dev-readonly
          username: readonly_user
          enabled: true
    sql-credentials:
      key-store-path: ${OPS_AGENT_SQL_KEYSTORE_PATH}
      store-password: ${OPS_AGENT_SQL_KEYSTORE_PASSWORD}
```

配置要求：

1. `target-environment` 只能是 `development` 或 `test`。
2. `platformType` 只能是 `DB2_FOR_I`、`H2` 或 `MYSQL`；配置中不得传入 JDBC URL、用户名密码或连接串。
3. `allowed-targets` 中必须显式列出连接目录的 `host` 和 `port`。
4. `credential-alias` 只能是 Worker 本地 KeyStore 中的别名，不能是密码、令牌或连接串。
5. `username` 是只读数据库账号名；如省略，Worker 会使用 `credential-alias` 作为账号名，仅适合别名与账号名一致的环境。
6. `key-store-path` 和 `store-password` 必须由部署系统或受控密钥系统注入，不得提交真实值。

## SQL 凭据 KeyStore 写入工具

真实数据库密码必须先写入 Worker 本地 `JCEKS` KeyStore，操作台和控制面只引用 `credentialAlias`。当前仓库提供 `SqlCredentialKeyStoreTool` 写入工具；不要使用 `keytool -importpass` 手工导入数据库密码，因为当前 Worker 读取逻辑要求别名内容与 `JavaKeyStorePasswordProvider` 的 UTF-8 密码读取方式兼容。

Windows 本地联调示例：

```powershell
backend\mvnw.cmd -f backend\pom.xml -pl execution-worker-sqlworkbench -DskipTests compile

$env:OPS_AGENT_SQL_KEYSTORE_PASSWORD = "<由部署系统或受控密钥系统注入的 KeyStore 解锁口令>"
$keyStorePath = "C:\secure\ops-agent\sql-credentials.jceks"
$credentialAlias = "as400-dev-readonly"
$secret = Read-Host "输入 AS/400 只读账号密码" -AsSecureString
$secretPtr = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secret)
try {
  $plainSecret = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($secretPtr)
  $plainSecret | java -cp backend\execution-worker-sqlworkbench\target\classes `
    com.company.opsagent.executionworker.sqlworkbench.SqlCredentialKeyStoreTool `
    put `
    --store $keyStorePath `
    --alias $credentialAlias `
    --store-password-env OPS_AGENT_SQL_KEYSTORE_PASSWORD `
    --secret-stdin
} finally {
  if ($secretPtr -ne [IntPtr]::Zero) {
    [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($secretPtr)
  }
  Remove-Variable plainSecret -ErrorAction SilentlyContinue
}
```

工具成功后只输出别名和 KeyStore 路径，不输出数据库密码。随后 Worker 启动时必须通过 `ops-agent.worker.sql-credentials.key-store-path` 和 `ops-agent.worker.sql-credentials.store-password` 指向同一个 KeyStore 和解锁口令；`sql-egress.connections[].credential-alias` 与操作台新建连接中的 `credentialAlias` 必须一致。

## 新建连接链路

1. 管理员先在 Worker 侧 KeyStore 中预置真实数据库密码，并记录 `credentialAlias`。
2. 管理员在 Worker 配置中登记连接目录和出口 allowlist。
3. 操作台新建连接时只提交显示名、环境、平台类型、主机、端口、Schema 白名单、能力、默认限制和 `credentialAlias`。
4. 控制面保存连接元数据并将连接置为待绑定或待探测状态。
5. 用户触发探测时，控制面通过签名 Worker 请求调用探测端点。
6. Worker 校验本地连接目录、出口 allowlist 和 KeyStore 别名后返回稳定探测状态。

操作台新建连接时，控制面会根据连接名称生成 `connectionId`：将名称转为小写，并把非字母数字字符替换为 `-`。例如连接名称 `as400-development` 会生成 `as400-development`。Worker 配置中的 `sql-egress.connections[].connection-id` 必须与该值一致；如名称冲突导致控制面追加 `-2` 后缀，也必须同步更新 Worker 连接目录后再探测。

探测状态包括：

- `READY`
- `CREDENTIAL_ALIAS_NOT_FOUND`
- `CREDENTIAL_LOCKED`
- `EGRESS_NOT_ALLOWED`
- `READ_ONLY_ACCOUNT_CHECK_FAILED`
- `PROBE_FAILED`

## 执行链路

1. 前端只向控制面提交版本化 SQL 请求，不自行判断授权状态。
2. 控制面执行 SQL 校验、策略检查、审计记录和短期执行信封生成。
3. 控制面使用 Worker 传输认证调用 `/internal/executions/sql-query`。
4. Worker 校验签名、请求过期时间、只读 SQL、连接目录、出口 allowlist 和凭据别名。
5. Worker 使用 JTOpen 创建 Db2 for i DataSource，并在隔离线程池执行阻塞 JDBC 查询。
6. Worker 将结果短期保存为分页结果，控制面通过签名结果读取请求暴露给前端。

## 本地验证

```powershell
backend\mvnw.cmd -f backend\pom.xml -pl contracts,execution-worker-sqlworkbench -am test
backend\mvnw.cmd -f backend\pom.xml -pl contracts,control-plane/modules/sqlworkbench,control-plane/bootstrap -am -Dtest=DefaultSqlWorkbenchServiceTest,WebClientSqlWorkbenchWorkerClientTest,PolicyEnforcementWebFilterTest "-Dsurefire.failIfNoSpecifiedTests=false" test
```

前端变更时再运行：

```powershell
Set-Location frontend\operator-console
npm run build
```

## 真实 AS/400 联调前置条件

1. 已创建开发或测试环境最小权限只读数据库账号。
2. 只读账号无法执行 DML、DDL、存储过程和管理命令，并已由数据库侧验证。
3. Worker 运行主机只能访问显式 allowlist 中的数据库地址和端口。
4. KeyStore 文件和解锁口令由部署系统或受控密钥系统提供。
5. 控制面和 Worker 传输认证使用相同 Key ID 和共享密钥。
6. 已验证未签名 Worker 请求返回 `401`。

## 回滚

1. 清空或覆盖 Worker `sql-egress.connections` 和 `sql-egress.allowed-targets`；如使用仓库内置配置启动，也必须移除本地 `h2-local-test` smoke 绑定。
2. 移除 Worker KeyStore 挂载和 `sql-credentials` 注入。
3. 保留控制面连接目录，但将受影响连接标记为不可用或等待重新探测。
4. 确认 SQL 页面只能继续做校验和 DML 预检，不再触发真实执行。

P2 之前不得开放受控 DML 写执行。开发环境受控 CRUD 必须另行完成策略、审批、持久化工作流、短事务、审计、安全评审和回滚设计。
