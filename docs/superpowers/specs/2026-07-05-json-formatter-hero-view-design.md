# JSON Formatter 结构浏览器设计

## 背景

工具中心首版已经提供 `JSON Formatter`，当前能力覆盖本地 JSON 解析、格式化和压缩。用户明确要求不要跳转到外部 JSON Hero，而是把类似 JSON Hero 的结构浏览体验集成到现有操作台界面中。

该能力归属 `M09 操作台与语义事件流`。它是现有工具中心的前端本地增强，不新增后端接口、执行链路、审计动作或外部服务依赖。

## 目标

1. 在现有 `JSON Formatter` 内提供 JSON Hero 风格的结构浏览体验。
2. 输入内容只保存在当前 React 组件状态中，不上传、不落盘、不写入 `localStorage` 或 `sessionStorage`。
3. 保留现有格式化和压缩能力。
4. 支持树形展开、折叠、搜索、类型识别、路径展示和复制操作。
5. 保持工具中心现有视觉语言、密度和响应式行为。

## 非目标

- 不嵌入外部 `jsonhero.io` 页面。
- 不调用 JSON Hero 创建 API 或任何外部 API。
- 不复制 JSON Hero 开源实现作为运行时代码。
- 不新增 npm 依赖。
- 不新增后端控制面接口、Worker 能力或审计事件。
- 不做 AI Schema 生成、差异分析或大文件索引，这些仍按工具中心原设计后续独立推进。

## 方案选择

采用自研轻量结构浏览器：

- 使用浏览器原生 `JSON.parse` 解析输入。
- 解析成功后生成前端内存中的树形节点。
- 由 React 组件渲染对象、数组和标量节点。
- 搜索、展开状态和选中节点只存在于组件状态。

放弃 iframe 或外部站点跳转，因为这会把用户输入暴露给外部页面，也不符合当前工具中心“本地解析、不上传内容”的边界。放弃直接复用 JSON Hero 代码，因为当前需求范围较小，引入外部 UI 代码会增加许可证、依赖、安全审查和样式隔离成本。

## 界面结构

`JSON Formatter` 从双栏调整为三栏工作台：

1. 左侧：JSON 输入。
2. 中间：JSON 输出，继续显示格式化或压缩结果。
3. 右侧：结构浏览。

桌面宽度下三栏并排。较窄视口下按输入、输出、结构浏览顺序纵向堆叠。三栏都必须使用稳定高度和 `min-width: 0`，避免长 key、长字符串或深层路径撑破布局。

右侧结构浏览包含：

- 顶部摘要：根类型、顶层字段数量或数组长度。
- 搜索输入：匹配 key、JSONPath 和标量预览文本。
- 当前选中路径：显示 JSONPath，例如 `$`, `$.service`, `$.items[0].name`。
- 操作按钮：复制路径、复制值、全部展开、全部折叠。
- 树形区域：按层级展示节点。

## 树形行为

节点类型：

- `object`
- `array`
- `string`
- `number`
- `boolean`
- `null`

对象和数组节点可以展开或折叠。标量节点不可展开。

默认展开策略：

- 解析成功后展开根节点。
- 根节点第一层默认可见。
- 深层节点默认折叠，避免大 JSON 初次渲染过重。

节点显示内容：

- key 或数组下标。
- 类型标签。
- 对象字段数或数组长度。
- 标量值预览。
- 当前选中状态。

长字符串预览截断显示，但复制值必须复制完整 JSON 片段。

## JSONPath 规则

路径从 `$` 开始。

- 对象 key 符合 JavaScript 标识符时使用点号，例如 `$.service.name`。
- 其他对象 key 使用括号和 JSON 字符串转义，例如 `$["service-name"]`。
- 数组使用下标，例如 `$.items[0]`。

复制路径只复制 JSONPath 文本。

复制值复制当前节点对应的 JSON 片段：

- 对象和数组使用两空格格式化。
- 字符串、数字、布尔和 `null` 使用合法 JSON 表达。

## 搜索

搜索在当前已解析 JSON 的节点元数据上本地执行，不发起网络请求。

匹配范围：

- key 或数组下标。
- JSONPath。
- 标量值预览。
- 类型名称。

搜索命中时：

- 展示命中数量。
- 自动展开命中节点的祖先路径。
- 命中节点使用轻量背景标识。

清空搜索后恢复用户当前展开状态，不强制重置树。

## 错误处理

解析失败时：

- 保留现有错误提示。
- 输出栏不覆盖上一次成功结果，除非用户再次成功格式化或压缩。
- 结构浏览显示本地错误空状态，不展示旧树，避免误导用户。

错误信息保持稳定中文，不回显完整输入。

## 安全与隐私

该功能必须满足：

- 不调用 `fetch`。
- 不写入浏览器存储。
- 不把输入内容放进 URL。
- 不产生后端审计或日志。
- 不在测试夹具中加入密钥、Token、Cookie 或生产数据。

剪贴板操作只在用户点击按钮时触发。复制失败时显示本地状态提示，不回退到隐藏输入框或不透明浏览器行为。

## 可访问性

- 工具切换继续使用现有 tab 语义。
- 树形区域使用可访问标签描述结构浏览。
- 展开按钮具备明确的 `aria-expanded`。
- 复制按钮使用图标和文本，避免只有图标导致含义不清。
- 搜索输入有稳定 `aria-label`。
- 错误提示使用 `role="alert"`。
- 复制成功提示使用 `role="status"`。

## 实现边界

预计修改范围：

- `frontend/operator-console/src/features/tool-center/ToolCenterPage.jsx`
- `frontend/operator-console/src/features/tool-center/ToolCenterPage.module.css`
- `frontend/operator-console/src/features/tool-center/tool-center-utils.js`
- `frontend/operator-console/src/features/tool-center/tool-center-utils.test.js`
- `frontend/operator-console/src/features/tool-center/ToolCenterPage.test.jsx`

不修改后端、契约、路由、菜单或工具中心外的页面。

## 测试与验收

单元测试：

- 合法 JSON 能生成结构节点。
- 对象、数组、字符串、数字、布尔和 `null` 类型识别正确。
- JSONPath 对普通 key、特殊 key 和数组下标生成正确。
- 复制值使用合法 JSON。
- 非法 JSON 返回稳定错误。
- 搜索能命中 key、路径和标量值。

组件测试：

- 解析成功后显示结构浏览根节点和类型摘要。
- 可以展开和折叠对象或数组。
- 点击节点后显示当前路径。
- 搜索命中后显示命中数量并展示命中节点。
- 复制路径和复制值按钮调用剪贴板。
- 非法 JSON 时结构浏览显示错误状态。
- 格式化和压缩现有行为不回退。

验证命令：

```powershell
cd frontend/operator-console
npm run check
npm run lint
npm run test -- ToolCenterPage tool-center-utils
```

如修改影响布局，需要补充运行：

```powershell
cd frontend/operator-console
npm run test:e2e -- tests/e2e/operator-console.spec.js
```

## 发布与回滚

发布时随工具中心前端一起启用，不需要功能开关。回滚方式是恢复本次前端改动，保留原有 JSON 输入、格式化输出和压缩按钮。

由于功能只在前端本地运行，不涉及数据迁移、后端部署或 Worker 配置变更。
