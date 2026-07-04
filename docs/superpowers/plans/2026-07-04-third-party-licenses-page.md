# 第三方组件声明页面 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 M09 操作台新增登录后可访问的 `/third-party-licenses` 第三方组件声明页，并通过帮助页和侧边栏底部低权重入口进入。

**Architecture:** 使用前端静态数据文件维护首版声明清单，新增独立 React 页面展示组件列表和许可证正文。路由继续经过 `ProtectedRoute`，不新增后端 API、授权、审批、审计或工作流能力。

**Tech Stack:** React 19、React Router、JavaScript/JSX、JSDoc `checkJs`、CSS Modules、Vitest、React Testing Library、Playwright。

---

## 文件结构

- Create: `frontend/operator-console/src/features/third-party-licenses/third-party-licenses-data.js`
  - 职责：维护运行时分发组件的静态许可证声明数据，以及只读查询函数。
- Create: `frontend/operator-console/src/features/third-party-licenses/third-party-licenses-data.test.js`
  - 职责：验证声明数据字段完整、许可证正文包含必要声明、未确认使用的 queFork 不被硬编码进清单。
- Create: `frontend/operator-console/src/features/third-party-licenses/ThirdPartyLicensesPage.jsx`
  - 职责：展示声明页主体、组件列表、当前组件详情和许可证正文。
- Create: `frontend/operator-console/src/features/third-party-licenses/ThirdPartyLicensesPage.module.css`
  - 职责：声明页布局、列表、详情和长文本阅读样式。
- Create: `frontend/operator-console/src/features/third-party-licenses/ThirdPartyLicensesPage.test.jsx`
  - 职责：验证页面标题、组件数量、列表切换和许可证文本展示。
- Modify: `frontend/operator-console/src/app/router.jsx`
  - 职责：注册受保护路由 `/third-party-licenses`。
- Modify: `frontend/operator-console/src/components/layout/AppShell.jsx`
  - 职责：在侧边栏底部添加低权重“法律信息”入口，不放入主导航。
- Modify: `frontend/operator-console/src/components/layout/AppShell.module.css`
  - 职责：补充底部法律信息入口样式和图标模式样式。
- Modify: `frontend/operator-console/src/app/router.test.jsx`
  - 职责：验证路由、共享 shell、法律信息入口和主导航边界。
- Modify: `frontend/operator-console/src/features/help/help-content.js`
  - 职责：新增“第三方组件声明”帮助章节和链接元数据。
- Modify: `frontend/operator-console/src/features/help/help-content.test.js`
  - 职责：验证帮助章节顺序和搜索关键词。
- Modify: `frontend/operator-console/src/features/help/HelpPage.jsx`
  - 职责：渲染帮助章节的可选页面链接。
- Modify: `frontend/operator-console/src/features/help/HelpPage.module.css`
  - 职责：补充帮助页入口链接样式。
- Modify: `frontend/operator-console/tests/e2e/operator-console.spec.js`
  - 职责：补充浏览器级可访问入口和页面无空白验收。

## Task 1: 静态声明数据

**Files:**
- Create: `frontend/operator-console/src/features/third-party-licenses/third-party-licenses-data.test.js`
- Create: `frontend/operator-console/src/features/third-party-licenses/third-party-licenses-data.js`

- [ ] **Step 1: 写失败测试**

Create `frontend/operator-console/src/features/third-party-licenses/third-party-licenses-data.test.js`:

```js
import { describe, expect, test } from "vitest";

import {
  getThirdPartyLicenseById,
  getThirdPartyLicenseSummary,
  thirdPartyLicenses,
} from "./third-party-licenses-data.js";

describe("third-party license declarations", () => {
  test("keeps confirmed runtime declarations complete", () => {
    expect(thirdPartyLicenses.length).toBeGreaterThanOrEqual(2);

    for (const entry of thirdPartyLicenses) {
      expect(entry.id).toMatch(/^[a-z0-9]+(?:-[a-z0-9]+)*$/u);
      expect(entry.name.length).toBeGreaterThan(0);
      expect(entry.version.length).toBeGreaterThan(0);
      expect(entry.homepageUrl).toMatch(/^https:\/\//u);
      expect(entry.licenseId.length).toBeGreaterThan(0);
      expect(entry.copyright.length).toBeGreaterThan(0);
      expect(entry.usage.length).toBeGreaterThan(0);
      expect(entry.noticeText).toContain(entry.copyright);
      expect(entry.noticeText).toMatch(/permission|Permission|license|License/u);
    }
  });

  test("does not declare queFork before integration is confirmed", () => {
    expect(thirdPartyLicenses.some((entry) => entry.id === "quefork")).toBe(false);
  });

  test("finds declarations by stable id", () => {
    expect(getThirdPartyLicenseById("react")?.name).toBe("React");
    expect(getThirdPartyLicenseById("missing")).toBeNull();
  });

  test("summarizes declaration counts and license families", () => {
    expect(getThirdPartyLicenseSummary()).toEqual({
      totalComponents: thirdPartyLicenses.length,
      runtimeScope: "operator-console-browser-runtime",
      licenseIds: expect.arrayContaining(["MIT"]),
    });
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd frontend/operator-console
npm run test -- src/features/third-party-licenses/third-party-licenses-data.test.js
```

Expected: FAIL，错误为 `Cannot find module './third-party-licenses-data.js'`。

- [ ] **Step 3: 新增最小数据实现**

Create `frontend/operator-console/src/features/third-party-licenses/third-party-licenses-data.js`:

```js
/**
 * @typedef {object} ThirdPartyLicenseEntry
 * @property {string} id
 * @property {string} name
 * @property {string} version
 * @property {string} homepageUrl
 * @property {string} licenseId
 * @property {string} copyright
 * @property {string} usage
 * @property {string} noticeText
 */

const MIT_PERMISSION_TEXT = `Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.`;

/**
 * @param {string} copyright
 * @returns {string}
 */
function mitNotice(copyright) {
  return `MIT License

${copyright}

${MIT_PERMISSION_TEXT}`;
}

/** @type {ThirdPartyLicenseEntry[]} */
export const thirdPartyLicenses = [
  {
    id: "react",
    name: "React",
    version: "19.0.0",
    homepageUrl: "https://github.com/facebook/react",
    licenseId: "MIT",
    copyright: "Copyright (c) Meta Platforms, Inc. and affiliates.",
    usage: "操作台浏览器运行时 UI 渲染。",
    noticeText: mitNotice("Copyright (c) Meta Platforms, Inc. and affiliates."),
  },
  {
    id: "react-router-dom",
    name: "React Router DOM",
    version: "7.17.0",
    homepageUrl: "https://github.com/remix-run/react-router",
    licenseId: "MIT",
    copyright: "Copyright (c) Remix Software Inc.",
    usage: "操作台浏览器路由和受保护页面导航。",
    noticeText: mitNotice("Copyright (c) Remix Software Inc."),
  },
  {
    id: "zod",
    name: "Zod",
    version: "4.4.3",
    homepageUrl: "https://github.com/colinhacks/zod",
    licenseId: "MIT",
    copyright: "Copyright (c) 2020 Colin McDonnell",
    usage: "操作台外部边界数据运行时校验。",
    noticeText: mitNotice("Copyright (c) 2020 Colin McDonnell"),
  },
];

/**
 * @param {string} id
 * @returns {ThirdPartyLicenseEntry | null}
 */
export function getThirdPartyLicenseById(id) {
  return thirdPartyLicenses.find((entry) => entry.id === id) ?? null;
}

export function getThirdPartyLicenseSummary() {
  return {
    totalComponents: thirdPartyLicenses.length,
    runtimeScope: "operator-console-browser-runtime",
    licenseIds: [...new Set(thirdPartyLicenses.map((entry) => entry.licenseId))].sort(),
  };
}
```

- [ ] **Step 4: 运行测试确认通过**

Run:

```powershell
cd frontend/operator-console
npm run test -- src/features/third-party-licenses/third-party-licenses-data.test.js
```

Expected: PASS。

- [ ] **Step 5: 提交本任务**

```powershell
git add frontend/operator-console/src/features/third-party-licenses/third-party-licenses-data.js frontend/operator-console/src/features/third-party-licenses/third-party-licenses-data.test.js
git commit -m "Add third-party license declaration data"
```

## Task 2: 声明页面 UI

**Files:**
- Create: `frontend/operator-console/src/features/third-party-licenses/ThirdPartyLicensesPage.test.jsx`
- Create: `frontend/operator-console/src/features/third-party-licenses/ThirdPartyLicensesPage.jsx`
- Create: `frontend/operator-console/src/features/third-party-licenses/ThirdPartyLicensesPage.module.css`

- [ ] **Step 1: 写失败测试**

Create `frontend/operator-console/src/features/third-party-licenses/ThirdPartyLicensesPage.test.jsx`:

```jsx
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, test } from "vitest";

import { ThirdPartyLicensesPage } from "./ThirdPartyLicensesPage.jsx";

function renderPage() {
  return render(
    <MemoryRouter>
      <ThirdPartyLicensesPage />
    </MemoryRouter>,
  );
}

describe("ThirdPartyLicensesPage", () => {
  test("renders compliance summary and confirmed runtime declarations", () => {
    renderPage();

    expect(screen.getByRole("heading", { name: "第三方组件声明" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "声明范围" })).toBeInTheDocument();
    expect(screen.getByText("operator-console-browser-runtime")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /React/u })).toHaveAttribute("aria-current", "true");
    expect(screen.getByText("Copyright (c) Meta Platforms, Inc. and affiliates.")).toBeInTheDocument();
    expect(screen.queryByText("queFork")).not.toBeInTheDocument();
  });

  test("switches the selected component and license text", async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole("button", { name: /Zod/u }));

    expect(screen.getByRole("button", { name: /Zod/u })).toHaveAttribute("aria-current", "true");
    const noticeRegion = screen.getByRole("region", { name: "许可证正文" });
    expect(within(noticeRegion).getByText(/Copyright \(c\) 2020 Colin McDonnell/u)).toBeInTheDocument();
  });
});
```

- [ ] **Step 2: 运行测试确认失败**

Run:

```powershell
cd frontend/operator-console
npm run test -- src/features/third-party-licenses/ThirdPartyLicensesPage.test.jsx
```

Expected: FAIL，错误为 `Cannot find module './ThirdPartyLicensesPage.jsx'`。

- [ ] **Step 3: 新增页面组件**

Create `frontend/operator-console/src/features/third-party-licenses/ThirdPartyLicensesPage.jsx`:

```jsx
import { FileText, ShieldCheck } from "lucide-react";
import { useState } from "react";

import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import { Badge } from "../../components/primitives/Badge.jsx";
import {
  getThirdPartyLicenseSummary,
  thirdPartyLicenses,
} from "./third-party-licenses-data.js";
import styles from "./ThirdPartyLicensesPage.module.css";

const firstLicenseId = thirdPartyLicenses[0]?.id ?? "";

export function ThirdPartyLicensesPage() {
  const [selectedLicenseId, setSelectedLicenseId] = useState(firstLicenseId);
  const selectedLicense =
    thirdPartyLicenses.find((entry) => entry.id === selectedLicenseId) ?? thirdPartyLicenses[0];
  const summary = getThirdPartyLicenseSummary();

  return (
    <WorkspacePageFrame className={styles.licenseCanvas}>
      <WorkspaceStatusBar title="第三方组件声明" />

      <div className={styles.licenseLayout}>
        <section aria-label="声明范围" className={styles.summaryPanel}>
          <div className={styles.summaryHeader}>
            <span aria-hidden="true" className={styles.summaryIcon}>
              <ShieldCheck size={19} strokeWidth={2.35} />
            </span>
            <div>
              <Badge tone="info">M09 / 合规披露</Badge>
              <h2>第三方组件声明</h2>
              <p>
                本页列出随操作台浏览器运行时分发的第三方开源组件。声明内容仅用于版权和许可证披露，
                不改变平台授权、审批、审计、Worker 隔离或服务端策略边界。
              </p>
            </div>
          </div>
          <dl className={styles.summaryGrid}>
            <div>
              <dt>已声明组件</dt>
              <dd>{summary.totalComponents}</dd>
            </div>
            <div>
              <dt>覆盖范围</dt>
              <dd>{summary.runtimeScope}</dd>
            </div>
            <div>
              <dt>许可证</dt>
              <dd>{summary.licenseIds.join(" / ")}</dd>
            </div>
          </dl>
        </section>

        <aside aria-label="第三方组件列表" className={styles.componentList}>
          <div className={styles.panelHeading}>
            <FileText aria-hidden="true" size={18} strokeWidth={2.3} />
            <h2>组件列表</h2>
          </div>
          <div className={styles.listBody}>
            {thirdPartyLicenses.map((entry) => (
              <button
                aria-current={entry.id === selectedLicense?.id ? "true" : undefined}
                className={styles.componentButton}
                key={entry.id}
                onClick={() => setSelectedLicenseId(entry.id)}
                type="button"
              >
                <span>{entry.name}</span>
                <small>{`${entry.version} · ${entry.licenseId}`}</small>
              </button>
            ))}
          </div>
        </aside>

        {selectedLicense ? <LicenseDetail entry={selectedLicense} /> : null}
      </div>
    </WorkspacePageFrame>
  );
}

/**
 * @param {{ entry: import("./third-party-licenses-data.js").ThirdPartyLicenseEntry }} props
 */
function LicenseDetail({ entry }) {
  return (
    <main aria-label="组件许可证详情" className={styles.detailPanel}>
      <header className={styles.detailHeader}>
        <Badge tone="neutral">{entry.licenseId}</Badge>
        <h2>{entry.name}</h2>
        <p>{entry.usage}</p>
      </header>

      <dl className={styles.detailGrid}>
        <div>
          <dt>版本</dt>
          <dd>{entry.version}</dd>
        </div>
        <div>
          <dt>来源</dt>
          <dd>
            <a href={entry.homepageUrl} rel="noreferrer" target="_blank">
              {entry.homepageUrl}
            </a>
          </dd>
        </div>
        <div>
          <dt>版权声明</dt>
          <dd>{entry.copyright}</dd>
        </div>
      </dl>

      <section aria-label="许可证正文" className={styles.noticePanel}>
        <h3>许可证正文</h3>
        <pre>{entry.noticeText}</pre>
      </section>
    </main>
  );
}
```

- [ ] **Step 4: 新增页面样式**

Create `frontend/operator-console/src/features/third-party-licenses/ThirdPartyLicensesPage.module.css`:

```css
.licenseCanvas {
  height: calc(100vh - 48px);
  min-height: 0;
  grid-template-rows: auto minmax(0, 1fr);
  overflow: auto;
}

.licenseLayout {
  display: grid;
  min-height: 0;
  grid-template-columns: minmax(220px, 300px) minmax(0, 1fr);
  grid-template-rows: auto minmax(0, 1fr);
  gap: 14px;
}

.summaryPanel,
.componentList,
.detailPanel {
  border: 1px solid #d7e4ec;
  border-radius: 14px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.78), rgba(247, 251, 253, 0.52)),
    linear-gradient(90deg, rgba(34, 126, 166, 0.055), transparent 42%, rgba(211, 17, 69, 0.035));
  box-shadow:
    0 12px 24px rgba(31, 41, 51, 0.045),
    inset 0 1px 0 rgba(255, 255, 255, 0.78);
}

.summaryPanel {
  grid-column: 1 / -1;
  display: grid;
  gap: 14px;
  padding: 16px;
}

.summaryHeader {
  display: grid;
  grid-template-columns: 44px minmax(0, 1fr);
  gap: 12px;
  align-items: start;
}

.summaryIcon {
  display: grid;
  width: 44px;
  height: 44px;
  place-items: center;
  border: 1px solid rgba(34, 126, 166, 0.18);
  border-radius: 12px;
  background: rgba(34, 126, 166, 0.09);
  color: #227ea6;
}

.summaryHeader h2,
.panelHeading h2,
.detailHeader h2,
.noticePanel h3 {
  margin: 0;
  color: #16202a;
  font-family: var(--font-display);
  letter-spacing: 0;
}

.summaryHeader h2 {
  margin-top: 8px;
  font-size: 22px;
}

.summaryHeader p,
.detailHeader p {
  max-width: 72ch;
  margin: 8px 0 0;
  color: #6f7c8b;
  font-size: 13px;
  font-weight: 760;
  line-height: 1.48;
}

.summaryGrid,
.detailGrid {
  display: grid;
  gap: 10px;
  margin: 0;
}

.summaryGrid {
  grid-template-columns: repeat(3, minmax(0, 1fr));
}

.summaryGrid div,
.detailGrid div {
  display: grid;
  min-width: 0;
  gap: 4px;
  padding: 10px 12px;
  border: 1px solid rgba(34, 126, 166, 0.13);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.58);
}

.summaryGrid dt,
.detailGrid dt {
  color: #6f7c8b;
  font-size: 11px;
  font-weight: 850;
}

.summaryGrid dd,
.detailGrid dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
  color: #16202a;
  font-size: 13px;
  font-weight: 850;
}

.componentList,
.detailPanel {
  display: grid;
  min-height: 0;
  padding: 14px;
}

.componentList {
  grid-template-rows: auto minmax(0, 1fr);
  gap: 12px;
}

.panelHeading {
  display: flex;
  align-items: center;
  gap: 8px;
  color: #227ea6;
}

.panelHeading h2,
.noticePanel h3 {
  font-size: 15px;
  font-weight: 850;
}

.listBody {
  display: grid;
  align-content: start;
  gap: 8px;
  overflow: auto;
  padding-right: 2px;
}

.componentButton {
  appearance: none;
  display: grid;
  min-width: 0;
  gap: 4px;
  padding: 11px 12px;
  border: 1px solid rgba(34, 126, 166, 0.13);
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.62);
  color: #16202a;
  cursor: pointer;
  font: inherit;
  text-align: left;
}

.componentButton:hover,
.componentButton[aria-current="true"] {
  border-color: rgba(34, 126, 166, 0.32);
  background: rgba(34, 126, 166, 0.08);
}

.componentButton span,
.componentButton small {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.componentButton span {
  font-size: 13px;
  font-weight: 900;
}

.componentButton small {
  color: #6f7c8b;
  font-size: 11px;
  font-weight: 780;
}

.detailPanel {
  grid-template-rows: auto auto minmax(0, 1fr);
  gap: 14px;
}

.detailHeader {
  display: grid;
  align-content: start;
  gap: 8px;
}

.detailHeader h2 {
  font-size: 22px;
}

.detailGrid {
  grid-template-columns: 160px minmax(0, 1fr) minmax(220px, 0.8fr);
}

.detailGrid a {
  color: #227ea6;
  text-decoration: none;
}

.detailGrid a:hover {
  text-decoration: underline;
}

.noticePanel {
  display: grid;
  min-height: 0;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 10px;
}

.noticePanel pre {
  min-height: 0;
  margin: 0;
  overflow: auto;
  padding: 14px;
  border: 1px solid rgba(114, 133, 154, 0.18);
  border-radius: 10px;
  background: rgba(246, 248, 250, 0.82);
  color: #16202a;
  font-family: "SFMono-Regular", Consolas, "Liberation Mono", monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
}

@media (max-width: 1020px) {
  .licenseCanvas {
    height: auto;
    min-height: calc(100vh - 48px);
  }

  .licenseLayout,
  .summaryGrid,
  .detailGrid {
    grid-template-columns: 1fr;
  }
}
```

- [ ] **Step 5: 运行页面测试确认通过**

Run:

```powershell
cd frontend/operator-console
npm run test -- src/features/third-party-licenses/ThirdPartyLicensesPage.test.jsx
```

Expected: PASS。

- [ ] **Step 6: 提交本任务**

```powershell
git add frontend/operator-console/src/features/third-party-licenses/ThirdPartyLicensesPage.jsx frontend/operator-console/src/features/third-party-licenses/ThirdPartyLicensesPage.module.css frontend/operator-console/src/features/third-party-licenses/ThirdPartyLicensesPage.test.jsx
git commit -m "Add third-party licenses page"
```

## Task 3: 路由和侧边栏法律信息入口

**Files:**
- Modify: `frontend/operator-console/src/app/router.test.jsx`
- Modify: `frontend/operator-console/src/app/router.jsx`
- Modify: `frontend/operator-console/src/components/layout/AppShell.jsx`
- Modify: `frontend/operator-console/src/components/layout/AppShell.module.css`

- [ ] **Step 1: 写失败测试**

Modify `frontend/operator-console/src/app/router.test.jsx`:

1. 在共享页面用例列表中加入：

```js
    ["/third-party-licenses", "第三方组件声明"],
```

2. 在 `shows the operator navigation for protected pages` 测试中加入：

```js
    expect(screen.getByRole("link", { name: "法律信息" })).toHaveAttribute(
      "href",
      "/third-party-licenses",
    );
```

3. 新增测试：

```jsx
  it("keeps third-party licenses outside the primary navigation", async () => {
    renderAt("/third-party-licenses");

    expect(await screen.findByRole("heading", { name: "第三方组件声明" })).toBeInTheDocument();
    const navigation = screen.getByRole("navigation", { name: "主导航" });
    expect(within(navigation).queryByRole("link", { name: "法律信息" })).not.toBeInTheDocument();
    expect(screen.getByRole("link", { name: "法律信息" })).toHaveAttribute(
      "href",
      "/third-party-licenses",
    );
  });
```

- [ ] **Step 2: 运行路由测试确认失败**

Run:

```powershell
cd frontend/operator-console
npm run test -- src/app/router.test.jsx
```

Expected: FAIL，`/third-party-licenses` 仍跳转登录或找不到页面，且缺少“法律信息”链接。

- [ ] **Step 3: 注册路由**

Modify `frontend/operator-console/src/app/router.jsx`:

1. Add import:

```js
import { ThirdPartyLicensesPage } from "../features/third-party-licenses/ThirdPartyLicensesPage.jsx";
```

2. 在 `/help` 路由之后加入：

```jsx
      <Route
        element={
          <ProtectedRoute>
            <ThirdPartyLicensesPage />
          </ProtectedRoute>
        }
        path="/third-party-licenses"
      />
```

- [ ] **Step 4: 新增侧边栏底部入口**

Modify `frontend/operator-console/src/components/layout/AppShell.jsx`:

1. Add `Scale` to lucide import list:

```js
  Scale,
```

2. 在 `</nav>` 后、`</aside>` 前加入：

```jsx
          <div className={styles.sidebarFooter}>
            <Link
              aria-label="法律信息"
              className={styles.legalLink}
              title={isIconOnly ? "法律信息" : undefined}
              to="/third-party-licenses"
            >
              <span aria-hidden="true" className={styles.legalIcon}>
                <Scale size={16} strokeWidth={2.3} />
              </span>
              <span aria-hidden={isIconOnly} className={styles.legalLabel} hidden={isIconOnly}>
                法律信息
              </span>
            </Link>
          </div>
```

- [ ] **Step 5: 新增侧边栏入口样式**

Append to `frontend/operator-console/src/components/layout/AppShell.module.css` before `.content`:

```css
.legalLink {
  display: grid;
  min-height: 38px;
  grid-template-columns: 30px minmax(0, 1fr);
  gap: 8px;
  align-items: center;
  padding: 5px 8px;
  border: 1px solid rgba(114, 133, 154, 0.16);
  border-radius: 8px;
  background: rgba(255, 255, 255, 0.48);
  color: #72859a;
  font-size: 12px;
  font-weight: 820;
  text-decoration: none;
}

.legalLink:hover {
  border-color: rgba(37, 132, 169, 0.22);
  background: rgba(255, 255, 255, 0.68);
  color: #2f748a;
}

.legalIcon {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 1px solid rgba(114, 133, 154, 0.16);
  border-radius: 7px;
  background: rgba(114, 133, 154, 0.08);
}

.legalLabel {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.iconOnlyShell .legalLink {
  grid-template-columns: 1fr;
  justify-items: center;
  padding: 5px;
}

.iconOnlyShell .legalLabel {
  display: none;
}
```

- [ ] **Step 6: 运行路由测试确认通过**

Run:

```powershell
cd frontend/operator-console
npm run test -- src/app/router.test.jsx
```

Expected: PASS。

- [ ] **Step 7: 提交本任务**

```powershell
git add frontend/operator-console/src/app/router.jsx frontend/operator-console/src/components/layout/AppShell.jsx frontend/operator-console/src/components/layout/AppShell.module.css frontend/operator-console/src/app/router.test.jsx
git commit -m "Add legal information route entry"
```

## Task 4: 帮助页入口和搜索

**Files:**
- Modify: `frontend/operator-console/src/features/help/help-content.test.js`
- Modify: `frontend/operator-console/src/features/help/help-content.js`
- Modify: `frontend/operator-console/src/features/help/HelpPage.jsx`
- Modify: `frontend/operator-console/src/features/help/HelpPage.module.css`
- Modify: `frontend/operator-console/src/features/help/HelpPage.test.jsx`

- [ ] **Step 1: 写失败测试**

Modify `frontend/operator-console/src/features/help/help-content.test.js`:

1. 在章节顺序中把 `faq` 前加入：

```js
      ["third-party-licenses", "第三方组件声明"],
```

2. 新增测试：

```js
  test("finds third-party license guidance by compliance keywords", () => {
    const openSourceResults = searchHelpContent("开源");
    const mitResults = searchHelpContent("MIT");

    expect(openSourceResults).toEqual(
      expect.arrayContaining([
        expect.objectContaining({
          type: "section",
          sectionId: "third-party-licenses",
          title: "第三方组件声明",
        }),
      ]),
    );
    expect(mitResults.some((result) => result.sectionId === "third-party-licenses")).toBe(true);
    expect(getHelpSectionById("third-party-licenses")?.primaryLink).toEqual({
      label: "查看第三方组件声明",
      to: "/third-party-licenses",
    });
  });
```

Modify `frontend/operator-console/src/features/help/HelpPage.test.jsx` by adding:

```jsx
  it("渲染第三方组件声明帮助入口链接", async () => {
    const user = userEvent.setup();
    renderHelpPage();

    await user.click(await screen.findByRole("button", { name: "第三方组件声明" }));

    expect(screen.getByRole("link", { name: "查看第三方组件声明" })).toHaveAttribute(
      "href",
      "/third-party-licenses",
    );
  });
```

- [ ] **Step 2: 运行帮助测试确认失败**

Run:

```powershell
cd frontend/operator-console
npm run test -- src/features/help/help-content.test.js src/features/help/HelpPage.test.jsx
```

Expected: FAIL，缺少 `third-party-licenses` 章节和 `primaryLink` 渲染。

- [ ] **Step 3: 扩展帮助数据类型和搜索内容**

Modify `frontend/operator-console/src/features/help/help-content.js`:

1. 在 `HelpSection` JSDoc 中加入：

```js
 * @property {{label: string, to: string}} [primaryLink]
```

2. 在 `permissions-security` 和 `faq` 之间加入章节：

```js
  {
    sectionId: "third-party-licenses",
    title: "第三方组件声明",
    module: "M09 / M00 / M11",
    summary: "说明操作台随运行时分发的第三方开源组件、许可证文本和维护边界。",
    roleHints: ["所有操作台用户", "平台管理员", "合规审查人员"],
    relatedPages: ["第三方组件声明", "帮助中心"],
    boundary: "第三方组件声明只用于版权和许可证披露，不提供授权、不改变审批、不作为执行事实源。",
    keywords: ["第三方组件", "开源", "许可证", "MIT", "法律信息", "queFork", "合规"],
    primaryLink: {
      label: "查看第三方组件声明",
      to: "/third-party-licenses",
    },
    scenarios: [
      {
        id: "review-third-party-license-notices",
        title: "查看第三方开源组件声明",
        page: "第三方组件声明",
        roles: ["所有操作台用户", "合规审查人员"],
        whenToUse: "需要核对操作台运行时分发组件的版权声明和许可证文本时使用。",
        prerequisites: ["已登录操作台", "需要查询的组件随操作台运行时分发"],
        steps: ["打开第三方组件声明", "在组件列表中选择组件", "核对版本、来源、版权声明和许可证正文"],
        howToReadResult: ["声明文本用于合规披露", "平台授权和安全边界仍以服务端策略和工作流事实源为准"],
        failureHandling: ["未列出的组件需要先确认是否实际复制、修改或打包分发", "不确定时交由合规或法务审查"],
        safetyNotes: ["声明页不展示密钥或目标系统配置", "声明页不调用 Worker、模型或目标系统"],
        keywords: ["第三方组件", "开源", "许可证", "MIT", "queFork", "法律信息"],
      },
    ],
    faqs: [],
  },
```

3. 在 `searchableText` 的 section 输入数组中加入：

```js
      section.primaryLink?.label,
      section.primaryLink?.to,
```

- [ ] **Step 4: 渲染帮助页链接**

Modify `frontend/operator-console/src/features/help/HelpPage.jsx`:

1. Add import:

```js
import { Link } from "react-router-dom";
```

2. 在 `SectionContent` 的 `sectionHero` 中，`promotedScenario` 后加入：

```jsx
        {section.primaryLink ? (
          <Link className={styles.primaryLink} to={section.primaryLink.to}>
            {section.primaryLink.label}
          </Link>
        ) : null}
```

3. Append to `frontend/operator-console/src/features/help/HelpPage.module.css`:

```css
.primaryLink {
  display: inline-flex;
  width: fit-content;
  min-height: 34px;
  align-items: center;
  justify-content: center;
  padding: 0 12px;
  border: 1px solid rgba(34, 126, 166, 0.22);
  border-radius: 8px;
  background: rgba(34, 126, 166, 0.08);
  color: #227ea6;
  font-size: 13px;
  font-weight: 880;
  text-decoration: none;
}

.primaryLink:hover {
  border-color: rgba(34, 126, 166, 0.36);
  background: rgba(34, 126, 166, 0.12);
}
```

- [ ] **Step 5: 运行帮助测试确认通过**

Run:

```powershell
cd frontend/operator-console
npm run test -- src/features/help/help-content.test.js src/features/help/HelpPage.test.jsx
```

Expected: PASS。

- [ ] **Step 6: 提交本任务**

```powershell
git add frontend/operator-console/src/features/help/help-content.js frontend/operator-console/src/features/help/help-content.test.js frontend/operator-console/src/features/help/HelpPage.jsx frontend/operator-console/src/features/help/HelpPage.module.css frontend/operator-console/src/features/help/HelpPage.test.jsx
git commit -m "Add third-party licenses help entry"
```

## Task 5: 全量验证和浏览器验收

**Files:**
- Modify: `frontend/operator-console/tests/e2e/operator-console.spec.js`

- [ ] **Step 1: 写 E2E 失败测试**

Add to `frontend/operator-console/tests/e2e/operator-console.spec.js`:

```js
test("opens the third-party licenses page from legal information", async ({ page }) => {
  await page.goto("/overview");

  await page.getByRole("link", { name: "法律信息" }).click();

  await expect(page.getByRole("heading", { name: "第三方组件声明" })).toBeVisible();
  await expect(page.getByRole("region", { name: "声明范围" })).toBeVisible();
  await expect(page.getByRole("button", { name: /React/u })).toBeVisible();
  await expect(page.getByText("MIT License")).toBeVisible();
});
```

- [ ] **Step 2: 运行 E2E 测试确认失败或通过**

Run:

```powershell
cd frontend/operator-console
npm run test:e2e -- tests/e2e/operator-console.spec.js
```

Expected before implementation: FAIL。Expected after Tasks 1-4: PASS。

- [ ] **Step 3: 运行静态检查和单元测试**

Run:

```powershell
cd frontend/operator-console
npm run check
npm run lint
npm run test
```

Expected: all PASS。

- [ ] **Step 4: 运行构建**

Run:

```powershell
cd frontend/operator-console
npm run build
```

Expected: PASS，生成 Vite 静态制品。

- [ ] **Step 5: 手工浏览器验收**

Run:

```powershell
cd frontend/operator-console
npm run dev -- --host 127.0.0.1
```

Open: `http://127.0.0.1:5173/third-party-licenses`

Verify:

- 登录后能访问 `/third-party-licenses`。
- 页面标题、声明范围、组件列表和许可证正文可见。
- 侧边栏展开状态下“法律信息”在底部，未进入主导航。
- 侧边栏折叠状态下“法律信息”仍可通过可访问名称进入。
- 长许可证文本可滚动，无文字重叠。

- [ ] **Step 6: 提交验证补充**

```powershell
git add frontend/operator-console/tests/e2e/operator-console.spec.js
git commit -m "Verify third-party licenses page flow"
```

## 自检清单

- Spec 覆盖：本计划覆盖独立页、低权重入口、帮助页入口、静态数据、测试和浏览器验收。
- 模块边界：没有新增后端 API、跨模块契约、授权、审批、审计或工作流能力。
- queFork 边界：数据测试明确禁止在未确认集成前硬编码 queFork 声明。
- 文档语言：除代码、命令、SPDX、许可证原文和技能要求头部外，说明文本使用中文。
- 验证路径：先局部测试，再 `check`、`lint`、全量测试、构建和 E2E。
