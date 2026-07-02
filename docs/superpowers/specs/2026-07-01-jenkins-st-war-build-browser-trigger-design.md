# Jenkins ST WAR 打包浏览器触发设计

- 日期：2026-07-01
- 状态：设计草案，待用户复核
- 相关模块：M02、M03、M04、M05、M07、M08、M09、M10、M11
- 目标阶段：P2 受控变更试点

## 1. 背景

当前发布中心 ADR 0010 已定义非生产受控变更边界，但其中明确不触发 CI 构建流水线。用户现在提出一个更窄的自动化诉求：Liberty 的 ST 环境 WAR 打包只能通过 Jenkins 页面触发，Jenkins 使用专用服务账号登录，无 MFA，Job 页面需要从分支选项中选择分支后点击 Build，执行结果只生成 WAR 包，不自动部署、不启停、不重启、不回滚，也不操作生产环境。

因此本设计不是开放通用浏览器 Agent，也不是扩大发布中心生产发布能力，而是新增一个受控的非生产构建触发切片：通过 M07 Worker 内部的确定性浏览器适配器触发白名单 Jenkins ST 打包 Job，并将构建结果和 WAR 产物引用登记回平台。

实现前必须补充或修订 ADR 0010，明确允许该受控 Jenkins ST 打包切片，并继续保留生产环境不可见、不可配置、不可调用的边界。

## 2. 目标

- 允许操作员或 AgentScope 主链路发起“为指定应用触发 Jenkins ST WAR 打包”的受控请求。
- 只允许访问白名单 Jenkins base URL 和白名单 Job。
- 只允许从 Jenkins 页面实际可选的分支列表中选择一个分支。
- 触发 Build 后等待构建结束，读取 build number、build URL、构建状态和可识别的 WAR 产物信息。
- 将构建结果写入 M05 工作流、M10 审计观测和发布中心制品目录。
- 保留截图、最后访问 URL、页面状态摘要和必要日志，支持失败后的人工接管。

## 3. 非目标

- 不提供通用浏览器控制能力。
- 不允许模型自由观察页面并自行决定点击路径。
- 不触发非白名单 Jenkins Job。
- 不填写任意字符串分支；分支必须来自 Jenkins 页面实际选项。
- 不执行 Jenkins 任意脚本、Groovy Console、配置修改或凭据管理。
- 不自动部署 WAR 到 Liberty。
- 不启停、重启、回滚任何目标服务器或应用。
- 不操作生产 Jenkins Job、生产 Liberty、生产参数或生产制品发布。
- 不把浏览器会话、截图或页面缓存作为执行事实源。

## 4. 方案比较

### 4.1 推荐方案：专用 Jenkins 浏览器 Worker 适配器

M07 Worker 使用 Playwright 或等价浏览器自动化库，在受限网络、受控凭据和固定步骤下登录 Jenkins、读取分支选项、选择分支、点击 Build、轮询构建结果。

优点：
- 符合当前“只能通过页面触发”的现实约束。
- 能复用 M03 契约、M05 工作流、M07 隔离、M10 审计和 M09 事件展示。
- URL、Job、选择器、参数和动作都可白名单化，风险可控。

缺点：
- Jenkins 页面结构变化会导致选择器失效，需要人工接管和选择器维护。
- 浏览器运行时依赖比 HTTP API 更重，需要隔离、超时和资源限制。

### 4.2 备选方案：Jenkins API 适配器

若 Jenkins 后续开放 crumb、token 或 remote build API，可改为 HTTP 调用触发 Job。

优点：
- 更稳定、更轻量、更容易做契约和幂等。

缺点：
- 不满足当前“只能操作页面”的约束。
- 需要额外确认 Jenkins API 权限、Token 管理和 crumb 获取策略。

### 4.3 拒绝方案：通用浏览器 Agent

把浏览器工具直接暴露给 AgentScope，让模型自行看页面、点击和输入。

拒绝原因：
- 会扩大能力边界，难以保证只触发白名单 Job。
- 容易绕过 M02 策略、M03 契约、M05 工作流和 M07 Worker 固定执行路径。
- 页面变化、Prompt 注入和错误点击风险不可接受。

## 5. 能力边界

新增能力命名为 `jenkins-st-war-build-trigger`。

首版只支持：
- 目标环境：Jenkins 侧 ST 打包 Job；平台侧必须映射到非生产环境策略，生产环境禁止。
- 认证方式：Jenkins 专用服务账号用户名和密码，通过凭据别名在 Worker 侧使用，不暴露给 AgentScope、前端、日志或事件。
- 参数方式：从 Jenkins 页面读取分支选项，选择与请求分支完全匹配的选项。
- 构建结果：读取 build number、build URL、状态、WAR 产物名、WAR 产物 URL 和可获取的校验信息。
- 执行动作：登录、进入构建页、读取分支、选择分支、点击 Build、轮询结果、读取产物。

首版不支持：
- 任意 URL 导航。
- 任意页面点击。
- 任意 JavaScript 执行。
- Jenkins 配置编辑。
- Job 创建、删除或参数定义修改。
- 自动部署或生产操作。

## 6. 组件设计

### 6.1 M03 Skill 契约

新增 `jenkins-st-war-build-trigger` Skill 契约包。

输入字段：
- `applicationId`：平台应用标识。
- `jobKey`：平台配置中的 Jenkins Job 白名单键，不直接接受任意 URL。
- `requestedBranch`：用户希望选择的分支名称。
- `idempotencyKey`：幂等键。

输出字段：
- `jobKey`
- `requestedBranch`
- `selectedBranch`
- `buildNumber`
- `buildUrl`
- `status`
- `artifactName`
- `artifactUrl`
- `artifactChecksum`
- `evidenceRef`
- `startedAt`
- `finishedAt`

Schema 必须禁止无约束对象和任意附加字段。

### 6.2 M04 AgentScope 主链路

AgentScope 只能基于已发布 Tool Catalog 提出触发意图。M04 将 ToolUse 转换为强类型 `AgentToolCall` 后交给 M05。AgentScope 不接收 Jenkins 凭据，不直接连接 Jenkins，不直接操作浏览器。

### 6.3 M05 工作流

M05 创建持久化工作流并记录：
- 操作员身份。
- `applicationId`
- `jobKey`
- `requestedBranch`
- 参数哈希。
- 策略版本。
- 确认或审批引用。
- 幂等键。
- 构建状态。
- Jenkins build number 和 build URL。
- 产物引用。
- 截图和证据引用。

工作流状态至少包括：
- `REQUESTED`
- `BRANCH_OPTIONS_READ`
- `BRANCH_REJECTED`
- `BUILD_TRIGGERED`
- `BUILD_RUNNING`
- `BUILD_SUCCEEDED`
- `BUILD_FAILED`
- `TIMED_OUT`
- `MANUAL_INTERVENTION_REQUIRED`

同一 `jobKey + requestedBranch + 参数哈希 + 幂等键` 命中时不得重复点击 Build。若已触发构建但结果未知，恢复逻辑只能继续轮询已知 build URL 或进入人工接管，不能重新触发。

### 6.4 M07 Worker 浏览器适配器

Worker 新增专用 Jenkins 浏览器适配器，作为同一执行 Worker 的受控适配能力加载。

适配器职责：
- 校验 Jenkins base URL 和 Job URL 是否在 allowlist。
- 使用凭据别名获取 Jenkins 专用服务账号。
- 在隔离浏览器上下文中登录 Jenkins。
- 打开白名单 Job 的构建页面。
- 读取分支下拉或分支选项。
- 校验 `requestedBranch` 是否存在于实际选项。
- 选择匹配分支并点击 Build。
- 获取 build URL 并轮询构建状态。
- 读取 WAR 产物信息。
- 保存截图、最后页面 URL 和有限日志摘要。

适配器不得：
- 接受任意选择器。
- 接受任意点击动作。
- 执行页面内任意脚本。
- 将 Jenkins Cookie 或凭据写入审计、事件、日志或制品。
- 访问非白名单网络出口。

### 6.5 M09 操作台

发布中心新增“ST 打包”入口或动作区。

页面能力：
- 显示应用、Jenkins Job、请求分支、实际可选分支和确认信息。
- 显示工作流进度、构建状态、build URL、产物引用和失败原因。
- 显示截图和日志摘要的受控证据引用。
- 对需要确认的动作展示服务端返回的确认要求。

前端不得根据页面文案、按钮状态或缓存推断授权结果。

### 6.6 制品登记

构建成功后，控制面将 WAR 登记为发布中心制品引用。登记内容包括：
- 应用。
- 目标非生产环境标识。
- WAR 文件名。
- Jenkins build URL。
- Jenkins artifact URL。
- 可获取的 checksum。
- 构建时间。
- 来源工作流 ID。

该制品引用后续是否部署，由另一个受控流程决定。本能力不自动继续部署。

## 7. 数据流

```text
操作员请求或 AgentScope ToolUse
  -> M01 身份认证
  -> M02 策略授权
  -> M03 已发布 Skill 契约校验
  -> M05 创建 Jenkins ST 打包工作流
  -> M07 Worker 执行受限浏览器适配器
  -> Jenkins 白名单 Job 页面读取分支选项
  -> M07 选择匹配分支并触发 Build
  -> M07 轮询构建结果并读取 WAR 产物
  -> M05 持久化结果、事件和证据引用
  -> M09 展示进度、结果和人工接管入口
  -> M10 记录审计、指标、日志和 trace
```

## 8. 失败处理和恢复

### 8.1 分支不可选

如果 `requestedBranch` 不在 Jenkins 页面实际选项中，Worker 返回业务拒绝。M05 记录可选分支摘要和拒绝原因，不触发构建。

### 8.2 登录失败

登录失败视为执行失败。不得反复重试密码，不得在日志中记录账号密码。事件只暴露稳定错误码和脱敏原因。

### 8.3 页面结构变化

如果选择器失效、分支列表不可识别或 Build 按钮不可定位，工作流进入 `MANUAL_INTERVENTION_REQUIRED`。系统保留截图、最后 URL 和页面标题摘要。

### 8.4 Build 已触发但结果未知

若已获得 build URL，恢复时只能继续轮询该 URL。若没有获得 build URL，但页面显示已触发的可识别排队项或构建编号，恢复逻辑应绑定该构建。无法确定时进入人工接管，不得重新点击 Build。

### 8.5 超时

超时后关闭浏览器上下文，记录最后截图、最后 URL、build URL、当前状态和超时原因。工作流进入可审计的失败或人工接管状态。

## 9. 安全控制

- 生产 Jenkins URL 和生产 Job 不可配置、不可见、不可调用。
- Job 配置必须由管理员维护并经服务端策略授权。
- Jenkins 凭据只通过凭据别名引用，服务端加密保存，Worker 使用时解密。
- Worker 网络出口必须限制到 Jenkins allowlist。
- 所有请求必须包含操作员身份、目标环境、Skill 版本、参数哈希、策略版本、幂等键和 trace 上下文。
- `sit`、`uat` 或等价非生产环境是否需要二次确认由服务端策略决定。
- 关键参数变化后确认或审批自动失效。
- 截图和日志摘要落盘前必须脱敏，不能包含密码、Cookie、Token、Jenkins crumb 或其他密钥。
- 失败恢复不得通过重复点击 Build 规避幂等。

## 10. 可观测性与审计

必须记录：
- 构建触发请求。
- 分支选项读取结果摘要。
- 分支命中或拒绝。
- Build 点击前后的状态。
- build number 和 build URL。
- 构建终态。
- WAR 产物登记结果。
- 人工接管原因。

指标建议：
- `jenkins_build_trigger_requests_total`
- `jenkins_build_trigger_rejected_total`
- `jenkins_build_trigger_succeeded_total`
- `jenkins_build_trigger_failed_total`
- `jenkins_build_trigger_manual_intervention_total`
- `jenkins_build_trigger_duration_seconds`

日志必须结构化，并传递 workflow、task、operator、Skill、version、jobKey、buildNumber 和 trace 标识。

## 11. 测试策略

### 11.1 契约测试

- 输入 Schema 拒绝任意 URL、任意选择器和额外字段。
- 输出 Schema 约束 build、状态和产物引用。
- 分支不可选返回稳定拒绝结果。
- 生产 Job、生产环境和未发布 Skill 被拒绝。

### 11.2 Worker 测试

使用本地模拟 Jenkins 页面验证：
- 登录成功。
- 登录失败。
- 读取分支选项。
- 请求分支命中并选择。
- 请求分支不在选项中。
- 点击 Build 后获取 build URL。
- 构建成功、失败、超时。
- 页面选择器失效进入人工接管。
- allowlist 外 URL 被拒绝。

### 11.3 工作流测试

- 幂等命中不重复点击 Build。
- Build 已触发后恢复只继续轮询。
- 参数变化导致确认或审批失效。
- 审计事件包含完整上下文。
- 人工接管状态可查询。

### 11.4 前端测试

- 发布中心展示 ST 打包入口。
- 显示分支确认、构建进度、成功产物、失败原因和人工接管。
- `401`、`403`、契约错误和网络失败正确展示。
- 前端不根据按钮文案或缓存判断授权。

## 12. 发布与回滚

发布前置条件：
- ADR 0010 已补充或新增 ADR 已接受。
- Skill 契约、Worker 命令契约和事件契约已版本化。
- Jenkins Job allowlist、凭据别名、网络出口和证据存储路径已配置。
- 功能开关默认关闭。
- 安全评审和 E2E 验收通过。

回滚方式：
- 关闭 Jenkins ST 打包触发功能开关。
- 保留历史工作流、审计和制品引用只读查询。
- 撤销 Worker 侧 Jenkins 凭据和网络出口配置。
- 未完成工作流进入人工接管。

## 13. 验收标准

- 用户可以通过发布中心或 AgentScope 意图触发白名单 Jenkins ST WAR 打包 Job。
- Worker 只能选择 Jenkins 页面实际存在的分支。
- 分支不可选时不会触发 Build。
- 构建成功后平台记录 build number、build URL 和 WAR 产物引用。
- 构建失败、超时或页面变化时进入明确失败或人工接管状态。
- 同一幂等请求不会重复点击 Build。
- Jenkins 凭据、Cookie、Token 和 crumb 不出现在日志、事件、审计、截图说明或前端状态中。
- 生产 Jenkins Job 和生产环境始终不可见、不可配置、不可调用。
- 相关契约测试、Worker 测试、工作流测试、前端测试和安全拒绝测试通过。
