# M07 Worker 传输认证运行手册

## 适用范围

本文用于控制面到独立 `execution-worker` 的 P1 只读执行调用认证。该能力只保护控制面与 Worker 之间的应用层调用，不替代 mTLS、私有网络、防火墙、短期目标系统凭据或 Windows 隔离。

## 配置项

控制面：

```yaml
ops-agent:
  worker:
    base-url: http://127.0.0.1:8091
    transport-auth:
      enabled: true
      key-id: ${OPS_AGENT_WORKER_KEY_ID}
      shared-secret: ${OPS_AGENT_WORKER_SHARED_SECRET}
```

Worker：

```yaml
ops-agent:
  worker:
    transport-auth:
      enabled: true
      key-id: ${OPS_AGENT_WORKER_KEY_ID}
      shared-secret: ${OPS_AGENT_WORKER_SHARED_SECRET}
      max-clock-skew: 30s
```

密钥必须由部署系统、密钥管理系统或受控环境变量注入。不得写入源码、运行手册实例、日志、Prompt 或工单正文。

## SQL 出口 allowlist

Worker 的 SQL 查询路径在创建 JDBC 连接前会执行本地出口 allowlist。部署环境的安全基线是空连接目录和空 allowlist，表示拒绝所有 SQL 连接；仓库内置 `application.yaml` 仅为本地 SQL 工作台 smoke 预置 `h2-local-test` 的 `localhost:9092` 绑定，不得作为生产默认配置。只有显式列入连接目录且主机和端口同时出现在 allowlist 中的 `development` 或 `test` 连接才会继续进入后续连接解析。

示例：

```yaml
ops-agent:
  worker:
    sql-egress:
      allowed-targets:
        - host: as400-dev.internal
          port: 446
      connections:
        - connection-id: as400-dev-readonly
          target-environment: development
          host: as400-dev.internal
          port: 446
          credential-alias: as400-dev-readonly
          enabled: true
```

配置要求：

1. `connections[].target-environment` 只能是 `development` 或 `test`，P1 禁止配置生产 SQL 连接。
2. `credential-alias` 只是凭据别名，不得把真实密码、密钥或连接串写入配置。
3. 禁用的连接目录项会返回稳定拒绝错误，不会继续解析数据源。
4. 请求环境、连接目录环境和 allowlist 目标必须同时匹配。
5. 该机制是 Worker 应用层出口控制，不替代防火墙、私有网络、mTLS、短期目标系统凭据、Windows 隔离或网络层出口策略。

## HTTP 出口 allowlist 与配置型 Skill

简单 HTTP/JSON 只读 Skill 应优先走配置型 Worker 适配器，不为每个第三方 Skill 新增专用 Java 类。Worker 发起 HTTP 请求前会校验 `scheme + host + port` 是否命中 `http-egress.allowed-targets`；默认配置为空，表示拒绝所有 HTTP 目标。

示例：

```yaml
ops-agent:
  worker:
    http-egress:
      allowed-targets:
        - scheme: https
          host: weather-gateway.internal
          port: 443
    configured-http-skills:
      skills:
        - skill-id: weather-current-read
          version: 1.0.0
          endpoint-url: https://weather-gateway.internal/current
          input-parameter-name: location
          query-parameter-name: location
          source: weather-read-model
          timeout: 5s
          allowed-response-fields:
            - location
            - condition
            - temperatureCelsius
            - humidityPercent
            - windSpeedKph
            - observationTime
```

配置要求：

1. `endpoint-url` 必须是不含用户名、密码、query 和 fragment 的基础 URL。
2. `configured-http-skills` 只保存非敏感元数据、端点和响应字段白名单，不得保存 API Key、Token、Cookie 或 Basic Auth 信息。
3. 如第三方天气源需要密钥，应先通过内部受控网关或后续短期凭据机制接入；不得把第三方密钥写入 Worker 配置。
4. 响应只会透传 `allowed-response-fields` 中列出的字段，并由适配器补充 `generatedAt`；`source` 为可选非敏感标识，省略时不会输出该字段。
5. 该机制是 Worker 应用层出口控制，不替代防火墙、私有网络、mTLS、短期目标系统凭据、Windows 隔离或网络层出口策略。

## 启用步骤

1. 确认控制面和 Worker 主机时间同步，建议接入同一 NTP 源。
2. 生成新的 Key ID 和共享密钥，并记录到受控密钥管理系统。
3. 在 Worker 环境注入 `OPS_AGENT_WORKER_KEY_ID` 和 `OPS_AGENT_WORKER_SHARED_SECRET`。
4. 在控制面环境注入相同 Key ID 和共享密钥。
5. 启用两端 `transport-auth.enabled=true`。
6. 启动 Worker，确认启动日志没有非回环绑定保护失败。
7. 启动控制面。
8. 调用一次只读诊断，确认 Worker 返回成功结果。
9. 使用不带签名的请求直接调用 Worker，确认返回 `401`。

## 非回环绑定要求

- 本地开发可使用 `server.address=127.0.0.1` 且关闭传输认证。
- 任何 `0.0.0.0`、内网 IP 或主机名绑定都必须启用传输认证。
- 非回环绑定还必须由网络策略限制只允许控制面访问 Worker。
- 生产环境不得把 Worker 直接暴露给浏览器、办公网段或公网。

## 密钥轮换

当前代码支持单 Key ID。轮换时使用维护窗口：

1. 暂停新的诊断请求。
2. 将 Worker 和控制面同时更新到新 Key ID 与新共享密钥。
3. 重启 Worker 和控制面。
4. 执行合法签名请求和未签名拒绝验证。
5. 删除旧密钥材料。

如果需要双 Key 平滑轮换，必须先扩展配置和测试，不得手工在代码中临时写死多个密钥。

## 排障

| 现象 | 可能原因 | 处理方式 |
|---|---|---|
| Worker 启动失败 | 非回环绑定但认证未启用 | 重新绑定回环地址，或启用传输认证后再启动 |
| 合法请求返回 `401` | Key ID 不一致、共享密钥不一致或时钟漂移 | 对比两端配置来源，检查 NTP 和 `max-clock-skew` |
| 未签名请求返回 `200` | Worker 认证未启用或命中旧版本部署 | 检查 Worker 配置和部署版本，禁止继续扩大执行范围 |
| 控制面调用失败 | 控制面启用签名但缺少密钥 | 恢复密钥注入或回滚到回环开发模式 |

## 回滚

1. 将 Worker 监听地址恢复为 `127.0.0.1`。
2. 停止跨主机访问 Worker。
3. 在回环开发模式下临时关闭两端 `transport-auth.enabled`。
4. 验证只读诊断闭环。
5. 在项目计划和已知风险中记录传输认证回滚原因。

禁止在非回环地址上关闭传输认证后继续运行。

## 验收证据

P1 M07 验收至少提供：

- 合法签名请求成功证据。
- 未签名请求 `401` 证据。
- 错误签名请求 `401` 证据。
- 非回环绑定且认证禁用时启动失败的自动化测试证据。
- SQL 出口 allowlist 默认拒绝、未知连接拒绝、禁用连接拒绝、环境不匹配拒绝和 host/port 不在 allowlist 时拒绝的自动化测试证据。
- HTTP 出口 allowlist 默认拒绝、未配置 HTTP Skill 端点拒绝、query 参数安全编码和响应字段白名单的自动化测试证据。
- 后端测试、契约检查和密钥扫描结果。
