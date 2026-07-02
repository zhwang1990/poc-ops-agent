# 操作台

操作台是 `M09` 的前端交付单元。

## 主要职责

- 展示强类型语义事件流。
- 消费后端提供的浏览器会话状态，而不是在前端做授权决策。
- 触发只读诊断请求，并在事件流中断时尝试恢复。
- 明确呈现登录状态、权限不足和执行失败等服务端结果。
- 通过 Zod 校验所有 API 和事件边界响应，页面只渲染通过契约的数据。

## 首轮页面

- 登录页：读取 `/auth/session`，匿名用户展示控制面登录入口，已认证用户进入操作台。
- Agent 工作台：接入 `/api/v1/agent/diagnostics` 主诊断入口，并读取 `/internal/routing/skills/search` 展示 P1 只读、已验证发布的候选 Skill；服务端策略仍是唯一授权决策点。
- Skill 注册中心：读取 `/internal/skills` 展示真实目录，并通过 `/internal/routing/skills/search` 支持自然语言候选查询、分类筛选、风险筛选和详情查看；安装、升级、卸载保持禁用。
- SQL 工作台：读取 `/internal/sql-workbench/connections`，通过 `/internal/sql-workbench/queries/validate` 校验 SQL，并通过 `/internal/sql-workbench/queries/run` 与 `/internal/sql-workbench/results/{resultId}` 展示开发/测试环境受控单条 `SELECT` 结果；DML 仍只进入预检报告。
- 快捷连接：入口和能力短期禁用；后续开放前必须补齐后端契约、服务端策略授权和审计。

## 技术栈

- JavaScript / JSX。
- JSDoc 契约注释。
- TypeScript `checkJs` 严格静态检查。
- React、React Router、TanStack Query、Zod。
- Vitest 覆盖组件、Hook、Schema 和 API 边界。
- Playwright 覆盖 `1280px`、`1440px`、`1920px` 桌面浏览器验收。

## 禁止事项

- 不在浏览器中自行做授权决策。
- 不直接调用目标系统。
- 不绕过控制面的策略、审计、幂等和恢复语义。
- 不把模型文本或展示文案当作安全状态事实源。
- 不提供生产写执行、任意脚本执行、生产 SQL 连接、DML 执行、Commit 或 Rollback。
- 不使用 Mock 成功数据伪装真实服务端能力；缺失接口必须显示禁用或错误状态。

## 登录与会话状态

- React 应用外壳、`/login`、`/agent`、`/skills` 和 `/sql` 路由已经建立。
- `/login` 已接入 React 登录页视觉，包含原型化首屏、安全能力展示、用户名与密码输入，以及“登录”按钮。
- 前端认证 API 当前封装 `/auth/session`、`POST /auth/login` 和 `GET /auth/logout`；登录页只在控制面返回已认证响应后跳转 `/overview`，登出成功后回到 `/login`。
- 内建身份模式的首次改密接口 `POST /auth/password` 尚未在前端封装；该能力转入 P2/P3 补齐强制改密页面和对应验收。
- 受保护页面已接入 `ProtectedRoute`，匿名访问会先读取浏览器会话并跳转登录页。
- `AppShell` 会话区域读取真实浏览器会话主体和角色；退出入口已接入服务端登出流程。

## Agent 工作台

- `/agent` 已从占位页切换为 React Agent 工作区页面，布局参考 `figma-prototype/ops-agent-aia-prototype.html` 的 Agent 工作区片段。
- 页面已还原原型中的顶部胶囊栏、会话工具栏、工作会话主窗、双 workflow 卡片、任务输入区、选中任务详情、Skill 与事件、会话上下文侧栏。
- 候选 Skill 只通过 `src/api/agent-api.js` 调用控制面 `POST /internal/routing/skills/search`，页面和组件不直接 `fetch`。
- 首轮固定请求 `READ_ONLY`、`VALIDATED` 候选能力；授权结论仍以服务端策略返回为准，前端不根据展示文本判断权限。
- Agent 工作台的任务发送已接入 `/api/v1/agent/diagnostics` 主诊断入口；服务端策略、工作流幂等和审计仍是唯一可信结果来源，页面不模拟任务执行成功。
- 页面不展示模型内部推理，只展示可审计计划摘要和服务端候选能力。
- 自动化测试覆盖候选 Skill 成功渲染、服务端 `403` 拒绝、空候选状态、诊断请求提交、提交失败展示和内部推理文案缺失。
- 视觉验收截图保存在 `.artifacts/agent-reference-screen.png` 和 `.artifacts/agent-react-screen.png`；`1440x1080` 下已确认页面高度回到原型画板高度、无横向溢出。

## SQL 工作台

- 顶部连接条展示当前连接、环境、Schema 和结果限制；数据库对象浏览器默认收起为抽屉。
- 只展示控制面返回的开发和测试连接；当前连接契约允许 `DB2_FOR_I`、`H2` 和 `MYSQL` 三类平台类型，生产连接仍被契约拒绝。
- 新建连接表单只提交连接元数据和 `credentialAlias`，不包含密码、JDBC URL 或真实凭据。
- 支持多 SQL 会话标签，每个会话独立保存 SQL 文本、连接、Schema、服务端校验报告、执行状态和结果引用。
- SQL 编辑器使用 MIT 许可的 CodeMirror 6 与官方 `@codemirror/lang-sql` 语言包处理光标、选择、SQL 注释高亮和单条 SQL gutter 执行入口；该依赖只负责前端编辑体验，不参与授权、校验或执行决策。原自绘透明 `textarea` 高亮无法可靠处理注释后的光标可见性和语法扩展。
- 支持单条 SELECT 校验与受控执行，结果通过控制面分页读取；INSERT、UPDATE、DELETE 仍只做静态预检。
- P1 不提供 DML 执行、交互事务、Commit 或 Rollback。
- 服务端校验详情展示在右侧信息面板；Copilot / AI SQL 助手在模型评测和数据脱敏门禁通过前保持禁用。
- 目标交互以主流数据库客户端为基线，包含对象浏览、多 SQL 文件标签、传统编辑器、执行工具栏和完整结果区；AI SQL 助手在右侧提供错误分析和性能优化。
- P2 计划开放开发环境受控 CRUD；生产 SQL 连接在所有阶段均不可见、不可调用。

### 2026-06-14 React 转换记录

- `/sql` 已从通用 `AppShell` 中独立出来，按仓库外原型中 `id="sql-workbench-screen"` 片段还原完整 screen；原型仅作为历史视觉参考，不是当前事实源。
- 页面包含原型同款左侧胶囊导航、顶部 EA 胶囊、连接工具条、数据库对象浏览器、多 SQL 文件标签、SQL 编辑器视觉区、服务端校验报告、结果区和右侧 AI SQL 助手禁用区。
- 数据只通过 `src/api/sql-api.js` 调用控制面 `GET /internal/sql-workbench/connections` 与 `POST /internal/sql-workbench/queries/validate`，页面和组件不直接 `fetch`。
- SQL 编辑区为了像素级贴近原型，采用原型同结构的行号、代码文本和 DML 高亮展示；交互动作仍只进入服务端校验契约。
- 自动化测试覆盖连接目录渲染、版本化校验请求、服务端拒绝报告、生产连接契约拒绝和 AI 助手禁用状态。
- 视觉 QA 记录见仓库根目录 `design-qa.md`；参考截图为 `.artifacts/sql-reference-screen.png`，React 截图为 `.artifacts/sql-react-screen.png`。
- 本次已验证：`npm run build`，包含 `check`、`lint`、40 个 Vitest 测试和 Vite production build。
- 浏览器检查使用本地 Vite `http://127.0.0.1:5174/sql` 和 Playwright API 拦截完成；真实联调仍需启动控制面 `http://127.0.0.1:8080`。

## 本地开发

```powershell
npm install
npm run dev
```

开发服务器会把 `/auth`、`/api` 和 `/internal` 请求代理到本机控制面 `http://127.0.0.1:8080`。

## 本地验证

```powershell
npm run check
npm run lint
npm run test
npm run test:e2e
npm run build
npm audit --audit-level=high
```

首次运行 Playwright 前需要安装 Chromium：

```powershell
npx playwright install chromium
```

如果本机 Playwright 浏览器下载不可用，可临时使用已安装的 Chrome：

```powershell
$env:PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH = "C:\Program Files\Google\Chrome\Application\chrome.exe"
npm run test:e2e
```

端到端测试通过浏览器路由 Mock 控制面接口，用于验证页面流程、桌面视口布局、禁用状态和关键操作可达性；它不替代后端契约测试。

## 本地 built-in 登录联调

默认联调方式是控制面内建身份的浏览器会话登录：

1. 启动控制面；默认 `application.yaml` 使用 `ops-agent.security.auth-mode=built-in`。
2. 打开操作台首页。
3. 在登录页输入控制面内建身份账号与密码，点击“登录”。
4. 登录成功后应跳转 `/overview`，并通过 `/auth/session` 读取浏览器会话主体。
5. 诊断请求最终应复用浏览器会话访问 `/internal/**`，当前页面不得在浏览器中自行构造授权事实。

如需排障，不得把 Bearer Token 覆盖入口作为默认链路；当前重写版本也尚未重新实现该调试入口。

`local-oidc` 仅用于显式启用 `local-oidc` profile 的 Mock OIDC 联调，不作为默认启动模式。

详细步骤见 [docs/runbooks/local-oidc-mock-testing.md](../../docs/runbooks/local-oidc-mock-testing.md)。

## 本地构建

```powershell
npm run build
```

## 发布与回滚影响

- 发布影响：首轮页面只增加浏览器前端能力和只读校验入口，不开放新的生产副作用操作。
- 回滚方式：回退当前前端构建产物或回退本仓库中 `frontend/operator-console` 的相关提交；后端接口和契约不因本页面重写而改变。
- 已知后续工作：RAG 问答页、审计详情页、SQL 查询结果分页与脱敏留存仍需后续任务完成。
