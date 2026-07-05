# JSON Formatter 结构浏览器 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 M09 工具中心的 `JSON Formatter` 内集成 JSON Hero 风格的本地结构浏览器。

**Architecture:** 在 `tool-center-utils.js` 中新增纯前端 JSON 树构建、JSONPath、搜索和复制值序列化函数，React 页面只负责状态、交互和展示。结构浏览器不新增后端接口、不调用外部服务、不写入浏览器存储，继续沿用工具中心 CSS Modules 和现有按钮组件。

**Tech Stack:** React 19、JavaScript/JSX、JSDoc `checkJs`、CSS Modules、lucide-react、Vitest、React Testing Library。

---

## 文件结构

- Modify: `frontend/operator-console/src/features/tool-center/tool-center-utils.js`
  - 职责：新增 JSON 结构节点构建、JSONPath 生成、节点搜索和复制值格式化的纯函数。
- Modify: `frontend/operator-console/src/features/tool-center/tool-center-utils.test.js`
  - 职责：覆盖对象、数组、特殊 key、标量类型、搜索和错误结果。
- Modify: `frontend/operator-console/src/features/tool-center/ToolCenterPage.jsx`
  - 职责：在 `JsonFormatterPanel` 中渲染三栏工作台和结构浏览器交互。
- Modify: `frontend/operator-console/src/features/tool-center/ToolCenterPage.module.css`
  - 职责：补充三栏布局、树形节点、搜索、类型标签和复制状态样式。
- Modify: `frontend/operator-console/src/features/tool-center/ToolCenterPage.test.jsx`
  - 职责：验证结构浏览器展示、展开折叠、搜索、路径和值复制，以及现有格式化/压缩行为不回退。

## Task 1: JSON 结构浏览纯函数

**Files:**
- Modify: `frontend/operator-console/src/features/tool-center/tool-center-utils.test.js`
- Modify: `frontend/operator-console/src/features/tool-center/tool-center-utils.js`

- [ ] **Step 1: 写失败测试**

Modify `frontend/operator-console/src/features/tool-center/tool-center-utils.test.js` imports:

```js
import {
  createJsonPath,
  deriveRequestOrigin,
  findJsonHeroMatches,
  formatJsonDocument,
  formatJsonHeroNodeValue,
  minifyJsonDocument,
  parseJsonForHeroView,
  previewSecretInput,
  validateAllowlistDraft,
} from "./tool-center-utils.js";
```

Append tests inside `describe("tool center utilities", () => { ... })`:

```js
  test("builds JSON hero nodes with stable paths and type metadata", () => {
    const result = parseJsonForHeroView(
      '{"service":{"name":"queFork","enabled":true},"ports":[8080,null],"release-window":"night"}',
    );

    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.root.path).toBe("$");
    expect(result.root.kind).toBe("object");
    expect(result.root.childCount).toBe(3);
    expect(result.root.children.map((node) => node.path)).toEqual([
      "$.service",
      "$.ports",
      '$["release-window"]',
    ]);
    expect(result.root.children[0].children[0]).toMatchObject({
      key: "name",
      kind: "string",
      path: "$.service.name",
      preview: '"queFork"',
    });
    expect(result.root.children[1].children[1]).toMatchObject({
      key: "1",
      kind: "null",
      path: "$.ports[1]",
      preview: "null",
    });
  });

  test("creates JSONPath segments for identifiers special keys and arrays", () => {
    expect(createJsonPath("$", "service", false)).toBe("$.service");
    expect(createJsonPath("$.service", "display-name", false)).toBe('$.service["display-name"]');
    expect(createJsonPath("$.items", "0", true)).toBe("$.items[0]");
  });

  test("formats selected JSON hero node values as valid JSON", () => {
    const result = parseJsonForHeroView('{"service":{"name":"queFork"},"enabled":true}');

    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    const serviceNode = result.root.children[0];
    const enabledNode = result.root.children[1];

    expect(formatJsonHeroNodeValue(serviceNode)).toBe('{\n  "name": "queFork"\n}');
    expect(formatJsonHeroNodeValue(enabledNode)).toBe("true");
  });

  test("searches JSON hero nodes by key path type and scalar preview", () => {
    const result = parseJsonForHeroView('{"service":{"name":"queFork"},"ports":[8080]}');

    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }

    expect(findJsonHeroMatches(result.root, "name").matchingPaths).toEqual(["$.service.name"]);
    expect(findJsonHeroMatches(result.root, "8080").matchingPaths).toEqual(["$.ports[0]"]);
    expect(findJsonHeroMatches(result.root, "array")).toEqual({
      matchingPaths: ["$.ports"],
      ancestorPaths: ["$"],
    });
  });

  test("returns a stable JSON hero parse error", () => {
    expect(parseJsonForHeroView('{"service":')).toEqual({
      ok: false,
      error: "JSON 解析失败，请检查对象、数组、逗号和引号。",
    });
  });
```

- [ ] **Step 2: 运行失败测试**

Run:

```powershell
cd frontend/operator-console
npm run test -- tool-center-utils
```

Expected: fail with missing exports such as `parseJsonForHeroView`.

- [ ] **Step 3: 实现纯函数**

Modify `frontend/operator-console/src/features/tool-center/tool-center-utils.js` by adding typedefs after the existing typedef block:

```js
/**
 * @typedef {"object" | "array" | "string" | "number" | "boolean" | "null"} JsonHeroNodeKind
 * @typedef {{
 *   key: string,
 *   path: string,
 *   kind: JsonHeroNodeKind,
 *   depth: number,
 *   preview: string,
 *   childCount: number,
 *   value: unknown,
 *   children: JsonHeroNode[],
 * }} JsonHeroNode
 * @typedef {{ok: true, root: JsonHeroNode} | {ok: false, error: string}} JsonHeroParseResult
 */
const jsonIdentifierPattern = /^[A-Za-z_$][\w$]*$/u;
const jsonParseErrorMessage = "JSON 解析失败，请检查对象、数组、逗号和引号。";
```

Add these exports before `formatJsonDocument`:

```js
/**
 * @param {string} source
 * @returns {JsonHeroParseResult}
 */
export function parseJsonForHeroView(source) {
  try {
    return { ok: true, root: createJsonHeroNode(JSON.parse(source), "$", "$", 0, false) };
  } catch {
    return { ok: false, error: jsonParseErrorMessage };
  }
}

/**
 * @param {string} parentPath
 * @param {string} key
 * @param {boolean} arrayItem
 */
export function createJsonPath(parentPath, key, arrayItem) {
  if (arrayItem) {
    return `${parentPath}[${key}]`;
  }
  if (jsonIdentifierPattern.test(key)) {
    return parentPath === "$" ? `$.${key}` : `${parentPath}.${key}`;
  }
  return `${parentPath}[${JSON.stringify(key)}]`;
}

/**
 * @param {JsonHeroNode} node
 */
export function formatJsonHeroNodeValue(node) {
  return JSON.stringify(node.value, null, 2);
}

/**
 * @param {JsonHeroNode} root
 * @param {string} query
 * @returns {{ matchingPaths: string[], ancestorPaths: string[] }}
 */
export function findJsonHeroMatches(root, query) {
  const normalizedQuery = query.trim().toLowerCase();
  if (!normalizedQuery) {
    return { matchingPaths: [], ancestorPaths: [] };
  }

  /** @type {string[]} */
  const matchingPaths = [];
  /** @type {Set<string>} */
  const ancestorPaths = new Set();

  visitJsonHeroNodes(root, (node, ancestors) => {
    const haystack = `${node.key} ${node.path} ${node.kind} ${node.preview}`.toLowerCase();
    if (haystack.includes(normalizedQuery)) {
      matchingPaths.push(node.path);
      for (const ancestor of ancestors) {
        ancestorPaths.add(ancestor.path);
      }
    }
  });

  return { matchingPaths, ancestorPaths: Array.from(ancestorPaths) };
}
```

Add private helpers near `transformJson`:

```js
/**
 * @param {unknown} value
 * @param {string} key
 * @param {string} path
 * @param {number} depth
 * @param {boolean} arrayItem
 * @returns {JsonHeroNode}
 */
function createJsonHeroNode(value, key, path, depth, arrayItem) {
  const kind = getJsonHeroNodeKind(value);
  const entries =
    kind === "object"
      ? Object.entries(/** @type {Record<string, unknown>} */ (value))
      : kind === "array"
        ? /** @type {unknown[]} */ (value).map((item, index) => [String(index), item])
        : [];
  const children = entries.map(([childKey, childValue]) =>
    createJsonHeroNode(childValue, childKey, createJsonPath(path, childKey, kind === "array"), depth + 1, kind === "array"),
  );

  return {
    key,
    path,
    kind,
    depth,
    preview: createJsonHeroPreview(value, kind),
    childCount: children.length,
    value,
    children,
  };
}

/**
 * @param {unknown} value
 * @returns {JsonHeroNodeKind}
 */
function getJsonHeroNodeKind(value) {
  if (value === null) {
    return "null";
  }
  if (Array.isArray(value)) {
    return "array";
  }
  if (typeof value === "object") {
    return "object";
  }
  if (typeof value === "string") {
    return "string";
  }
  if (typeof value === "number") {
    return "number";
  }
  return "boolean";
}

/**
 * @param {unknown} value
 * @param {JsonHeroNodeKind} kind
 */
function createJsonHeroPreview(value, kind) {
  if (kind === "object") {
    return `${Object.keys(/** @type {Record<string, unknown>} */ (value)).length} fields`;
  }
  if (kind === "array") {
    return `${/** @type {unknown[]} */ (value).length} items`;
  }
  const serialized = JSON.stringify(value);
  if (typeof serialized !== "string") {
    return String(value);
  }
  return serialized.length > 80 ? `${serialized.slice(0, 77)}...` : serialized;
}

/**
 * @param {JsonHeroNode} node
 * @param {(node: JsonHeroNode, ancestors: JsonHeroNode[]) => void} visitor
 * @param {JsonHeroNode[]} ancestors
 */
function visitJsonHeroNodes(node, visitor, ancestors = []) {
  visitor(node, ancestors);
  for (const child of node.children) {
    visitJsonHeroNodes(child, visitor, [...ancestors, node]);
  }
}
```

Update `transformJson` catch to reuse the shared error:

```js
  } catch {
    return { ok: false, error: jsonParseErrorMessage };
  }
```

- [ ] **Step 4: 运行单元测试通过**

Run:

```powershell
cd frontend/operator-console
npm run test -- tool-center-utils
```

Expected: `tool-center-utils.test.js` passes.

## Task 2: 结构浏览器组件测试

**Files:**
- Modify: `frontend/operator-console/src/features/tool-center/ToolCenterPage.test.jsx`

- [ ] **Step 1: 写失败组件测试**

Update the existing import from `vitest`:

```js
import { beforeEach, describe, expect, test, vi } from "vitest";
```

Add this setup inside `beforeEach` after `server.use(...)`:

```js
  Object.assign(navigator, {
    clipboard: {
      writeText: vi.fn(() => Promise.resolve()),
    },
  });
```

Append this test inside `describe("ToolCenterPage", () => { ... })` after the existing JSON formatter test:

```js
  test("browses parsed JSON in a local JSON Hero style structure view", async () => {
    const user = userEvent.setup();
    renderToolCenter();

    const input = await screen.findByLabelText("JSON 输入");
    await user.clear(input);
    fireEvent.change(input, {
      target: {
        value: '{"service":{"name":"queFork","enabled":true},"ports":[8080,null],"release-window":"night"}',
      },
    });

    const browser = screen.getByRole("region", { name: "JSON 结构浏览" });
    expect(within(browser).getByText("object")).toBeInTheDocument();
    expect(within(browser).getByText("3 fields")).toBeInTheDocument();
    expect(within(browser).getByRole("button", { name: "service object 2 fields" })).toBeInTheDocument();
    expect(within(browser).getByRole("button", { name: "ports array 2 items" })).toBeInTheDocument();
    expect(within(browser).getByText('$["release-window"]')).toBeInTheDocument();

    await user.click(within(browser).getByRole("button", { name: "service object 2 fields" }));
    expect(within(browser).getByRole("button", { name: "name string \"queFork\"" })).toBeInTheDocument();

    await user.click(within(browser).getByRole("button", { name: "name string \"queFork\"" }));
    expect(within(browser).getByText("$.service.name")).toBeInTheDocument();

    await user.click(within(browser).getByRole("button", { name: "复制路径" }));
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith("$.service.name");

    await user.click(within(browser).getByRole("button", { name: "复制值" }));
    expect(navigator.clipboard.writeText).toHaveBeenCalledWith('"queFork"');
    expect(within(browser).getByRole("status")).toHaveTextContent("已复制值");

    await user.type(within(browser).getByLabelText("搜索 JSON 结构"), "8080");
    expect(within(browser).getByText("1 个命中")).toBeInTheDocument();
    expect(within(browser).getByRole("button", { name: "0 number 8080" })).toBeInTheDocument();
  });
```

Add this invalid JSON test:

```js
  test("shows a local structure browser error for invalid JSON", async () => {
    const user = userEvent.setup();
    renderToolCenter();

    const input = await screen.findByLabelText("JSON 输入");
    await user.clear(input);
    fireEvent.change(input, { target: { value: '{"service":' } });

    const browser = screen.getByRole("region", { name: "JSON 结构浏览" });
    expect(within(browser).getByRole("alert")).toHaveTextContent("JSON 解析失败，请检查对象、数组、逗号和引号。");
    expect(within(browser).queryByRole("button", { name: "复制值" })).not.toBeInTheDocument();
  });
```

- [ ] **Step 2: 运行失败测试**

Run:

```powershell
cd frontend/operator-console
npm run test -- ToolCenterPage
```

Expected: fail because `JSON 结构浏览` region does not exist.

## Task 3: 实现结构浏览器组件和样式

**Files:**
- Modify: `frontend/operator-console/src/features/tool-center/ToolCenterPage.jsx`
- Modify: `frontend/operator-console/src/features/tool-center/ToolCenterPage.module.css`

- [ ] **Step 1: 接入工具函数和图标**

Modify imports in `ToolCenterPage.jsx`:

```js
import {
  Braces,
  Check,
  ChevronRight,
  Copy,
  ListCollapse,
  ListTree,
  Plus,
  Search,
  SendHorizontal,
  Settings2,
  ShieldCheck,
  Trash2,
  WandSparkles,
} from "lucide-react";
import { useEffect, useMemo, useState } from "react";
```

Add imports from `tool-center-utils.js`:

```js
  findJsonHeroMatches,
  formatJsonHeroNodeValue,
  parseJsonForHeroView,
```

- [ ] **Step 2: 扩展 `JsonFormatterPanel` 状态和布局**

Inside `JsonFormatterPanel`, add state:

```js
  const parsedJson = useMemo(() => parseJsonForHeroView(source), [source]);
  const [selectedPath, setSelectedPath] = useState("$");
  const [expandedPaths, setExpandedPaths] = useState(() => new Set(["$"]));
  const [searchQuery, setSearchQuery] = useState("");
  const [copyStatus, setCopyStatus] = useState("");
  const searchResult = useMemo(
    () => (parsedJson.ok ? findJsonHeroMatches(parsedJson.root, searchQuery) : { matchingPaths: [], ancestorPaths: [] }),
    [parsedJson, searchQuery],
  );
```

Add reset effect:

```js
  useEffect(() => {
    setSelectedPath("$");
    setExpandedPaths(new Set(["$"]));
    setCopyStatus("");
  }, [parsedJson.ok ? parsedJson.root.preview : parsedJson.error]);
```

Replace the returned `styles.toolGrid` wrapper with `styles.jsonHeroGrid` and append the browser panel:

```jsx
      <JsonHeroBrowserPanel
        copyStatus={copyStatus}
        expandedPaths={expandedPaths}
        onCopyStatus={setCopyStatus}
        onExpandedPathsChange={setExpandedPaths}
        onSearchQueryChange={setSearchQuery}
        onSelectPath={setSelectedPath}
        parseResult={parsedJson}
        searchQuery={searchQuery}
        searchResult={searchResult}
        selectedPath={selectedPath}
      />
```

- [ ] **Step 3: 新增浏览器组件**

Add these components after `JsonFormatterPanel`:

```jsx
/**
 * @param {{
 *   copyStatus: string,
 *   expandedPaths: Set<string>,
 *   onCopyStatus: (value: string) => void,
 *   onExpandedPathsChange: (value: Set<string>) => void,
 *   onSearchQueryChange: (value: string) => void,
 *   onSelectPath: (value: string) => void,
 *   parseResult: ReturnType<typeof parseJsonForHeroView>,
 *   searchQuery: string,
 *   searchResult: ReturnType<typeof findJsonHeroMatches>,
 *   selectedPath: string,
 * }} props
 */
function JsonHeroBrowserPanel({
  copyStatus,
  expandedPaths,
  onCopyStatus,
  onExpandedPathsChange,
  onSearchQueryChange,
  onSelectPath,
  parseResult,
  searchQuery,
  searchResult,
  selectedPath,
}) {
  const matchingPaths = useMemo(() => new Set(searchResult.matchingPaths), [searchResult.matchingPaths]);
  const visibleExpandedPaths = useMemo(
    () => new Set([...expandedPaths, ...searchResult.ancestorPaths]),
    [expandedPaths, searchResult.ancestorPaths],
  );
  const selectedNode = parseResult.ok ? findJsonHeroNodeByPath(parseResult.root, selectedPath) ?? parseResult.root : null;

  function expandAll() {
    if (!parseResult.ok) {
      return;
    }
    onExpandedPathsChange(new Set(collectExpandableJsonHeroPaths(parseResult.root)));
  }

  function collapseAll() {
    onExpandedPathsChange(new Set(["$"]));
  }

  async function copyPath() {
    if (!selectedNode) {
      return;
    }
    await navigator.clipboard.writeText(selectedNode.path);
    onCopyStatus("已复制路径");
  }

  async function copyValue() {
    if (!selectedNode) {
      return;
    }
    await navigator.clipboard.writeText(formatJsonHeroNodeValue(selectedNode));
    onCopyStatus("已复制值");
  }

  return (
    <aside aria-label="JSON 结构浏览" className={styles.jsonHeroPanel}>
      <PanelTitle icon={ListTree} title="结构浏览" subtitle="本地树形查看，不发送内容。" />
      {!parseResult.ok ? (
        <div className={styles.errorMessage} role="alert">
          {parseResult.error}
        </div>
      ) : (
        <>
          <div className={styles.jsonHeroSummary}>
            <span>{parseResult.root.kind}</span>
            <strong>{parseResult.root.preview}</strong>
          </div>
          <label className={styles.jsonHeroSearch}>
            <Search aria-hidden="true" size={15} />
            <span>搜索 JSON 结构</span>
            <input
              aria-label="搜索 JSON 结构"
              onChange={(event) => onSearchQueryChange(event.target.value)}
              value={searchQuery}
            />
          </label>
          <div className={styles.jsonHeroPathBar}>
            <span>{selectedNode?.path ?? "$"}</span>
            {searchQuery.trim() ? <strong>{searchResult.matchingPaths.length} 个命中</strong> : null}
          </div>
          <div className={styles.jsonHeroActions}>
            <button onClick={copyPath} type="button">
              <Copy aria-hidden="true" size={14} /> 复制路径
            </button>
            <button onClick={copyValue} type="button">
              <Check aria-hidden="true" size={14} /> 复制值
            </button>
            <button onClick={expandAll} type="button">
              <ListTree aria-hidden="true" size={14} /> 全部展开
            </button>
            <button onClick={collapseAll} type="button">
              <ListCollapse aria-hidden="true" size={14} /> 全部折叠
            </button>
          </div>
          {copyStatus ? (
            <div className={styles.successMessage} role="status">
              {copyStatus}
            </div>
          ) : null}
          <div className={styles.jsonHeroTree} role="tree">
            <JsonHeroNodeRow
              expandedPaths={visibleExpandedPaths}
              matchingPaths={matchingPaths}
              node={parseResult.root}
              onExpandedPathsChange={onExpandedPathsChange}
              onSelectPath={onSelectPath}
              selectedPath={selectedPath}
              userExpandedPaths={expandedPaths}
            />
          </div>
        </>
      )}
    </aside>
  );
}
```

Add `JsonHeroNodeRow` and helpers:

```jsx
function JsonHeroNodeRow({
  expandedPaths,
  matchingPaths,
  node,
  onExpandedPathsChange,
  onSelectPath,
  selectedPath,
  userExpandedPaths,
}) {
  const expandable = node.children.length > 0;
  const expanded = expandedPaths.has(node.path);
  const selected = selectedPath === node.path;
  const matched = matchingPaths.has(node.path);

  function toggleExpanded() {
    const next = new Set(userExpandedPaths);
    if (next.has(node.path)) {
      next.delete(node.path);
    } else {
      next.add(node.path);
    }
    onExpandedPathsChange(next);
  }

  return (
    <div role="treeitem" aria-expanded={expandable ? expanded : undefined}>
      <button
        aria-label={`${node.key} ${node.kind} ${node.preview}`}
        className={`${styles.jsonHeroNode} ${selected ? styles.selectedJsonHeroNode : ""} ${
          matched ? styles.matchedJsonHeroNode : ""
        }`}
        onClick={() => onSelectPath(node.path)}
        style={{ "--json-node-depth": node.depth }}
        type="button"
      >
        <span className={styles.jsonHeroTwisty} onClick={expandable ? toggleExpanded : undefined}>
          {expandable ? <ChevronRight aria-hidden="true" size={14} /> : null}
        </span>
        <span className={styles.jsonHeroKey}>{node.key}</span>
        <span className={styles.jsonHeroType}>{node.kind}</span>
        <span className={styles.jsonHeroPreview}>{node.preview}</span>
      </button>
      {expanded
        ? node.children.map((child) => (
            <JsonHeroNodeRow
              expandedPaths={expandedPaths}
              key={child.path}
              matchingPaths={matchingPaths}
              node={child}
              onExpandedPathsChange={onExpandedPathsChange}
              onSelectPath={onSelectPath}
              selectedPath={selectedPath}
              userExpandedPaths={userExpandedPaths}
            />
          ))
        : null}
    </div>
  );
}

function findJsonHeroNodeByPath(node, path) {
  if (node.path === path) {
    return node;
  }
  for (const child of node.children) {
    const result = findJsonHeroNodeByPath(child, path);
    if (result) {
      return result;
    }
  }
  return null;
}

function collectExpandableJsonHeroPaths(node) {
  const paths = node.children.length > 0 ? [node.path] : [];
  for (const child of node.children) {
    paths.push(...collectExpandableJsonHeroPaths(child));
  }
  return paths;
}
```

- [ ] **Step 4: 补充 CSS**

Add CSS near `.toolGrid`:

```css
.jsonHeroGrid {
  display: grid;
  height: 100%;
  min-height: 0;
  grid-template-columns: minmax(260px, 0.95fr) minmax(260px, 0.95fr) minmax(300px, 1.1fr);
  gap: 14px;
  overflow: auto;
  padding: 14px;
}

.jsonHeroGrid .editorPanel {
  grid-template-rows: auto minmax(0, 1fr) auto;
}

.jsonHeroPanel {
  display: grid;
  min-width: 0;
  min-height: 0;
  align-content: start;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--tool-border);
  border-radius: 12px;
  background: oklch(0.991 0.004 236);
}
```

Add tree CSS near related panel styles:

```css
.jsonHeroSummary,
.jsonHeroPathBar,
.jsonHeroActions {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 8px;
}

.jsonHeroSummary {
  justify-content: space-between;
  color: var(--tool-muted);
  font-size: 12px;
  font-weight: 820;
}

.jsonHeroSummary span,
.jsonHeroType {
  border-radius: 999px;
  background: rgba(37, 132, 169, 0.1);
  color: var(--tool-accent);
  padding: 3px 7px;
  font-size: 11px;
  font-weight: 850;
}

.jsonHeroSearch {
  display: grid;
  grid-template-columns: 18px minmax(0, 1fr);
  align-items: center;
  gap: 6px;
  color: var(--tool-muted);
}

.jsonHeroSearch span {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
  white-space: nowrap;
}

.jsonHeroSearch input {
  min-width: 0;
  min-height: 34px;
  padding: 0 10px;
  border: 1px solid var(--tool-border);
  border-radius: 8px;
  background: oklch(0.985 0.006 236);
  color: var(--tool-ink);
  font: inherit;
  font-size: 13px;
}

.jsonHeroPathBar {
  justify-content: space-between;
  padding: 8px 10px;
  border: 1px solid var(--tool-border);
  border-radius: 8px;
  background: oklch(0.985 0.005 236);
  color: var(--tool-muted);
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 12px;
}

.jsonHeroPathBar span {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.jsonHeroPathBar strong {
  flex: 0 0 auto;
  color: var(--tool-accent);
  font-family: var(--agent-font-ui);
}

.jsonHeroActions {
  flex-wrap: wrap;
}

.jsonHeroActions button {
  appearance: none;
  display: inline-flex;
  min-height: 30px;
  align-items: center;
  gap: 5px;
  padding: 0 9px;
  border: 1px solid var(--tool-border);
  border-radius: 8px;
  background: oklch(0.985 0.006 236);
  color: var(--tool-accent);
  cursor: pointer;
  font: inherit;
  font-size: 12px;
  font-weight: 840;
}

.jsonHeroTree {
  min-height: 220px;
  overflow: auto;
  padding: 6px;
  border: 1px solid var(--tool-border);
  border-radius: 8px;
  background: oklch(0.997 0.002 236);
}

.jsonHeroNode {
  appearance: none;
  display: grid;
  width: 100%;
  min-width: 0;
  grid-template-columns: 18px minmax(72px, auto) auto minmax(0, 1fr);
  align-items: center;
  gap: 7px;
  min-height: 30px;
  padding: 0 8px 0 calc(8px + var(--json-node-depth, 0) * 14px);
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--tool-ink);
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.jsonHeroNode:hover,
.selectedJsonHeroNode {
  background: rgba(37, 132, 169, 0.09);
}

.matchedJsonHeroNode {
  background: rgba(255, 196, 87, 0.18);
}

.jsonHeroTwisty {
  display: grid;
  width: 18px;
  height: 24px;
  place-items: center;
  color: var(--tool-muted);
}

.jsonHeroKey {
  min-width: 0;
  overflow: hidden;
  color: var(--tool-ink);
  font-size: 12px;
  font-weight: 850;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.jsonHeroPreview {
  min-width: 0;
  overflow: hidden;
  color: var(--tool-muted);
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  text-overflow: ellipsis;
  white-space: nowrap;
}
```

Update media query list:

```css
  .jsonHeroGrid,
```

Expected responsive rule: `grid-template-columns: minmax(0, 1fr);`.

- [ ] **Step 5: 运行组件测试通过**

Run:

```powershell
cd frontend/operator-console
npm run test -- ToolCenterPage
```

Expected: `ToolCenterPage.test.jsx` passes.

## Task 4: 静态检查和回归验证

**Files:**
- Modify only files already touched by Tasks 1-3.

- [ ] **Step 1: 运行类型检查**

Run:

```powershell
cd frontend/operator-console
npm run check
```

Expected: TypeScript `checkJs` passes.

- [ ] **Step 2: 运行 lint**

Run:

```powershell
cd frontend/operator-console
npm run lint
```

Expected: ESLint passes.

- [ ] **Step 3: 运行聚焦测试**

Run:

```powershell
cd frontend/operator-console
npm run test -- ToolCenterPage tool-center-utils
```

Expected: Tool Center focused tests pass.

- [ ] **Step 4: 检查未引入外部调用或存储**

Run:

```powershell
rg -n "jsonhero|fetch\\(|localStorage|sessionStorage|window\\.open|iframe" frontend/operator-console/src/features/tool-center
```

Expected: no JSON Formatter implementation uses external JSON Hero, network calls, iframe, or browser storage. Existing unrelated matches must be inspected and explained.

## 自检

- 规格覆盖：计划覆盖结构浏览、三栏布局、节点类型、JSONPath、搜索、复制、错误状态、安全隐私和测试验收。
- 占位符扫描：计划不包含空泛占位语句或未定义的后续任务。
- 类型一致性：新增纯函数统一使用 `JsonHeroNode`、`JsonHeroParseResult`、`parseJsonForHeroView`、`findJsonHeroMatches` 和 `formatJsonHeroNodeValue`。
