# 身份、策略与审计接入说明

## 适用范围

本文用于 T008、T009、T010 当前实现的开发、联调和环境配置说明。

## 当前实现形态

- 认证：默认使用正式内建身份 `built-in` 浏览器登录模式；仍支持开发态 `HS256` Bearer Token 和真实 OIDC 模式作为显式配置。
- 授权：当前为服务端基础 RBAC V0，客户端、Prompt 和人格均不能绕过。
- 审计：默认使用追加式文件持久化审计链，写入 `var/audit/control-plane-audit.jsonl`；P2 可显式切换为数据库审计主存储。

## 默认内建身份配置

`application.yaml` 默认使用：

```yaml
ops-agent:
  security:
    auth-mode: built-in
    browser-login-enabled: true
  built-in-identity:
    schema-initializer-enabled: true
    session-cookie-name: OPS_AGENT_SESSION
  audit:
    storage-mode: file
    storage-path: var/audit/control-plane-audit.jsonl
```

适用场景：

- 本地开发和人工联调；
- 无企业身份提供方的内网部署；
- 前端 `/login` 账号密码登录链路。

账号、角色授予和密码凭据仍需通过受控方式预置到身份表；不得把测试账号或密码写入源码、配置样例或日志。

## 开发态 Bearer Token 配置

需要使用开发态 HS256 Bearer Token 调试内部接口时，必须显式覆盖：

```yaml
ops-agent:
  security:
    auth-mode: dev-hs256
    issuer: ops-agent-dev
    audience: ops-agent-internal
    shared-secret: your-dev-secret
```

## 真实 OIDC 配置

当接入企业身份提供方时，切换为：

```yaml
ops-agent:
  security:
    auth-mode: oidc
    issuer: https://your-idp.example.com/realms/ops
    issuer-uri: https://your-idp.example.com/realms/ops
    audience: ops-agent-internal
    jwk-set-uri:
    username-claim: preferred_username
    role-claim: roles
    role-prefix: ROLE_
```

说明：

- 优先使用 `issuer-uri` 进行 OIDC 发现。
- 如果环境不允许发现，可直接配置 `jwk-set-uri`。
- `username-claim` 用于映射操作人用户名，默认 `preferred_username`。
- `role-claim` 用于映射角色，支持数组或空格/逗号分隔字符串。
- `roles` Claim 最终必须能映射到服务端角色，例如 `ops-reader`、`ops-admin`、`ops-auditor`。

## 标准 OIDC 浏览器登录模式

当需要为前端或人工联调提供标准浏览器登录流程时，启用：

```yaml
ops-agent:
  security:
    browser-login-enabled: true
    browser-registration-id: ops-agent
    browser-login-success-uri: /auth/session
    browser-logout-success-uri: "{baseUrl}/"

spring:
  security:
    oauth2:
      client:
        provider:
          ops-agent:
            issuer-uri: https://your-idp.example.com/realms/ops
        registration:
          ops-agent:
            provider: ops-agent
            client-id: your-client-id
            client-secret: your-client-secret
            authorization-grant-type: authorization_code
            redirect-uri: "{baseUrl}/login/oauth2/code/{registrationId}"
            scope:
              - openid
              - profile
              - email
```

标准地址如下：

- 登录入口：`GET /auth/login`
- OIDC 授权入口：`GET /oauth2/authorization/ops-agent`
- 回调地址：`GET /login/oauth2/code/ops-agent`
- 当前会话信息：`GET /auth/session`
- 退出入口：`GET /auth/logout`
- Spring Security 实际退出地址：`GET /logout`

如果前端采用浏览器会话模式，登录成功后的会话可直接访问 `/internal/**` 受保护接口，不必再手工拼接 Bearer Token。

## 策略与角色

当前策略规则已经外置到 `ops-agent.policy.required-roles-by-action`，默认动作与角色要求：

- `internal.health.read`：`ops-reader` 或 `ops-admin`
- `internal.modules.read`：`ops-reader` 或 `ops-admin`
- `internal.echo.read`：`ops-reader` 或 `ops-admin`
- `internal.failures.read`：`ops-admin`
- `internal.audit.read`：`ops-admin` 或 `ops-auditor`

后续如果接入独立策略引擎，应保持动作命名稳定。

## 审计持久化

- 默认采用追加式 JSONL 文件。P2 数据库模式通过 `ops-agent.audit.storage-mode=database` 启用，记录写入 `audit_event` 表。
- 每次请求至少记录：
  - 主体
  - 动作
  - 资源
  - 策略版本
  - 结果
  - traceId
  - requestId
- 文件路径必须纳入环境备份、留存和访问控制策略。
- 保留、归档、恢复和访问控制按 [审计保留、归档与恢复运行手册](audit-retention-and-recovery.md) 执行。

## 联调检查

1. 无 Token 访问内部接口，返回 `401`。
2. 无效 issuer、audience 或签名的 Token，返回 `401`。
3. 角色不足访问受限接口，返回 `403`。
4. 成功访问后，审计文件中出现对应事件。
5. 管理角色可访问 `/internal/audit/latest` 检查最新审计记录。
