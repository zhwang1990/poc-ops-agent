/* global document */
import { expect, test } from "@playwright/test";

test.beforeEach(async ({ page }) => {
  await mockConsoleApi(page);
});

test("登录页在桌面视口中保持安全边界可见", async ({ page }, testInfo) => {
  await page.route("**/auth/session", async (route) => {
    await route.fulfill({
      json: {
        authenticated: false,
        subject: null,
        username: null,
        roles: [],
        authenticationType: "anonymous",
      },
    });
  });

  await page.goto("/");

  await expect(page.getByRole("heading", { name: "用户登录" })).toBeVisible();
  await expect(page.getByLabel("用户名")).toBeVisible();
  await expect(page.getByRole("textbox", { name: "密码", exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "登录" })).toBeVisible();
  await expect(page.getByText("内建身份登录")).toHaveCount(0);
  await expect(page.getByText("身份确认后，权限仍由服务端策略独立判定。")).toHaveCount(0);
  await assertNoHorizontalOverflow(page);
  await attachVisualEvidence(page, testInfo, "login");
});

test("受保护页面导航、层级和禁用态在桌面视口中稳定", async ({ page }, testInfo) => {
  await page.goto("/agent");

  await expect(page.getByRole("heading", { name: "Agent 工作区" })).toBeVisible();
  await expect(page.getByText("提交后由服务端路由")).toBeVisible();
  await expect(page.getByRole("button", { name: "发送任务" })).toBeDisabled();
  await assertNoHorizontalOverflow(page);
  await attachVisualEvidence(page, testInfo, "agent");

  await page.getByRole("link", { name: "Skill 注册中心" }).click();
  await expect(page.getByRole("heading", { name: "Skill 注册中心" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "内置 Skill" })).toBeVisible();
  await expect(page.getByText("node-health-read")).toBeVisible();
  await expect(page.getByText("角色: ops-reader")).toBeVisible();
  await expect(page.getByRole("button", { name: "安装" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "升级" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "卸载" })).toHaveCount(0);
  await assertNoHorizontalOverflow(page);
  await attachVisualEvidence(page, testInfo, "skills");

  await page.goto("/sql");
  await expect(page.getByRole("heading", { name: "SQL 工作台" })).toBeVisible();
  await expect(page.getByLabel("SQL 工作区连接上下文")).toContainText("已连接 · development");
  await expect(page.getByRole("button", { name: "管理连接" })).toBeVisible();
  await expect(page.getByRole("button", { name: "展开工作区" })).toBeVisible();
  await expect(page.getByText("执行边界")).toHaveCount(0);
  await expect(page.getByRole("button", { name: "解释 SQL" })).toBeDisabled();
  await assertNoHorizontalOverflow(page);
  await attachVisualEvidence(page, testInfo, "sql");

  await page.getByRole("link", { name: "帮助" }).click();
  await expect(page.getByRole("heading", { name: "帮助" })).toBeVisible();
  await expect(page.getByRole("navigation", { name: "帮助章节目录" })).toBeVisible();
  await expect(page.getByRole("searchbox", { name: "搜索场景、页面、错误或权限问题" })).toBeVisible();
  await expect(page.getByText("用 Agent 排查服务错误")).toBeVisible();
  await expect(page.getByRole("button", { name: "提交 RAG 问题" })).toHaveCount(0);
  await assertNoHorizontalOverflow(page);
  await attachVisualEvidence(page, testInfo, "help");
});

test("发布中心页面在桌面视口中展示非生产发布配置", async ({ page }, testInfo) => {
  await page.goto("/release");

  await expect(page.getByRole("link", { name: "发布中心" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "发布中心" })).toBeVisible();
  const tabs = page.getByRole("tablist", { name: "发布中心配置" });
  for (const label of ["发布单", "制品", "应用", "服务器", "策略", "凭据"]) {
    await expect(tabs.getByRole("tab", { name: label })).toBeVisible();
  }
  await expect(page.getByText("orders", { exact: true })).toBeVisible();
  await expect(page.getByText("node-1", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "上传 WAR" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "新建发布单" })).toBeDisabled();
  await assertNoHorizontalOverflow(page);
  await attachVisualEvidence(page, testInfo, "release");
});

test("SQL 工作台执行受控 SELECT 且 DML 只进入预检", async ({ page }) => {
  await page.goto("/sql");

  await expect(page.getByRole("heading", { name: "SQL 工作台" })).toBeVisible();
  await expect(page.getByRole("button", { name: "执行 SELECT" })).toBeDisabled();
  await page.getByLabel("SQL 文本").fill("SELECT order_id, status FROM ORDERS.ORDERS");

  await page.getByRole("button", { name: "校验" }).click();

  await expect(page.getByText("VALIDATED")).toBeVisible();
  await expect(page.getByText("sha256:readonly").first()).toBeVisible();
  await expect(page.getByRole("button", { name: "执行 SELECT" })).toBeEnabled();

  await page.getByRole("button", { name: "执行 SELECT" }).click();
  await expect(page.getByText("OD-10500")).toBeVisible();
  await expect(page.getByText("result-001").first()).toBeVisible();

  await page.getByLabel("SQL 文本").fill("UPDATE ORDERS.ORDERS SET status = 'X'");
  await page.getByRole("button", { name: "DML 预检" }).click();

  await expect(page.getByText("REJECTED")).toBeVisible();
  await expect(page.getByText("DML execution is not allowed in P1")).toBeVisible();
  await expect(page.getByRole("button", { name: "执行 SELECT" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "分析错误" })).toBeEnabled();
  await expect(page.getByRole("button", { name: "校验" })).toBeEnabled();
  await expect(page.getByRole("button", { name: "DML 预检" })).toBeEnabled();
});

/**
 * @param {import("@playwright/test").Page} page
 */
async function mockConsoleApi(page) {
  await page.route("**/auth/session", async (route) => {
    await route.fulfill({
      json: {
        authenticated: true,
        subject: "alice-id",
        username: "alice",
        roles: ["ROLE_ops-reader"],
        authenticationType: "built-in",
      },
    });
  });

  await page.route("**/internal/routing/skills/search", async (route) => {
    await route.fulfill({
      json: {
        total: 1,
        candidates: [
          {
            skill: registeredSkill,
            releaseSnapshot: {
              skillId: "node-health-read",
              version: "1.1.0",
              stage: "GENERAL_AVAILABLE",
              rolloutPercentage: 100,
              targetContextTags: ["p1", "readonly"],
              reason: "P1 read-only diagnostic baseline",
              updatedAt: "2026-06-14T00:00:00Z",
            },
            score: 96,
            matchedRules: ["risk<=READ_ONLY", "publication=VALIDATED"],
          },
        ],
      },
    });
  });

  await page.route("**/internal/skills", async (route) => {
    await route.fulfill({
      json: {
        total: 1,
        skills: [registeredSkill],
      },
    });
  });

  await page.route("**/internal/sql-workbench/connections", async (route) => {
    await route.fulfill({
      json: [
        {
          contractVersion: "1.0",
          connectionId: "as400-development",
          displayName: "AS/400 Development",
          targetEnvironment: "development",
          platformType: "DB2_FOR_I",
          status: "READY",
          defaultSchema: "ORDERS",
          allowedSchemas: ["ORDERS"],
          capabilities: ["VALIDATE", "RUN_READ_ONLY", "PREFLIGHT_DML"],
          maxRowsDefault: 500,
          timeoutSecondsDefault: 30,
        },
        {
          contractVersion: "1.0",
          connectionId: "as400-test",
          displayName: "AS/400 Test",
          targetEnvironment: "test",
          platformType: "DB2_FOR_I",
          status: "READY",
          defaultSchema: "ORDERS",
          allowedSchemas: ["ORDERS"],
          capabilities: ["VALIDATE", "RUN_READ_ONLY", "PREFLIGHT_DML"],
          maxRowsDefault: 500,
          timeoutSecondsDefault: 30,
        },
      ],
    });
  });

  await page.route("**/internal/sql-workbench/queries/validate", async (route) => {
    const request = route.request().postDataJSON();
    expect(request).toMatchObject({
      contractVersion: "1.0",
      connectionId: "as400-development",
      targetEnvironment: "development",
      schema: "ORDERS",
    });

    await route.fulfill({
      json:
        request.action === "PREFLIGHT_DML"
          ? {
              ...validationReport,
              validationLevel: "REJECTED",
              sqlHash: "sha256:dml",
              rejectionReasons: ["DML execution is not allowed in P1"],
              risks: ["WRITE_OPERATION"],
              unverifiedItems: ["Target row count"],
            }
          : validationReport,
    });
  });

  await page.route("**/internal/sql-workbench/queries/run", async (route) => {
    const request = route.request().postDataJSON();
    expect(request).toMatchObject({
      contractVersion: "1.0",
      connectionId: "as400-development",
      targetEnvironment: "development",
      schema: "ORDERS",
      action: "RUN_READ_ONLY",
    });

    await route.fulfill({
      json: {
        contractVersion: "1.0",
        executionRequestId: "exec-001",
        workflowId: "wf-001",
        resultId: "result-001",
        status: "SUCCEEDED",
      },
    });
  });

  await page.route("**/internal/sql-workbench/results/result-001", async (route) => {
    await route.fulfill({
      json: {
        contractVersion: "1.0",
        resultId: "result-001",
        columns: [
          { name: "order_id", type: "VARCHAR", masked: false },
          { name: "status", type: "VARCHAR", masked: false },
        ],
        rows: [["OD-10500", "PENDING"]],
        nextCursor: null,
        truncated: false,
        expiresAt: "2026-06-27T09:10:00Z",
      },
    });
  });

  await page.route("**/internal/release-center/applications", async (route) => {
    await route.fulfill({
      json: releaseApplications,
    });
  });

  await page.route("**/internal/release-center/plans", async (route) => {
    await route.fulfill({
      json: releasePlans,
    });
  });

  await page.route("**/internal/release-center/servers**", async (route) => {
    await route.fulfill({
      json: releaseServers,
    });
  });

  await page.route("**/execution-worker/**", async (route) => {
    await route.fulfill({
      status: 500,
      json: { message: "frontend must not call worker directly" },
    });
  });
}

/**
 * @param {import("@playwright/test").Page} page
 */
async function assertNoHorizontalOverflow(page) {
  const layout = await page.evaluate(() => {
    const root = document.documentElement;
    return {
      clientWidth: root.clientWidth,
      scrollWidth: root.scrollWidth,
    };
  });

  expect(layout.scrollWidth).toBeLessThanOrEqual(layout.clientWidth + 1);
}

/**
 * @param {import("@playwright/test").Page} page
 * @param {import("@playwright/test").TestInfo} testInfo
 * @param {string} name
 */
async function attachVisualEvidence(page, testInfo, name) {
  const screenshot = await page.screenshot({ fullPage: true });
  await testInfo.attach(`${testInfo.project.name}-${name}`, {
    body: screenshot,
    contentType: "image/png",
  });
}

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

const validationReport = {
  contractVersion: "1.0",
  statementType: "SELECT",
  validationLevel: "VALIDATED",
  sqlHash: "sha256:readonly",
  validationHash: "sha256:validation-readonly",
  referencedObjects: ["ORDERS.ORDERS"],
  risks: [],
  rejectionReasons: [],
  unverifiedItems: [],
};

const releaseApplications = [
  {
    applicationId: "orders",
    displayName: "订单服务",
    artifactType: "WAR",
    healthCheckPath: "/health",
    enabled: true,
  },
];

const releaseServers = [
  {
    nodeId: "node-1",
    targetEnvironment: "dev",
    serverType: "TOMCAT",
    managementMode: "TOMCAT_WAR_UPLOAD",
    managementEndpoint: "https://tomcat-dev.example",
    applicationPath: "/orders",
    credentialAlias: "tomcat-dev",
    enabled: true,
  },
];

const releasePlans = [
  {
    releaseId: "rel-1",
    applicationId: "orders",
    targetEnvironment: "dev",
    artifactId: "artifact-1",
    status: "DRAFT",
    parametersHash: "sha256:abc123",
    nodes: [
      {
        nodeId: "node-1",
        serverType: "TOMCAT",
        managementMode: "TOMCAT_WAR_UPLOAD",
        sequence: 1,
        status: "PENDING",
      },
    ],
  },
];
