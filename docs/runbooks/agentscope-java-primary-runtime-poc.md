# AgentScope Java 主链路 POC 运行手册

## 当前状态

AgentScope Java 是 P1 只读诊断目标主链路中的 M04 主 Agent Runtime。当前代码已完成运行时边界、受保护入口、禁用/未配置失败关闭、最终摘要 POC、workflow-backed Agent Tool 执行器，以及 AgentScope ReAct 真实工具回调接线；该执行器已经能在服务端重做目录校验、M02 策略决策、执行器级授权审计、参数哈希、M05 Tool Step 持久化、Agent Tool 语义事件发布、M07 WorkerGateway 调用和结果映射。

控制面受保护入口：

```text
POST /api/v1/agent/diagnostics
```

该入口必须先通过 M01 身份认证、M02 策略授权和审计记录。客户端不得传入授权结论、策略版本或工作流事实源字段。

确定性单 Skill 只读入口作为联调、兼容和紧急回退路径保留。AgentScope 主链路当前已补齐 Agent Tool 请求、完成和拒绝三类语义事件契约骨架、M05 发布接线、执行器级授权审计和多 Tool 幂等恢复演练；终态 Agent workflow 会复用持久化的 `AgentTaskResult` 状态、摘要和 toolCallCount。后续仍需继续补齐更完整评测集和路由解释 API。

## 主链路运行条件

控制面默认启用 Agent Runtime。默认配置仍依赖本地占位模型密钥或动态模型供应方配置；缺少可用模型供应方时会进入运行时失败关闭，不会伪造成诊断成功。需要覆盖静态模型参数时，可以启用已提交的 `agent-runtime` Spring profile。该 profile 文件位于 `backend/control-plane/bootstrap/src/main/resources/application-agent-runtime.yaml`，只保存非敏感配置：

```yaml
ops-agent:
  agent-runtime:
    enabled: true
    provider: agentscope
    model-name: ${OPS_AGENT_AGENT_RUNTIME_MODEL_NAME:gpt-4.1-mini}
    base-url: ${OPS_AGENT_AGENT_RUNTIME_BASE_URL:https://api.openai.com/v1}
    api-key-env: OPS_AGENT_AGENT_RUNTIME_API_KEY
```

本地演示供应方只用于验证配置和失败关闭路径，不会向模型供应方发起真实请求。需要真实调用时，必须通过 `OPS_AGENT_AGENT_RUNTIME_API_KEY` 由部署密钥系统注入运行时材料。

2026-06-28 起，推荐通过操作台“模型设置”维护运行时模型供应方，而不是长期依赖静态 `model-name`、`base-url` 和 `api-key` 配置。入口为：

```text
GET /model-settings
```

受保护管理 API：

```text
GET  /internal/model-providers
POST /internal/model-providers
PATCH /internal/model-providers/{providerId}
POST /internal/model-providers/{providerId}/api-key
POST /internal/model-providers/{providerId}/test
POST /internal/model-providers/{providerId}/default
POST /internal/model-providers/{providerId}/disable
```

这些入口只允许管理员角色访问。API Key 只在新增或轮换请求中直接输入一次；控制面返回的模型供应方摘要只包含 `apiKeyFingerprint`、`configVersion` 和时间戳，不返回明文或密文。切换默认供应方后，Agent Runtime 下一次调用会读取当前默认供应方并使用其配置构造 OpenAI-compatible AgentScope 客户端；未配置默认供应方时，才回退到上述环境变量配置。

本地 H2 初始化脚本会种子化一个 `deepseek` / `deepseek-v4-pro` 的 OpenAI-compatible 演示供应方，用于新建本地库时保留模型供应方结构和默认选择。演示供应方不会触发真实出网调用。已经通过操作台配置了同名、同 Base URL、同模型名的供应方时，启动脚本不会重复插入。需要真实调用 DeepSeek 时，仍必须通过“模型设置”页面或受保护管理 API 轮换运行时材料，并在生产或长期环境中提供 `OPS_AGENT_MODEL_SECRET_MASTER_KEY`。

`POST /internal/model-providers/{providerId}/test` 会将供应方 `baseUrl` 拼接 `/chat/completions`，发送最小 OpenAI-compatible 非流式请求做受控连通性探测。控制面只返回 `SUCCEEDED`、`FAILED`、`SKIPPED_FAKE_API_KEY` 等稳定状态和脱敏说明；本地占位 Key 不会出网，401/403、网络异常和供应方错误响应体不会回显给操作台、日志或审计原因。

动态模型配置需要提供模型密钥加密主密钥：

```bash
# 由部署密钥系统注入 OPS_AGENT_MODEL_SECRET_MASTER_KEY；不要在终端或文档中写入值。
```

该值必须来自部署密钥系统、Kubernetes Secret、CI/CD Secret 或外部 Spring 配置源，不得写入源码、配置样例、日志、Prompt、制品或测试数据。未提供时，控制面必须在可处理模型供应方密钥前失败关闭。

真实 API Key 必须通过运行环境、部署密钥系统或受保护的外部 Spring 配置源注入，不得写入源码、配置样例、日志、Prompt、制品或测试数据。运行环境统一使用 `OPS_AGENT_AGENT_RUNTIME_API_KEY`；使用百炼千问时，在目标环境专用配置中覆盖 `model-name`、`base-url` 和密钥变量，仍不得提交真实密钥。

真实 Tool Call 还需要 M02 策略动作 `internal.agent.tool.execute`。基础 `application.yaml` 已为 `ROLE_ops-reader` 和 `ROLE_ops-admin` 配置该动作，避免模型发起工具调用后被平台策略默认拒绝。

不依赖 PowerShell 的启用方式：

```bash
export SPRING_PROFILES_ACTIVE=agent-runtime
# 由部署密钥系统注入 OPS_AGENT_AGENT_RUNTIME_API_KEY；不要在终端或文档中写入值。
./mvnw -pl control-plane/bootstrap spring-boot:run
```

也可以在 systemd、Docker、Kubernetes Secret、CI/CD 变量或外部 Spring 配置文件中设置同等配置；原则是 profile 和非敏感模型参数可以进配置文件，真实 Key 只进密钥通道。

未配置模型供应方、API Key 或显式关闭启用开关时，主入口必须失败关闭，返回明确错误，不得静默改走未审计路径。

## 回退

将以下配置恢复为默认值并重启控制面：

```yaml
ops-agent:
  agent-runtime:
    enabled: false
```

显式关闭后：

- `/api/v1/agent/diagnostics` 返回 `AGENT_RUNTIME_DISABLED`。
- `/internal/diagnostics/read-only` 单 Skill 只读闭环作为兼容和紧急回退路径继续可用。
- 历史 Agent workflow、Tool Step 和审计记录不得删除或篡改。

## 验证命令

从 `backend` 目录运行。Windows 环境：

```powershell
.\mvnw.cmd -pl control-plane/modules/agentruntime -am test
.\mvnw.cmd -pl control-plane/modules/workflow -am test
.\mvnw.cmd -pl control-plane/bootstrap -am test
.\mvnw.cmd -pl control-plane/modules/agentruntime,control-plane/modules/workflow,control-plane/bootstrap -am test
.\mvnw.cmd -pl control-plane/modules/agentruntime -am '-Dtest=AgentscopeReActAgentClientTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -pl control-plane/bootstrap -am dependency:tree '-Dincludes=io.modelcontextprotocol.sdk:*'
```

Linux、macOS 或容器环境：

```bash
./mvnw -pl control-plane/modules/agentruntime -am test
./mvnw -pl control-plane/modules/workflow -am test
./mvnw -pl control-plane/bootstrap -am test
./mvnw -pl control-plane/modules/agentruntime,control-plane/modules/workflow,control-plane/bootstrap -am test
./mvnw -pl control-plane/modules/agentruntime -am -Dtest=AgentscopeReActAgentClientTest -Dsurefire.failIfNoSpecifiedTests=false test
./mvnw -pl control-plane/bootstrap -am dependency:tree -Dincludes=io.modelcontextprotocol.sdk:*
```

期望：

- 所有测试通过。
- dependency tree 不出现 `io.modelcontextprotocol.sdk` 依赖条目。

## 故障处理

| 现象 | 处理 |
|---|---|
| 返回 `AGENT_RUNTIME_DISABLED` | 检查 `ops-agent.agent-runtime.enabled` 是否被显式设置为 `false` |
| 返回 `AGENT_RUNTIME_NOT_CONFIGURED` | 检查 `agent-runtime` profile、`model-name`、`base-url` 和 `api-key-env` 指向的运行环境变量 |
| 返回 `AGENT_RUNTIME_NOT_CONFIGURED` | 未注入 `OPS_AGENT_AGENT_RUNTIME_API_KEY`；由部署密钥系统注入后重启 |
| 模型设置页保存失败 | 检查调用身份是否具备 `internal.model-providers.write`，并确认 `baseUrl` 使用 HTTPS，或仅在本地开发使用 `http://localhost` / `http://127.0.0.1` |
| 模型设置页测试配置失败 | 检查调用身份是否具备 `internal.model-providers.test`、供应方 `baseUrl` 是否可达、模型名是否存在、API Key 是否有效；控制面不会返回供应方响应体或 Key，需要到供应方侧按指纹和时间窗口排查 |
| 设为默认失败 | 检查供应方是否已启用；禁用供应方不能成为默认模型 |
| API Key 指纹未变化 | 确认轮换请求体包含新的 `apiKey`，前端编辑已有供应方时留空表示不轮换 |
| 返回 `POLICY_DENIED` | 检查调用身份是否具备 `internal.agent.diagnostics.read` 和 `internal.agent.tool.execute` 对应角色 |
| Agent 输出为空 | 检查模型供应方响应；平台只返回最终文本摘要，不暴露模型内部推理 |
| 出现非只读工具调用 | 保持 P1 拒绝，不得临时放宽策略；先补安全评审和 ADR |
