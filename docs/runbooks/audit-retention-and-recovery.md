# 审计保留、归档与恢复运行手册

## 适用范围

本文用于 P1 只读诊断 MVP 的 T010 审计链路运维，并覆盖 P2 初始数据库审计主存储的启用、检查和回退。默认仍使用追加式 JSONL 文件审计存储，路径为 `var/audit/control-plane-audit.jsonl`。

本文定义文件审计和数据库审计的保留、归档、恢复和访问控制方式。P2 数据库模式只提供平台统一 `AuditTrail` 的关系型主存储，不替代后续 SIEM 接入、WORM 存储或组织级备份保全。

## 当前边界

- 控制面通过统一 `AuditTrail` 抽象记录认证、授权和内部接口访问结果。
- `ops-agent.audit.storage-mode=file` 时装配 `FileBackedAuditTrail`，按行追加 JSON 序列化的 `AuditEvent`。
- `ops-agent.audit.storage-mode=database` 时装配 `R2dbcAuditTrail`，写入关系型数据库 `audit_event` 表，并在启动时加载已有事件作为最新记录快照。
- `/internal/audit/latest` 只暴露最近审计事件和当前事件计数，访问动作是 `internal.audit.read`。
- 默认策略仅允许 `ROLE_ops-admin` 和 `ROLE_ops-auditor` 读取审计查询入口。
- 审计记录由控制面进程写入；当前不提供浏览器端审计修改、删除、导出或批量查询入口。
- 当前“不可篡改”能力依赖应用追加写入、操作系统或数据库访问控制、归档哈希和备份保全；尚未提供 WORM 存储或集中审计系统级防篡改。

## 保留策略

P1 最低保留要求如下：

| 分层 | 默认周期 | 位置 | 访问方式 |
|---|---:|---|---|
| 文件热数据 | 30 天 | 控制面本机 `ops-agent.audit.storage-path` | 仅控制面服务账号写入；审计员通过受保护 API 查看最新记录 |
| 数据库热数据 | 30 天 | 控制面数据库 `audit_event` 表 | 仅控制面数据库账号写入；审计员通过受保护 API 查看最新记录 |
| 归档数据 | 180 天 | 受控备份目录或组织备份系统 | 仅平台负责人、安全负责人和审计员按变更单读取 |
| 安全事件保全 | 按事件要求 | 独立保全目录 | 只读保存，禁止覆盖，访问必须记录审批和审计编号 |

如果组织已有更严格的日志保留制度，采用更严格制度。任何缩短保留周期的变更都必须经过安全评审，并更新本手册和项目事实源。

## 访问控制

### 服务账号权限

控制面运行账号只需要对审计目录具备创建、追加写入和读取自身文件的权限。禁止将审计目录放在源码目录、临时下载目录或对普通用户可写的共享目录。

Windows 部署时，管理员应按真实服务账号替换以下示例中的账户名：

```powershell
New-Item -ItemType Directory -Force -Path "D:\ops-agent\audit"
icacls "D:\ops-agent\audit" /inheritance:r
icacls "D:\ops-agent\audit" /grant "OPSAGENT-SVC:(OI)(CI)M"
icacls "D:\ops-agent\audit" /grant "OPS-AUDITORS:(OI)(CI)R"
icacls "D:\ops-agent\audit" /grant "OPS-ADMINS:(OI)(CI)F"
```

### 数据库账号权限

数据库模式下，控制面数据库账号必须具备 `audit_event` 表的建表初始化、插入和读取权限。生产环境不得把通用管理员账号配置给控制面；如需归档、清理或保全历史审计记录，必须由独立运维账号按变更单执行，并记录审计编号、操作者、时间窗口和校验哈希。

### API 访问

- `internal.audit.read` 必须继续要求 `ops-admin` 或 `ops-auditor`。
- 前端不得根据展示文本推断审计授权状态。
- 不得新增绕过策略过滤器的审计下载或查询接口。
- 排障时不得把审计 JSONL 内容复制到 Prompt、聊天窗口或非受控工单附件中。

## 日常检查

每天至少检查一次：

1. 控制面启动配置中的 `ops-agent.audit.storage-mode` 只能为 `file` 或 `database`。
2. 文件模式下，`ops-agent.audit.storage-path` 指向受控目录，审计文件存在且当天有新增记录。
3. 文件模式下，最新一行是合法 JSON，包含 `subject`、`action`、`resource`、`policyVersion`、`result`、`traceId` 和 `requestId`。
4. 数据库模式下，`audit_event` 表存在，最新记录包含 `event_id`、`subject`、`action`、`resource`、`policy_version`、`result`、`trace_id` 和 `request_id`。
5. 备份任务已经覆盖审计目录或控制面数据库。
6. `/internal/audit/latest` 对审计员返回 `200`，对普通读者返回 `403`。

检查失败时，先将控制面切到只读诊断降级窗口，不得继续扩大执行范围；随后按“故障处理”章节处理。

## 归档流程

归档应在维护窗口执行，避免复制时遗漏正在追加的行。

1. 通知当前操作员维护窗口开始。
2. 停止控制面进程，确保审计文件不再写入。
3. 记录当前文件大小和最后修改时间。
4. 将当前 JSONL 文件移动到归档目录，文件名使用 `control-plane-audit-YYYYMMDD-HHmmss.jsonl`。
5. 计算归档文件 SHA-256，并将哈希、原始路径、归档路径、操作者、时间和变更单号写入同目录 `.sha256` 或组织备份清单。
6. 将生产路径重建为空 JSONL 文件，并继承原目录权限。
7. 启动控制面。
8. 访问一个受保护只读接口，确认新审计文件出现新增记录。
9. 用审计员身份访问 `/internal/audit/latest`，确认可读取最新审计事件。

示例命令：

```powershell
$timestamp = Get-Date -Format "yyyyMMdd-HHmmss"
$source = "D:\ops-agent\audit\control-plane-audit.jsonl"
$archive = "D:\ops-agent\audit-archive\control-plane-audit-$timestamp.jsonl"
New-Item -ItemType Directory -Force -Path (Split-Path -Parent $archive)
Move-Item -LiteralPath $source -Destination $archive
Get-FileHash -Algorithm SHA256 -LiteralPath $archive | Format-List | Out-File "$archive.sha256" -Encoding utf8
New-Item -ItemType File -Path $source
```

归档完成后，不得手工编辑归档 JSONL。若归档文件需要取证保全，应复制到独立保全目录并设置只读权限。

## 恢复流程

### 恢复到排障环境

用于验证历史审计内容时，优先恢复到隔离排障环境：

1. 从归档清单选择目标 JSONL 文件。
2. 校验 SHA-256 与归档时记录一致。
3. 将文件复制到排障环境的只读目录。
4. 以排障配置启动控制面，设置 `ops-agent.audit.storage-path` 指向复制文件。
5. 使用审计员账号访问 `/internal/audit/latest`，确认服务可读取文件尾部事件。
6. 排障完成后删除排障环境副本，保留归档原件。

### 恢复到生产路径

只有当前审计文件损坏或误清空时，才允许恢复到生产路径：

1. 停止控制面。
2. 备份当前损坏文件，文件名增加 `.corrupt-YYYYMMDD-HHmmss` 后缀。
3. 校验归档文件 SHA-256。
4. 将归档文件复制到 `ops-agent.audit.storage-path`。
5. 确认文件权限仍只允许服务账号写入和审计角色读取。
6. 启动控制面。
7. 访问受保护只读接口生成一条新审计记录。
8. 使用审计员账号读取 `/internal/audit/latest`。
9. 在变更记录中说明恢复原因、归档版本、校验哈希和恢复后验证结果。

禁止在生产路径手工合并多份 JSONL。确需合并时必须先在隔离环境完成校验、排序和重复记录检查，并经过安全评审。

## 故障处理

| 故障 | 处理方式 | 发布影响 |
|---|---|---|
| 审计目录不存在 | 创建目录并恢复 ACL；重启控制面验证写入 | P1 只读诊断可恢复，期间不得放宽策略 |
| 审计文件不可写 | 检查服务账号权限和磁盘空间；恢复后生成验证事件 | 未恢复前不得声明审计链完整 |
| JSONL 存在损坏行 | 保全损坏文件；从最近归档恢复或在排障环境定位损坏范围 | 需要记录审计证据缺口 |
| `/internal/audit/latest` 返回 `403` | 检查角色映射和 `internal.audit.read` 策略 | 不得临时给普通读者授予审计读取 |
| 备份缺失 | 立即补跑备份并记录风险窗口 | 已验收环境必须列入 P2/P3 审计治理跟进，不得放宽审计要求 |

## 回滚

如果新的审计路径、数据库连接或归档策略导致控制面无法启动：

1. 停止控制面。
2. 将 `ops-agent.audit.storage-mode` 恢复为上一个已验证值；数据库模式异常时优先回退到 `file`。
3. 恢复上一个已验证的 `ops-agent.audit.storage-path`、审计文件和目录权限。
4. 启动控制面并访问受保护只读接口。
5. 用审计员身份验证 `/internal/audit/latest`。
6. 在变更记录中说明回滚原因和验证结果。

## P1 验收证据

P1 已于 2026-07-01 完成验收。审计收口证据至少包含：

- 当前环境的审计路径、目录权限截图或命令输出摘要。
- 最近一次归档文件名、SHA-256 和归档操作者。
- `/internal/audit/latest` 的审计员成功读取证据。
- 普通读者访问 `/internal/audit/latest` 的 `403` 证据。
- 一次恢复到排障环境或生产路径的演练记录，或明确的 P2/P3 恢复演练跟进项。
- 若尚未接入集中审计存储，必须在 P2/P3 审计治理跟进项中保留该限制。

## P2 数据库审计存储证据

启用 `ops-agent.audit.storage-mode=database` 前，至少保留以下证据：

- `audit_event` 表初始化成功，索引存在，控制面账号权限符合最小权限要求。
- 受保护接口访问后，数据库中出现对应审计记录，且 `/internal/audit/latest` 能读取最新事件。
- 服务重启后，`R2dbcAuditTrail` 能从 `audit_event` 表恢复最新记录快照。
- 文件模式回退演练通过，回退后仍能生成并读取新审计记录。
