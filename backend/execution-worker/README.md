# 执行 Worker

执行 Worker 是 M07 的独立部署受限执行边界。

## 主要职责

- 校验已授权、带版本的执行请求。
- 强制实施资源、工作区、网络和凭据限制。
- 执行已批准的 Skill 适配器。
- 返回强类型结果以及安全和审计事件。
- 可靠终止执行并清理资源。

## 禁止事项

- 自行进行授权或审批决策。
- 接受任意未签名 Skill 定义。
- 使用生产长期凭据。
- 在运行时从公网安装依赖。
- 将 Job Object 或 WDAC 视为完整隔离。

## 配置型 HTTP/JSON Skill 边界

- 简单第三方 HTTP/JSON 只读 Skill 优先通过 `ConfiguredHttpReadOnlySkillAdapter` 配置接入，不为每个 Skill 新增专用 Java 适配器。
- Worker 在发起 HTTP 请求前会先执行 `ops-agent.worker.http-egress.allowed-targets` allowlist；默认 allowlist 为空，拒绝所有 HTTP 目标。
- 配置型适配器只支持单查询参数、JSON 响应和响应字段白名单；不执行第三方脚本，不读取环境变量，不支持在配置中保存 API Key。
- 需要密钥、专用认证、SDK、复杂协议或目标系统会话的 Skill，必须先完成安全评审，并以专用适配器或内部受控网关方式接入。
- 当前 `weather-current-read:1.0.0` 使用该通用适配器配置；默认 `endpoint-url` 为空，因此未配置受控天气源时会失败关闭。

## 发布中心 Liberty 脚本 Profile

- Liberty 脚本发布只允许 `LIBERTY_SCRIPT_PROFILE` 管理模式调用控制面随已授权请求下发的已审核脚本 Profile 定义。
- 操作台和发布单只能提交 `profileId` 与受约束的 `name/value` 参数，不能临时提交脚本路径、命令行或 shell 片段。
- Worker 使用 `ProcessBuilder` 参数数组执行已审核定义中的 `executablePath` 与 `arguments` 模板，不通过 shell 拼接命令。
- 未引用 `{{artifactPath}}`、`{{artifactId}}`、`{{artifactStorageKey}}` 或 `{{artifactChecksum}}` 的 Profile 可以无制品执行；引用任一制品占位符时必须提供受控 WAR 制品上下文。
- Profile 是跨 `dev`、`sit`、`uat` 复用的通用脚本定义，必须配置参数模板、超时、成功退出码和受限工作目录，并且处于已审核和已启用状态；不同节点的 `serverName`、`applicationName`、`artifactPath` 等值由服务器配置中的脚本参数提供，模板引用的参数缺失时必须失败关闭。
- 脚本参数不得携带密码、密钥或 token；敏感材料只能通过凭据别名、短期凭据或等价受控边界提供。
- Worker 会把脚本 stdout/stderr 写入受限工作目录中的执行日志，并同步产出脱敏后的 `LOG` 事件；控制面只转发强类型 `RELEASE_NODE_LOG` 事件给操作台实时展示，不能让前端直接读取 Worker 文件系统。

## SQL 工作台只读边界

- SQL 工作台 Worker 侧代码位于 `backend/execution-worker-sqlworkbench`，由 `execution-worker` 作为运行时依赖加载；这不新增部署服务或模块编号。
- SQL 查询入口会在 Worker 内再次使用 AST 校验，只接受单条 `SELECT`。
- Worker 在解析 JDBC `DataSource` 前会先执行本地 SQL 出口 allowlist；部署安全基线要求环境配置为空或显式替换，未显式批准的连接会被拒绝。
- 仓库内置 `application.yaml` 仅为本地 SQL 工作台 smoke 预置 `h2-local-test` 的 `localhost:9092` 绑定，不代表生产默认配置。
- SQL 连接目录只允许 `development` 和 `test` 环境，并且只保存连接元数据和凭据别名，不保存真实密码或密钥。
- JTOpen 仅用于 Db2 for i JDBC 适配，不允许控制面或浏览器直接连接 AS/400。
- 当前默认执行器未配置真实连接和 KeyStore；只有通过 allowlist 的开发或测试连接才会继续进入后续连接解析。
- P1 真实联调允许管理员启动时人工解锁 Java KeyStore；P2 若继续推进真实目标系统联调，必须将无人值守安全解锁纳入启用门禁。
- SQL 出口 allowlist 是应用层保护，不替代防火墙、私有网络、mTLS、短期目标系统凭据或 Windows 隔离。

## 传输认证边界

- 控制面到 Worker 的 P1/P2 HTTP 调用支持应用层 HMAC 签名认证。
- 启用 `ops-agent.worker.transport-auth.enabled=true` 后，Worker 会校验 Key ID、时间戳漂移和请求签名。
- 未签名、错误签名或时间漂移过大的请求会在 HTTP 边界返回 `401`，不会进入执行器。
- Worker 绑定非回环地址时必须启用传输认证，否则启动保护会失败。
- 该机制不替代 mTLS、私有网络、防火墙、短期目标系统凭据或 Windows 隔离。

## 构建结构

执行 Worker 现在拆分为两个 Maven 构建模块：

- `execution-worker`：通用 Worker 启动、传输认证、HTTP 边界和配置型只读 Skill 适配器。
- `execution-worker-sqlworkbench`：SQL 工作台 Worker 侧只读拒绝、SQL 出口 allowlist、JDBC 执行和短期结果存储适配器。
