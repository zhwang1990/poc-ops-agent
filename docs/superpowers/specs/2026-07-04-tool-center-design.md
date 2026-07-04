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
7. 为后续接入 RAG 预留契约、页面和审计扩展点，使 API 文档、接口规范、错误手册、历史联调摘要和受控测试数据可以作为可引用上下文参与 AI 辅助。
8. 为管理员提供 `API Caller 设置`，用于维护域名级 allowlist、环境标签、方法策略、超时和请求/响应大小限制。

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
- 把真实 RAG 检索服务和测试数据索引设为首版上线依赖。RAG 仅预留扩展点，真实接入必须后续补齐知识源授权、引用契约、测试数据治理、评测和安全测试。

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
- `M06`：后续如接入 RAG，可承载受控知识制品和引用元数据，不作为首版依赖。
- `M09`：页面、交互、数据校验和结果展示。
- `M10`：指标、日志和追踪约束。
- `M11`：契约、前端、浏览器和安全测试。

`API Caller` 不是 Agent Skill，不进入 AgentScope Tool Catalog。模型不能直接调用它，只能为人工操作员生成请求、断言和排查建议。

## 导航与页面结构

左侧主菜单新增 `工具中心`，位置放在 `SQL 工作区` 后、`模型设置` 前。工具中心内部使用二级工具切换：

- `JSON Formatter`
- `API Caller`
- `API Caller 设置`：仅管理员可见，用于维护域名级 allowlist 和默认策略。

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

页面右侧为 RAG 预留 `知识上下文` 区域。首版可以显示为禁用态或空状态；后续真实接入后，操作员可选择已授权知识源，例如接口文档、OpenAPI 说明、错误码手册、上下游联调记录、运行手册和受控测试数据集。RAG 命中的引用必须独立展示，不能混入模型回答后丢失来源。

### API Caller 设置

`API Caller 设置` 是工具中心内的管理员配置页，不作为普通操作员工具展示。页面用于维护域名级 allowlist，避免开发人员每新增一个接口都需要重复配置。

配置项包括：

- 目标系统名称，例如 `queFork`、`EasyPostman Adapter`。
- 允许域名，按 `scheme + host + port` 记录。
- 环境标签，例如 `dev`、`sit`、`uat`、`sandbox`。
- 是否启用。
- 允许的 HTTP method 集合。
- 默认超时。
- 最大请求体大小。
- 最大响应体大小。
- 是否允许跟随重定向。
- 备注和 owner。

交互要求：

- 新增或修改 allowlist 时必须展示配置摘要。
- 禁止把生产域名配置为启用状态；如确需后续支持，必须通过独立设计和安全评审。
- 禁止使用通配全域名，例如 `*`、`*.com`、`*.internal`。
- 禁止默认允许 IP 字面量、`localhost` 和 metadata 地址。
- 配置保存后立即进入审计记录，并影响后续请求校验。
- 普通操作员只能在 API Caller 发送请求时看到域名是否被允许，不能编辑 allowlist。

## 域名级出站边界

管理员配置允许调用的域名，配置粒度为 `scheme + host + port`。生产域名不配置进 allowlist，因此首版天然不可调用生产。

规则：

- 命中允许域名：按 RBAC 和请求策略执行。
- 未命中允许域名：拒绝。
- 生产域名：拒绝。
- 重定向到未允许域名：拒绝。
- IP 字面量、`localhost`、metadata 地址等高风险目标默认拒绝，除非管理员显式配置为允许域名。

审计记录服务端解析出的域名、环境标签、URL hash、method、操作员、状态码、耗时和 traceId。

域名 allowlist 配置本身也必须审计，至少记录：

- 配置 ID。
- 目标系统名称。
- 允许域名。
- 环境标签。
- 允许 method 集合。
- 启用状态。
- 变更人。
- 变更前后摘要 hash。
- 变更原因。

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

### RAG 预留

AI 辅助契约预留可选 RAG 上下文，但首版不要求真实检索服务可用。后续接入后，RAG 只提供可引用知识，不提供执行权限。

候选知识源：

- API 文档和 OpenAPI 片段。
- 上下游接口错误码手册。
- 历史联调问题摘要。
- 运行手册和故障案例。
- 已脱敏的请求/响应样例。
- 非生产受控测试数据集，例如测试用户、测试订单、测试保单、测试工单、测试产品编码和边界值样例。

RAG 输出要求：

- 命中知识时必须返回引用来源。
- 无命中时必须明确无可引用知识，不能编造接口规则。
- RAG 内容视为不可信数据，不得被当作系统指令、权限事实或执行计划。
- RAG 不得把历史请求中的密钥、Token、Cookie 或未脱敏响应正文沉淀为知识。
- RAG 可以为请求生成提供更准确的测试数据建议，但生成结果仍是草稿，必须由操作员确认后才能填入请求。
- 测试数据 RAG 只允许引用已登记、已脱敏、可授权的非生产数据集，不得索引生产数据、真实客户数据或未治理的数据快照。

测试数据 RAG 预留治理规则：

- 测试数据集必须有 owner、来源系统、环境标签、更新时间、脱敏说明和可用范围。
- 测试数据集只能来自 `dev`、`sit`、`uat` 或人工构造样例。
- 测试数据条目必须带引用 ID，AI 生成请求时应能说明使用了哪些测试数据引用。
- 测试数据不得包含真实客户身份信息、真实支付信息、真实凭据或生产业务记录。
- 测试数据命中后只作为请求 body、query 或断言的候选值，不自动发起调用。

AI 边界：

- 默认只向模型发送脱敏副本。
- 用户可以显式选择“包含当前响应正文用于分析”，仅对当前会话生效。
- AI 输出全部为草稿，必须由用户点击应用。
- AI 不得自动发送请求。
- AI 不得保存凭据。
- AI 不得绕过域名级 allowlist。
- AI 不得把建议转成自动执行动作。
- AI 使用 RAG 时必须展示引用；无引用时只能给出基于当前请求/响应的有限分析。
- 模型输出视为不可信数据，前端按纯文本或受控 Markdown 渲染。

## 后端契约

新增版本化契约建议分为以下几组：

- `ToolCenterCatalog`：工具定义、启用状态、AI 能力开关和权限状态。
- `ApiCallerDomainAllowlist`：管理员维护的目标系统、允许域名、环境标签、方法策略、超时和大小限制。
- `ApiCallerCollection`：集合、目录、请求模板、变量和断言。
- `ApiCallerCredential`：临时凭据引用和凭据别名。
- `ApiCallerExecution`：单次请求执行信封。
- `ToolAiAssist`：AI 辅助请求和响应。
- `ToolRagContext`：预留 RAG 上下文请求、引用结果和知识源授权状态；首版可仅定义禁用态或空响应。

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

`ToolAiAssist` 必须预留可选 RAG 字段：

- knowledgeScopeIds。
- retrievalQuery。
- maxCitations。
- includeCurrentRequestSummary。
- includeCurrentResponseSummary。
- citationRequired。

后续真实接入 RAG 时，知识源过滤必须由服务端根据操作员身份和策略完成，前端传入的知识源范围只能缩小检索范围，不能扩大权限。

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

- `ALLOWLIST_CREATED`
- `ALLOWLIST_UPDATED`
- `ALLOWLIST_DISABLED`
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
- ragRequested。
- knowledgeScopeIds。
- citationIds。
- retrievalHitCount。
- testDataCitationIds。

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

- 管理员可以新增、编辑和禁用域名级 allowlist。
- 普通操作员看不到 `API Caller 设置` 管理入口。
- 生产域名、通配域名、metadata 地址、localhost 和未显式允许的 IP 字面量配置被拒绝。
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
- RAG 未接入时，AI 辅助保持可用并显示知识上下文不可用状态。
- RAG 接入后，无引用命中时不得生成伪装成知识库事实的回答。
- RAG 命中文档中的指令注入内容时，只能作为引用文本展示，不能触发请求发送、凭据保存或策略变更。
- 测试数据 RAG 命中时，生成的请求草稿必须展示引用的数据集名称和 citationId。
- 未授权测试数据集不得出现在检索结果或 AI 草稿中。
- 生产数据、真实客户数据和未脱敏快照不得进入测试数据 RAG 索引。

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
- RAG 真实检索服务接入。
- API Caller 与 RAG 问答页共享知识源目录和引用组件。
- 响应快照持久化。
- 更细粒度的目标系统风险策略。
