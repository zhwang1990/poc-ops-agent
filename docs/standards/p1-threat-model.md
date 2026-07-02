# P1 只读诊断威胁模型

## 范围

本威胁模型覆盖操作员、控制面、版本化 Skill、独立 execution-worker、语义事件和审计链。P1 已于 2026-07-01 完成验收；该阶段不允许生产写操作和任意脚本执行。

## 资产

- 已认证操作人身份和角色。
- 服务端策略决策及版本。
- Skill 版本、摘要和签名。
- 只读命令、Worker 请求和结果。
- 语义事件、审计事件和链路追踪标识。
- M04 模型供应方配置、加密后的 API Key 和 Key 指纹。

## 信任边界

1. 浏览器和控制面之间：输入和 Bearer Token 均视为不可信。
2. 控制面模块之间：跨模块行为使用明确接口和共享契约。
3. 控制面和 Worker 之间：仅发送已授权、带版本、短期有效的只读请求。
4. Worker 和目标系统之间：P1 只允许显式注册的只读适配器；SQL 查询必须命中 Worker 本地开发或测试出口 allowlist；配置型 HTTP/JSON Skill 必须命中 Worker 本地 HTTP 出口 allowlist。

## 主要威胁与当前控制

| 威胁 | 当前控制 | 剩余风险 |
|---|---|---|
| 未认证或越权诊断 | 服务端认证、RBAC、拒绝审计 | 真实企业 IdP 联调转入 P2/P3 环境落地 |
| 伪造 Skill 或版本 | Manifest 摘要、HMAC 签名、发布态校验 | HMAC 仅适合当前开发阶段，生产签名方案待 ADR |
| 写操作伪装为诊断 | 命令契约固定 `READ_ONLY`，Worker 仅加载显式只读适配器 | 新适配器仍需独立安全评审 |
| 重放 Worker 请求 | 幂等键、请求 ID、30 秒过期时间、M05 持久化工作流事实源和终态幂等复用 | 更长期恢复演练和跨环境恢复策略转入 P2/P3 |
| 绕过控制面直接调用 Worker | 开发 Worker 默认仅绑定 `127.0.0.1`；控制面到 Worker 支持 HMAC 签名；非回环绑定未启用认证时启动失败 | mTLS、网络层出口策略和部署隔离仍需生产 ADR 与演练 |
| Worker 连接未批准 SQL 目标 | SQL Worker 在创建 JDBC 连接前执行本地连接目录和 host/port allowlist；部署安全基线为空连接目录和空 allowlist；仓库内置 `h2-local-test` 仅限本地 smoke；P1 禁止生产 SQL 连接目录 | 该控制仍属于应用层保护，不能替代防火墙、私有网络、mTLS、短期凭据和 Windows 隔离 |
| Worker 连接未批准 HTTP 目标 | 配置型 HTTP/JSON Skill 在请求前执行 `scheme + host + port` allowlist；默认空 allowlist 和空 `endpoint-url` 会失败关闭；响应字段按白名单透传 | 该控制仍属于应用层保护，不能替代防火墙、私有网络、mTLS、短期凭据、内部网关和 Windows 隔离 |
| 模型供应方密钥泄露 | M04 仅允许 `ROLE_ops-admin` 通过受策略保护的接口写入 API Key；服务端使用 AES-GCM 加密存储，只在运行时解密给 Agent Runtime；列表、详情、审计事件和前端状态只暴露 Key 指纹，不暴露明文或密文 | 当前密钥轮换仅覆盖单个供应方配置；统一 KMS、主密钥轮换和更细粒度审计告警仍待后续 ADR |
| 事件内容欺骗操作台 | 强类型事件和载荷，前端边界解析 | SSE 当前在工作流完成后批量输出 |
| 敏感数据进入日志和事件 | Skill 拦截器声明、结构化事件 | 全链路脱敏策略仍需扩展 |

## 禁止条件

- 不得把 Worker 监听地址改为非回环地址后直接用于生产。
- 不得在非回环地址上关闭 Worker 传输认证。
- 不得新增任意脚本执行入口。
- 不得接受 `READ_ONLY` 以外的命令类型。
- 不得由前端、Prompt 或 Worker 自行授予权限。
- 不得在 P1 配置生产 SQL 连接目录或绕过 Worker SQL 出口 allowlist。
- 不得在配置型 HTTP/JSON Skill 中保存 API Key、Token、Cookie、Basic Auth 信息或绕过 Worker HTTP 出口 allowlist。
- 不得在源码、日志、语义事件、审计事件、前端持久化状态或 API 响应中输出模型供应方 API Key 明文或密文。
