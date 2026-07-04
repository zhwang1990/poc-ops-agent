/* global document, getComputedStyle */
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

test("1920x1080 下导航图标保持清晰且右侧状态组件不裁切", async ({ page }) => {
  await page.goto("/agent");

  const navIconMetrics = await page.locator("nav[aria-label] a svg").evaluateAll((icons) =>
    icons.map((icon) => {
      const rect = icon.getBoundingClientRect();
      const styles = getComputedStyle(icon);
      return {
        filter: styles.filter,
        height: rect.height,
        strokeWidth: icon.getAttribute("stroke-width"),
        width: rect.width,
      };
    }),
  );

  expect(navIconMetrics.length).toBeGreaterThan(0);
  expect(
    navIconMetrics.filter(
      (icon) =>
        Math.round(icon.width) !== 22 ||
        Math.round(icon.height) !== 22 ||
        icon.filter !== "none" ||
        icon.strokeWidth !== "2",
    ),
  ).toEqual([]);

  const clippedStatusPanels = await page.evaluate(() => {
    const panels = [];

    for (const panel of document.querySelectorAll("main aside > section")) {
      const factList = panel.querySelector("dl");
      const button = panel.querySelector("button");
      const lastFact = factList?.lastElementChild;
      if (!factList || !button || !lastFact) {
        continue;
      }

      const listRect = factList.getBoundingClientRect();
      const lastFactRect = lastFact.getBoundingClientRect();
      const buttonRect = button.getBoundingClientRect();
      const panelState = {
        clippedByList: factList.scrollHeight > factList.clientHeight + 1,
        lastFactBehindButton: lastFactRect.bottom > buttonRect.top + 1,
        lastFactOutsideList: lastFactRect.bottom > listRect.bottom + 1,
        title: panel.querySelector("h3")?.textContent?.trim() ?? "unknown",
      };

      if (
        panelState.clippedByList ||
        panelState.lastFactBehindButton ||
        panelState.lastFactOutsideList
      ) {
        panels.push(panelState);
      }
    }

    return panels;
  });

  expect(clippedStatusPanels).toEqual([]);

  const helpLink = page.locator('nav[aria-label] a[href="/help"]');
  await helpLink.click();
  await page.waitForURL("**/help");
  await expect(helpLink).toHaveAttribute("aria-current", "page");

  const activeHelpLinkState = await helpLink.evaluate((link) => {
    /**
     * @param {string} value
     */
    function parseColorChannels(value) {
      if (/^#[\dA-Fa-f]{6}$/u.test(value)) {
        return [1, 3, 5].map((start) => Number.parseInt(value.slice(start, start + 2), 16));
      }

      return (value.match(/[\d.]+/gu) ?? []).slice(0, 3).map(Number);
    }

    /**
     * @param {string} value
     */
    function normalizeColor(value) {
      const canvas = document.createElement("canvas");
      const context = canvas.getContext("2d");
      if (!context) {
        return value;
      }

      context.fillStyle = value;
      return context.fillStyle;
    }

    const icon = link.querySelector("span");
    const symbol = icon?.querySelector("span");
    if (!icon || !symbol) {
      return {
        ariaCurrent: link.getAttribute("aria-current"),
        iconBackgroundColor: "",
        iconBackgroundColorChannels: [],
        symbolBackgroundColor: "",
        symbolBackgroundColorChannels: [],
      };
    }

    const iconStyles = getComputedStyle(icon);
    const symbolStyles = getComputedStyle(symbol);
    const iconBackgroundColor = normalizeColor(iconStyles.backgroundColor);
    const symbolBackgroundColor = normalizeColor(symbolStyles.backgroundColor);
    return {
      ariaCurrent: link.getAttribute("aria-current"),
      iconBackgroundColor,
      iconBackgroundColorChannels: parseColorChannels(iconBackgroundColor),
      symbolBackgroundColor,
      symbolBackgroundColorChannels: parseColorChannels(symbolBackgroundColor),
    };
  });

  expect(activeHelpLinkState).toMatchObject({
    ariaCurrent: "page",
  });
  expect(activeHelpLinkState.iconBackgroundColor).not.toBe("");
  await expect
    .poll(async () =>
      helpLink.evaluate((link) => {
        /**
         * @param {string} value
         */
        function parseColorChannels(value) {
          const normalized =
            /^#[\dA-Fa-f]{6}$/u.test(value)
              ? value
              : (() => {
                  const canvas = document.createElement("canvas");
                  const context = canvas.getContext("2d");
                  if (!context) {
                    return value;
                  }
                  context.fillStyle = value;
                  return context.fillStyle;
                })();

          if (/^#[\dA-Fa-f]{6}$/u.test(normalized)) {
            return [1, 3, 5].map((start) => Number.parseInt(normalized.slice(start, start + 2), 16));
          }

          return (normalized.match(/[\d.]+/gu) ?? []).slice(0, 3).map(Number);
        }

        const symbol = link.querySelector("span span");
        if (!symbol) {
          return false;
        }

        const channels = parseColorChannels(getComputedStyle(symbol).backgroundColor);
        return channels.length === 3 && channels.every((channel) => channel >= 245);
      }),
    )
    .toBe(true);
});

test("发布中心页面在桌面视口中展示非生产发布配置", async ({ page }, testInfo) => {
  await page.goto("/release");

  await expect(page.getByRole("link", { name: "发布中心" })).toBeVisible();
  await expect(page.getByRole("heading", { name: "发布中心" })).toBeVisible();
  const tabs = page.getByRole("tablist", { name: "发布中心环境资源" });
  for (const label of ["发布单", "制品", "服务器", "策略"]) {
    await expect(tabs.getByRole("tab", { name: label })).toBeVisible();
  }
  await expect(page.getByRole("complementary", { name: "发布中心全局配置" })).toContainText(
    "应用目录",
  );
  await expect(page.getByRole("complementary", { name: "发布中心全局配置" })).toContainText(
    "凭据别名",
  );
  await expect(page.getByText("订单服务 / dev")).toBeVisible();
  await expect(page.getByText("artifact-1")).toBeVisible();
  await expect(page.getByRole("button", { name: "上传 WAR" })).toBeEnabled();
  await expect(page.getByRole("button", { name: "新建发布单" })).toBeEnabled();
  await tabs.getByRole("tab", { name: "服务器" }).click();
  await expect(page.getByText("node-1", { exact: true })).toBeVisible();
  await expect(page.getByRole("button", { name: "Add release server" })).toBeVisible();
  await assertNoHorizontalOverflow(page);
  await attachVisualEvidence(page, testInfo, "release");
});

test("tool center exposes local tools and disabled API execution", async ({ page }, testInfo) => {
  await page.goto("/overview");

  await page.locator('a[href="/tools"]').first().click();
  await page.waitForURL("**/tools");

  await expect(page.getByRole("tab", { name: "JSON Formatter" })).toBeVisible();
  await expect(page.getByRole("tab", { exact: true, name: "API Caller" })).toBeVisible();

  await page.getByLabel("JSON 输入").fill('{"orders":[{"id":1}]}');
  await page.getByRole("button", { name: "格式化 JSON" }).click();
  await expect(page.getByLabel("JSON 输出")).toHaveValue(
    '{\n  "orders": [\n    {\n      "id": 1\n    }\n  ]\n}',
  );

  await page.getByRole("tab", { exact: true, name: "API Caller" }).click();
  await expect(page.getByText("https://api.quefork.internal")).toBeVisible();
  await expect(page.getByRole("button", { name: "发送请求" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "AI 生成请求草稿" })).toBeDisabled();
  await page.getByLabel("临时凭据").fill("super-secret-token");
  await expect(page.getByText("super-secret-token")).toHaveCount(0);

  await page.getByRole("tab", { name: "API Caller 设置" }).click();
  await page.getByLabel("允许域名").fill("https://api.quefork.internal");
  await page.getByRole("button", { name: "校验 allowlist 草稿" }).click();
  await expect(page.getByRole("status")).toContainText("allowlist 草稿校验通过");
  await expect(page.getByRole("button", { name: "保存 allowlist 草稿" })).toBeDisabled();
  await assertNoHorizontalOverflow(page);
  await attachVisualEvidence(page, testInfo, "tool-center");
});

test("第三方组件声明可从法律信息入口打开", async ({ page }, testInfo) => {
  await page.goto("/agent");

  await page.getByRole("link", { name: "法律信息" }).click();

  await expect(page.getByRole("heading", { name: "第三方组件声明" })).toBeVisible();
  await expect(page.getByText("前端操作台 14 项")).toBeVisible();
  await expect(page.getByText("后端服务 16 项")).toBeVisible();
  await expect(page.getByText("第 1 / 3 页")).toBeVisible();
  await expect(page.getByRole("region", { name: "第三方组件清单" })).toContainText("React");
  const componentList = page.getByRole("region", { name: "第三方组件清单" });
  const reactRow = componentList.getByRole("row", { exact: true, name: "React 19.2.7" });
  await expect(reactRow.getByRole("link", { name: "React 许可证" })).toHaveAttribute(
    "href",
    "/third-party-licenses/react",
  );

  await page.getByRole("button", { name: "下一页" }).click();
  await expect(page.getByText("第 2 / 3 页")).toBeVisible();
  await expect(componentList.getByRole("row", { name: "Zod 4.4.3" })).toContainText("MIT License");
  await page.getByRole("button", { name: "下一页" }).click();
  await expect(page.getByText("第 3 / 3 页")).toBeVisible();
  await expect(componentList.getByText("后端服务", { exact: true })).toHaveCount(10);
  await expect(componentList.getByRole("row", { name: "AgentScope Java 2.0.0-RC4" })).toContainText(
    "Apache License 2.0",
  );
  await expect(componentList.getByRole("row", { name: "JT400 21.0.6" })).toContainText(
    "IBM Public License 1.0",
  );
  await page.getByRole("button", { name: "第 1 页" }).click();

  await reactRow.getByRole("link", { name: "React 许可证" }).click();

  await expect(page.getByRole("region", { name: "React 许可证全文" })).toContainText(
    "Permission is hereby granted, free of charge",
  );
  await expect(page.getByRole("heading", { name: "React 许可证" })).toHaveCount(0);

  await page.getByRole("button", { name: "返回工作区" }).click();
  await expect(page.getByRole("heading", { name: "第三方组件声明" })).toBeVisible();
  await assertNoHorizontalOverflow(page);
  await attachVisualEvidence(page, testInfo, "third-party-licenses");
});

test("SQL 工作台通过行级按钮执行受控 SELECT 且隐藏顶部校验执行入口", async ({ page }) => {
  await page.goto("/sql");

  await expect(page.getByRole("heading", { name: "SQL 工作台" })).toBeVisible();
  await expect(page.getByRole("button", { name: "校验" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "执行 SELECT" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "DML 预检" })).toHaveCount(0);

  await page.getByLabel("SQL 文本").fill("SELECT order_id, status FROM ORDERS.ORDERS");
  await expect(page.getByRole("button", { name: "执行此 SQL" })).toBeEnabled();

  await page.getByRole("button", { name: "执行此 SQL" }).click();
  await expect(page.getByText("OD-10500")).toBeVisible();
  await expect(page.getByText("result-001").first()).toBeVisible();

  await page.getByLabel("SQL 文本").fill("UPDATE ORDERS.ORDERS SET status = 'X'");
  await expect(page.getByRole("button", { name: "执行此 SQL" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "分析错误" })).toBeEnabled();
  await expect(page.getByRole("button", { name: "校验" })).toHaveCount(0);
  await expect(page.getByRole("button", { name: "DML 预检" })).toHaveCount(0);
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
          capabilities: ["VALIDATE", "RUN_READ_ONLY", "PREFLIGHT_DML", "COMMIT_DML"],
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
          capabilities: ["VALIDATE", "RUN_READ_ONLY", "PREFLIGHT_DML", "COMMIT_DML"],
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

  await page.route("**/internal/release-center/script-profiles", async (route) => {
    await route.fulfill({
      json: releaseScriptProfiles,
    });
  });

  await page.route("**/internal/release-center/plans", async (route) => {
    await route.fulfill({
      json: releasePlans,
    });
  });

  await page.route("**/internal/release-center/artifacts**", async (route) => {
    await route.fulfill({
      json: releaseArtifacts,
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

const releaseScriptProfiles = [
  {
    profileId: "liberty-war-deploy",
    displayName: "Liberty WAR deploy",
    executablePath: "C:\\ops\\scripts\\liberty-war-deploy.cmd",
    workingDirectory: "C:\\ops-agent\\work\\release",
    arguments: ["{{param.serverName}}", "{{param.applicationName}}", "{{param.artifactPath}}"],
    successExitCodes: [0],
    timeoutSeconds: 600,
    approved: true,
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

const releaseArtifacts = [
  {
    artifactId: "artifact-1",
    applicationId: "orders",
    targetEnvironment: "dev",
    artifactType: "WAR",
    checksum: "sha256:abc123",
    originalFilename: "orders.war",
    storageKey: "artifact-1.war",
    byteSize: 3,
    uploadedBy: "operator-1",
    sourceType: "OPERATOR_UPLOAD",
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
