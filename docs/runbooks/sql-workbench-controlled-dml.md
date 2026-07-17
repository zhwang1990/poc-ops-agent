# SQL 工作台非生产受控 DML 运行手册

## 1. 适用范围

本手册适用于 P2 已交付的 `sit` / `h2-local-test` 受控 SQL DML 试点切片。该切片仅允许策略明确放行的单条 `INSERT`、`UPDATE`、`DELETE`，生产环境始终只读，任意脚本和任意 SQL 写执行均不在范围内。

`dev` / `uat` 的真实数据库接入、环境演练和安全评审仍属于后续推广工作，不得直接复制试点配置后视为已启用。

## 2. 启用前提

启用前必须同时满足以下条件：

- 控制面使用数据库审计主存储，即 `ops-agent.audit.storage-mode=database`，且审计迁移已完成。
- 控制面配置显式回执密钥标识 `task7-sql-dml-receipt-v1`。HMAC 密钥只能由部署密钥源注入 `OPS_AGENT_SQL_DML_RECEIPT_HMAC_SECRET`，不得写入 YAML、文档、日志、测试数据或制品。
- 控制面 `demo` profile 的管理员种子口令必须由 `OPS_AGENT_DEMO_ADMIN_PASSWORD` 注入，且没有默认值。它必须是一次性生成的受控演示口令，不得写入 YAML、脚本、文档、测试数据或制品。
- 预检和提交使用 v1.1 服务端签发回执。控制面未获得签名密钥时必须失败关闭，并返回稳定的回执不可用错误；客户端自造或旧版回执不得进入 Worker。
- 受控 DML 即使仅通过回环地址访问 Worker，也必须在控制面和 Worker 两端显式设置 `ops-agent.worker.transport-auth.enabled=true`，并由同一受控密钥源注入 `OPS_AGENT_WORKER_KEY_ID` 与 `OPS_AGENT_WORKER_SHARED_SECRET`。缺少配置、未签名、错误签名或过期签名必须返回 `401`，且不得访问目标数据库。
- Worker 必须配置 `OPS_AGENT_SQL_DML_REPLAY_DIRECTORY`，指向仅由 Worker 运行身份可写、跨进程重启和应用回滚保留的持久目录。目录不得位于临时目录、容器临时层或发布版本目录；无法建立或验证重放状态时必须失败关闭。
- 同一受控 DML 请求流只能由一个 Worker 副本处理；如部署多个副本，所有副本必须挂载同一支持原子 `CREATE_NEW` 语义的重放目录。无法提供这一共享原子命名空间时必须保持单副本，不得以各副本本地目录分别启用写能力。
- Worker 基础 `application.yaml` 的 DML 必须关闭。只有 `demo` profile 的 `h2-local-test` 连接使用独立写凭据别名 `h2-local-dml-writer`，并与只读别名 `h2-local-readonly` 分离；真实环境的 KeyStore 解锁材料只能通过已批准的部署密钥注入方式提供。
- 控制面和 Worker 的基础配置以及 `demo` profile 均保持 `transport-auth.enabled=false` 的默认关闭行为。`demo` profile 只提供试点 DML 目录项，不会自动完成传输认证；受控启用必须使用部署覆盖显式打开两端认证，不得修改仓库默认值来省略该步骤。
- 服务端策略、允许表、允许列、谓词、影响预览和确认规则已经过 M02、M05、M07 安全评审。
- 不存在尚未完成人工核对的 `UNKNOWN_REQUIRES_HANDOFF` 工作流。

## 3. 启用步骤

1. 记录当前控制面和 Worker 配置版本，确认数据库审计可写且可回读；确认基础配置与 `demo` profile 的传输认证仍为默认关闭，DML 未因 profile 激活而自动开放。
2. 在 Worker 持久卷上创建重放目录，将所有权和最小写权限授予 Worker 运行身份，并通过部署配置注入 `OPS_AGENT_SQL_DML_REPLAY_DIRECTORY`。先验证目录跨 Worker 重启仍保留已有标记，再继续启用。
3. 生成专用于当前控制面到 Worker 通道的 Key ID 和高熵共享密钥。通过部署密钥源向两端注入相同的 `OPS_AGENT_WORKER_KEY_ID` 与 `OPS_AGENT_WORKER_SHARED_SECRET`，并在两端部署覆盖中显式设置 `ops-agent.worker.transport-auth.enabled=true`；不得在 YAML 或命令行中提供密钥明文或空默认值。
4. 使用仓库批准的 SQL 凭据管理工具，在 Worker 本地 KeyStore 中写入专用写凭据别名 `h2-local-dml-writer`。不得在命令历史、配置文件或工单中记录凭据明文；写凭据别名和数据库用户名均不得与对应只读身份相同。
5. 通过部署密钥源向控制面进程同时注入 `OPS_AGENT_SQL_DML_RECEIPT_HMAC_SECRET`、`OPS_AGENT_DEMO_ADMIN_PASSWORD` 和 `OPS_AGENT_SKILL_REGISTRY_SIGNING_SECRET`，并确认配置的回执 key ID 为 `task7-sql-dml-receipt-v1`。三个变量均不得提供 YAML 默认值或命令行明文值。
6. 在控制面配置中保持数据库审计，且只将 `sit` 加入 `ops-agent.controlled-sql-dml.enabled-environments`；逐条配置允许的表、语句类型、变更列、谓词列和谓词操作符。
7. 仅在 `sit` H2 演示和 E2E 路径以 `demo` profile 启动 Worker；该 profile 才包含 `h2-local-test` 的 `dml-enabled: true`、`dml-credential-alias: h2-local-dml-writer` 和对应最小权限数据库用户名。不得在 `dev`、`uat` 或生产启用该 profile。
8. 先以 `demo` profile 和显式传输认证覆盖启动 Worker，再以相同认证材料及显式覆盖启动控制面。任何传输签名、重放状态、审计、策略、凭据或数据源检查失败时停止启用，不得临时绕过门禁。

## 4. 验证步骤

从 `backend` 目录执行聚焦验收：

```powershell
.\mvnw.cmd -am -pl control-plane/bootstrap "-Dtest=ControlledSqlDmlEndToEndTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
.\mvnw.cmd -am -pl execution-worker-sqlworkbench "-Dtest=SqlQueryExecutionControllerTest,RestrictedSqlQueryExecutionWorkerTest,FileSqlDmlExecutionReplayGuardTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

`demo` profile 启动和验收验证必须同时注入 `OPS_AGENT_SQL_DML_RECEIPT_HMAC_SECRET`、`OPS_AGENT_DEMO_ADMIN_PASSWORD` 与
`OPS_AGENT_SKILL_REGISTRY_SIGNING_SECRET`，并向控制面和 Worker 同时注入相同的 `OPS_AGENT_WORKER_KEY_ID`、
`OPS_AGENT_WORKER_SHARED_SECRET`，向 Worker 注入 `OPS_AGENT_SQL_DML_REPLAY_DIRECTORY`。密钥变量均没有空默认值；
缺少任一认证或重放配置时，写入能力必须失败关闭。

随后完成以下人工核对：

1. 对允许的 `INSERT`、`UPDATE`、`DELETE` 分别发起合法签名的预检和提交，确认响应包含服务端签发的 v1.1 回执、key ID、过期时间和参数绑定摘要，并确认 Worker 接受的传输签名使用当前 Key ID。
2. 分别直接发送未签名、错误 Key ID、错误签名和超出时钟偏差的预检及提交请求；Worker 必须返回 `401`，数据源连接计数和目标数据库写入计数必须保持为零。
3. 使用匹配的确认信息提交一次，再用同一浏览器提交上下文和幂等键重复提交；两次响应必须复用同一工作流结果，目标数据库只能发生一次写入。使用相同 `executionRequestId` 直接重放已签名 Worker 信封时，也必须在任何数据库访问前被拒绝。
4. 重启 Worker 后重放同一 `executionRequestId`，确认持久重放标记仍生效；再将重放目录改为不可创建或不可写路径，确认启用 DML 的 Worker 在写能力可用前失败关闭。
5. 在数据库审计中核对创建、确认、提交和成功事件，事件必须包含 workflow、operator、trace、Skill 版本和策略版本，且不得包含 SQL 凭据或签名密钥。
6. 将环境改为 `production` 发起同类请求，控制面必须拒绝且 Worker 调度次数保持为零。生产连接的只读查询能力应保持正常。
7. 分别验证控制面能力关闭、控制面环境配置关闭、任一端传输认证关闭和 Worker DML 关闭，所有场景都必须在写入前失败关闭；恢复基础 profile 时传输认证和 DML 均必须保持默认关闭。
8. 验证篡改、过期回执和不匹配确认均被拒绝，且目标数据库无新增写入。

## 5. `UNKNOWN_REQUIRES_HANDOFF` 处置

出现 `UNKNOWN_REQUIRES_HANDOFF` 表示 Worker 调用结果无法确定，不代表写入失败。必须执行以下步骤：

1. 立即冻结该工作流的自动和人工重试，不得更换幂等键重新提交，也不得重放原 SQL。
2. 依据 workflow、trace、operator、目标连接和 SQL 哈希核对数据库状态、数据库审计与 Worker 脱敏日志。
3. 能证明写入已提交时，记录实际影响并关闭重放路径；能证明写入未发生时，也必须由授权人员创建新的受控工作流，不得复用未知工作流直接执行。
4. 无法确定结果时保持未知状态，记录证据、责任人和下一步，由数据库负责人和安全负责人共同人工接管。
5. 所有核对与决策必须追加审计，不得修改或删除原工作流和原审计事实。

## 6. 停用与撤销

按以下顺序停用，确保控制面先停止签发新的可执行工作：

1. 从控制面的 `enabled-environments` 删除 `sit`，或设置为空列表，并移除对应 DML 规则；重启或受控重载后验证预检和提交均失败关闭。
2. 将 Worker 数据源的 `dml-enabled` 设置为 `false` 并重启 Worker，验证写请求返回稳定的 Worker DML 禁用错误。
3. 使用批准的 KeyStore 管理流程撤销并删除 Worker 写凭据别名 `h2-local-dml-writer`。只读别名不得一并删除。
4. 验证 `h2-local-readonly` 对应的只读查询仍可用，且任何 DML 都不能到达数据库。
5. 在确认两端不再接收 DML 后，撤销或轮换 Worker 传输认证材料。不得先关闭认证再继续保留 DML 能力。
6. 保留重放目录及全部已消费标记，纳入与工作流和审计事实一致的保留期；停用、重启、版本回滚和凭据轮换均不得清空这些标记。
7. 紧急事件中同时轮换或撤销回执签名密钥并更新 key ID，使旧回执立即失效。轮换不能触发未知工作流重试。

## 7. 回滚

- 应用回滚时先执行停用与撤销步骤，再回滚控制面和 Worker 版本；数据库工作流与审计表不得回滚、清空或降级为非事实源。
- Worker 回滚必须继续挂载原重放目录并保留已消费标记；旧版本无法识别当前重放状态时不得重新开放 DML。
- 配置回滚必须恢复默认关闭状态，而不是恢复到更宽的环境、表或列允许范围。
- 目标数据需要修复时，先核对原工作流是否已提交，再由授权人员以新的幂等键、预检回执和确认创建补偿性 DML 工作流。禁止盲目重放原写操作。
- 回滚后重新执行生产拒绝、能力关闭、无效回执和只读查询验证，并保存验收证据。
