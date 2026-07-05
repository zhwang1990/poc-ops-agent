# SQL 工作台契约

本目录保存 SQL 工作台跨控制面、Worker 和操作台使用的版本化契约。

P1 契约已完成开发、测试环境只读查询与 DML 预检边界。P2 契约采用标准环境名
`dev`、`sit`、`uat`、`production`，并兼容旧输入 `development` 和 `test` 的归一化。

`RUN_READ_ONLY` 只能执行只读查询。`PREFLIGHT_DML` 用于生成受控 DML 预检报告。
`COMMIT_DML` 是唯一可进入 Worker 执行信封的写动作，并且仅允许 `dev`、`sit`、`uat`
环境。`production` 连接只能携带 `VALIDATE` 和 `RUN_READ_ONLY` 查询能力，任何
`PREFLIGHT_DML` 或 `COMMIT_DML` 请求都必须在契约、控制面和 Worker 边界被拒绝。

无 `WHERE` 的 `UPDATE` / `DELETE` 必须在服务端校验报告中标记风险，并要求操作员二次确认
绑定的 `sqlHash`、风险列表和确认码后，才允许提交 `COMMIT_DML`。
二次确认只表示操作员承认静态风险，不等同于执行授权；在 M05 持久化工作流、审计事件、
恢复状态和幂等绑定完成接入前，控制面必须拒绝 `COMMIT_DML` 并不得向 Worker 提交执行请求。

AI SQL 助手契约只用于生成解释、优化和错误分析建议。助手请求不得包含密钥、JDBC URL
或结果行；助手响应必须标记 `validationRequired=true`，建议 SQL 只能由操作员显式应用回
编辑器，并重新进入服务端校验和策略链路。

`sql-metadata-response-v1.schema.json` 描述对象浏览器使用的数据库元数据响应。该响应只允许
返回授权连接和授权 Schema 下的表、视图、系统表、字段和索引结构，不得包含用户名、密码、
JDBC URL、凭据材料或查询结果行。
