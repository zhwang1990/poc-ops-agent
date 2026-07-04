import { readFileSync } from "node:fs";

import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, test } from "vitest";

import App from "../../app/App.jsx";
import { AppProviders } from "../../app/providers.jsx";
import { server } from "../../test/server.js";

const skillRegistryStyles = readFileSync(
  "src/features/skill-registry/SkillRegistryPage.module.css",
  "utf8",
);
const skillRegistrySource = readFileSync(
  "src/features/skill-registry/SkillRegistryPage.jsx",
  "utf8",
);

function renderSkillRegistry() {
  return render(
    <AppProviders Router={MemoryRouter} routerProps={{ initialEntries: ["/skills"] }}>
      <App />
    </AppProviders>,
  );
}

function useAuthenticatedSession() {
  server.use(
    http.get("/auth/session", () =>
      HttpResponse.json({
        authenticated: true,
        subject: "alice-id",
        username: "alice",
        roles: ["ROLE_ops-reader"],
        authenticationType: "built-in",
      }),
    ),
  );
}

describe("SkillRegistryPage", () => {
  test("aligns registry body cards with the workspace status bar edges", () => {
    const workspaceBodyRule =
      skillRegistryStyles.match(/[.]workspaceBody\s*[{][^}]+[}]/u)?.[0] ?? "";
    const mobileRule =
      skillRegistryStyles.match(/@media \(max-width: 720px\)\s*\{[\s\S]*?\.workspaceBody\s*\{[^}]+\}/u)?.[0] ??
      "";

    expect(workspaceBodyRule).toContain("padding: 0 0 24px");
    expect(workspaceBodyRule).toContain("gap: var(--workspace-layout-gap)");
    expect(workspaceBodyRule).not.toContain("gap: 18px");
    expect(workspaceBodyRule).not.toContain("padding: 20px 0 24px");
    expect(workspaceBodyRule).not.toContain("padding: 20px 24px 24px");
    expect(mobileRule).toContain("gap: var(--workspace-layout-gap)");
    expect(mobileRule).not.toContain("gap: 14px");
    expect(mobileRule).toContain("padding: 0 0 16px");
  });

  test("stretches the registry result table to the remaining workspace height", () => {
    const registryTableRule =
      Array.from(skillRegistryStyles.matchAll(/[.]registryTable\s*[{][^}]+[}]/gu))
        .map((match) => match[0])
        .find((rule) => rule.includes("grid-template-rows")) ?? "";
    const tableHeaderRule =
      skillRegistryStyles.match(/[.]tableHeader\s*[{][^}]+[}]/u)?.[0] ?? "";
    const registryDataTableRule =
      skillRegistryStyles.match(/[.]registryDataTable\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(registryTableRule).toContain("display: grid");
    expect(registryTableRule).toContain("grid-template-rows: auto minmax(0, 1fr)");
    expect(registryTableRule).toContain("gap: 14px");
    expect(tableHeaderRule).not.toContain("margin-bottom");
    expect(registryDataTableRule).toContain("height: 100%");
    expect(registryDataTableRule).toContain("min-height: 0");
    expect(skillRegistrySource).toContain("className={styles.registryDataTable}");
  });

  test("uses red only for search and renders view as an icon hyperlink action", () => {
    expect(skillRegistryStyles).toContain(".skillSearch button[type=\"submit\"]");
    expect(skillRegistryStyles).toContain("color: var(--registry-red)");
    expect(skillRegistryStyles).toContain(".detailLinkButton");
    expect(skillRegistryStyles).toContain("color: var(--registry-blue)");
    expect(skillRegistryStyles).toContain("background: transparent");
    expect(skillRegistryStyles).toContain("text-decoration: underline");
    expect(skillRegistryStyles).not.toContain(".registryTable button:not(.detailLinkButton)");
    expect(skillRegistryStyles).not.toContain(".skillSearch button[aria-pressed=\"true\"]");
    expect(skillRegistrySource).toContain("styles.detailLinkButton");
    expect(skillRegistrySource).toContain("<Eye aria-hidden=\"true\"");
    expect(skillRegistrySource).not.toContain("styles.detailButton");
  });

  test("renders condition and natural-language search as shared search tabs", async () => {
    useAuthenticatedSession();
    server.use(
      http.get("/internal/skills", () =>
        HttpResponse.json({ total: 1, skills: [registeredSkill] }),
      ),
    );

    renderSkillRegistry();

    const filterRegion = await screen.findByRole("region", { name: "Skill 条件匹配" });
    expect(screen.getByRole("tab", { name: "条件" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "自然语言" })).toBeInTheDocument();
    expect(
      within(filterRegion).getByRole("group", { name: "Skill 条件过滤" }),
    ).toBeInTheDocument();
    expect(screen.queryByLabelText("自然语言查询 Skill")).not.toBeInTheDocument();
  });

  test("renders real read-only skills without the page intro block", async () => {
    useAuthenticatedSession();
    server.use(
      http.get("/internal/skills", () =>
        HttpResponse.json({ total: 1, skills: [registeredSkill] }),
      ),
    );

    renderSkillRegistry();

    expect(await screen.findByLabelText("当前工作台")).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "主导航" })).toBeInTheDocument();
    expect(
      screen.queryByRole("navigation", { name: "Skill 注册中心导航" }),
    ).not.toBeInTheDocument();
    const filterRegion = screen.getByRole("region", { name: "Skill 条件匹配" });
    expect(filterRegion.parentElement?.firstElementChild).toBe(filterRegion);
    expect(
      within(filterRegion).queryByRole("heading", { name: "条件匹配" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("查看 P1 只读诊断 Skill 的版本、风险、角色、签名和治理拦截器。"),
    ).not.toBeInTheDocument();
    expect(filterRegion).toBeInTheDocument();
    expect(within(filterRegion).getByRole("group", { name: "Skill 条件过滤" })).toBeInTheDocument();
    expect(within(filterRegion).getByRole("button", { name: "READ_ONLY" })).toBeInTheDocument();
    expect(screen.queryByText("搜索 Skill / Owner")).not.toBeInTheDocument();
    expect(
      screen.getByRole("searchbox", { name: "搜索 Skill ID、描述、Owner、参数或标签" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "搜索" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "内置 Skill" })).toBeInTheDocument();
    const table = await screen.findByRole("table", { name: "内置 Skill 表格" });
    expect(within(table).getByRole("columnheader", { name: "Skill ID" })).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "描述" })).toBeInTheDocument();
    expect(within(table).getByRole("columnheader", { name: "条件匹配" })).toBeInTheDocument();
    expect(within(table).getByText("node-health-read")).toBeInTheDocument();
    expect(within(table).getByText("Reads node health")).toBeInTheDocument();
    expect(screen.getAllByText("READ_ONLY").length).toBeGreaterThan(0);
    expect(screen.getByText(/ops-reader/u)).toBeInTheDocument();
    expect(screen.queryByText("ROLE_ops-reader")).not.toBeInTheDocument();
    expect(screen.queryByText("选中项详情： Node health")).not.toBeInTheDocument();
    expect(screen.queryByText(/Owner: platform-observability/u)).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "上传" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "注册" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "安装" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "升级" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "卸载" })).not.toBeInTheDocument();
  });

  test("opens skill details in a dialog instead of a persistent side panel", async () => {
    const user = userEvent.setup();
    useAuthenticatedSession();
    server.use(
      http.get("/internal/skills", () =>
        HttpResponse.json({ total: 1, skills: [registeredSkill] }),
      ),
    );

    renderSkillRegistry();

    await user.click(await screen.findByRole("button", { name: "查看 Node health 详情" }));

    const dialog = screen.getByRole("dialog", { name: "Node health" });
    expect(dialog).toHaveTextContent("platform-observability");
    expect(dialog).toHaveTextContent("P1 阶段只展示已签名只读 Skill");
  });

  test("paginates the skill catalog through the shared table", async () => {
    const user = userEvent.setup();
    useAuthenticatedSession();
    server.use(
      http.get("/internal/skills", () =>
        HttpResponse.json({
          total: 6,
          skills: Array.from({ length: 6 }, (_, index) => skillFixture(index + 1)),
        }),
      ),
    );

    renderSkillRegistry();

    expect(await screen.findByText("skill-1-read")).toBeInTheDocument();
    expect(screen.queryByText("skill-6-read")).not.toBeInTheDocument();
    expect(screen.getByText("第 1 / 2 页，共 6 条")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "下一页" }));

    expect(screen.queryByText("skill-1-read")).not.toBeInTheDocument();
    expect(screen.getByText("skill-6-read")).toBeInTheDocument();
    expect(screen.getByText("第 2 / 2 页，共 6 条")).toBeInTheDocument();
  });

  test("applies keyword matching only after submitting the search form", async () => {
    const user = userEvent.setup();
    useAuthenticatedSession();
    server.use(
      http.get("/internal/skills", () =>
        HttpResponse.json({
          total: 6,
          skills: Array.from({ length: 6 }, (_, index) => skillFixture(index + 1)),
        }),
      ),
    );

    renderSkillRegistry();

    const searchbox = await screen.findByRole("searchbox", {
      name: "搜索 Skill ID、描述、Owner、参数或标签",
    });
    expect(
      await screen.findByText("6 个匹配项，来源于 M03 已签名发布目录。"),
    ).toBeInTheDocument();
    expect(screen.getByText("skill-1-read")).toBeInTheDocument();

    await user.type(searchbox, "Skill 6");

    expect(screen.getByText("6 个匹配项，来源于 M03 已签名发布目录。")).toBeInTheDocument();
    expect(screen.queryByText("skill-6-read")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "搜索" }));

    expect(screen.getByText("1 个匹配项，来源于 M03 已签名发布目录。")).toBeInTheDocument();
    expect(screen.queryByText("skill-1-read")).not.toBeInTheDocument();
    expect(screen.getByText("skill-6-read")).toBeInTheDocument();
  });

  test("supports natural-language search through the shared search box", async () => {
    const user = userEvent.setup();
    useAuthenticatedSession();
    /** @type {unknown[]} */
    const requests = [];
    server.use(
      http.get("/internal/skills", () =>
        HttpResponse.json({
          total: 2,
          skills: [
            {
              ...registeredSkill,
              descriptor: {
                ...registeredSkill.descriptor,
                skillId: "application-log-summary-read",
                displayName: "应用日志错误摘要",
                description: "读取应用日志错误并生成摘要",
              },
              manifestPath: "application-log-summary/manifest.json",
            },
            {
              ...registeredSkill,
              descriptor: {
                ...registeredSkill.descriptor,
                skillId: "node-health-read",
                displayName: "节点健康检查",
                description: "读取指定节点的 CPU、内存、磁盘和最近心跳状态。",
              },
              manifestPath: "node-health/manifest.json",
            },
          ],
        }),
      ),
      http.post("/internal/routing/skills/search", async ({ request }) => {
        requests.push(await request.json());
        return HttpResponse.json({
          total: 1,
          candidates: [
            {
              skill: registeredSkill,
              releaseSnapshot: releaseSnapshotFor(registeredSkill),
              score: 105,
              matchedRules: ["分类匹配", "风险等级满足约束", "发布状态匹配"],
            },
          ],
        });
      }),
    );

    renderSkillRegistry();

    await screen.findByText("application-log-summary-read");
    await user.click(screen.getByRole("tab", { name: "自然语言" }));
    await user.type(
      screen.getByRole("textbox", { name: "自然语言搜索" }),
      "我想检查节点健康状态",
    );
    await user.click(screen.getByRole("button", { name: "搜索自然语言" }));

    expect(await screen.findByText("候选 Skill")).toBeInTheDocument();
    expect(screen.getByText("候选分 105")).toBeInTheDocument();
    expect(screen.queryByText("application-log-summary-read")).not.toBeInTheDocument();
    expect(screen.getAllByText("node-health-read").length).toBeGreaterThan(0);
    expect(screen.getByText("1 个匹配项，来源于 M03 已签名发布目录。")).toBeInTheDocument();
    expect(requests).toEqual([
      {
        skillId: null,
        category: "INFRASTRUCTURE_DIAGNOSTICS",
        maxRiskLevel: "READ_ONLY",
        requiredParameters: [],
        requiredTags: ["health"],
        requestContextTags: [],
        publicationStatusRequired: "VALIDATED",
      },
    ]);
  });

  test("shows an empty registry without example skills", async () => {
    useAuthenticatedSession();
    server.use(
      http.get("/internal/skills", () => HttpResponse.json({ total: 0, skills: [] })),
    );

    renderSkillRegistry();

    expect(
      await screen.findByRole("status", { name: "没有已注册 Skill" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("example-skill")).not.toBeInTheDocument();
  });

  test("shows the server refusal for a forbidden registry request", async () => {
    useAuthenticatedSession();
    server.use(
      http.get("/internal/skills", () =>
        HttpResponse.json(
          { code: "POLICY_DENIED", message: "当前主体无权读取 Skill 目录。" },
          { status: 403 },
        ),
      ),
    );

    renderSkillRegistry();

    expect(
      await screen.findByRole("alert", { name: "Skill 目录读取被拒绝" }),
    ).toBeInTheDocument();
  });

  test("blocks invalid skill catalog data", async () => {
    useAuthenticatedSession();
    server.use(
      http.get("/internal/skills", () => HttpResponse.json({ total: 1, skills: [] })),
    );

    renderSkillRegistry();

    expect(
      await screen.findByRole("alert", { name: "Skill 目录契约不兼容" }),
    ).toBeInTheDocument();
  });
});

const registeredSkill = {
  descriptor: {
    skillId: "node-health-read",
    version: "1.1.0",
    displayName: "Node health",
    description: "Reads node health",
    category: "INFRASTRUCTURE_DIAGNOSTICS",
    riskLevel: "READ_ONLY",
    executor: "HTTP",
    outputType: "JSON",
    readOnly: true,
    timeoutSeconds: 30,
    owner: "platform-observability",
    requiredRoles: ["ROLE_ops-reader"],
    tags: ["health"],
    interceptors: ["AUTHORIZATION", "AUDIT"],
    parameters: [],
  },
  publication: {
    publishedBy: "platform-observability",
    publishedAt: "2026-06-14T00:00:00Z",
    checksumSha256: "a".repeat(64),
    signatureAlgorithm: "HmacSHA256",
    signature: "signed",
  },
  publicationStatus: "VALIDATED",
  manifestPath: "node-health/manifest.json",
};

/**
 * @param {number} index
 */
function skillFixture(index) {
  return {
    ...registeredSkill,
    descriptor: {
      ...registeredSkill.descriptor,
      skillId: `skill-${index}-read`,
      displayName: `Skill ${index}`,
      description: `Skill ${index} description`,
    },
    manifestPath: `skill-${index}/manifest.json`,
  };
}

/**
 * @param {{descriptor: {skillId: string, version: string}}} skill
 */
function releaseSnapshotFor(skill) {
  return {
    skillId: skill.descriptor.skillId,
    version: skill.descriptor.version,
    stage: "GENERAL_AVAILABLE",
    rolloutPercentage: 100,
    targetContextTags: [],
    reason: "P1 read-only registry search",
    updatedAt: "2026-06-14T00:00:00Z",
  };
}
