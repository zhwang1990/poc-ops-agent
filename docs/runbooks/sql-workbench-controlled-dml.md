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
- Worker 基础 `application.yaml` 的 DML 必须关闭。只有 `demo` profile 的 `h2-local-test` 连接使用独立写凭据别名 `h2-local-dml-writer`，并与只读别名 `h2-local-readonly` 分离；真实环境的 KeyStore 解锁材料只能通过已批准的部署密钥注入方式提供。
- 服务端策略、允许表、允许列、谓词、影响预览和确认规则已经过 M02、M05、M07 安全评审。
- 不存在尚未完成人工核对的 `UNKNOWN_REQUIRES_HANDOFF` 工作流。

## 3. 启用步骤

1. 记录当前控制面和 Worker 配置版本，确认数据库审计可写且可回读。
2. 使用仓库批准的 SQL 凭据管理工具，在 Worker 本地 KeyStore 中写入专用写凭据别名 `h2-local-dml-writer`。不得在命令历史、配置文件或工单中记录凭据明文。
3. 通过部署密钥源向控制面进程注入 `OPS_AGENT_SQL_DML_RECEIPT_HMAC_SECRET` 和一次性 `OPS_AGENT_DEMO_ADMIN_PASSWORD`，并确认配置的回执 key ID 为 `task7-sql-dml-receipt-v1`。
4. 在控制面配置中保持数据库审计，且只将 `sit` 加入 `ops-agent.controlled-sql-dml.enabled-environments`；逐条配置允许的表、语句类型、变更列、谓词列和谓词操作符。
5. 仅在 `sit` H2 演示和 E2E 路径以 `demo` profile 启动 Worker；该 profile 才包含 `h2-local-test` 的 `dml-enabled: true`、`dml-credential-alias: h2-local-dml-writer` 和对应最小权限数据库用户名。不得在 `dev`、`uat` 或生产启用该 profile。
6. 先以 `demo` profile 启动 Worker，再以 `demo` profile 启动控制面。任何签名、审计、策略、凭据或数据源检查失败时停止启用，不得临时绕过门禁。

## 4. 验证步骤

从 `backend` 目录执行聚焦验收：

```powershell
.\mvnw.cmd -am -pl control-plane/bootstrap "-Dtest=ControlledSqlDmlEndToEndTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

随后完成以下人工核对：

1. 对允许的 `INSERT`、`UPDATE`、`DELETE` 分别请求预检，确认响应包含服务端签发的 v1.1 回执、key ID、过期时间和参数绑定摘要。
2. 使用匹配的确认信息提交一次，再用同一幂等键重复提交；两次响应必须复用同一工作流结果，目标数据库只能发生一次写入。
3. 在数据库审计中核对创建、确认、提交和成功事件，事件必须包含 workflow、operator、trace、Skill 版本和策略版本，且不得包含 SQL 凭据或签名密钥。
4. 将环境改为 `production` 发起同类请求，控制面必须拒绝且 Worker 调度次数保持为零。生产连接的只读查询能力应保持正常。
5. 分别验证控制面能力关闭、控制面环境配置关闭和 Worker DML 关闭，所有场景都必须在写入前失败关闭。
6. 验证篡改、过期回执和不匹配确认均被拒绝，且目标数据库无新增写入。

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
5. 紧急事件中同时轮换或撤销回执签名密钥并更新 key ID，使旧回执立即失效。轮换不能触发未知工作流重试。

## 7. 回滚

- 应用回滚时先执行停用与撤销步骤，再回滚控制面和 Worker 版本；数据库工作流与审计表不得回滚、清空或降级为非事实源。
- 配置回滚必须恢复默认关闭状态，而不是恢复到更宽的环境、表或列允许范围。
- 目标数据需要修复时，先核对原工作流是否已提交，再由授权人员以新的幂等键、预检回执和确认创建补偿性 DML 工作流。禁止盲目重放原写操作。
- 回滚后重新执行生产拒绝、能力关闭、无效回执和只读查询验证，并保存验收证据。
