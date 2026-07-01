/**
 * @typedef {Object} HelpScenario
 * @property {string} id
 * @property {string} title
 * @property {string} page
 * @property {string[]} roles
 * @property {string} whenToUse
 * @property {string[]} prerequisites
 * @property {string[]} steps
 * @property {string[]} howToReadResult
 * @property {string[]} failureHandling
 * @property {string[]} safetyNotes
 * @property {string[]} keywords
 */

/**
 * @typedef {Object} HelpFaq
 * @property {string} id
 * @property {string} title
 * @property {string} summary
 * @property {string} answer
 * @property {string[]} keywords
 */

/**
 * @typedef {Object} HelpSection
 * @property {string} sectionId
 * @property {string} title
 * @property {string} module
 * @property {string} summary
 * @property {string[]} roleHints
 * @property {string[]} relatedPages
 * @property {string} boundary
 * @property {string[]} keywords
 * @property {HelpScenario[]} scenarios
 * @property {HelpFaq[]} faqs
 */

export const popularHelpKeywords = ["Agent", "权限拒绝", "SQL 校验", "发布失败", "生产不可见", "API Key"];

/** @type {HelpSection[]} */
export const helpSections = [
  {
    sectionId: "quick-start",
    title: "快速开始",
    module: "M09 / M11",
    summary: "用最短路径了解只读诊断、受控变更试点和页面内状态解释。",
    roleHints: ["一线运维", "值班工程师", "平台管理员"],
    relatedPages: ["概览", "Agent 工作区", "工作流事件", "审计记录"],
    boundary: "快速开始只说明如何使用现有页面，不授予权限，也不绕过审批、审计或 Worker 隔离。",
    keywords: ["入门", "只读诊断", "工作流", "审计", "Agent"],
    scenarios: [
      {
        id: "read-only-diagnostic-intro",
        title: "完成一次只读诊断入门",
        page: "Agent 工作区",
        roles: ["ops-reader", "值班工程师"],
        whenToUse: "首次使用平台排查节点、日志或服务状态时使用。",
        prerequisites: ["已登录操作台", "具备只读诊断角色", "目标 Skill 已发布且对当前工作区可见"],
        steps: ["进入 Agent 工作区", "输入诊断目标和现象", "选择只读诊断候选", "提交并观察事件流"],
        howToReadResult: ["先看最终摘要", "再核对引用的 Skill、workflowId 和事件序列", "必要时打开审计记录追踪操作者"],
        failureHandling: ["如果被拒绝，查看拒绝原因和所需角色", "如果任务失败，进入工作流事件查看失败阶段"],
        safetyNotes: ["只读诊断不会执行生产写操作", "模型摘要不能替代审计和工作流事实源"],
        keywords: ["入门", "只读诊断", "Agent", "workflow", "事件流"],
      },
    ],
    faqs: [],
  },
  {
    sectionId: "agent-workspace",
    title: "Agent 工作区",
    module: "M04 / M05 / M07 / M09",
    summary: "说明如何提交只读诊断、阅读 Agent 计划摘要、Tool 调用和最终诊断结果。",
    roleHints: ["一线运维", "SRE", "平台管理员"],
    relatedPages: ["Agent 工作区", "工作流事件", "Skill 注册中心"],
    boundary: "Agent 工作区只能通过平台守护执行器调用已授权 Skill；模型不能直接执行操作或授予权限。",
    keywords: ["Agent", "只读诊断", "服务错误", "节点健康", "Tool 调用"],
    scenarios: [
      {
        id: "troubleshoot-service-error",
        title: "用 Agent 排查服务错误",
        page: "Agent 工作区",
        roles: ["ops-reader", "SRE"],
        whenToUse: "服务出现错误码、超时或告警，需要聚合只读证据时使用。",
        prerequisites: ["已知服务名或节点名", "具备相关只读 Skill 权限", "目标环境对当前用户可见"],
        steps: ["描述错误现象和时间范围", "确认候选 Skill 均为只读风险", "提交诊断", "查看 Tool 请求、完成或拒绝事件"],
        howToReadResult: ["摘要只代表可审计证据的解释", "以事件流、Skill 输出和审计记录作为事实源"],
        failureHandling: ["若 Skill 不可见，检查 Skill 发布状态和角色", "若 Worker 失败，查看工作流事件中的失败载荷摘要"],
        safetyNotes: ["不要在输入中粘贴密钥", "不要要求模型绕过审批或直接修复生产系统"],
        keywords: ["Agent", "服务错误", "诊断", "日志", "Tool"],
      },
      {
        id: "read-node-health-result",
        title: "查看节点健康诊断结果",
        page: "Agent 工作区",
        roles: ["ops-reader", "值班工程师"],
        whenToUse: "需要确认节点 CPU、内存、磁盘或心跳状态时使用。",
        prerequisites: ["节点健康 Skill 已发布", "当前角色可读取节点健康信息", "目标节点在允许范围内"],
        steps: ["输入节点名称", "提交节点健康诊断", "等待 workflow 进入终态", "展开结果与事件序列"],
        howToReadResult: ["绿色状态表示只读检查成功", "异常项需要结合时间戳和目标节点确认"],
        failureHandling: ["节点不可见时联系平台管理员核对目录", "策略拒绝时不要改前端状态，应申请角色或目标范围"],
        safetyNotes: ["节点健康查询是只读能力", "结果不得用于绕过后续变更审批"],
        keywords: ["节点健康", "Agent", "只读", "CPU", "内存"],
      },
      {
        id: "read-execution-refusal",
        title: "查看执行链为什么被拒绝",
        page: "Agent 工作区",
        roles: ["ops-reader", "平台管理员"],
        whenToUse: "Agent 计划、Tool 调用或执行请求被服务端拒绝时使用。",
        prerequisites: ["已获得 workflowId", "能访问工作流事件或审计记录", "了解当前用户角色"],
        steps: ["在结果区找到拒绝事件", "打开工作流事件查看 sequence", "对照审计记录中的策略动作和资源"],
        howToReadResult: ["拒绝事件说明平台守护链路生效", "以服务端策略和审计事件为准"],
        failureHandling: ["不要重试降低限制的请求", "需要权限时走角色或审批流程"],
        safetyNotes: ["客户端展示不能降低安全基线", "模型不能解释为已授权状态"],
        keywords: ["拒绝", "执行链", "策略", "审计", "workflow"],
      },
    ],
    faqs: [],
  },
  {
    sectionId: "rag-question",
    title: "RAG 问答",
    module: "M04 / M09",
    summary: "说明带来源引用的知识问答如何阅读，以及它与执行类 Agent 的边界。",
    roleHints: ["运维工程师", "知识库维护者"],
    relatedPages: ["RAG 问答", "审计记录"],
    boundary: "RAG 问答只提供带引用的知识说明，不执行操作，不生成未审计的处置结论。",
    keywords: ["RAG", "引用", "知识说明", "帮助页", "问答"],
    scenarios: [
      {
        id: "read-cited-knowledge",
        title: "查看带引用的知识说明",
        page: "RAG 问答",
        roles: ["ops-reader", "知识库维护者"],
        whenToUse: "需要查阅手册、规范或故障案例说明时使用。",
        prerequisites: ["知识源已纳入检索范围", "问题不要求执行目标系统操作"],
        steps: ["输入具体问题", "查看回答中的来源引用", "打开引用材料核对上下文"],
        howToReadResult: ["有引用表示命中了知识材料", "无命中时应回到原始手册或补充知识源"],
        failureHandling: ["不要把无引用回答当作事实", "知识缺口应反馈给知识库维护者"],
        safetyNotes: ["RAG 不授予权限", "RAG 不替代审批、审计或工作流记录"],
        keywords: ["RAG", "引用", "知识", "帮助页", "不直接回答"],
      },
    ],
    faqs: [],
  },
  {
    sectionId: "sql-workbench",
    title: "SQL 工作区",
    module: "M02 / M05 / M07 / M09",
    summary: "说明 SQL 校验、开发环境查询和 DML 影响预检的安全边界。",
    roleHints: ["开发运维", "数据库支持", "平台管理员"],
    relatedPages: ["SQL 工作区", "工作流事件", "审计记录"],
    boundary: "生产连接始终不可见且不可调用；P2 仅允许非生产、低风险、可回滚的受控变更能力。",
    keywords: ["SQL", "SQL 校验", "只读", "DML", "生产不可见"],
    scenarios: [
      {
        id: "query-development-data",
        title: "查询开发环境数据",
        page: "SQL 工作区",
        roles: ["sql-reader", "开发运维"],
        whenToUse: "需要在开发或测试连接上执行受控单条 SELECT 查询时使用。",
        prerequisites: ["连接目录中存在可见的非生产连接", "SQL 已通过只读校验", "查询不包含敏感输出"],
        steps: ["选择 dev 或 test 连接", "输入单条 SELECT", "运行校验", "确认后执行查询"],
        howToReadResult: ["结果仅代表当前非生产连接", "分页和字段展示以服务端返回为准"],
        failureHandling: ["连接不可见时不要手写连接串", "校验失败时按报告修改 SQL"],
        safetyNotes: ["禁止生产连接", "禁止在浏览器保存凭据"],
        keywords: ["SQL", "开发环境", "SELECT", "只读查询"],
      },
      {
        id: "validate-read-only-sql",
        title: "校验 SQL 是否只读",
        page: "SQL 工作区",
        roles: ["sql-reader", "开发运维"],
        whenToUse: "提交查询前需要确认语句是否为平台允许的只读 SQL 时使用。",
        prerequisites: ["已选择非生产连接", "SQL 为单条语句"],
        steps: ["输入 SQL", "点击校验", "阅读语句类型、风险和拒绝原因", "仅在通过后继续查询"],
        howToReadResult: ["通过表示当前语句满足只读边界", "拒绝表示服务端或 Worker 二次校验不会执行"],
        failureHandling: ["删除 DML、DDL 或多语句内容", "无法判断时联系数据库支持"],
        safetyNotes: ["前端校验不是唯一边界", "Worker 仍会进行二次拒绝"],
        keywords: ["SQL 校验", "只读", "SELECT", "拒绝", "Worker"],
      },
      {
        id: "preview-dml-impact",
        title: "执行 DML 影响预检",
        page: "SQL 工作区",
        roles: ["开发运维", "审批人"],
        whenToUse: "P2 试点中需要在非生产环境评估 DML 影响范围时使用。",
        prerequisites: ["目标环境不是生产", "语句符合试点范围", "具备预检角色或审批条件"],
        steps: ["输入 DML", "运行影响预检", "查看影响行数和风险说明", "按策略决定是否进入受控流程"],
        howToReadResult: ["预检不是最终执行授权", "影响范围需要结合目标表和事务边界判断"],
        failureHandling: ["生产环境请求会被拒绝", "高风险语句应转人工评审"],
        safetyNotes: ["禁止生产写执行", "受控执行必须绑定工作流、幂等键和审计事件"],
        keywords: ["DML", "影响预检", "SQL", "受控变更", "生产不可见"],
      },
    ],
    faqs: [],
  },
  {
    sectionId: "model-settings",
    title: "模型设置",
    module: "M02 / M04 / M09",
    summary: "说明模型供应方、API Key 轮换、连通性测试和默认模型切换的管理边界。",
    roleHints: ["平台管理员", "安全管理员"],
    relatedPages: ["模型设置", "审计记录"],
    boundary: "模型设置只保存受控供应方配置；API Key 必须加密保存并只展示指纹。",
    keywords: ["模型设置", "API Key", "供应方", "连通性", "默认模型"],
    scenarios: [
      {
        id: "add-model-provider",
        title: "新增模型供应方",
        page: "模型设置",
        roles: ["平台管理员"],
        whenToUse: "需要接入一个 OpenAI-compatible 模型供应方时使用。",
        prerequisites: ["已获得管理员角色", "供应方 URL 和模型名已评审", "密钥来源符合部署要求"],
        steps: ["进入模型设置", "新增供应方", "填写 URL、模型名和运行限制", "保存并查看审计记录"],
        howToReadResult: ["保存后只显示密钥指纹", "默认供应方切换需要单独操作"],
        failureHandling: ["连通性失败时查看脱敏错误", "不要在日志或备注中写入密钥"],
        safetyNotes: ["API Key 不得进入源码或测试数据", "模型供应方不改变授权边界"],
        keywords: ["模型供应方", "OpenAI-compatible", "API Key", "模型设置"],
      },
      {
        id: "rotate-api-key",
        title: "轮换 API Key",
        page: "模型设置",
        roles: ["平台管理员", "安全管理员"],
        whenToUse: "密钥到期、泄露风险或供应方要求更换时使用。",
        prerequisites: ["具备 Key 轮换权限", "新密钥已通过安全渠道取得", "确认业务低峰窗口"],
        steps: ["选择供应方", "点击轮换 Key", "输入新密钥", "执行受控连通性测试"],
        howToReadResult: ["页面只显示新指纹和更新时间", "连通性结果不得回显供应方响应体或密钥"],
        failureHandling: ["测试失败时保持旧配置或按回滚流程处理", "疑似泄露时通知安全管理员"],
        safetyNotes: ["密钥不得出现在 Prompt、日志或制品中", "轮换不授予模型执行权限"],
        keywords: ["API Key", "轮换", "模型设置", "密钥", "连通性"],
      },
    ],
    faqs: [],
  },
  {
    sectionId: "release-center",
    title: "发布中心",
    module: "M02 / M03 / M05 / M07 / M08 / M09 / M10",
    summary: "说明 P2 非生产发布试点中的 WAR 发布、失败日志分析和生产不可见边界。",
    roleHints: ["发布工程师", "审批人", "平台管理员"],
    relatedPages: ["发布中心", "工作流事件", "审计记录"],
    boundary: "发布中心仅覆盖 dev、sit、uat 非生产范围；生产环境不可见、不可配置、不可调用。",
    keywords: ["发布中心", "WAR", "发布失败", "dev", "生产不可见"],
    scenarios: [
      {
        id: "deploy-war-dev",
        title: "在 dev 发布 WAR",
        page: "发布中心",
        roles: ["发布工程师"],
        whenToUse: "需要在 dev 环境进行低风险、可回滚的 WAR 发布试点时使用。",
        prerequisites: ["制品来源已确认", "目标环境为 dev", "发布 Skill 和 Worker 适配器已发布"],
        steps: ["选择 dev 环境和应用", "上传或选择 WAR 制品", "确认发布参数", "提交受控工作流"],
        howToReadResult: ["以确定性状态检查判断成功", "日志分析只作为只读辅助说明"],
        failureHandling: ["失败时查看工作流事件和回滚入口", "不要手工绕过 Worker 执行"],
        safetyNotes: ["禁止生产发布", "发布必须保留幂等、审计和回滚路径"],
        keywords: ["发布中心", "WAR", "dev", "受控变更"],
      },
      {
        id: "read-release-failure-log-analysis",
        title: "查看发布失败后的日志分析",
        page: "发布中心",
        roles: ["发布工程师", "SRE"],
        whenToUse: "非生产发布失败后需要读取脱敏日志摘要时使用。",
        prerequisites: ["发布工作流已失败或进入待处理状态", "日志读取 Skill 可用", "具备目标环境读取权限"],
        steps: ["打开失败发布记录", "查看状态检查结果", "展开日志分析", "按建议进入重试、回滚或人工接管"],
        howToReadResult: ["日志分析是只读诊断建议", "最终状态以工作流和目标系统检查为准"],
        failureHandling: ["日志缺失时查看 Worker 事件", "涉及凭据或敏感信息时停止传播截图"],
        safetyNotes: ["模型不能决定回滚或重试授权", "日志落盘前必须脱敏"],
        keywords: ["发布失败", "日志分析", "发布中心", "回滚", "非生产"],
      },
    ],
    faqs: [],
  },
  {
    sectionId: "skill-registry",
    title: "Skill 注册中心",
    module: "M03 / M08 / M09",
    summary: "说明如何查看 Skill 风险等级、版本、发布状态、角色和参数边界。",
    roleHints: ["平台管理员", "Skill Owner", "运维工程师"],
    relatedPages: ["Skill 注册中心", "Agent 工作区"],
    boundary: "注册中心只展示已注册 Skill 治理信息；不能直接安装、升级、卸载或执行 Skill。",
    keywords: ["Skill", "风险等级", "版本", "注册中心", "READ_ONLY"],
    scenarios: [
      {
        id: "read-skill-risk-version",
        title: "查看 Skill 风险等级和版本",
        page: "Skill 注册中心",
        roles: ["ops-reader", "平台管理员"],
        whenToUse: "需要确认某个 Skill 是否只读、已发布并适合当前诊断任务时使用。",
        prerequisites: ["具备 Skill 目录读取权限", "Skill 已进入注册目录"],
        steps: ["进入 Skill 注册中心", "搜索 Skill ID 或标签", "查看风险等级、版本、Owner 和发布状态"],
        howToReadResult: ["READ_ONLY 表示 Skill 契约声明为只读", "是否可调用仍取决于服务端策略"],
        failureHandling: ["目录读取被拒绝时查看角色", "发布状态异常时联系 Skill Owner"],
        safetyNotes: ["展示信息不能替代执行时授权", "不得从第三方 Skill 直接执行脚本"],
        keywords: ["Skill", "风险等级", "版本", "注册中心", "READ_ONLY"],
      },
    ],
    faqs: [],
  },
  {
    sectionId: "workflow-events",
    title: "工作流事件",
    module: "M05 / M09",
    summary: "说明如何按 workflowId 和 sequence 追踪语义事件，确认任务状态和恢复路径。",
    roleHints: ["值班工程师", "SRE", "平台管理员"],
    relatedPages: ["工作流事件", "Agent 工作区", "发布中心"],
    boundary: "工作流事件是执行状态展示，不允许通过前端事件文本推断或修改授权状态。",
    keywords: ["workflow", "事件序列", "sequence", "恢复", "状态"],
    scenarios: [
      {
        id: "trace-workflow-event-sequence",
        title: "追踪 workflow 事件序列",
        page: "工作流事件",
        roles: ["ops-reader", "SRE"],
        whenToUse: "需要解释任务从提交到完成、失败或拒绝的完整过程时使用。",
        prerequisites: ["已获得 workflowId", "具备事件读取权限"],
        steps: ["输入 workflowId", "按 sequence 查看事件", "核对 timestamp、type 和 payload 摘要"],
        howToReadResult: ["sequence 越大表示越晚发生", "终态事件决定当前工作流状态"],
        failureHandling: ["事件缺口需要查看恢复状态", "重复事件以幂等键和 sequence 去重"],
        safetyNotes: ["事件展示不执行操作", "不得从展示文案推断安全状态"],
        keywords: ["workflow", "事件序列", "sequence", "恢复", "幂等"],
      },
    ],
    faqs: [],
  },
  {
    sectionId: "audit-records",
    title: "审计记录",
    module: "M02 / M10 / M09",
    summary: "说明如何查看操作者、动作、资源、策略版本、traceId 和结果。",
    roleHints: ["审计员", "平台管理员", "安全管理员"],
    relatedPages: ["审计记录", "工作流事件", "模型设置"],
    boundary: "审计记录用于追踪事实，不用于修改权限、隐藏事件或替代审批。",
    keywords: ["审计", "操作者", "traceId", "策略", "拒绝记录"],
    scenarios: [
      {
        id: "read-who-started-operation",
        title: "查看一次操作是谁发起的",
        page: "审计记录",
        roles: ["审计员", "平台管理员"],
        whenToUse: "需要追踪某次诊断、配置或发布动作的发起人和结果时使用。",
        prerequisites: ["具备审计读取权限", "已知时间范围、workflowId 或 traceId"],
        steps: ["进入审计记录", "按 traceId、主体或动作过滤", "查看 subject、action、resource 和 result"],
        howToReadResult: ["subject 表示发起主体", "policyVersion 和 result 说明当时的策略判断"],
        failureHandling: ["查不到记录时扩大时间范围", "审计读取被拒绝时联系安全管理员"],
        safetyNotes: ["审计记录不得包含密钥明文", "不得删除或篡改审计证据"],
        keywords: ["审计", "操作者", "traceId", "action", "拒绝记录"],
      },
    ],
    faqs: [],
  },
  {
    sectionId: "permissions-security",
    title: "权限与安全边界",
    module: "M01 / M02 / M05 / M07 / M09",
    summary: "解释按钮不可用、策略拒绝、生产不可见和模型不能直接执行操作的原因。",
    roleHints: ["所有操作台用户", "平台管理员", "安全管理员"],
    relatedPages: ["Agent 工作区", "SQL 工作区", "发布中心", "审计记录"],
    boundary: "服务端策略是唯一授权决策点；前端、Prompt、模型输出只能提高限制，不能降低安全基线。",
    keywords: ["权限拒绝", "按钮不可用", "生产不可见", "安全边界", "策略"],
    scenarios: [
      {
        id: "explain-disabled-button",
        title: "解释按钮不可用",
        page: "全局操作台",
        roles: ["所有操作台用户"],
        whenToUse: "按钮灰显、入口不可见或提交后返回权限拒绝时使用。",
        prerequisites: ["已登录操作台", "已确认当前页面和目标环境"],
        steps: ["查看按钮旁边或页面顶部的状态说明", "打开审计记录或工作流事件核对拒绝原因", "确认当前角色和目标环境是否满足策略"],
        howToReadResult: ["不可用通常表示角色、环境、审批、发布状态或风险等级不满足", "权限拒绝以服务端响应和审计记录为准"],
        failureHandling: ["不要修改前端状态或直接调用 API 绕过", "需要权限时按组织流程申请角色或审批"],
        safetyNotes: ["客户端不能授予权限", "生产写执行在 P2 阶段禁止开放"],
        keywords: ["权限拒绝", "按钮不可用", "策略拒绝", "生产不可见", "审批"],
      },
    ],
    faqs: [
      {
        id: "why-button-hidden-or-disabled",
        title: "为什么我看不到某个按钮或按钮不可用？",
        summary: "按钮状态通常来自角色、环境、风险等级、审批状态或服务端策略限制。",
        answer: "操作台不会通过隐藏或显示按钮授予权限。真正的授权由服务端策略、工作流、审批和审计链路决定。",
        keywords: ["按钮不可用", "权限拒绝", "策略拒绝", "角色"],
      },
      {
        id: "why-production-invisible",
        title: "为什么生产环境不可见？",
        summary: "P2 阶段禁止生产写执行，SQL 和发布中心的生产环境始终不可见、不可配置、不可调用。",
        answer: "当前阶段只推进非生产、低风险、可回滚的受控变更试点。生产能力需要新的设计决策、安全评审和验收证据。",
        keywords: ["生产不可见", "生产环境", "P2", "发布中心", "SQL"],
      },
      {
        id: "why-model-cannot-execute",
        title: "为什么模型不能直接执行操作？",
        summary: "模型只能提出意图、计划或摘要，不能授予权限，也不能绕过平台守护链路。",
        answer: "每次 Tool 调用都必须经过目录校验、服务端策略、工作流事实源、Worker 隔离和审计事件。",
        keywords: ["模型", "直接执行", "权限拒绝", "安全边界", "Tool"],
      },
    ],
  },
  {
    sectionId: "faq",
    title: "常见问题",
    module: "M09 / M11",
    summary: "集中回答帮助页、权限、生产边界和模型执行边界的高频问题。",
    roleHints: ["所有操作台用户"],
    relatedPages: ["帮助中心", "审计记录", "工作流事件"],
    boundary: "FAQ 只解释产品使用和安全边界，不提供生成式处置回答或执行建议。",
    keywords: ["FAQ", "常见问题", "帮助页", "权限拒绝", "生产不可见"],
    scenarios: [],
    faqs: [
      {
        id: "why-help-does-not-answer-directly",
        title: "为什么帮助页不直接回答问题？",
        summary: "帮助页是静态产品手册和页面内搜索，不是 RAG、Agent 或生成式问答入口。",
        answer: "这样可以避免把帮助内容误认为已授权诊断结论。需要知识问答时应使用带引用的 RAG 问答，需要执行诊断时应使用 Agent 工作区。",
        keywords: ["帮助页", "不直接回答", "RAG", "Agent", "生成式回答"],
      },
      {
        id: "why-model-cannot-execute-faq",
        title: "为什么模型不能直接执行操作？",
        summary: "模型输出不可信，不能成为授权来源或执行事实源。",
        answer: "平台必须由 M02 策略、M05 工作流、M07 Worker 隔离和 M10 审计观测共同约束执行链路。",
        keywords: ["模型", "执行", "权限", "安全边界"],
      },
    ],
  },
];

/**
 * @param {string} sectionId
 * @returns {HelpSection | null}
 */
export function getHelpSectionById(sectionId) {
  return helpSections.find((section) => section.sectionId === sectionId) ?? null;
}

/**
 * @param {unknown} value
 * @returns {string}
 */
function normalizeHelpText(value) {
  return String(value ?? "").trim().toLocaleLowerCase("zh-CN");
}

/**
 * @param {unknown[]} parts
 * @returns {string}
 */
function searchableText(parts) {
  return normalizeHelpText(parts.flat(Infinity).filter(Boolean).join(" "));
}

/**
 * @param {string} query
 * @returns {Array<{
 *   type: "section" | "scenario" | "faq",
 *   sectionId: string,
 *   sectionTitle: string,
 *   title: string,
 *   summary: string,
 *   anchorId: string,
 *   tags: string[],
 * }>}
 */
export function searchHelpContent(query) {
  const normalizedQuery = normalizeHelpText(query);

  if (!normalizedQuery) {
    return [];
  }

  const scenarioResults = [];
  const sectionResults = [];
  const faqResults = [];

  for (const section of helpSections) {
    const sectionSearchText = searchableText([
      section.sectionId,
      section.title,
      section.module,
      section.summary,
      section.boundary,
      section.keywords,
      section.roleHints,
      section.relatedPages,
    ]);

    if (sectionSearchText.includes(normalizedQuery)) {
      sectionResults.push({
        type: "section",
        sectionId: section.sectionId,
        sectionTitle: section.title,
        title: section.title,
        summary: section.summary,
        anchorId: section.sectionId,
        tags: section.keywords,
      });
    }

    for (const scenario of section.scenarios) {
      const scenarioSearchText = searchableText([
        scenario.id,
        scenario.title,
        scenario.page,
        scenario.roles,
        scenario.whenToUse,
        scenario.prerequisites,
        scenario.steps,
        scenario.howToReadResult,
        scenario.failureHandling,
        scenario.safetyNotes,
        scenario.keywords,
      ]);

      if (scenarioSearchText.includes(normalizedQuery)) {
        scenarioResults.push({
          type: "scenario",
          sectionId: section.sectionId,
          sectionTitle: section.title,
          title: scenario.title,
          summary: scenario.whenToUse,
          anchorId: scenario.id,
          tags: scenario.keywords,
        });
      }
    }

    for (const faq of section.faqs) {
      const faqSearchText = searchableText([faq.id, faq.title, faq.summary, faq.answer, faq.keywords]);

      if (faqSearchText.includes(normalizedQuery)) {
        faqResults.push({
          type: "faq",
          sectionId: section.sectionId,
          sectionTitle: section.title,
          title: faq.title,
          summary: faq.summary,
          anchorId: faq.id,
          tags: faq.keywords,
        });
      }
    }
  }

  return [...scenarioResults, ...sectionResults, ...faqResults];
}
