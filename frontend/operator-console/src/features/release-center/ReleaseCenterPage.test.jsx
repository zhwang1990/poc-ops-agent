import { readFileSync } from "node:fs";
import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { describe, expect, it, vi } from "vitest";
import { MemoryRouter } from "react-router-dom";

import { server } from "../../test/server.js";
import { ReleaseCenterPage } from "./ReleaseCenterPage.jsx";

const releaseCenterCss = readFileSync("src/features/release-center/ReleaseCenterPage.module.css", "utf8");
const releaseCenterSource = readFileSync("src/features/release-center/ReleaseCenterPage.jsx", "utf8");
const pageToolbarCss = readFileSync("src/components/layout/PageToolbar.module.css", "utf8");

describe("ReleaseCenterPage", () => {
  it("uses the shared Agent-style toolbar surface for release actions", () => {
    const summaryRule = cssRule("summaryBand");
    const summaryActionsRule = cssRule("summaryActions");
    const pageToolbarSurfaceRule =
      pageToolbarCss.match(/[.]surface\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(releaseCenterSource).toContain("PageToolbar.module.css");
    expect(releaseCenterSource).toContain("styles.summaryBand} ${toolbarStyles.surface}");
    expect(pageToolbarSurfaceRule).toContain("border-radius: 14px");
    expect(pageToolbarSurfaceRule).toContain("backdrop-filter: blur(18px)");
    expect(summaryRule).not.toContain("background: var(--release-surface)");
    expect(summaryRule).toContain("grid-template-columns: max-content minmax(0, 1fr)");
    expect(summaryRule).toContain("justify-content: start");
    expect(summaryRule).not.toContain("justify-content: end");
    expect(summaryActionsRule).toContain("justify-self: end");
  });

  it("keeps Liberty script parameter remove buttons compact", () => {
    const rowRule = cssRule("scriptParameterRow");
    const removeButtonRule = cssRule("removeParameterButton");
    const actionsRule = cssRule("dialogActions");

    expect(rowRule).toContain("grid-template-columns: minmax(0, 2fr) minmax(0, 4fr) 88px");
    expect(rowRule).toContain("align-items: end");
    expect(removeButtonRule).toContain("height: 32px");
    expect(removeButtonRule).toContain("align-self: end");
    expect(removeButtonRule).toContain("margin-bottom: 10px");
    expect(removeButtonRule).not.toContain("align-self: stretch");
    expect(removeButtonRule).not.toContain("min-height: 52px");
    expect(actionsRule).toContain("padding-top: 2px");
  });

  it("keeps target environment buttons icon aligned", () => {
    const switchRule = cssRule("environmentSwitch");
    const buttonRule = cssRule("environmentButton");
    const iconRule = cssRule("environmentIcon");

    expect(switchRule).toContain("width: 206px");
    expect(cssRule("environmentSwitch::before")).toBe("");
    expect(buttonRule).toContain("display: inline-flex");
    expect(buttonRule).toContain("align-items: center");
    expect(buttonRule).toContain("gap: 5px");
    expect(iconRule).toContain("flex: 0 0 auto");
    expect(iconRule).toContain("width: 13px");
  });

  it("anchors global config below a flexible release history area", () => {
    const inventoryRule = cssRule("inventoryPanel");
    const historyRule = cssRule("releaseHistoryCard");
    const historyListRule = cssRule("releaseHistoryList");
    const configSectionRule = cssRule("globalConfigSection");
    const paginationRule = cssRule("releaseHistoryPagination");

    expect(inventoryRule).toContain("grid-template-rows: minmax(0, 1fr) auto");
    expect(inventoryRule).not.toContain("grid-template-rows: 320px minmax(0, 1fr)");
    expect(historyRule).toContain("display: flex");
    expect(historyRule).toContain("height: auto");
    expect(historyRule).toContain("min-height: 0");
    expect(historyRule).toContain("flex-direction: column");
    expect(historyRule).toContain("padding-bottom: 6px");
    expect(historyListRule).toContain("flex: 1 1 auto");
    expect(historyListRule).toContain("align-content: start");
    expect(historyListRule).toContain("grid-auto-rows: max-content");
    expect(configSectionRule).toContain("align-self: end");
    expect(paginationRule).toContain("margin-top: auto");
    expect(paginationRule).toContain("grid-template-columns: 32px minmax(0, 1fr) 32px");
  });

  it("renders workspace resource tabs as navigation controls", () => {
    const tabsRule = cssRule("tabs");
    const tabButtonRule = cssRule("tabButton");
    const activeRule = cssRule("tabButtonActive");

    expect(tabsRule).toContain("display: inline-flex");
    expect(tabsRule).toContain("width: max-content");
    expect(tabsRule).toContain("justify-content: flex-start");
    expect(tabsRule).not.toContain("grid-template-columns: repeat(4, minmax(0, 1fr))");
    expect(tabButtonRule).toContain("width: auto");
    expect(tabButtonRule).toContain("min-width: 86px");
    expect(tabButtonRule).toContain("border-radius: 8px");
    expect(activeRule).toContain("background: oklch(0.995 0.004 232)");
    expect(activeRule).toContain("box-shadow:");
  });

  it("renders icons in the target environment switch", async () => {
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([releasePlan])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
    );

    renderReleaseCenter();

    await screen.findByRole("heading", { name: "发布中心" });
    for (const label of ["DEV", "SIT", "UAT"]) {
      const environmentButton = screen.getByRole("button", { name: label });
      expect(environmentButton).toBeInTheDocument();
      expect(environmentButton.querySelector("svg")).toBeInTheDocument();
    }
  });

  it("omits the release workspace summary component", async () => {
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
    );

    renderReleaseCenter();

    const overview = await screen.findByRole("region", { name: "发布中心概览" });
    expect(within(overview).queryByText("M09 / P2")).not.toBeInTheDocument();
    expect(within(overview).queryByRole("heading", { name: "非生产发布工作区" })).not.toBeInTheDocument();
    for (const label of ["DEV", "SIT", "UAT"]) {
      expect(within(overview).getByRole("button", { name: label })).toBeInTheDocument();
    }
  });

  it("omits the empty release history environment summary", async () => {
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
    );

    renderReleaseCenter();

    const sideCard = await screen.findByRole("complementary", { name: "发布中心全局配置" });
    const history = await within(sideCard).findByRole("region", { name: "发布历史" });
    expect(within(history).queryByText("DEV 暂无发布记录")).not.toBeInTheDocument();
    expect(within(history).queryByText(/暂无发布记录/)).not.toBeInTheDocument();
    expect(within(history).getByText("暂无发布历史")).toBeInTheDocument();
  });

  it("renders the release center workspace tabs", async () => {
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([releasePlan])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
    );

    render(
      <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
        <MemoryRouter>
          <ReleaseCenterPage />
        </MemoryRouter>
      </QueryClientProvider>,
    );

    expect(await screen.findByRole("heading", { name: "发布中心" })).toBeInTheDocument();
    const tabs = screen.getByRole("tablist", { name: "发布中心环境资源" });
    for (const label of ["发布单", "制品", "服务器", "策略"]) {
      expect(within(tabs).getByRole("tab", { name: label })).toBeInTheDocument();
    }
    for (const label of ["应用", "发布脚本", "凭据"]) {
      expect(within(tabs).queryByRole("tab", { name: label })).not.toBeInTheDocument();
    }
    const planList = await screen.findByRole("region", { name: "发布单列表" });
    expect(within(planList).getByText("rel-1")).toBeInTheDocument();
    await userEvent.click(screen.getByRole("tab", { name: "服务器" }));
    expect((await screen.findAllByText("node-1")).length).toBeGreaterThan(0);
  });

  it("moves global release resources into the right configuration panel", async () => {
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([releasePlan])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
      http.get("/internal/release-center/script-profiles", () => HttpResponse.json([releaseScriptProfile])),
    );

    renderReleaseCenter();

    const globalPanel = await screen.findByRole("complementary", { name: "发布中心全局配置" });
    expect(within(globalPanel).getByRole("heading", { name: "全局配置" })).toBeInTheDocument();
    expect(within(globalPanel).queryByText("Global catalog")).not.toBeInTheDocument();
    expect(await within(globalPanel).findByText("应用目录")).toBeInTheDocument();
    expect(within(globalPanel).getByText("发布脚本")).toBeInTheDocument();
    expect(within(globalPanel).queryByText("Script profiles")).not.toBeInTheDocument();
    expect(within(globalPanel).getByText("启动脚本")).toBeInTheDocument();
    expect(within(globalPanel).getByText("停止脚本")).toBeInTheDocument();
    expect(within(globalPanel).getByText("凭据别名")).toBeInTheDocument();

    const tabs = screen.getByRole("tablist", { name: "发布中心环境资源" });
    expect(within(tabs).queryByRole("tab", { name: "发布脚本" })).not.toBeInTheDocument();
    expect(within(tabs).queryByRole("tab", { name: "应用" })).not.toBeInTheDocument();
    expect(within(tabs).queryByRole("tab", { name: "凭据" })).not.toBeInTheDocument();

    const scriptProfilesConfigButton = await within(globalPanel).findByRole("button", { name: "配置 发布脚本" });
    expect(scriptProfilesConfigButton.textContent?.trim()).toBe("");
    expect(scriptProfilesConfigButton.querySelector("svg")).toBeInTheDocument();

    await userEvent.click(scriptProfilesConfigButton);
    const dialog = await screen.findByRole("dialog", { name: "配置 发布脚本" });
    expect(within(dialog).getByText("Liberty WAR deploy")).toBeInTheDocument();
    await userEvent.click(within(dialog).getByLabelText("关闭配置 发布脚本"));

    await userEvent.click(within(globalPanel).getByRole("button", { name: "配置 启动脚本" }));
    const startScriptDialog = await screen.findByRole("dialog", { name: "配置 启动脚本" });
    expect(within(startScriptDialog).getByText("Liberty WAR deploy")).toBeInTheDocument();
    expect(within(startScriptDialog).getByText("启动脚本使用全局 Script profile 定义，节点仅引用 profileId 和自身参数。")).toBeInTheDocument();
    await userEvent.click(within(startScriptDialog).getByLabelText("关闭配置 启动脚本"));

    await userEvent.click(within(globalPanel).getByRole("button", { name: "配置 停止脚本" }));
    const stopScriptDialog = await screen.findByRole("dialog", { name: "配置 停止脚本" });
    expect(within(stopScriptDialog).getByText("Liberty WAR deploy")).toBeInTheDocument();
    expect(within(stopScriptDialog).getByText("停止脚本使用全局 Script profile 定义，节点仅引用 profileId 和自身参数。")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("tab", { name: "服务器" }));
    expect((await screen.findAllByText("node-1")).length).toBeGreaterThan(0);
  });

  it("shows full script profile values on hover and copies them", async () => {
    const longProfile = {
      ...releaseScriptProfile,
      profileId: "liberty-mock-sse-20260702-230813",
      displayName: "Liberty mock SSE stream 20260702-230813",
      executablePath: "C:\\Windows\\System32\\WindowsPowerShell\\v1.0\\powershell.exe",
      arguments: [
        "-NoProfile",
        "-ExecutionPolicy",
        "Bypass",
        "-File",
        "C:\\Users\\Lenovo\\.codex\\worktrees\\deb5\\poc-ops-agent\\var\\release-mock\\liberty-mock-sse.ps1",
      ],
    };
    const clipboardWriteText = vi.fn().mockResolvedValue(undefined);
    const clipboardDescriptor = Object.getOwnPropertyDescriptor(navigator, "clipboard");
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: { writeText: clipboardWriteText },
    });
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([releasePlan])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
      http.get("/internal/release-center/script-profiles", () => HttpResponse.json([longProfile])),
    );

    try {
      renderReleaseCenter();

      const globalPanel = await screen.findByRole("complementary", { name: "发布中心全局配置" });
      await userEvent.click(within(globalPanel).getByRole("button", { name: "配置 发布脚本" }));
      const dialog = await screen.findByRole("dialog", { name: "配置 发布脚本" });
      const identityText = `${longProfile.profileId}\n${longProfile.displayName}`;
      const argumentsText = longProfile.arguments.join(" ");

      const identityCopyButton = within(dialog).getByRole("button", {
        name: `Copy script profile identity for ${longProfile.profileId}`,
      });
      const executableCopyButton = within(dialog).getByRole("button", {
        name: `Copy script profile executable for ${longProfile.profileId}`,
      });
      const argumentsCopyButton = within(dialog).getByRole("button", {
        name: `Copy script profile arguments for ${longProfile.profileId}`,
      });

      expect(identityCopyButton.parentElement).toHaveAttribute("title", identityText);
      expect(executableCopyButton.parentElement).toHaveAttribute("title", longProfile.executablePath);
      expect(argumentsCopyButton.parentElement).toHaveAttribute("title", argumentsText);

      await userEvent.click(identityCopyButton);
      expect(clipboardWriteText).toHaveBeenLastCalledWith(identityText);

      await userEvent.click(executableCopyButton);
      expect(clipboardWriteText).toHaveBeenLastCalledWith(longProfile.executablePath);

      await userEvent.click(argumentsCopyButton);
      expect(clipboardWriteText).toHaveBeenLastCalledWith(argumentsText);
    } finally {
      if (clipboardDescriptor) {
        Object.defineProperty(navigator, "clipboard", clipboardDescriptor);
      } else {
        // @ts-expect-error jsdom may not define clipboard by default.
        delete navigator.clipboard;
      }
    }
  });

  it("keeps global configuration entries visible when one resource is denied", async () => {
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([releasePlan])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
      http.get("/internal/release-center/script-profiles", () =>
        HttpResponse.json({ message: "no policy rule for request" }, { status: 403 }),
      ),
    );

    renderReleaseCenter();

    const globalPanel = await screen.findByRole("complementary", { name: "发布中心全局配置" });
    expect(await within(globalPanel).findByText("应用目录")).toBeInTheDocument();
    expect(within(globalPanel).getByText("发布脚本")).toBeInTheDocument();
    expect(within(globalPanel).getByText("启动脚本")).toBeInTheDocument();
    expect(within(globalPanel).getByText("停止脚本")).toBeInTheDocument();
    expect(within(globalPanel).getByText("凭据别名")).toBeInTheDocument();
    expect(within(globalPanel).getAllByText("读取失败").length).toBeGreaterThanOrEqual(1);
    expect(within(globalPanel).getByRole("button", { name: "配置 应用目录" })).toBeInTheDocument();

    await userEvent.click(within(globalPanel).getByRole("button", { name: "配置 发布脚本" }));
    const dialog = await screen.findByRole("dialog", { name: "配置 发布脚本" });
    expect(within(dialog).getByRole("button", { name: "Add script profile" })).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "Add script profile" }));
    expect(await screen.findByRole("dialog", { name: "New script profile" })).toBeInTheDocument();
  });

  it("scopes release plans to the selected target environment", async () => {
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([releasePlan, sitReleasePlan])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
    );

    renderReleaseCenter();

    const planList = await screen.findByRole("region", { name: "发布单列表" });
    expect(within(planList).getByText("rel-1")).toBeInTheDocument();
    expect(within(planList).queryByText("rel-sit-1")).not.toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "SIT" }));

    const sitPlanList = await screen.findByRole("region", { name: "发布单列表" });
    expect(within(sitPlanList).getByText("rel-sit-1")).toBeInTheDocument();
    expect(within(sitPlanList).queryByText("rel-1")).not.toBeInTheDocument();
  });

  it("moves release history into the right card in ascending sequence", async () => {
    const firstHistoryPlan = {
      ...releasePlan,
      releaseId: "rel-history-1",
      status: "SUCCEEDED",
      createdAt: "2026-07-02T22:55:06Z",
    };
    const secondHistoryPlan = {
      ...releasePlan,
      releaseId: "rel-history-2",
      status: "FAILED",
      createdAt: "2026-07-02T23:08:13Z",
    };
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([secondHistoryPlan, firstHistoryPlan])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
    );

    renderReleaseCenter();

    const sideCard = await screen.findByRole("complementary", { name: "发布中心全局配置" });
    const history = await within(sideCard).findByRole("region", { name: "发布历史" });
    expect(within(history).queryByText("Release history")).not.toBeInTheDocument();
    const historyRows = within(history).getAllByRole("listitem").map((item) => item.textContent ?? "");
    expect(historyRows).toHaveLength(2);
    expect(historyRows[0]).toContain("#1");
    expect(historyRows[0]).toContain("rel-history-1");
    expect(historyRows[0]).not.toContain("发布单");
    expect(historyRows[0]).not.toContain("SUCCEEDED");
    expect(historyRows[1]).toContain("#2");
    expect(historyRows[1]).toContain("rel-history-2");
    expect(historyRows[1]).not.toContain("发布单");
    expect(historyRows[1]).not.toContain("FAILED");
    expect(
      within(within(history).getByRole("button", { name: "查看 rel-history-1 发布日志" })).getByRole("img", {
        name: "状态 SUCCEEDED",
      }),
    ).toBeInTheDocument();
    expect(
      within(within(history).getByRole("button", { name: "查看 rel-history-2 发布日志" })).getByRole("img", {
        name: "状态 FAILED",
      }),
    ).toBeInTheDocument();

    const planList = await screen.findByRole("region", { name: "发布单列表" });
    expect(within(planList).queryByRole("list", { name: "发布历史" })).not.toBeInTheDocument();
  });

  it("paginates release history in the right card", async () => {
    const historyPlans = Array.from({ length: 7 }, (_, index) => ({
      ...releasePlan,
      releaseId: `rel-history-page-${index + 1}`,
      status: index % 2 === 0 ? "SUCCEEDED" : "FAILED",
      createdAt: `2026-07-02T22:${String(50 + index).padStart(2, "0")}:00Z`,
    })).reverse();
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json(historyPlans)),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
    );

    renderReleaseCenter();

    const sideCard = await screen.findByRole("complementary", { name: "发布中心全局配置" });
    const history = await within(sideCard).findByRole("region", { name: "发布历史" });
    const firstPageRows = within(history).getAllByRole("listitem").map((item) => item.textContent ?? "");
    expect(firstPageRows).toHaveLength(5);
    expect(firstPageRows[0]).toContain("#1");
    expect(firstPageRows[0]).toContain("rel-history-page-1");
    expect(firstPageRows[4]).toContain("#5");
    expect(firstPageRows[4]).toContain("rel-history-page-5");
    expect(within(history).getByText("第 1 / 2 页")).toBeInTheDocument();

    const previousButton = within(history).getByRole("button", { name: "上一页发布历史" });
    const nextButton = within(history).getByRole("button", { name: "下一页发布历史" });
    expect(previousButton).toBeDisabled();
    expect(nextButton).toBeEnabled();

    await userEvent.click(nextButton);

    const secondPageRows = within(history).getAllByRole("listitem").map((item) => item.textContent ?? "");
    expect(secondPageRows).toHaveLength(2);
    expect(secondPageRows[0]).toContain("#6");
    expect(secondPageRows[0]).toContain("rel-history-page-6");
    expect(secondPageRows[1]).toContain("#7");
    expect(secondPageRows[1]).toContain("rel-history-page-7");
    expect(within(history).getByText("第 2 / 2 页")).toBeInTheDocument();
    expect(previousButton).toBeEnabled();
    expect(nextButton).toBeDisabled();

    const globalConfig = within(sideCard).getByRole("region", { name: "全局配置" });
    expect(history.compareDocumentPosition(globalConfig) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  });

  it("opens full release logs in the workspace when a release history row is clicked", async () => {
    const planWithHistory = {
      ...releasePlan,
      releaseId: "rel-history-logs",
      nodes: [
        {
          nodeId: "node-1",
          serverType: "LIBERTY",
          managementMode: "LIBERTY_SCRIPT_PROFILE",
          sequence: 1,
          status: "SUCCEEDED",
        },
      ],
    };
    const releaseNodeLogEvents = [
      buildReleaseNodeLogEvent({
        releaseId: "rel-history-logs",
        sequence: 1,
        message: "history deploy line 1",
      }),
      buildReleaseNodeLogEvent({
        releaseId: "rel-history-logs",
        sequence: 2,
        message: "history deploy line 2",
      }),
    ];
    const eventStream = releaseNodeLogEvents.map((event) => `data: ${JSON.stringify(event)}\n\n`).join("");
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([planWithHistory])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
      http.get("/internal/release-center/plans/rel-history-logs/events", () =>
        new HttpResponse(eventStream, {
          status: 200,
          headers: { "Content-Type": "text/event-stream" },
        }),
      ),
    );

    renderReleaseCenter();

    await userEvent.click(await screen.findByRole("tab", { name: "服务器" }));
    const sideCard = await screen.findByRole("complementary", { name: "发布中心全局配置" });
    const history = await within(sideCard).findByRole("region", { name: "发布历史" });
    await userEvent.click(within(history).getByRole("button", { name: "查看 rel-history-logs 发布日志" }));

    const planList = await screen.findByRole("region", { name: "发布单列表" });
    const logPanel = await within(planList).findByRole("region", { name: "rel-history-logs 脚本输出" });
    expect(within(logPanel).getByText("history deploy line 1")).toBeInTheDocument();
    expect(within(logPanel).getByText("history deploy line 2")).toBeInTheDocument();
    expect(within(logPanel).getByRole("combobox", { name: "日志显示范围" })).toHaveValue("all");

    await userEvent.click(within(logPanel).getByRole("button", { name: "关闭日志" }));
    expect(
      within(planList).queryByRole("region", { name: "rel-history-logs 脚本输出" }),
    ).not.toBeInTheDocument();
    expect(within(planList).getByText("rel-history-logs")).toBeInTheDocument();
  });

  it("creates a dev release plan from registered catalog data", async () => {
    let createCalled = false;
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
      http.post("/internal/release-center/plans", async () => {
        createCalled = true;
        return HttpResponse.json(releasePlan);
      }),
    );

    renderReleaseCenter();

    const createButton = await screen.findByRole("button", { name: "新建发布单" });
    await waitFor(() => expect(createButton).toBeEnabled());
    await userEvent.click(createButton);
    await userEvent.click(await screen.findByRole("button", { name: "创建发布单" }));

    expect(createCalled).toBe(true);
    const planList = await screen.findByRole("region", { name: "发布单列表" });
    expect(within(planList).getByText("rel-1")).toBeInTheDocument();
  });

  it("opens the release plan form even when artifacts are missing", async () => {
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
    );

    renderReleaseCenter();

    const createButton = await screen.findByRole("button", { name: "新建发布单" });
    expect(createButton).toBeEnabled();
    await userEvent.click(createButton);

    const dialog = await screen.findByRole("dialog", { name: "新建发布单" });
    expect(within(dialog).getAllByText("缺少可发布制品").length).toBeGreaterThan(0);
    expect(within(dialog).getByRole("button", { name: "创建发布单" })).toBeDisabled();
  });

  it("does not report missing artifacts before release nodes determine the mode", async () => {
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([])),
    );

    renderReleaseCenter();

    await userEvent.click(await screen.findByRole("button", { name: "新建发布单" }));

    const dialog = await screen.findByRole("dialog", { name: "新建发布单" });
    expect(within(dialog).queryByText("缺少可发布制品")).not.toBeInTheDocument();
    expect(within(dialog).getByText("等待发布节点确定发布方式")).toBeInTheDocument();
    expect(within(dialog).getAllByText("缺少可用发布节点").length).toBeGreaterThan(0);
    expect(within(dialog).getByRole("button", { name: "创建发布单" })).toBeDisabled();
  });

  it("creates a Liberty script release plan without artifacts", async () => {
    /** @type {unknown} */
    let savedRequest = null;
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseLibertyScriptServer])),
      http.post("/internal/release-center/plans", async ({ request }) => {
        const requestBody = /** @type {Record<string, unknown>} */ (await request.json());
        savedRequest = requestBody;
        return HttpResponse.json({
          ...releasePlan,
          artifactId: null,
          nodes: [
            {
              nodeId: "liberty-script-1",
              serverType: "LIBERTY",
              managementMode: "LIBERTY_SCRIPT_PROFILE",
              sequence: 1,
              status: "PENDING",
            },
          ],
        });
      }),
    );

    renderReleaseCenter();

    const actionButtons = (await screen.findAllByRole("button"))
      .filter((button) => String(button.className).includes("actionButton"));
    const createButton = actionButtons[1];
    await waitFor(() => expect(createButton).toBeEnabled());
    await userEvent.click(createButton);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getByText("\\\\jenkins\\share\\orders\\latest\\orders.war")).toBeInTheDocument();
    const dialogButtons = within(dialog).getAllByRole("button");
    await userEvent.click(dialogButtons[dialogButtons.length - 1]);

    await waitFor(() =>
      expect(savedRequest).toEqual({
        applicationId: "orders",
        targetEnvironment: "dev",
        nodeIds: ["liberty-script-1"],
      }),
    );
  });

  it("creates a SIT Liberty script release plan from script applicationName when no application catalog entry exists", async () => {
    /** @type {unknown} */
    let savedRequest = null;
    const sitLibertyServer = {
      ...releaseLibertyScriptServer,
      nodeId: "liberty-sit-1",
      targetEnvironment: "sit",
      managementEndpoint: "https://liberty-sit.example",
    };
    const sitArtifact = {
      ...releaseArtifact,
      artifactId: "artifact-sit-1",
      targetEnvironment: "sit",
    };
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([])),
      http.get("/internal/release-center/artifacts", ({ request }) => {
        const url = new URL(request.url);
        return HttpResponse.json(url.searchParams.get("targetEnvironment") === "sit" ? [sitArtifact] : []);
      }),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", ({ request }) => {
        const url = new URL(request.url);
        return HttpResponse.json(url.searchParams.get("targetEnvironment") === "sit" ? [sitLibertyServer] : []);
      }),
      http.post("/internal/release-center/plans", async ({ request }) => {
        const requestBody = /** @type {Record<string, unknown>} */ (await request.json());
        savedRequest = requestBody;
        return HttpResponse.json({
          ...releasePlan,
          applicationId: "orders",
          targetEnvironment: "sit",
          artifactId: null,
          nodes: [
            {
              nodeId: "liberty-sit-1",
              serverType: "LIBERTY",
              managementMode: "LIBERTY_SCRIPT_PROFILE",
              sequence: 1,
              status: "PENDING",
            },
          ],
        });
      }),
    );

    renderReleaseCenter();

    await userEvent.click(await screen.findByRole("button", { name: "SIT" }));
    await userEvent.click(await screen.findByRole("tab", { name: "服务器" }));
    expect((await screen.findAllByText("liberty-sit-1")).length).toBeGreaterThan(0);
    await userEvent.click(await screen.findByRole("button", { name: "新建发布单" }));

    const dialog = await screen.findByRole("dialog", { name: "新建发布单" });
    expect(within(dialog).queryByText("缺少已启用应用")).not.toBeInTheDocument();
    expect(within(dialog).getByText("orders（脚本参数） / orders")).toBeInTheDocument();
    expect(within(dialog).getByText("\\\\jenkins\\share\\orders\\latest\\orders.war")).toBeInTheDocument();
    await userEvent.click(within(dialog).getByRole("button", { name: "创建发布单" }));

    await waitFor(() =>
      expect(savedRequest).toEqual({
        applicationId: "orders",
        targetEnvironment: "sit",
        nodeIds: ["liberty-sit-1"],
      }),
    );
  });

  it("requires a shared artifact path for Liberty script releases", async () => {
    const serverWithoutArtifactPath = {
      ...releaseLibertyScriptServer,
      scriptProfile: {
        profileId: "liberty-war-deploy",
        parameters: [
          { name: "serverName", value: "defaultServer" },
          { name: "applicationName", value: "orders" },
        ],
      },
    };
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([serverWithoutArtifactPath])),
    );

    renderReleaseCenter();

    const actionButtons = (await screen.findAllByRole("button"))
      .filter((button) => String(button.className).includes("actionButton"));
    const createButton = actionButtons[1];
    await userEvent.click(createButton);

    const dialog = await screen.findByRole("dialog");
    expect(within(dialog).getAllByText("缺少 Liberty 制品共享路径").length).toBeGreaterThan(0);
    expect(within(dialog).getAllByRole("button").at(-1)).toBeDisabled();
  });

  it("executes a draft release plan from the plan list", async () => {
    let executeCalled = false;
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([releasePlan])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
      http.get("/internal/release-center/plans/rel-1/events", () =>
        new HttpResponse(`data: ${JSON.stringify(releaseNodeLogEvent)}\n\n`, {
          status: 200,
          headers: { "Content-Type": "text/event-stream" },
        }),
      ),
      http.post("/internal/release-center/plans/rel-1/execute", async () => {
        executeCalled = true;
        return HttpResponse.json({ ...releasePlan, status: "PARTIAL_FAILED" });
      }),
    );

    renderReleaseCenter();

    const planList = await screen.findByRole("region", { name: "发布单列表" });
    const executeButton = within(planList).getByRole("button", { name: "发布" });
    expect(executeButton).toBeEnabled();
    expect(within(planList).queryByRole("button", { name: "执行" })).not.toBeInTheDocument();
    expect(within(planList).getByRole("button", { name: "打包" })).toBeDisabled();
    expect(within(planList).getByRole("button", { name: "打包" })).toHaveAttribute("title", "打包工作流待接入");
    expect(within(planList).getByRole("button", { name: "重启" })).toBeDisabled();
    expect(within(planList).getByRole("button", { name: "重启" })).toHaveAttribute("title", "重启工作流待接入");
    expect(within(planList).getByRole("button", { name: "停止" })).toBeDisabled();
    expect(within(planList).getByRole("button", { name: "停止" })).toHaveAttribute("title", "停止工作流待接入");
    await userEvent.click(executeButton);

    expect(executeCalled).toBe(true);
    expect(await screen.findByText("deploy started")).toBeInTheDocument();
    expect((await screen.findAllByText("PARTIAL_FAILED")).length).toBeGreaterThan(0);
  });

  it("lets operators view all script logs and collapse the log panel", async () => {
    const releaseNodeLogEvents = Array.from({ length: 30 }, (_, index) =>
      buildReleaseNodeLogEvent({
        sequence: index + 1,
        message: `deploy line ${index + 1}`,
      }),
    );
    const eventStream = releaseNodeLogEvents
      .map((event) => `data: ${JSON.stringify(event)}\n\n`)
      .join("");
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([releasePlan])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
      http.get("/internal/release-center/plans/rel-1/events", () =>
        new HttpResponse(eventStream, {
          status: 200,
          headers: { "Content-Type": "text/event-stream" },
        }),
      ),
      http.post("/internal/release-center/plans/rel-1/execute", async () =>
        HttpResponse.json({ ...releasePlan, status: "SUCCEEDED" }),
      ),
    );

    renderReleaseCenter();

    await userEvent.click(await screen.findByRole("button", { name: "发布" }));

    expect(await screen.findByText("deploy line 1")).toBeInTheDocument();
    expect(await screen.findByText("deploy line 30")).toBeInTheDocument();
    const displayRange = await screen.findByRole("combobox", { name: "日志显示范围" });
    expect(displayRange).toHaveValue("all");

    await userEvent.selectOptions(displayRange, "recent-20");
    expect(screen.queryByText("deploy line 1")).not.toBeInTheDocument();
    expect(screen.getByText("deploy line 30")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: "展开日志" }));
    expect(screen.getByRole("button", { name: "收起日志" })).toHaveAttribute("aria-expanded", "true");
    await userEvent.click(screen.getByRole("button", { name: "收起日志" }));
    expect(screen.getByRole("button", { name: "展开日志" })).toHaveAttribute("aria-expanded", "false");
  });

  it("registers a Tomcat server from the servers tab", async () => {
    /** @type {unknown} */
    let savedServer = null;
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([])),
      http.post("/internal/release-center/servers", async ({ request }) => {
        const requestBody = /** @type {Record<string, unknown>} */ (await request.json());
        savedServer = requestBody;
        return HttpResponse.json(requestBody);
      }),
    );

    renderReleaseCenter();

    await userEvent.click(await screen.findByRole("tab", { name: "服务器" }));
    await userEvent.click(await screen.findByRole("button", { name: "Add release server" }));

    const dialog = await screen.findByRole("dialog", { name: "新增服务器" });
    await userEvent.type(within(dialog).getByLabelText("Node ID"), "tomcat-dev-1");
    await userEvent.clear(within(dialog).getByLabelText("Management endpoint"));
    await userEvent.type(within(dialog).getByLabelText("Management endpoint"), "http://127.0.0.1:18080/manager/text");
    await userEvent.clear(within(dialog).getByLabelText("Application path"));
    await userEvent.type(within(dialog).getByLabelText("Application path"), "/orders-demo");
    await userEvent.type(within(dialog).getByLabelText("Credential alias"), "tomcat-dev");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save release server" }));

    await waitFor(() =>
      expect(savedServer).toEqual({
        nodeId: "tomcat-dev-1",
        targetEnvironment: "dev",
        serverType: "TOMCAT",
        managementMode: "TOMCAT_WAR_UPLOAD",
        managementEndpoint: "http://127.0.0.1:18080/manager/text",
        applicationPath: "/orders-demo",
        credentialAlias: "tomcat-dev",
        enabled: true,
      }),
    );
    expect((await screen.findAllByText("tomcat-dev-1")).length).toBeGreaterThan(0);
  });

  it("registers a Liberty script profile server from the servers tab", async () => {
    /** @type {unknown} */
    let savedServer = null;
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([])),
      http.post("/internal/release-center/servers", async ({ request }) => {
        const requestBody = /** @type {Record<string, unknown>} */ (await request.json());
        savedServer = requestBody;
        return HttpResponse.json(requestBody);
      }),
    );

    renderReleaseCenter();

    await userEvent.click(await screen.findByRole("tab", { name: "服务器" }));
    await userEvent.click(await screen.findByRole("button", { name: "Add release server" }));

    const dialog = await screen.findByRole("dialog");
    await userEvent.type(within(dialog).getByLabelText("Node ID"), "liberty-dev-1");
    await userEvent.selectOptions(within(dialog).getByLabelText("Server type"), "LIBERTY");
    await userEvent.clear(within(dialog).getByLabelText("Management endpoint"));
    await userEvent.type(within(dialog).getByLabelText("Management endpoint"), "https://liberty-dev.example");
    await userEvent.clear(within(dialog).getByLabelText("Application path"));
    await userEvent.type(within(dialog).getByLabelText("Application path"), "/orders");
    expect(within(dialog).getByLabelText("Script Profile ID")).toHaveValue("liberty-war-deploy");
    expect(within(dialog).getByLabelText("Parameter 1 name")).toHaveValue("serverName");
    expect(within(dialog).getByLabelText("Parameter 1 value")).toHaveValue("defaultServer");
    expect(within(dialog).getByLabelText("Parameter 3 name")).toHaveValue("artifactPath");
    expect(within(dialog).getByLabelText("Parameter 3 value")).toHaveValue(
      "\\\\jenkins\\share\\orders\\latest\\orders.war",
    );
    await userEvent.click(within(dialog).getByRole("button", { name: "Save release server" }));

    await waitFor(() =>
      expect(savedServer).toEqual({
        nodeId: "liberty-dev-1",
        targetEnvironment: "dev",
        serverType: "LIBERTY",
        managementMode: "LIBERTY_SCRIPT_PROFILE",
        managementEndpoint: "https://liberty-dev.example",
        applicationPath: "/orders",
        credentialAlias: null,
        scriptProfile: {
          profileId: "liberty-war-deploy",
          parameters: [
            { name: "serverName", value: "defaultServer" },
            { name: "applicationName", value: "orders" },
            { name: "artifactPath", value: "\\\\jenkins\\share\\orders\\latest\\orders.war" },
          ],
        },
        enabled: true,
      }),
    );
    expect(await screen.findByText("\\\\jenkins\\share\\orders\\latest\\orders.war")).toBeInTheDocument();
    expect(screen.getByText("\\\\jenkins\\share\\orders\\latest\\orders.war").closest("a")).toBeNull();
    expect(await screen.findByRole("button", { name: "Copy artifact path for liberty-dev-1" })).toBeInTheDocument();
  });

  it("registers an approved Liberty script profile from the profiles tab", async () => {
    /** @type {unknown} */
    let savedProfile = null;
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([])),
      http.get("/internal/release-center/script-profiles", () => HttpResponse.json([])),
      http.post("/internal/release-center/script-profiles", async ({ request }) => {
        const requestBody = /** @type {Record<string, unknown>} */ (await request.json());
        savedProfile = requestBody;
        return HttpResponse.json(requestBody);
      }),
    );

    renderReleaseCenter();

    await userEvent.click(await screen.findByRole("button", { name: "配置 发布脚本" }));
    const configDialog = await screen.findByRole("dialog", { name: "配置 发布脚本" });
    await userEvent.click(await within(configDialog).findByRole("button", { name: "Add script profile" }));

    const dialog = await screen.findByRole("dialog", { name: "New script profile" });
    await userEvent.type(within(dialog).getByLabelText("Profile ID"), "liberty-war-deploy");
    await userEvent.type(within(dialog).getByLabelText("Display name"), "Liberty WAR deploy");
    await userEvent.type(
      within(dialog).getByLabelText("Executable path"),
      "C:\\ops\\scripts\\liberty-war-deploy.cmd",
    );
    await userEvent.type(within(dialog).getByLabelText("Working directory"), "C:\\ops-agent\\work\\release");
    expect(within(dialog).queryByLabelText("Required parameters")).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText("Allowed parameters")).not.toBeInTheDocument();
    await userEvent.click(within(dialog).getByLabelText("Approved"));
    await userEvent.click(within(dialog).getByRole("button", { name: "Save script profile" }));

    await waitFor(() =>
      expect(savedProfile).toEqual({
        profileId: "liberty-war-deploy",
        displayName: "Liberty WAR deploy",
        executablePath: "C:\\ops\\scripts\\liberty-war-deploy.cmd",
        workingDirectory: "C:\\ops-agent\\work\\release",
        arguments: [],
        successExitCodes: [0],
        timeoutSeconds: 600,
        approved: true,
        enabled: true,
      }),
    );
    expect(await screen.findByText("liberty-war-deploy")).toBeInTheDocument();
  });

  it("edits a release server from the servers tab", async () => {
    /** @type {unknown} */
    let savedServer = null;
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
      http.post("/internal/release-center/servers", async ({ request }) => {
        const requestBody = /** @type {Record<string, unknown>} */ (await request.json());
        savedServer = requestBody;
        return HttpResponse.json(requestBody);
      }),
    );

    renderReleaseCenter();

    await userEvent.click(await screen.findByRole("tab", { name: "服务器" }));
    await userEvent.click(await screen.findByRole("button", { name: "Edit node-1" }));

    const dialog = await screen.findByRole("dialog", { name: "编辑服务器" });
    expect(within(dialog).getByLabelText("Node ID")).toHaveAttribute("readonly");
    await userEvent.clear(within(dialog).getByLabelText("Application path"));
    await userEvent.type(within(dialog).getByLabelText("Application path"), "/orders-edit");
    await userEvent.click(within(dialog).getByRole("button", { name: "Save release server" }));

    await waitFor(() =>
      expect(savedServer).toEqual({
        ...releaseServer,
        applicationPath: "/orders-edit",
      }),
    );
    expect(await screen.findByText("/orders-edit")).toBeInTheDocument();
  });

  it("deletes a release server from the servers tab", async () => {
    /** @type {string | null} */
    let deletedNodeId = null;
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "ops.release",
          roles: ["ROLE_ops-release"],
          authenticationType: "built-in",
        }),
      ),
      http.get("/internal/release-center/applications", () => HttpResponse.json([releaseApplication])),
      http.get("/internal/release-center/artifacts", () => HttpResponse.json([releaseArtifact])),
      http.get("/internal/release-center/plans", () => HttpResponse.json([])),
      http.get("/internal/release-center/servers", () => HttpResponse.json([releaseServer])),
      http.delete("/internal/release-center/servers/:nodeId", ({ params }) => {
        deletedNodeId = String(params.nodeId);
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderReleaseCenter();

    await userEvent.click(await screen.findByRole("tab", { name: "服务器" }));
    await userEvent.click(await screen.findByRole("button", { name: "Delete node-1" }));
    await userEvent.click(await screen.findByRole("button", { name: "Confirm delete node-1" }));

    await waitFor(() => expect(deletedNodeId).toBe("node-1"));
    await waitFor(() => expect(screen.queryByText("node-1")).not.toBeInTheDocument());
  });
});

function renderReleaseCenter() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter>
        <ReleaseCenterPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * @param {string} className
 */
function cssRule(className) {
  const escapedClassName = className.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = new RegExp(`\\.${escapedClassName}\\s*\\{([^}]*)\\}`).exec(releaseCenterCss);
  return match?.[1] ?? "";
}

const releaseApplication = {
  applicationId: "orders",
  displayName: "订单服务",
  artifactType: "WAR",
  healthCheckPath: "/health",
  enabled: true,
};

const releaseServer = {
  nodeId: "node-1",
  targetEnvironment: "dev",
  serverType: "TOMCAT",
  managementMode: "TOMCAT_WAR_UPLOAD",
  managementEndpoint: "https://tomcat-dev.example",
  applicationPath: "/orders",
  credentialAlias: "tomcat-dev",
  enabled: true,
};

const releaseLibertyScriptServer = {
  nodeId: "liberty-script-1",
  targetEnvironment: "dev",
  serverType: "LIBERTY",
  managementMode: "LIBERTY_SCRIPT_PROFILE",
  managementEndpoint: "https://liberty-dev.example",
  applicationPath: "/orders",
  credentialAlias: null,
  scriptProfile: {
    profileId: "liberty-war-deploy",
    parameters: [
      { name: "serverName", value: "defaultServer" },
      { name: "applicationName", value: "orders" },
      { name: "artifactPath", value: "\\\\jenkins\\share\\orders\\latest\\orders.war" },
    ],
  },
  enabled: true,
};

const releaseScriptProfile = {
  profileId: "liberty-war-deploy",
  displayName: "Liberty WAR deploy",
  executablePath: "C:\\ops\\scripts\\liberty-war-deploy.cmd",
  workingDirectory: "C:\\ops-agent\\work\\release",
  arguments: [],
  successExitCodes: [0],
  timeoutSeconds: 600,
  approved: true,
  enabled: true,
};

const releaseArtifact = {
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
};

const releasePlan = {
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
};

const sitReleasePlan = {
  ...releasePlan,
  releaseId: "rel-sit-1",
  targetEnvironment: "sit",
  nodes: [
    {
      nodeId: "node-sit-1",
      serverType: "LIBERTY",
      managementMode: "LIBERTY_SCRIPT_PROFILE",
      sequence: 1,
      status: "PENDING",
    },
  ],
};

const releaseNodeLogEvent = {
  contractVersion: "1.0",
  eventId: "88888888-8888-4888-8888-888888888888",
  workflowId: "99999999-9999-4999-8999-999999999999",
  releaseId: "rel-1",
  sequence: 3,
  timestamp: "2026-07-02T00:00:00Z",
  type: "RELEASE_NODE_LOG",
  payload: {
    payloadType: "RELEASE_NODE_LOG",
    nodeId: "node-1",
    stream: "STDOUT",
    message: "deploy started",
    emittedAt: "2026-07-02T00:00:00Z",
  },
  audit: {
    action: "RELEASE_NODE_LOG",
    resource: "release:rel-1",
    policyVersion: "release-center-policy-v1",
    result: "LOG",
    reason: "release node script output",
    traceId: "trace:rel-1",
    requestId: "request:rel-1",
  },
};

/**
 * @param {{message: string, releaseId?: string, sequence: number}} input
 */
function buildReleaseNodeLogEvent(input) {
  return {
    ...releaseNodeLogEvent,
    eventId: `88888888-8888-4888-8888-${String(input.sequence).padStart(12, "0")}`,
    releaseId: input.releaseId ?? releaseNodeLogEvent.releaseId,
    sequence: input.sequence,
    payload: {
      ...releaseNodeLogEvent.payload,
      message: input.message,
      emittedAt: `2026-07-02T00:00:${String(input.sequence).padStart(2, "0")}Z`,
    },
  };
}
