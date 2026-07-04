# 工具中心首版设计

## 背景

当前项目已完成 P1 只读诊断 MVP，阶段进入 P2 受控变更试点。研发和测试人员需要在操作台内使用高频辅助工具，减少在本地工具、浏览器插件和外部接口调试工具之间切换。

首版工具中心内置两个工具：

- `JSON Formatter`：面向 JSON 校验、格式化、压缩、差异分析和 Schema 草稿生成。
- `API Caller`：面向非生产接口调用、上下游接口联调、响应分析、断言生成和请求集合沉淀。

本设计参考 EasyPostman 的集合、环境、请求和响应模型语义，并优先评估复用其非 GUI 核心模块，以降低 API Caller 的开发成本。EasyPostman 是 Apache-2.0 许可的 Java/Swing 本地优先工具，ops-agent 首版不直接暴露其完整桌面应用能力，而是在平台安全边界内复用可控的集合、环境、请求构造和 HTTP 执行能力。

参考资料：

- [lakernote/EasyPostman](https://github.com/lakernote/EasyPostman)
- [EasyPostman pom.xml](https://raw.githubusercontent.com/lakernote/EasyPostman/master/pom.xml)
- [EasyPostman 架构文档](https://raw.githubusercontent.com/lakernote/EasyPostman/master/docs/ARCHITECTURE_zh.md)

## 目标

1. 在左侧主菜单新增 `工具中心` 顶层入口。
2. 工具中心内置 `JSON Formatter` 和 `API Caller`。
3. `JSON Formatter` 纯前端本地处理，不上传、不落盘输入内容。
4. `API Caller` 支持完整 URL 输入、域名级 allowlist、临时凭据、凭据别名、单请求执行、响应查看、简单断言和脱敏历史。
5. 接入 AI 辅助能力，但 AI 只能生成草稿、解释和建议，不能自动执行请求或绕过平台策略。
6. 通过技术验证评估引入 EasyPostman 的非 GUI 核心模块，优先复用集合模型、环境变量、请求构造、响应解析和 HTTP runtime 中安全可控的部分。

## 非目标

首版不交付以下能力：

- 生产接口调用。
- 任意内网 HTTP 代理。
- 逐接口 allowlist。
- 脚本执行、插件执行、压测和批量编排。
- AgentScope Tool Catalog 暴露。
- 自动化测试平台和完整 CI 测试编排。
- 直接嵌入 EasyPostman 桌面 GUI。
- 直接暴露 EasyPostman 的脚本、插件、压测、任意外联或本地工作区能力。

## EasyPostman 复用策略

首版不再假设 API Caller 完全自研。实施前必须先做一个受限技术验证，评估 EasyPostman 非 GUI 核心模块是否可以被安全复用。

优先评估范围：

- 集合与目录模型。
- 环境变量和变量替换。
- 请求构造模型。
- 响应解析模型。
- HTTP runtime 中可被域名级 allowlist 包裹的单请求执行能力。

禁止直接复用或必须默认关闭的能力：

- Swing GUI。
- 脚本执行。
- 插件执行。
- 压测和批量运行。
- 任意外网或任意内网访问。
- 本地文件工作区、Git workspace 或不受平台治理的数据持久化。

复用前置条件：

- 完成 Apache-2.0 许可证和 NOTICE 处理确认。
- 完成依赖树和安全漏洞扫描。
- 确认 EasyPostman 模块可以在 Java 21 / Maven 多模块工程中稳定构建或被隔离适配。
- 确认所有 HTTP 出站都能被 ops-agent 的域名级 allowlist、凭据治理、审计和超时限制包裹。
- 禁止把 EasyPostman 的本地存储、脚本、插件或压测机制作为 ops-agent 的执行事实源。

如果技术验证发现模块耦合 GUI、本地工作区或高风险能力过深，则回退为兼容 EasyPostman 数据模型并自研最小受控执行器。

## 模块归属

工具中心归属 `M09 操作台与语义事件流`，后端适配位于控制面内部的工具中心模块，不新增部署服务或模块编号。

相关模块协作：

- `M01`：登录态和可信操作员身份。
- `M02`：工具中心动作授权、域名调用策略和审计。
- `M04`：仅复用现有模型供应方能力处理 AI 辅助，不让模型获得执行权限。
- `M09`：页面、交互、数据校验和结果展示。
- `M10`：指标、日志和追踪约束。
- `M11`：契约、前端、浏览器和安全测试。

`API Caller` 不是 Agent Skill，不进入 AgentScope Tool Catalog。模型不能直接调用它，只能为人工操作员生成请求、断言和排查建议。

## 导航与页面结构

左侧主菜单新增 `工具中心`，位置放在 `SQL 工作区` 后、`模型设置` 前。工具中心内部使用二级工具切换：

- `JSON Formatter`
- `API Caller`

### JSON Formatter

页面采用双栏布局：

- 左侧：JSON 输入。
- 右侧：格式化输出、压缩输出、错误定位、差异分析结果和 Schema 草稿。

支持行为：

- 格式化。
- 压缩。
- 复制。
- 清空。
- 解析错误行列提示。
- JSON Schema 草稿生成。
- 两段 JSON 差异分析。

输入内容只保存在前端组件状态中。离开页面后丢弃，不写入 `localStorage`、服务端、日志或审计。

### API Caller

页面采用类 Postman 的工作台结构：

- 左侧：集合、目录、历史。
- 中间：请求编辑器。
- 右侧：响应、断言、AI 分析、Trace。

请求编辑器包含：

- method。
- 完整 URL。
- query。
- headers。
- auth。
- body。
- assertions。

用户可以输入完整 URL。前端不提供环境下拉，环境只作为后端根据域名规则识别出的审计属性。服务端只做域名级 allowlist，不做逐接口 allowlist。同一允许域名下的新 path 可直接调用。

## 域名级出站边界

管理员配置允许调用的域名，配置粒度为 `scheme + host + port`。生产域名不配置进 allowlist，因此首版天然不可调用生产。

规则：

- 命中允许域名：按 RBAC 和请求策略执行。
- 未命中允许域名：拒绝。
- 生产域名：拒绝。
- 重定向到未允许域名：拒绝。
- IP 字面量、`localhost`、metadata 地址等高风险目标默认拒绝，除非管理员显式配置为允许域名。

审计记录服务端解析出的域名、环境标签、URL hash、method、操作员、状态码、耗时和 traceId。

## 凭据治理

`API Caller` 支持两类凭据：

### 临时凭据

临时凭据用于临时联调，只在本次请求或当前浏览器会话中使用。

约束：

- 不落库。
- 不进入历史。
- 不进入导出集合。
- 不进入审计明文。
- 刷新或离开会话后丢弃。

### 凭据别名

凭据别名用于常用上下游系统。服务端加密保存明文，页面只显示：

- 名称。
- 类型。
- 指纹。
- 更新时间。
- 可用域名范围。

所有凭据使用都必须记录审计元数据，但不得记录明文。

敏感字段始终包括：

- `Authorization`
- `Cookie`
- `Set-Cookie`
- `X-API-Key`
- `Proxy-Authorization`
- Basic Auth 密码
- Bearer Token

AI 生成 `curl`、`fetch` 或 Java 代码片段时，敏感值必须输出占位符。

## 响应展示与历史

非生产响应在当前页面可以按原文展示，不做业务字段脱敏，便于联调排查。

落盘边界：

- 请求历史保存 method、URL hash、域名、状态码、耗时、操作员、时间、请求摘要和响应摘要。
- 完整响应默认不持久化。
- 审计、服务端日志、导出集合和断言报告不得包含密钥、Token、Cookie 或完整敏感响应正文。
- 用户保存请求到集合时，自动剥离临时凭据。

## AI 辅助能力

首版 AI 能力包括：

### JSON Formatter

- Schema 生成：根据 JSON 样例生成 JSON Schema、Zod schema 或 Java DTO 草稿。
- 差异分析：对两段 JSON 的结构和值差异生成摘要。

### API Caller

- 请求生成：根据自然语言生成 URL、method、headers、query 和 body 草稿。
- 响应解读：解释状态码、headers 和响应 body。
- 错误排查：结合请求、响应、耗时和 traceId 给出排查建议。
- 断言生成：根据响应样例生成状态码、JSONPath、字段类型、非空和耗时断言草稿。

AI 边界：

- 默认只向模型发送脱敏副本。
- 用户可以显式选择“包含当前响应正文用于分析”，仅对当前会话生效。
- AI 输出全部为草稿，必须由用户点击应用。
- AI 不得自动发送请求。
- AI 不得保存凭据。
- AI 不得绕过域名级 allowlist。
- AI 不得把建议转成自动执行动作。
- 模型输出视为不可信数据，前端按纯文本或受控 Markdown 渲染。

## 后端契约

新增版本化契约建议分为以下几组：

- `ToolCenterCatalog`：工具定义、启用状态、AI 能力开关和权限状态。
- `ApiCallerCollection`：集合、目录、请求模板、变量和断言。
- `ApiCallerCredential`：临时凭据引用和凭据别名。
- `ApiCallerExecution`：单次请求执行信封。
- `ToolAiAssist`：AI 辅助请求和响应。

`ApiCallerExecution` 必须包含：

- 操作员身份上下文。
- 完整 URL。
- method。
- headers。
- query。
- body。
- credentialRef。
- idempotencyKey。
- traceContext。

动态请求和响应载荷必须受 Schema 约束，不能在完整执行链路中使用无约束的 `Map<String, Object>`。

## 执行链路

```text
M09 工具中心前端
  -> M01 会话认证
  -> M02 工具中心动作授权
  -> 控制面工具中心 API
  -> 域名级 allowlist 校验
  -> 凭据解析与敏感字段保护
  -> 审计 REQUESTED
  -> 受控 HTTP 调用
  -> 响应大小、超时、content type 校验
  -> 审计 COMPLETED / FAILED / REJECTED
  -> 返回当前页面展示
```

`dev`、`sit`、`uat` 的区分由服务端域名规则解析，只作为策略和审计属性，不要求用户在页面上选择环境。

## 审计与观测

审计事件至少覆盖：

- `REQUESTED`
- `COMPLETED`
- `FAILED`
- `REJECTED`

审计字段至少包含：

- operator。
- action。
- domain。
- environmentLabel。
- urlHash。
- method。
- requestBodyHash。
- responseBodyHash。
- statusCode。
- durationMs。
- credentialAlias 或 temporaryCredentialUsed。
- traceId。
- rejectionReason。

AI 辅助审计至少记录：

- aiAssistType。
- inputSummaryHash。
- responseIncluded。
- outputApplied。

日志和指标：

- 不记录密钥和完整请求/响应正文。
- 记录域名调用量、拒绝量、错误率、超时率、响应体过大次数和 AI 辅助使用量。

## 测试与验收

### JSON Formatter

- 合法 JSON 格式化成功。
- 非法 JSON 显示稳定错误和行列信息。
- 压缩结果正确。
- Schema 生成只产出草稿。
- 差异分析不修改输入。

### API Caller

- 允许域名请求成功。
- 未允许域名请求被拒绝。
- 生产域名请求被拒绝。
- 重定向到未允许域名被拒绝。
- IP 字面量、localhost 和 metadata 地址默认拒绝。
- 敏感 header 不进入历史、日志、审计和导出。
- 临时凭据不持久化。
- 凭据别名加密保存且只展示指纹。
- 保存集合时自动剥离临时凭据。

### AI

- AI 请求生成只生成草稿，不自动执行。
- AI 响应解读默认使用脱敏副本。
- 用户显式选择后才允许把当前响应正文发给模型。
- AI 断言生成需要用户点击应用。
- AI 输出不能降低域名 allowlist、RBAC 或凭据治理要求。

### 浏览器验收

- 左侧主菜单出现 `工具中心`。
- `JSON Formatter` 和 `API Caller` 可切换。
- API Caller 完整 URL 输入、发送、响应查看和错误拒绝路径可用。
- 当前页面可以原文展示非生产响应。
- 历史和导出不含敏感字段。
- 桌面和移动视口无文本溢出、控件重叠或布局跳动。

## 发布与回滚

发布默认启用 `JSON Formatter`。`API Caller` 只有在至少配置一个允许域名、RBAC 动作和审计事件后才可启用。

回滚方式：

- 前端隐藏工具中心入口或禁用 `API Caller`。
- 控制面关闭 `API Caller` 功能开关。
- 清空域名 allowlist。
- 保留已产生的审计记录。

## 待后续评估

以下能力不进入首版，但可以在后续独立设计：

- 集合批量运行。
- 变量跨请求传递。
- Newman 类测试报告。
- OpenAPI 导入。
- EasyPostman 集合格式导入和导出。
- 响应快照持久化。
- 更细粒度的目标系统风险策略。
