# Help Product Manual Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a protected `/help` product manual page with global navigation, structured manual content, page-local search, scenario cards, and Agent-workspace-consistent styling.

**Architecture:** Implement the feature entirely in `frontend/operator-console`. Keep help content in a dedicated data module, expose pure search helpers for unit tests, render the page with `WorkspacePageFrame` and `WorkspaceStatusBar`, then wire the route and AppShell navigation. Do not touch backend, contracts, authorization, workflow, Worker, or RAG code.

**Tech Stack:** React 19, React Router, lucide-react, CSS modules, Vitest, Testing Library, Playwright.

---

## Scope Check

The approved spec describes one frontend subsystem: a protected product manual page. It does not require backend APIs, new contracts, RAG, model calls, role-specific content, or persistence. Keep the implementation focused on the operator console.

## File Structure

- Create: `frontend/operator-console/src/features/help/help-content.js`
  - Owns section, scenario, FAQ, keyword content.
  - Exports pure helpers: `getHelpSectionById`, `searchHelpContent`, `helpSections`, `popularHelpKeywords`.
- Create: `frontend/operator-console/src/features/help/help-content.test.js`
  - Unit tests for content completeness, section lookup, search ordering, and no-hit behavior.
- Create: `frontend/operator-console/src/features/help/HelpPage.jsx`
  - Renders the protected help manual page.
  - Owns active section state, search input state, result click behavior, and layout composition.
- Create: `frontend/operator-console/src/features/help/HelpPage.module.css`
  - Uses existing `WorkspacePageFrame` and `--agent-*` tokens.
  - Defines three-column desktop layout and responsive stacking.
- Create: `frontend/operator-console/src/features/help/HelpPage.test.jsx`
  - Component-level tests for rendering, search, no-hit state, result click, and no AI/RAG affordance.
- Modify: `frontend/operator-console/src/components/layout/AppShell.jsx`
  - Import `CircleHelp`.
  - Add the Help navigation item after `审计记录`.
- Modify: `frontend/operator-console/src/app/router.jsx`
  - Import `HelpPage`.
  - Add protected `/help` route.
- Modify: `frontend/operator-console/src/app/router.test.jsx`
  - Include Help in navigation tests and shared route coverage.
  - Add a protected route assertion for `/help`.
- Modify: `frontend/operator-console/tests/e2e/operator-console.spec.js`
  - Add a desktop Playwright check for the Help page in the protected navigation test.

## Content Contract

`help-content.js` must include exactly these section ids and titles in this order:

```js
[
  ["quick-start", "快速开始"],
  ["agent-workspace", "Agent 工作区"],
  ["rag-question", "RAG 问答"],
  ["sql-workbench", "SQL 工作区"],
  ["model-settings", "模型设置"],
  ["release-center", "发布中心"],
  ["skill-registry", "Skill 注册中心"],
  ["workflow-events", "工作流事件"],
  ["audit-records", "审计记录"],
  ["permissions-security", "权限与安全边界"],
  ["faq", "常见问题"],
]
```

Use these scenario titles at minimum:

```js
[
  "完成一次只读诊断入门",
  "用 Agent 排查服务错误",
  "查看节点健康诊断结果",
  "查看执行链为什么被拒绝",
  "查看带引用的知识说明",
  "查询开发环境数据",
  "校验 SQL 是否只读",
  "执行 DML 影响预检",
  "新增模型供应方",
  "轮换 API Key",
  "在 dev 发布 WAR",
  "查看发布失败后的日志分析",
  "查看 Skill 风险等级和版本",
  "追踪 workflow 事件序列",
  "查看一次操作是谁发起的",
  "解释按钮不可用",
]
```

Every scenario object must include these fields:

```js
{
  id: "stable-kebab-case-id",
  title: "场景标题",
  page: "适用页面",
  roles: ["角色"],
  whenToUse: "使用时机。",
  prerequisites: ["前置条件"],
  steps: ["操作步骤"],
  howToReadResult: ["结果解释"],
  failureHandling: ["失败或拒绝处理"],
  safetyNotes: ["安全边界"],
  keywords: ["搜索词"]
}
```

Every section object must include these fields:

```js
{
  sectionId: "stable-kebab-case-id",
  title: "章节标题",
  module: "Mxx / Mxx",
  summary: "本章摘要。",
  roleHints: ["适用角色"],
  relatedPages: ["相关页面"],
  boundary: "边界说明。",
  keywords: ["搜索词"],
  scenarios: [],
  faqs: []
}
```

---

### Task 1: Help Content And Search Helpers

**Files:**
- Create: `frontend/operator-console/src/features/help/help-content.test.js`
- Create: `frontend/operator-console/src/features/help/help-content.js`

- [ ] **Step 1: Write failing content/search tests**

Create `frontend/operator-console/src/features/help/help-content.test.js`:

```js
import { describe, expect, it } from "vitest";

import {
  getHelpSectionById,
  helpSections,
  popularHelpKeywords,
  searchHelpContent,
} from "./help-content.js";

describe("help manual content", () => {
  it("defines the approved help sections in navigation order", () => {
    expect(helpSections.map((section) => [section.sectionId, section.title])).toEqual([
      ["quick-start", "快速开始"],
      ["agent-workspace", "Agent 工作区"],
      ["rag-question", "RAG 问答"],
      ["sql-workbench", "SQL 工作区"],
      ["model-settings", "模型设置"],
      ["release-center", "发布中心"],
      ["skill-registry", "Skill 注册中心"],
      ["workflow-events", "工作流事件"],
      ["audit-records", "审计记录"],
      ["permissions-security", "权限与安全边界"],
      ["faq", "常见问题"],
    ]);
  });

  it("keeps every section searchable and bounded", () => {
    for (const section of helpSections) {
      expect(section.summary).toEqual(expect.any(String));
      expect(section.boundary).toEqual(expect.any(String));
      expect(section.keywords.length).toBeGreaterThan(0);
      expect(section.roleHints.length).toBeGreaterThan(0);
    }
  });

  it("finds a section by id", () => {
    expect(getHelpSectionById("agent-workspace")?.title).toBe("Agent 工作区");
    expect(getHelpSectionById("missing-section")).toBeNull();
  });

  it("searches scenarios before sections and faqs", () => {
    const results = searchHelpContent("权限拒绝");

    expect(results[0]).toMatchObject({
      type: "scenario",
      sectionId: "permissions-security",
      title: "解释按钮不可用",
    });
    expect(results.some((result) => result.type === "faq")).toBe(true);
  });

  it("searches SQL validation content", () => {
    const results = searchHelpContent("SQL 校验");

    expect(results.some((result) => result.title === "校验 SQL 是否只读")).toBe(true);
    expect(results.every((result) => result.type !== "generated")).toBe(true);
  });

  it("returns an empty list for no-hit queries", () => {
    expect(searchHelpContent("完全不存在的关键词")).toEqual([]);
    expect(popularHelpKeywords).toEqual(expect.arrayContaining(["Agent", "权限拒绝", "SQL 校验", "发布失败"]));
  });
});
```

- [ ] **Step 2: Run the new tests and verify they fail**

Run from `frontend/operator-console`:

```bash
npm run test -- src/features/help/help-content.test.js -t "help manual content"
```

Expected: FAIL because `src/features/help/help-content.js` does not exist.

- [ ] **Step 3: Create the content module and pure search helpers**

Create `frontend/operator-console/src/features/help/help-content.js`.

Implementation requirements:

- Export `popularHelpKeywords` with `["Agent", "权限拒绝", "SQL 校验", "发布失败", "生产不可见", "API Key"]`.
- Export `helpSections` with the exact 11 section ids from the Content Contract.
- Add at least the 16 scenario titles listed in the Content Contract.
- Make scenario content concrete, using the fields defined in the Content Contract.
- Include FAQ items for:
  - “为什么我看不到某个按钮或按钮不可用？”
  - “为什么生产环境不可见？”
  - “为什么帮助页不直接回答问题？”
  - “为什么模型不能直接执行操作？”

Use this helper implementation after defining `helpSections`:

```js
export const popularHelpKeywords = ["Agent", "权限拒绝", "SQL 校验", "发布失败", "生产不可见", "API Key"];

/**
 * @typedef {"section" | "scenario" | "faq"} HelpSearchResultType
 */

/**
 * @typedef {object} HelpSearchResult
 * @property {HelpSearchResultType} type
 * @property {string} sectionId
 * @property {string} sectionTitle
 * @property {string} title
 * @property {string} summary
 * @property {string} anchorId
 * @property {string[]} tags
 */

/**
 * @param {string} sectionId
 */
export function getHelpSectionById(sectionId) {
  return helpSections.find((section) => section.sectionId === sectionId) ?? null;
}

/**
 * @param {unknown[]} parts
 */
function searchableText(parts) {
  return normalizeHelpText(parts.flat(Number.POSITIVE_INFINITY).filter(Boolean).join(" "));
}

/**
 * @param {string} value
 */
function normalizeHelpText(value) {
  return value.trim().toLocaleLowerCase("zh-CN");
}

/**
 * @param {string} query
 * @returns {HelpSearchResult[]}
 */
export function searchHelpContent(query) {
  const normalizedQuery = normalizeHelpText(query);
  if (!normalizedQuery) {
    return [];
  }

  /** @type {HelpSearchResult[]} */
  const scenarioResults = [];
  /** @type {HelpSearchResult[]} */
  const sectionResults = [];
  /** @type {HelpSearchResult[]} */
  const faqResults = [];

  for (const section of helpSections) {
    const sectionText = searchableText([
      section.title,
      section.module,
      section.summary,
      section.boundary,
      section.keywords,
      section.roleHints,
      section.relatedPages,
    ]);
    if (sectionText.includes(normalizedQuery)) {
      sectionResults.push({
        type: "section",
        sectionId: section.sectionId,
        sectionTitle: section.title,
        title: section.title,
        summary: section.summary,
        anchorId: `help-section-${section.sectionId}`,
        tags: section.keywords,
      });
    }

    for (const scenario of section.scenarios) {
      const scenarioText = searchableText([
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
      if (scenarioText.includes(normalizedQuery)) {
        scenarioResults.push({
          type: "scenario",
          sectionId: section.sectionId,
          sectionTitle: section.title,
          title: scenario.title,
          summary: scenario.whenToUse,
          anchorId: `help-scenario-${scenario.id}`,
          tags: scenario.keywords,
        });
      }
    }

    for (const faq of section.faqs) {
      const faqText = searchableText([faq.question, faq.answer, faq.keywords]);
      if (faqText.includes(normalizedQuery)) {
        faqResults.push({
          type: "faq",
          sectionId: section.sectionId,
          sectionTitle: section.title,
          title: faq.question,
          summary: faq.answer,
          anchorId: `help-faq-${faq.id}`,
          tags: faq.keywords,
        });
      }
    }
  }

  return [...scenarioResults, ...sectionResults, ...faqResults];
}
```

- [ ] **Step 4: Run the content tests and verify they pass**

Run from `frontend/operator-console`:

```bash
npm run test -- src/features/help/help-content.test.js -t "help manual content"
```

Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```bash
git add frontend/operator-console/src/features/help/help-content.js frontend/operator-console/src/features/help/help-content.test.js
git commit -m "Add help manual content model"
```

---

### Task 2: Help Page Component And Interaction Tests

**Files:**
- Create: `frontend/operator-console/src/features/help/HelpPage.test.jsx`
- Create: `frontend/operator-console/src/features/help/HelpPage.jsx`
- Create: `frontend/operator-console/src/features/help/HelpPage.module.css`

- [ ] **Step 1: Write failing Help page tests**

Create `frontend/operator-console/src/features/help/HelpPage.test.jsx`:

```jsx
import { http, HttpResponse } from "msw";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";

import { AppProviders } from "../../app/providers.jsx";
import { server } from "../../test/server.js";
import { HelpPage } from "./HelpPage.jsx";

function renderHelpPage() {
  return render(
    <AppProviders Router={MemoryRouter} routerProps={{ initialEntries: ["/help"] }}>
      <HelpPage />
    </AppProviders>,
  );
}

beforeEach(() => {
  server.use(
    http.get("/auth/session", () =>
      HttpResponse.json({
        authenticated: true,
        subject: "operator-1",
        username: "ops.reader",
        roles: ["ROLE_ops-reader"],
        authenticationType: "built-in",
      }),
    ),
  );
});

describe("HelpPage", () => {
  it("renders the product manual workspace", async () => {
    renderHelpPage();

    expect(await screen.findByRole("heading", { name: "帮助" })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "帮助章节目录" })).toBeInTheDocument();
    expect(screen.getByRole("searchbox", { name: "搜索场景、页面、错误或权限问题" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "帮助正文" })).toBeInTheDocument();
    expect(screen.getByRole("complementary", { name: "本章速览" })).toBeInTheDocument();
    expect(screen.getByText("用 Agent 排查服务错误")).toBeInTheDocument();
    expect(screen.getByText("只读诊断，不执行生产写操作。")).toBeInTheDocument();
  });

  it("searches scenarios and opens a result", async () => {
    const user = userEvent.setup();
    renderHelpPage();

    const searchbox = await screen.findByRole("searchbox", {
      name: "搜索场景、页面、错误或权限问题",
    });

    await user.type(searchbox, "权限拒绝");

    const results = screen.getByRole("list", { name: "帮助搜索结果" });
    expect(within(results).getByRole("button", { name: /解释按钮不可用/u })).toBeInTheDocument();

    await user.click(within(results).getByRole("button", { name: /解释按钮不可用/u }));

    expect(screen.getByRole("heading", { name: "权限与安全边界" })).toBeInTheDocument();
    expect(screen.getByRole("article", { name: "解释按钮不可用" })).toBeInTheDocument();
  });

  it("shows a no-hit state without generating an answer", async () => {
    const user = userEvent.setup();
    renderHelpPage();

    await user.type(
      await screen.findByRole("searchbox", { name: "搜索场景、页面、错误或权限问题" }),
      "完全不存在的关键词",
    );

    expect(screen.getByRole("status")).toHaveTextContent("未找到匹配手册内容");
    expect(screen.queryByRole("button", { name: "提交 RAG 问题" })).not.toBeInTheDocument();
    expect(screen.queryByText("正在生成")).not.toBeInTheDocument();
  });
});
```

- [ ] **Step 2: Run Help page tests and verify they fail**

Run from `frontend/operator-console`:

```bash
npm run test -- src/features/help/HelpPage.test.jsx -t "HelpPage"
```

Expected: FAIL because `HelpPage.jsx` does not exist.

- [ ] **Step 3: Create the Help page component**

Create `frontend/operator-console/src/features/help/HelpPage.jsx` with these implementation pieces:

```jsx
import { CircleHelp, Search, ShieldCheck } from "lucide-react";
import { useMemo, useState } from "react";

import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import { Badge } from "../../components/primitives/Badge.jsx";
import {
  getHelpSectionById,
  helpSections,
  popularHelpKeywords,
  searchHelpContent,
} from "./help-content.js";
import styles from "./HelpPage.module.css";

const initialSectionId = helpSections[0]?.sectionId ?? "quick-start";

export function HelpPage() {
  const [activeSectionId, setActiveSectionId] = useState(initialSectionId);
  const [query, setQuery] = useState("");
  const activeSection = getHelpSectionById(activeSectionId) ?? helpSections[0];
  const searchResults = useMemo(() => searchHelpContent(query), [query]);
  const hasSearch = query.trim().length > 0;

  /**
   * @param {import("./help-content.js").HelpSearchResult} result
   */
  function openSearchResult(result) {
    setActiveSectionId(result.sectionId);
    setQuery("");
    window.requestAnimationFrame(() => {
      document.getElementById(result.anchorId)?.scrollIntoView({ block: "start" });
    });
  }

  return (
    <WorkspacePageFrame className={styles.helpCanvas}>
      <WorkspaceStatusBar title="帮助" />

      <section aria-label="帮助产品手册" className={styles.helpLayout}>
        <nav aria-label="帮助章节目录" className={styles.sectionNav}>
          <div className={styles.navHeader}>
            <CircleHelp aria-hidden="true" size={17} strokeWidth={2.4} />
            <span>线上产品手册</span>
          </div>
          <div className={styles.navList}>
            {helpSections.map((section) => (
              <button
                aria-current={section.sectionId === activeSection.sectionId ? "page" : undefined}
                className={`${styles.navItem} ${section.sectionId === activeSection.sectionId ? styles.navItemActive : ""}`}
                key={section.sectionId}
                onClick={() => setActiveSectionId(section.sectionId)}
                type="button"
              >
                <strong>{section.title}</strong>
                <span>{section.module}</span>
              </button>
            ))}
          </div>
        </nav>

        <main aria-label="帮助正文" className={styles.manualPanel}>
          <label className={styles.searchBox}>
            <span aria-hidden="true">
              <Search size={16} strokeWidth={2.4} />
            </span>
            <input
              aria-label="搜索场景、页面、错误或权限问题"
              onChange={(event) => setQuery(event.target.value)}
              placeholder="搜索场景、页面、错误或权限问题"
              type="search"
              value={query}
            />
          </label>

          {hasSearch ? (
            <SearchResults
              onOpenResult={openSearchResult}
              query={query}
              results={searchResults}
            />
          ) : (
            <SectionContent section={activeSection} />
          )}
        </main>

        <aside aria-label="本章速览" className={styles.overviewPanel}>
          <h2>
            <ShieldCheck aria-hidden="true" size={17} strokeWidth={2.4} />
            本章速览
          </h2>
          <QuickFact label="适用角色" values={activeSection.roleHints} />
          <QuickFact label="相关页面" values={activeSection.relatedPages} />
          <QuickFact label="相关模块" values={[activeSection.module]} />
          <div className={styles.boundaryBox}>
            <strong>边界</strong>
            <p>{activeSection.boundary}</p>
          </div>
          <div className={styles.keywordCloud}>
            {activeSection.keywords.map((keyword) => (
              <button key={keyword} onClick={() => setQuery(keyword)} type="button">
                {keyword}
              </button>
            ))}
          </div>
        </aside>
      </section>
    </WorkspacePageFrame>
  );
}
```

Continue the file with focused render helpers:

```jsx
function SectionContent({ section }) {
  return (
    <article className={styles.sectionContent} id={`help-section-${section.sectionId}`}>
      <header className={styles.sectionHead}>
        <Badge tone="info">{section.module}</Badge>
        <h2>{section.title}</h2>
        <p>{section.summary}</p>
      </header>
      <div className={styles.scenarioList}>
        {section.scenarios.map((scenario, index) => (
          <ScenarioCard index={index + 1} key={scenario.id} scenario={scenario} />
        ))}
      </div>
      <FaqList faqs={section.faqs} />
    </article>
  );
}

function ScenarioCard({ index, scenario }) {
  return (
    <article
      aria-label={scenario.title}
      className={styles.scenarioCard}
      id={`help-scenario-${scenario.id}`}
    >
      <header>
        <span>{String(index).padStart(2, "0")}</span>
        <div>
          <h3>{scenario.title}</h3>
          <p>{scenario.whenToUse}</p>
        </div>
      </header>
      <HelpList title="操作步骤" values={scenario.steps} />
      <HelpList title="结果怎么看" values={scenario.howToReadResult} />
      <HelpList title="失败或拒绝时怎么办" values={scenario.failureHandling} />
      <HelpList title="安全边界" values={scenario.safetyNotes} tone="safety" />
    </article>
  );
}

function SearchResults({ onOpenResult, query, results }) {
  if (results.length === 0) {
    return (
      <section className={styles.emptyState} role="status">
        <strong>未找到匹配手册内容</strong>
        <p>请更换关键词，或从热门关键词进入对应章节。</p>
        <div>
          {popularHelpKeywords.map((keyword) => (
            <span key={keyword}>{keyword}</span>
          ))}
        </div>
      </section>
    );
  }

  return (
    <section className={styles.searchResults}>
      <h2>搜索结果：{query}</h2>
      <ul aria-label="帮助搜索结果">
        {results.map((result) => (
          <li key={`${result.type}-${result.sectionId}-${result.anchorId}`}>
            <button onClick={() => onOpenResult(result)} type="button">
              <span>{result.sectionTitle}</span>
              <strong>{result.title}</strong>
              <small>{result.summary}</small>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}
```

Add small helpers below those snippets:

```jsx
function HelpList({ title, values, tone = "default" }) {
  return (
    <section className={styles.helpList} data-tone={tone}>
      <h4>{title}</h4>
      <ol>
        {values.map((value) => (
          <li key={value}>{value}</li>
        ))}
      </ol>
    </section>
  );
}

function FaqList({ faqs }) {
  if (faqs.length === 0) {
    return null;
  }
  return (
    <section className={styles.faqList}>
      <h3>常见问题</h3>
      {faqs.map((faq) => (
        <article id={`help-faq-${faq.id}`} key={faq.id}>
          <h4>{faq.question}</h4>
          <p>{faq.answer}</p>
        </article>
      ))}
    </section>
  );
}

function QuickFact({ label, values }) {
  return (
    <section className={styles.quickFact}>
      <h3>{label}</h3>
      <div>
        {values.map((value) => (
          <span key={value}>{value}</span>
        ))}
      </div>
    </section>
  );
}
```

- [ ] **Step 4: Add Help page styles**

Create `frontend/operator-console/src/features/help/HelpPage.module.css` with:

```css
.helpCanvas {
  height: calc(100vh - 48px);
  min-height: 0;
  grid-template-rows: auto minmax(0, 1fr);
  overflow: hidden;
}

.helpLayout {
  display: grid;
  min-height: 0;
  grid-template-columns: 248px minmax(0, 1fr) 292px;
  gap: var(--workspace-layout-gap);
}

.sectionNav,
.manualPanel,
.overviewPanel {
  min-width: 0;
  min-height: 0;
  border: 1px solid rgba(34, 126, 166, 0.16);
  border-radius: 14px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.88), rgba(248, 252, 253, 0.72)),
    linear-gradient(rgba(34, 126, 166, 0.025) 1px, transparent 1px),
    linear-gradient(90deg, rgba(34, 126, 166, 0.025) 1px, transparent 1px);
  background-size: auto, 28px 28px, 28px 28px;
  box-shadow:
    0 14px 34px rgba(31, 41, 51, 0.06),
    inset 0 1px 0 rgba(255, 255, 255, 0.78);
}

.sectionNav,
.overviewPanel {
  display: grid;
  align-content: start;
  gap: 12px;
  overflow: auto;
  padding: 14px;
}

.manualPanel {
  display: grid;
  grid-template-rows: auto minmax(0, 1fr);
  gap: 14px;
  overflow: hidden;
  padding: 16px;
}
```

Continue the file with these class definitions. Keep selectors scoped to `HelpPage.module.css`:

```css
.navHeader {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--agent-ink);
  font-size: 13px;
  font-weight: 750;
}

.navList {
  display: grid;
  gap: 8px;
}

.navItem {
  display: grid;
  gap: 4px;
  width: 100%;
  min-height: 56px;
  padding: 10px 12px;
  border: 1px solid var(--agent-border);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.72);
  color: var(--agent-muted);
  text-align: left;
  cursor: pointer;
  transition:
    border-color 160ms ease,
    background-color 160ms ease,
    color 160ms ease;
}

.navItem strong {
  color: var(--agent-ink);
  font-size: 13px;
}

.navItem span {
  font-size: 11px;
  line-height: 1.35;
}

.navItem:hover,
.navItemActive {
  border-color: rgba(34, 126, 166, 0.36);
  background: rgba(34, 126, 166, 0.08);
  color: var(--agent-blue);
}

.searchBox {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 8px;
  min-height: 42px;
  padding: 0 12px;
  border: 1px solid var(--agent-border);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.86);
  color: var(--agent-muted);
}

.searchBox input {
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--agent-ink);
  font: inherit;
}

.sectionContent,
.searchResults {
  min-height: 0;
  overflow: auto;
  padding-right: 4px;
}

.sectionHead {
  display: grid;
  gap: 8px;
  padding-bottom: 14px;
  border-bottom: 1px solid var(--agent-border);
}

.sectionHead h2 {
  margin: 0;
  color: var(--agent-ink);
  font-size: 22px;
  line-height: 1.2;
}

.sectionHead p {
  margin: 0;
  max-width: 760px;
  color: var(--agent-muted);
  line-height: 1.7;
}

.scenarioList {
  display: grid;
  gap: 12px;
  margin-top: 14px;
}

.scenarioCard {
  display: grid;
  gap: 12px;
  padding: 14px;
  border: 1px solid var(--agent-border);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.78);
}

.scenarioCard header {
  display: grid;
  grid-template-columns: 32px minmax(0, 1fr);
  gap: 10px;
}

.scenarioCard header > span {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 10px;
  background: rgba(34, 126, 166, 0.11);
  color: var(--agent-blue);
  font-size: 12px;
  font-weight: 800;
}

.scenarioCard h3 {
  margin: 0;
  color: var(--agent-ink);
  font-size: 16px;
  line-height: 1.3;
}

.scenarioCard p {
  margin: 4px 0 0;
  color: var(--agent-muted);
  line-height: 1.65;
}

.helpList {
  display: grid;
  gap: 6px;
}

.helpList h4 {
  margin: 0;
  color: var(--agent-ink);
  font-size: 13px;
}

.helpList ol {
  display: grid;
  gap: 6px;
  margin: 0;
  padding-left: 20px;
  color: var(--agent-muted);
  line-height: 1.62;
}

.helpList[data-tone="safety"] {
  padding: 10px 12px;
  border: 1px solid rgba(37, 136, 92, 0.22);
  border-radius: 12px;
  background: rgba(37, 136, 92, 0.06);
}

.faqList {
  display: grid;
  gap: 10px;
  margin-top: 16px;
}

.faqList h3,
.overviewPanel h2 {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  color: var(--agent-ink);
  font-size: 15px;
}

.faqList article {
  padding: 12px;
  border: 1px solid var(--agent-border);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.68);
}

.faqList h4 {
  margin: 0 0 6px;
  color: var(--agent-ink);
  font-size: 14px;
}

.faqList p {
  margin: 0;
  color: var(--agent-muted);
  line-height: 1.65;
}

.searchResults h2 {
  margin: 0 0 12px;
  color: var(--agent-ink);
  font-size: 18px;
}

.searchResults ul {
  display: grid;
  gap: 10px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.searchResults button {
  display: grid;
  gap: 5px;
  width: 100%;
  padding: 12px;
  border: 1px solid var(--agent-border);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.78);
  color: var(--agent-muted);
  text-align: left;
  cursor: pointer;
}

.searchResults button:hover {
  border-color: rgba(34, 126, 166, 0.36);
  background: rgba(34, 126, 166, 0.07);
}

.searchResults button span,
.searchResults button small {
  color: var(--agent-muted);
  font-size: 12px;
}

.searchResults button strong {
  color: var(--agent-ink);
  font-size: 15px;
}

.emptyState {
  display: grid;
  gap: 10px;
  align-content: start;
  min-height: 180px;
  padding: 18px;
  border: 1px dashed rgba(34, 126, 166, 0.32);
  border-radius: 14px;
  background: rgba(34, 126, 166, 0.06);
}

.emptyState strong {
  color: var(--agent-ink);
}

.emptyState p {
  margin: 0;
  color: var(--agent-muted);
}

.emptyState div,
.quickFact div,
.keywordCloud {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.emptyState span,
.quickFact span {
  padding: 5px 8px;
  border-radius: 999px;
  background: rgba(34, 126, 166, 0.08);
  color: var(--agent-blue);
  font-size: 12px;
  font-weight: 650;
}

.quickFact {
  display: grid;
  gap: 8px;
}

.quickFact h3 {
  margin: 0;
  color: var(--agent-ink);
  font-size: 13px;
}

.boundaryBox {
  display: grid;
  gap: 6px;
  padding: 12px;
  border: 1px solid rgba(220, 73, 73, 0.18);
  border-radius: 12px;
  background: rgba(220, 73, 73, 0.05);
}

.boundaryBox strong {
  color: var(--agent-red);
  font-size: 13px;
}

.boundaryBox p {
  margin: 0;
  color: var(--agent-muted);
  line-height: 1.65;
}

.keywordCloud button {
  border: 1px solid rgba(34, 126, 166, 0.2);
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.76);
  color: var(--agent-blue);
  padding: 6px 10px;
  font: inherit;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
}

@media (max-width: 1360px) {
  .helpCanvas {
    height: auto;
    overflow: auto;
  }

  .helpLayout {
    grid-template-columns: minmax(0, 1fr);
  }

  .sectionNav,
  .manualPanel,
  .overviewPanel {
    overflow: visible;
  }
}

@media (prefers-reduced-motion: reduce) {
  .navItem,
  .searchResults button {
    transition: none;
  }
}
```

- [ ] **Step 5: Run Help page tests and verify they pass**

Run from `frontend/operator-console`:

```bash
npm run test -- src/features/help/HelpPage.test.jsx -t "HelpPage"
```

Expected: PASS.

- [ ] **Step 6: Commit Task 2**

```bash
git add frontend/operator-console/src/features/help/HelpPage.jsx frontend/operator-console/src/features/help/HelpPage.module.css frontend/operator-console/src/features/help/HelpPage.test.jsx
git commit -m "Add help product manual page"
```

---

### Task 3: AppShell Navigation And Protected Route

**Files:**
- Modify: `frontend/operator-console/src/components/layout/AppShell.jsx`
- Modify: `frontend/operator-console/src/app/router.jsx`
- Modify: `frontend/operator-console/src/app/router.test.jsx`

- [ ] **Step 1: Write failing route/navigation tests**

In `frontend/operator-console/src/app/router.test.jsx`, update the protected navigation test:

```jsx
expect(screen.getByRole("link", { name: "帮助" })).toHaveAttribute("href", "/help");
```

Add `/help` to the shared route table:

```jsx
["/help", "帮助"],
```

Add this dedicated test inside `describe("operator console routes", () => { ... })`:

```jsx
it("renders the help product manual as a protected route", async () => {
  renderAt("/help");

  expect(screen.getByRole("navigation", { name: "主导航" })).toBeVisible();
  expect(await screen.findByRole("heading", { name: "帮助" })).toBeInTheDocument();
  expect(screen.getByRole("navigation", { name: "帮助章节目录" })).toBeInTheDocument();
  expect(screen.getByRole("searchbox", { name: "搜索场景、页面、错误或权限问题" })).toBeInTheDocument();
  expect(screen.getByText("用 Agent 排查服务错误")).toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "提交 RAG 问题" })).not.toBeInTheDocument();
});
```

- [ ] **Step 2: Run route tests and verify they fail**

Run from `frontend/operator-console`:

```bash
npm run test -- src/app/router.test.jsx -t "help product manual"
```

Expected: FAIL because `/help` is not routed and the navigation item does not exist.

- [ ] **Step 3: Wire AppShell navigation**

In `frontend/operator-console/src/components/layout/AppShell.jsx`, add `CircleHelp` to the lucide import:

```jsx
import {
  AudioLines,
  Bot,
  Boxes,
  CircleDot,
  CircleHelp,
  DatabaseZap,
  FileClock,
  Network,
  Rocket,
  SearchCheck,
  SlidersHorizontal,
  Workflow,
} from "lucide-react";
```

Add the navigation item after `审计记录`:

```jsx
  { icon: FileClock, label: "审计记录", tone: "slate", to: "/audit" },
  { icon: CircleHelp, label: "帮助", tone: "quick", to: "/help" },
```

`navTonequick` already exists in `AppShell.module.css`, so no AppShell CSS change is required.

- [ ] **Step 4: Wire the protected route**

In `frontend/operator-console/src/app/router.jsx`, add the import:

```jsx
import { HelpPage } from "../features/help/HelpPage.jsx";
```

Add the route near the other protected workspace routes:

```jsx
      <Route
        element={
          <ProtectedRoute>
            <HelpPage />
          </ProtectedRoute>
        }
        path="/help"
      />
```

- [ ] **Step 5: Run route tests and verify they pass**

Run from `frontend/operator-console`:

```bash
npm run test -- src/app/router.test.jsx -t "help product manual"
npm run test -- src/app/router.test.jsx -t "shared navigation and status bar"
```

Expected: PASS.

- [ ] **Step 6: Commit Task 3**

```bash
git add frontend/operator-console/src/components/layout/AppShell.jsx frontend/operator-console/src/app/router.jsx frontend/operator-console/src/app/router.test.jsx
git commit -m "Wire help manual route"
```

---

### Task 4: Desktop E2E Coverage

**Files:**
- Modify: `frontend/operator-console/tests/e2e/operator-console.spec.js`

- [ ] **Step 1: Add a failing Playwright assertion**

In the test named `受保护页面导航、层级和禁用态在桌面视口中稳定`, after the SQL assertions and visual evidence, add:

```js
  await page.getByRole("link", { name: "帮助" }).click();
  await expect(page.getByRole("heading", { name: "帮助" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "帮助章节目录" })).toBeVisible();
  await expect(page.getByRole("searchbox", { name: "搜索场景、页面、错误或权限问题" })).toBeVisible();
  await expect(page.getByText("用 Agent 排查服务错误")).toBeVisible();
  await expect(page.getByRole("button", { name: "提交 RAG 问题" })).toHaveCount(0);
  await assertNoHorizontalOverflow(page);
  await attachVisualEvidence(page, testInfo, "help");
```

- [ ] **Step 2: Run the targeted E2E test**

Run from `frontend/operator-console`:

```bash
npm run test:e2e -- tests/e2e/operator-console.spec.js --grep "受保护页面导航"
```

Expected after Tasks 1 to 3: PASS. If it fails due to responsive overflow, adjust only `HelpPage.module.css` grid breakpoints and rerun the same command.

- [ ] **Step 3: Commit Task 4**

```bash
git add frontend/operator-console/tests/e2e/operator-console.spec.js
git commit -m "Cover help manual in browser smoke test"
```

---

### Task 5: Final Verification

**Files:**
- Verify all files changed in Tasks 1 to 4.

- [ ] **Step 1: Run frontend static checks**

Run from `frontend/operator-console`:

```bash
npm run check
npm run lint
```

Expected: both commands PASS.

- [ ] **Step 2: Run frontend unit tests**

Run from `frontend/operator-console`:

```bash
npm run test -- src/features/help/help-content.test.js src/features/help/HelpPage.test.jsx src/app/router.test.jsx
```

Expected: PASS.

- [ ] **Step 3: Run targeted E2E**

Run from `frontend/operator-console`:

```bash
npm run test:e2e -- tests/e2e/operator-console.spec.js --grep "受保护页面导航"
```

Expected: PASS.

- [ ] **Step 4: Inspect git diff**

Run from repository root:

```bash
git diff --stat
git diff -- frontend/operator-console/src/features/help frontend/operator-console/src/components/layout/AppShell.jsx frontend/operator-console/src/app/router.jsx frontend/operator-console/src/app/router.test.jsx frontend/operator-console/tests/e2e/operator-console.spec.js
```

Expected:

- No backend, contract, workflow, policy, Worker, or RAG implementation files changed.
- Help content remains static front-end data.
- No API calls were added for Help.

- [ ] **Step 5: Commit final fixes if verification required changes**

If Step 1, 2, or 3 required small fixes, stage only the touched frontend files:

```bash
git add frontend/operator-console/src/features/help frontend/operator-console/src/components/layout/AppShell.jsx frontend/operator-console/src/app/router.jsx frontend/operator-console/src/app/router.test.jsx frontend/operator-console/tests/e2e/operator-console.spec.js
git commit -m "Stabilize help manual verification"
```

If no fixes were needed after Task 4, do not create an empty commit.

## Self-Review

- Spec coverage: The tasks cover protected `/help`, AppShell navigation, structured static content, page-local search, scenario cards, no-hit state, Agent-workspace visual reuse, accessibility labels, unit tests, route tests, and Playwright desktop coverage.
- Placeholder scan: The plan uses concrete file paths, commands, acceptance checks, and implementation details instead of unresolved placeholders.
- Type consistency: Search result fields are defined in Task 1 and consumed by `HelpPage.jsx` in Task 2 with the same names: `type`, `sectionId`, `sectionTitle`, `title`, `summary`, `anchorId`, and `tags`.
