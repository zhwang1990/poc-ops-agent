import { readFileSync } from "node:fs";

import { http, HttpResponse } from "msw";
import { act, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, beforeEach, describe, expect, test, vi } from "vitest";

import { AppProviders } from "../../app/providers.jsx";
import { server } from "../../test/server.js";
import { AgentWorkspacePage } from "./AgentWorkspacePage.jsx";

const agentWorkspaceCss = readFileSync(
  "src/features/agent-workspace/AgentWorkspacePage.module.css",
  "utf8",
);
const workspaceStatusBarCss = readFileSync(
  "src/components/layout/WorkspaceStatusBar.module.css",
  "utf8",
);
const pageToolbarCss = readFileSync(
  "src/components/layout/PageToolbar.module.css",
  "utf8",
);
const workspaceFrameCss = readFileSync(
  "src/components/layout/WorkspacePageFrame.module.css",
  "utf8",
);
const dialogCss = readFileSync(
  "src/components/primitives/Dialog.module.css",
  "utf8",
);
const agentWorkspaceSource = readFileSync(
  "src/features/agent-workspace/AgentWorkspacePage.jsx",
  "utf8",
);

/** @type {unknown[]} */
let diagnosticRequests = [];

beforeEach(() => {
  installWebStorageMocks();
  diagnosticRequests = [];
  server.use(...defaultHandlers);
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.useRealTimers();
});

function renderPage() {
  return render(
    <AppProviders>
      <AgentWorkspacePage />
    </AppProviders>,
  );
}

function installWebStorageMocks() {
  Object.defineProperty(window, "localStorage", {
    configurable: true,
    value: storageMock(),
  });
  Object.defineProperty(window, "sessionStorage", {
    configurable: true,
    value: storageMock(),
  });
}

function storageMock() {
  /** @type {Map<string, string>} */
  const values = new Map();
  return {
    clear: () => values.clear(),
    /** @param {string} key */
    getItem: (key) => values.get(key) ?? null,
    /** @param {string} key */
    removeItem: (key) => values.delete(key),
    /** @param {string} key @param {string} value */
    setItem: (key, value) => values.set(key, String(value)),
  };
}

describe("AgentWorkspacePage", () => {
  test("uses the shared workspace status bar instead of a local capsule implementation", () => {
    expect(agentWorkspaceSource).toContain("WorkspaceStatusBar");
    expect(agentWorkspaceSource).toContain('<WorkspaceStatusBar title="Agent 工作区" />');
    expect(agentWorkspaceSource).not.toContain("function TopCapsule");
    expect(agentWorkspaceSource).not.toContain("function OperatorDock");
    expect(agentWorkspaceSource).not.toContain("function WorkdayCountdown");
    expect(agentWorkspaceSource).not.toContain("getBrowserSession");
    expect(agentWorkspaceSource).not.toContain("logoutMutation");
  });

  test("uses the shared workspace frame for the same expanded mode as other menu pages", () => {
    const expandedFrameRule =
      workspaceFrameCss.match(/[.]workspaceFrame\[data-workspace-frame="true"\][.]workspaceFrameExpanded\s*[{][^}]+[}]/u)?.[0] ??
      "";

    expect(agentWorkspaceSource).toContain("WorkspacePageFrame");
    expect(agentWorkspaceSource).toContain("<WorkspacePageFrame className={styles.agentCanvas}>");
    expect(agentWorkspaceSource).not.toContain("workspaceFullscreen");
    expect(agentWorkspaceCss).not.toContain(".workspaceFullscreen");
    expect(agentWorkspaceCss).toContain('.agentCanvas[data-workspace-expanded="true"] .agentLayout');
    expect(agentWorkspaceCss).toContain('.agentCanvas[data-workspace-expanded="true"] .agentSide');
    expect(expandedFrameRule).toContain("position: fixed");
    expect(expandedFrameRule).toContain("inset: 0");
    expect(expandedFrameRule).toContain("padding: 9px");
    expect(expandedFrameRule).toContain("overflow: hidden");
  });

  test("renders a single-session toolbar without the seeded node-a investigation title", async () => {
    const { container } = renderPage();

    expect(await screen.findByLabelText("会话工具栏")).toBeInTheDocument();
    expect(screen.getByText("会话 1")).toBeInTheDocument();
    expect(screen.getByText("当前会话")).toBeInTheDocument();
    expect(screen.queryByText("会话列表")).not.toBeInTheDocument();
    expect(screen.queryByText("node-a 健康排查")).not.toBeInTheDocument();
    expect(screen.queryByText(/2 个 workflow/u)).not.toBeInTheDocument();

    const sessionIcon = container.querySelector('[data-session-icon="active-conversation"]');
    const sessionStackIconRule =
      agentWorkspaceCss.match(/[.]sessionStackIcon\s*[{][^}]+[}]/u)?.[0] ?? "";
    const sessionStackIconBeforeRule =
      agentWorkspaceCss.match(/[.]sessionStackIcon::before\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(sessionIcon).toBeInTheDocument();
    expect(sessionIcon?.querySelector("svg")).toBeInTheDocument();
    expect(sessionIcon?.querySelector('[class*="sessionIconNode"]')).toBeInTheDocument();
    expect(sessionStackIconRule).toContain("conic-gradient");
    expect(sessionStackIconBeforeRule).toContain("linear-gradient");
  });

  test("does not present broad route candidates as current task skill before submission", async () => {
    renderPage();

    expect(await screen.findByText("提交后由服务端路由")).toBeInTheDocument();
    expect(screen.getByText("Skill 路由状态")).toBeInTheDocument();
    expect(screen.queryByText("候选 Skill 预览")).not.toBeInTheDocument();
    expect(screen.queryByText("node-health-read")).not.toBeInTheDocument();
    expect(screen.queryByText("health")).not.toBeInTheDocument();
  });

  test("keeps right rail summaries and fact rows compact", () => {
    const agentLayoutRule =
      agentWorkspaceCss.match(/(?:^|\n)[.]agentLayout\s*[{][^}]+[}]/u)?.[0] ?? "";
    const agentSideRule =
      agentWorkspaceCss.match(/(?:^|\n)[.]agentSide\s*[{][^}]+[}]/u)?.[0] ?? "";
    const agentPanelRule =
      agentWorkspaceCss.match(/(?:^|\n)[.]agentPanel\s*[{][^}]+[}]/u)?.[0] ?? "";
    const panelHeadingRule =
      agentWorkspaceCss.match(/(?:^|\n)[.]agentPanel h3\s*[{][^}]+[}]/u)?.[0] ?? "";
    const panelSummaryRule =
      agentWorkspaceCss.match(/(?:^|\n)[.]panelSummary\s*[{][^}]+[}]/u)?.[0] ?? "";
    const panelSummaryLabelRule =
      agentWorkspaceCss.match(/(?:^|\n)[.]panelSummaryLabel\s*[{][^}]+[}]/u)?.[0] ?? "";
    const panelSummaryValueRule =
      agentWorkspaceCss.match(/(?:^|\n)[.]panelSummary strong\s*[{][^}]+[}]/u)?.[0] ?? "";
    const panelSummaryTextRule =
      agentWorkspaceCss.match(/(?:^|\n)[.]panelSummary p\s*[{][^}]+[}]/u)?.[0] ?? "";
    const panelFactListRule =
      agentWorkspaceCss.match(/(?:^|\n)[.]panelFactList\s*[{][^}]+[}]/u)?.[0] ?? "";
    const panelFactRule =
      agentWorkspaceCss.match(/(?:^|\n)[.]panelFact\s*[{][^}]+[}]/u)?.[0] ?? "";
    const panelFactLabelRule =
      agentWorkspaceCss.match(/(?:^|\n)[.]panelFact dt\s*[{][^}]+[}]/u)?.[0] ?? "";
    const panelFactValueRules = Array.from(
      agentWorkspaceCss.matchAll(/(?:^|\n)[.]panelFact dd\s*[{][^}]+[}]/gu),
      (match) => match[0],
    );
    const panelFactValueRule = panelFactValueRules.at(-1) ?? "";
    const detailButtonRule =
      agentWorkspaceCss.match(/(?:^|\n)[.]detailButton\s*[{][^}]+[}]/u)?.[0] ?? "";
    const panelDetailButtonRule =
      agentWorkspaceCss.match(/[.]agentPanel\s*[>]\s*[.]detailButton\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(agentLayoutRule).toContain("align-items: stretch");
    expect(agentSideRule).toContain("max-height: 100%");
    expect(agentSideRule).toContain("align-self: stretch");
    expect(agentSideRule).not.toContain("--agent-side-row-gap-share");
    expect(agentSideRule).not.toContain("--agent-side-row-shift");
    expect(agentSideRule).toContain("grid-auto-rows: max-content");
    expect(agentSideRule).toContain("align-content: start");
    expect(agentSideRule).not.toContain("grid-template-rows: repeat(3, minmax(0, 1fr))");
    expect(agentSideRule).not.toContain("overflow-y: hidden");
    expect(agentPanelRule).toContain("grid-template-rows: auto auto auto auto");
    expect(agentPanelRule).toContain("align-content: start");
    expect(agentPanelRule).toContain("gap: 7px");
    expect(agentPanelRule).toContain("overflow: hidden");
    expect(agentWorkspaceCss).not.toContain(".agentPanel::before");
    expect(agentPanelRule).not.toContain("minmax(38px, 1fr)");
    expect(agentWorkspaceCss).not.toContain(".agentPanel:has(> .statusNote)");
    expect(agentWorkspaceCss).not.toContain(".panelStatusNote");
    expect(panelHeadingRule).toContain("min-height: 36px");
    expect(panelSummaryRule).toContain("display: grid");
    expect(panelSummaryRule).toContain("grid-template-columns: minmax(0, 0.64fr) minmax(82px, 1fr)");
    expect(panelSummaryRule).toContain('grid-template-areas: "label value" "note note"');
    expect(panelSummaryRule).toContain("border-bottom: 1px solid rgba(37, 132, 169, 0.11)");
    expect(panelSummaryRule).not.toContain("border: 1px solid rgba(37, 132, 169, 0.12)");
    expect(panelSummaryRule).not.toContain("border-radius: 10px");
    expect(panelSummaryRule).not.toContain("background:");
    expect(panelSummaryLabelRule).toContain("grid-area: label");
    expect(panelSummaryLabelRule).toContain("align-self: center");
    expect(panelSummaryLabelRule).toContain("color: #698190");
    expect(panelSummaryLabelRule).toContain("font-size: 12px");
    expect(panelSummaryValueRule).toContain("grid-area: value");
    expect(panelSummaryValueRule).toContain("align-self: center");
    expect(panelSummaryValueRule).toContain("justify-self: end");
    expect(panelSummaryValueRule).toContain("justify-content: center");
    expect(panelSummaryValueRule).toContain("width: fit-content");
    expect(panelSummaryValueRule).toContain("max-width: 100%");
    expect(panelSummaryValueRule).toContain("font-size: 11px");
    expect(panelSummaryValueRule).toContain("text-align: center");
    expect(panelSummaryTextRule).toContain("grid-area: note");
    expect(panelSummaryTextRule).toContain("font-size: 12px");
    expect(panelSummaryTextRule).toContain("overflow-wrap: anywhere");
    expect(panelFactListRule).toContain("overflow: visible");
    expect(panelFactRule).toContain("min-height: 26px");
    expect(panelFactRule).toContain("padding: 4px 0");
    expect(panelFactLabelRule).toContain("align-self: center");
    expect(panelFactLabelRule).toContain("font-size: 12px");
    expect(panelFactValueRule).toContain("align-self: center");
    expect(panelFactValueRule).toContain("justify-content: center");
    expect(panelFactValueRule).toContain("font-size: 11px");
    expect(panelFactValueRule).toContain("text-align: center");
    expect(agentWorkspaceSource).toContain("styles.panelSummary");
    expect(agentWorkspaceSource).toContain("styles.panelSummaryLabel");
    expect(agentWorkspaceSource).not.toContain("styles.panelStatusNote");
    expect(detailButtonRule).toContain("min-height: 32px");
    expect(detailButtonRule).toContain("font-size: 12px");
    expect(detailButtonRule).toContain("margin-top: 0");
    expect(panelDetailButtonRule).toContain("align-self: stretch");
  });

  test("keeps right rail basic diagnostics visible before submission", async () => {
    const { container } = renderPage();

    const sideRail = container.querySelector("aside");
    expect(sideRail).toBeInTheDocument();
    const rail = within(/** @type {HTMLElement} */ (sideRail));

    expect(await rail.findByText("对话执行状态")).toBeInTheDocument();
    expect(rail.getByText("策略")).toBeInTheDocument();
    expect(rail.getAllByText("READ_ONLY").length).toBeGreaterThan(0);
    expect(rail.getByText("环境")).toBeInTheDocument();
    expect(rail.getByText("development")).toBeInTheDocument();
    expect(rail.getAllByText("workflow").length).toBeGreaterThan(0);
    expect(rail.getAllByText("pending").length).toBeGreaterThan(0);
    expect(rail.getByText("路由")).toBeInTheDocument();
    expect(rail.getByText("服务端待路由")).toBeInTheDocument();
    expect(rail.getByText("Worker")).toBeInTheDocument();
    expect(rail.getByText("等待调用")).toBeInTheDocument();
    expect(rail.getByText("Runtime")).toBeInTheDocument();
    expect(rail.getByText("Tool 调用")).toBeInTheDocument();
    expect(agentWorkspaceSource).toContain("PanelFactList");
    expect(agentWorkspaceCss).toContain(".panelFactList");
  });

  test("keeps the single-track Skill route animation compact", () => {
    const skillRouteAnimationRule =
      agentWorkspaceCss.match(/[.]flowAnimation\[data-flow-animation="skill-route"\]\s*[{][^}]+[}]/u)?.[0] ??
      "";
    const skillRouteFlattenRule =
      agentWorkspaceCss.match(
        /[.]flowAnimation\[data-flow-animation="skill-route"\]\s+[.]flowTrack,\s*\n[.]flowAnimation\[data-flow-animation="skill-route"\]\s+[.]flowTrackHeader\s*[{][^}]+[}]/u,
      )?.[0] ?? "";
    const skillRouteStepListRule =
      agentWorkspaceCss.match(
        /[.]flowAnimation\[data-flow-animation="skill-route"\]\s+[.]flowStepList\s*[{][^}]+[}]/u,
      )?.[0] ?? "";
    const routeHeaderPillRule =
      agentWorkspaceCss.match(/[.]flowAnimationHeader strong,\s*\n[.]flowTrackHeader strong\s*[{][^}]+[}]/u)?.[0] ??
      "";

    expect(agentWorkspaceSource).toContain("const routeSummary");
    expect(agentWorkspaceSource).toContain("summary={routeSummary}");
    expect(agentWorkspaceSource).not.toContain("<span>路由演示</span>");
    expect(skillRouteAnimationRule).toContain("grid-template-columns: minmax(0, 1fr) max-content");
    expect(skillRouteAnimationRule).toContain("gap: 8px 10px");
    expect(skillRouteAnimationRule).toContain("padding: 10px 12px 12px");
    expect(skillRouteFlattenRule).toContain("display: contents");
    expect(agentWorkspaceCss).not.toContain(
      '.flowAnimation[data-flow-animation="skill-route"] .flowAnimationHeader strong',
    );
    expect(routeHeaderPillRule).toContain("min-width: 76px");
    expect(routeHeaderPillRule).toContain("justify-content: center");
    expect(skillRouteStepListRule).toContain("grid-column: 1 / -1");
    expect(skillRouteStepListRule).toContain("grid-row: 2");
  });

  test("renders the read-only workspace without a broad route candidate preview", async () => {
    renderPage();

    expect(await screen.findByText("工作会话")).toBeInTheDocument();
    expect(await screen.findByText("ops.reader")).toBeInTheDocument();
    expect(screen.getByText("ID operator-1")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "登出当前账号" })).toBeEnabled();
    expect(await screen.findByText("提交后由服务端路由")).toBeInTheDocument();
    expect(screen.queryByText("node-health-read")).not.toBeInTheDocument();
    expect(screen.queryByText("health")).not.toBeInTheDocument();
    expect(screen.getByText("ROLE_agent-reader · policy-v1 · READ_ONLY")).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "任务目标" })).toHaveValue("");
    expect(screen.getByRole("button", { name: "发送任务" })).toBeDisabled();
    expect(screen.queryByText("任务会话")).not.toBeInTheDocument();
    expect(screen.queryByText("只读模式")).not.toBeInTheDocument();
    expect(screen.queryByText("模型内部推理")).not.toBeInTheDocument();
    expect(screen.queryByText("服务依赖健康检查")).not.toBeInTheDocument();
    expect(screen.queryByText(/wf-042/u)).not.toBeInTheDocument();
    expect(screen.queryByText(/wf-043/u)).not.toBeInTheDocument();
    expect(
      screen.queryByText("帮我检查 node-a 的健康状态和关键依赖，只做只读诊断，并关联 INC-2841。"),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText("已拆分为两个独立只读任务。当前会话来自评审模板，符合范围的任务将在服务端策略通过后自动执行。"),
    ).not.toBeInTheDocument();
  });

  test("shows the inline execution indicator with elapsed time while a task is running", async () => {
    vi.useFakeTimers({ now: new Date("2026-06-30T10:00:00Z") });
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000099");
    /** @type {undefined | ((response: Response) => void)} */
    let resolveDiagnostic;
    const diagnosticResponse = new Promise((resolve) => {
      resolveDiagnostic = resolve;
    });
    server.use(
      http.post("/api/v1/agent/diagnostics", async ({ request }) => {
        diagnosticRequests.push(await request.json());
        return diagnosticResponse;
      }),
    );
    const { container } = renderPage();
    const taskGoal = screen.getByRole("textbox", { name: "任务目标" });

    fireEvent.change(taskGoal, { target: { value: "今天北京天气怎么样？" } });
    const sendButton = screen.getByRole("button", { name: "发送任务" });
    expect(sendButton).toBeEnabled();
    fireEvent.click(sendButton);

    expect(screen.getByText("正在执行只读诊断。")).toBeInTheDocument();
    const executionIndicator = container.querySelector('[data-agent-execution-indicator="running"]');
    expect(executionIndicator).toBeInTheDocument();
    expect(within(/** @type {HTMLElement} */ (executionIndicator)).getByText("执行中")).toBeInTheDocument();
    expect(within(/** @type {HTMLElement} */ (executionIndicator)).getByText("耗时 00:00")).toBeInTheDocument();
    expect(executionIndicator?.querySelector('[class*="executionInfoMark"]')?.textContent).toBe("i");

    await act(async () => {
      vi.advanceTimersByTime(3000);
    });

    expect(within(/** @type {HTMLElement} */ (executionIndicator)).getByText("耗时 00:03")).toBeInTheDocument();
    expect(agentWorkspaceCss).toContain(".executionProgress");
    expect(agentWorkspaceCss).toContain("@keyframes agent-execution-info-pulse");
    const executionProgressRule =
      agentWorkspaceCss.match(/(?:^|\n)[.]executionProgress\s*[{][^}]+[}]/u)?.[0] ?? "";
    expect(executionProgressRule).toContain("overflow: visible");
    expect(executionProgressRule).toContain("padding: 10px 0 3px 4px");

    await act(async () => {
      resolveDiagnostic?.(HttpResponse.json(agentTaskResult));
      await Promise.resolve();
    });
  });

  test("submits typed task goals through the main AgentScope diagnostic endpoint", async () => {
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000001");
    const user = userEvent.setup();

    renderPage();

    expect(await screen.findByText("提交后由服务端路由")).toBeInTheDocument();
    await user.type(
      screen.getByRole("textbox", { name: "任务目标" }),
      "检查 node-a 健康状态并总结风险",
    );
    const sendButton = screen.getByRole("button", { name: "发送任务" });
    await waitFor(() => expect(sendButton).toBeEnabled());
    await user.click(sendButton);

    expect(await screen.findByText("已完成只读诊断，未发现阻塞风险。")).toBeInTheDocument();
    expect(await screen.findByText("AGENT_TASK_RESULT")).toBeInTheDocument();
    expect(await screen.findByText(/tools 1/u)).toBeInTheDocument();
    expect(await screen.findByText("Shanghai")).toBeInTheDocument();
    expect(await screen.findByText("Sunny")).toBeInTheDocument();
    expect(await screen.findByText("31.2°C")).toBeInTheDocument();
    expect(await screen.findByText("对话执行状态")).toBeInTheDocument();
    expect(screen.queryByText("当前输入")).not.toBeInTheDocument();
    expect(screen.getAllByText("已完成").length).toBeGreaterThan(0);
    expect(screen.getAllByText("检查 node-a 健康状态并总结风险")).toHaveLength(1);
    expect(await screen.findByText("已执行 Skill")).toBeInTheDocument();
    expect(screen.getAllByText(/weather-current-read/u).length).toBeGreaterThan(0);
    expect(screen.getByText("执行链")).toBeInTheDocument();
    expect(screen.queryByText("READ_ONLY 策略")).not.toBeInTheDocument();
    expect(screen.queryByText("M07 Worker")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "查看对话执行详情" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "查看 Skill 调用详情" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "查看执行链详情" })).toBeEnabled();
    expect(screen.queryByText(agentTaskResult.taskId)).not.toBeInTheDocument();
    expect(screen.queryByText("tool-call-weather-1")).not.toBeInTheDocument();
    expect(screen.queryByText("weather-current-read:1.0.0:output")).not.toBeInTheDocument();
    expect(screen.queryByText("Agent 请求入参")).not.toBeInTheDocument();
    expect(screen.queryByText(/Skill 原始入参未包含在 AgentTaskResult 中/u)).not.toBeInTheDocument();
    expect(screen.queryByText("Skill 出参")).not.toBeInTheDocument();
    expect(screen.queryByText(/"location": "Shanghai"/u)).not.toBeInTheDocument();
    expect(screen.queryByText("weather-current-read@1.0.0")).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "查看对话执行详情" }));
    const taskDialog = await screen.findByRole("dialog", { name: "对话执行详情" });
    expect(taskDialog).toBeInTheDocument();
    const taskDialogIcon = taskDialog.querySelector("[data-dialog-title-icon]");
    expect(taskDialogIcon?.className).toContain("dialogTitleIcon");
    expect(taskDialogIcon?.className).not.toContain("panelIconTask");
    expect(taskDialogIcon?.querySelector("svg")).toBeInTheDocument();
    expect(screen.queryByText("P1 只读诊断")).not.toBeInTheDocument();
    expect(document.documentElement.style.overflow).toBe("hidden");
    expect(document.body.style.overflow).toBe("hidden");
    expect(within(taskDialog).getByText("状态")).toBeInTheDocument();
    expect(within(taskDialog).getByText("策略")).toBeInTheDocument();
    expect(screen.getByText("输入意图")).toBeInTheDocument();
    expect(screen.getAllByText("检查 node-a 健康状态并总结风险")).toHaveLength(2);
    expect(within(taskDialog).getByText("development")).toBeInTheDocument();
    expect(within(taskDialog).getByText(agentTaskResult.taskId)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "关闭详情" }));
    expect(document.documentElement.style.overflow).toBe("");
    expect(document.body.style.overflow).toBe("");
    await user.click(screen.getByRole("button", { name: "查看 Skill 调用详情" }));
    const skillDialog = await screen.findByRole("dialog", { name: "Skill 调用详情" });
    expect(skillDialog).toBeInTheDocument();
    expect(skillDialog.querySelector("[data-dialog-title-icon] svg")).toBeInTheDocument();
    const skillRouteAnimation = screen.getByLabelText("Skill 路由动画");
    expect(skillRouteAnimation).toHaveAttribute("data-flow-animation", "skill-route");
    expect(screen.getByText("Skill 路由流程")).toBeInTheDocument();
    expect(screen.getByText("目录筛选")).toBeInTheDocument();
    expect(screen.getByText("参数契约")).toBeInTheDocument();
    expect(screen.getByText("入参要求")).toBeInTheDocument();
    expect(screen.getByText("出参要求")).toBeInTheDocument();
    expect(screen.getByText("Agent inputParameters")).toBeInTheDocument();
    expect(screen.getByText("weather-current-read:1.0.0:output")).toBeInTheDocument();
    expect(screen.getByText("tool-call-weather-1")).toBeInTheDocument();
    expect(screen.getByText("Agent 请求入参")).toBeInTheDocument();
    expect(screen.getByText(/Skill 原始入参未包含在 AgentTaskResult 中/u)).toBeInTheDocument();
    expect(screen.getByText("Skill 出参")).toBeInTheDocument();
    expect(screen.getByText(/"location": "Shanghai"/u)).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "关闭详情" }));
    await user.click(screen.getByRole("button", { name: "查看执行链详情" }));
    const chainDialog = await screen.findByRole("dialog", { name: "执行链详情" });
    expect(chainDialog).toBeInTheDocument();
    expect(chainDialog.querySelector("[data-dialog-title-icon] svg")).toBeInTheDocument();
    const flowAnimation = screen.getByLabelText("执行流程动画");
    expect(flowAnimation).toHaveAttribute("data-flow-animation", "agent-chain");
    expect(screen.getByText("Agent 主链路")).toBeInTheDocument();
    expect(screen.getByText("Skill 调用流程")).toBeInTheDocument();
    expect(screen.getByText("意图接入")).toBeInTheDocument();
    expect(screen.getByText("策略校验")).toBeInTheDocument();
    expect(screen.getByText("路由匹配")).toBeInTheDocument();
    expect(screen.getByText("授权校验")).toBeInTheDocument();
    expect(screen.getByText("Worker 只读执行")).toBeInTheDocument();
    expect(screen.getByText("输出契约校验")).toBeInTheDocument();
    expect(screen.getByText("操作员意图")).toBeInTheDocument();
    expect(screen.getByText("weather-current-read@1.0.0")).toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "关闭详情" }));
    expect(document.documentElement.style.overflow).toBe("");
    expect(document.body.style.overflow).toBe("");
    expect(screen.queryByText("INC-2841")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("当前 Agent 诊断任务")).not.toBeInTheDocument();
    expect(window.localStorage.getItem("ops-agent:last-workflow-id")).toBe(agentTaskResult.workflowId);
    expect(JSON.parse(window.sessionStorage.getItem("ops-agent:last-agent-result") ?? "{}"))
      .toMatchObject({
        workflowId: agentTaskResult.workflowId,
        toolResults: [
          {
            outputSchemaId: "weather-current-read:1.0.0:output",
          },
        ],
      });
    expect(JSON.parse(window.sessionStorage.getItem("ops-agent:last-agent-exchange") ?? "{}"))
      .toMatchObject({
        userIntent: "检查 node-a 健康状态并总结风险",
        result: {
          workflowId: agentTaskResult.workflowId,
        },
      });
    expect(diagnosticRequests).toEqual([
      {
        targetEnvironment: "development",
        idempotencyKey:
          "agent-workspace-task-00000000-0000-4000-8000-000000000001",
        userIntent: "检查 node-a 健康状态并总结风险",
        inputParameters: {},
      },
    ]);
  });

  test("renders the submitted weather question and Agent answer as chat bubbles", async () => {
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000002");
    const user = userEvent.setup();

    const { container } = renderPage();

    expect(await screen.findByText("提交后由服务端路由")).toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: "任务目标" }), "今天天气怎么样");
    const sendButton = screen.getByRole("button", { name: "发送任务" });
    await waitFor(() => expect(sendButton).toBeEnabled());
    await user.click(sendButton);

    await waitFor(() =>
      expect(container.querySelectorAll('[data-message-tone="operator"]')).toHaveLength(1),
    );
    await waitFor(() =>
      expect(container.querySelectorAll('[data-message-tone="agent"]')).toHaveLength(1),
    );
    const weatherQuestion = container.querySelector('[data-message-tone="operator"]');
    const weatherAnswerBubble = container.querySelector('[data-message-tone="agent"]');

    expect(weatherQuestion).toHaveTextContent("今天天气怎么样");
    expect(weatherQuestion?.className).toContain("operator");
    expect(weatherAnswerBubble).toHaveTextContent("Shanghai");
    expect(weatherAnswerBubble?.className).toContain("agent");
    expect(container.querySelectorAll('[data-message-tone="operator"]')).toHaveLength(1);
    expect(container.querySelectorAll('[data-message-tone="agent"]')).toHaveLength(1);
  });

  test("renders Agent markdown summaries as structured rich text", async () => {
    server.use(
      http.post("/api/v1/agent/diagnostics", async ({ request }) => {
        diagnosticRequests.push(await request.json());
        return HttpResponse.json({
          ...agentTaskResult,
          summary: [
            "# Shanghai 当前天气",
            "",
            "| 字段 | 值 |",
            "|---|---|",
            "| **地点** | Shanghai |",
            "| 天气状况 | Cloudy |",
            "| 观测时间 | `2026-06-28T10:00:30Z` |",
            "",
            "- 执行状态：**SUCCEEDED**",
          ].join("\n"),
        });
      }),
    );
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000012");
    const user = userEvent.setup();

    const { container } = renderPage();

    expect(await screen.findByText("提交后由服务端路由")).toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: "任务目标" }), "查询上海天气");
    const sendButton = screen.getByRole("button", { name: "发送任务" });
    await waitFor(() => expect(sendButton).toBeEnabled());
    await user.click(sendButton);

    await waitFor(() =>
      expect(container.querySelectorAll('[data-message-tone="agent"]')).toHaveLength(1),
    );
    const agentMessage = container.querySelector('[data-message-tone="agent"]');
    expect(agentMessage).toBeInTheDocument();
    const agentMessageScope = within(/** @type {HTMLElement} */ (agentMessage));
    expect(agentMessageScope.getByRole("heading", { name: "Shanghai 当前天气" })).toBeInTheDocument();
    const markdownTable = agentMessageScope.getByRole("table", { name: "Agent 摘要表格" });
    expect(markdownTable).toBeInTheDocument();
    const markdownTableScope = within(markdownTable);
    expect(markdownTableScope.getByText("字段")).toBeInTheDocument();
    expect(markdownTableScope.getByText("地点")).toBeInTheDocument();
    expect(markdownTableScope.getByText("Shanghai")).toBeInTheDocument();
    expect(markdownTableScope.getByText("2026-06-28T10:00:30Z")).toBeInTheDocument();
    expect(agentMessageScope.getByText("执行状态：")).toBeInTheDocument();
    expect(agentMessageScope.getByText("SUCCEEDED")).toBeInTheDocument();
    expect(agentMessage).not.toHaveTextContent("|---|---|");
    expect(agentWorkspaceSource).not.toContain("dangerouslySetInnerHTML");
  });

  test("submits the task goal when pressing Enter in the composer", async () => {
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000010");
    const user = userEvent.setup();

    const { container } = renderPage();

    expect(await screen.findByText("提交后由服务端路由")).toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: "任务目标" }), "今天天气怎么样{enter}");

    await waitFor(() => expect(diagnosticRequests).toHaveLength(1));
    expect(diagnosticRequests[0]).toMatchObject({
      targetEnvironment: "development",
      idempotencyKey:
        "agent-workspace-task-00000000-0000-4000-8000-000000000010",
      userIntent: "今天天气怎么样",
      inputParameters: {},
    });
    await waitFor(() =>
      expect(container.querySelector('[data-message-tone="operator"]')).toHaveTextContent(
        "今天天气怎么样",
      ),
    );
  });

  test("keeps the send action inside the composer shell and hides unused shortcut buttons", async () => {
    const { container } = renderPage();

    expect(await screen.findByText("工作会话")).toBeInTheDocument();

    const composerBox = container.querySelector('[class*="composerBox"]');
    const sendButton = screen.getByRole("button", { name: "发送任务" });
    expect(composerBox).toContainElement(sendButton);
    expect(container.querySelector('[class*="composerFooter"]')).toBeNull();
    expect(container.querySelector('[class*="composerTags"]')).toBeNull();
    expect(screen.queryByText("+ 服务")).not.toBeInTheDocument();
    expect(screen.queryByText("+ 告警")).not.toBeInTheDocument();
    expect(screen.queryByText("+ 工单")).not.toBeInTheDocument();
    expect(screen.queryByText("+ 历史 workflow")).not.toBeInTheDocument();
  });

  test("restores the latest Agent chat exchange when returning to the workspace", async () => {
    window.sessionStorage.setItem(
      "ops-agent:last-agent-exchange",
      JSON.stringify({
        userIntent: "今天天气怎么样",
        result: agentTaskResult,
      }),
    );

    const { container } = renderPage();

    await waitFor(() =>
      expect(container.querySelector('[data-message-tone="operator"]')).toHaveTextContent(
        "今天天气怎么样",
      ),
    );
    await waitFor(() =>
      expect(container.querySelector('[data-message-tone="agent"]')).toHaveTextContent("Shanghai"),
    );
    expect(await screen.findByText("AGENT_TASK_RESULT")).toBeInTheDocument();
  });

  test("appends a new chat exchange when sending the same weather question again", async () => {
    window.sessionStorage.setItem(
      "ops-agent:last-agent-exchange",
      JSON.stringify({
        userIntent: "今天天气怎么样",
        result: agentTaskResult,
      }),
    );
    vi.spyOn(crypto, "randomUUID").mockReturnValue("00000000-0000-4000-8000-000000000003");
    const user = userEvent.setup();

    const { container } = renderPage();

    expect(await screen.findByText("AGENT_TASK_RESULT")).toBeInTheDocument();
    expect(container.querySelectorAll('[data-message-tone="operator"]')).toHaveLength(1);
    expect(container.querySelectorAll('[data-message-tone="agent"]')).toHaveLength(1);

    await user.type(screen.getByRole("textbox", { name: "任务目标" }), "今天天气怎么样");
    const sendButton = screen.getByRole("button", { name: "发送任务" });
    await waitFor(() => expect(sendButton).toBeEnabled());
    await user.click(sendButton);

    await waitFor(() => expect(diagnosticRequests).toHaveLength(1));
    [...container.querySelectorAll('[data-message-tone="operator"]')].forEach((message) => {
      expect(message).toHaveTextContent("今天天气怎么样");
    });
    expect(container.querySelectorAll('[data-message-tone="operator"]')).toHaveLength(2);
    expect(container.querySelectorAll('[data-message-tone="agent"]')).toHaveLength(2);
    expect(diagnosticRequests).toEqual([
      {
        targetEnvironment: "development",
        idempotencyKey:
          "agent-workspace-task-00000000-0000-4000-8000-000000000003",
        userIntent: "今天天气怎么样",
        inputParameters: {},
      },
    ]);
  });

  test("restores the latest Agent result when returning to the workspace", async () => {
    window.sessionStorage.setItem(
      "ops-agent:last-agent-result",
      JSON.stringify(agentTaskResult),
    );

    renderPage();

    expect(await screen.findByText("AGENT_TASK_RESULT")).toBeInTheDocument();
    expect(screen.getAllByText(agentTaskResult.workflowId).length).toBeGreaterThan(0);
    expect(screen.queryByText("Shanghai")).not.toBeInTheDocument();
    expect(screen.queryByText("Sunny")).not.toBeInTheDocument();
    expect(screen.queryByText("31.2°C")).not.toBeInTheDocument();
    await userEvent.click(screen.getByRole("button", { name: "查看对话执行详情" }));
    const taskDialog = await screen.findByRole("dialog", { name: "对话执行详情" });
    expect(within(taskDialog).getByText(agentTaskResult.workflowId)).toBeInTheDocument();
  });

  test("shows AgentScope runtime disabled response without falling back to fixed Skill", async () => {
    /** @type {unknown[]} */
    const fixedSkillRequests = [];
    server.use(
      http.post("/api/v1/agent/diagnostics", async ({ request }) => {
        diagnosticRequests.push(await request.json());
        return HttpResponse.json(
          {
            code: "AGENT_RUNTIME_DISABLED",
            message: "Agent runtime is disabled for this environment.",
          },
          { status: 503 },
        );
      }),
      http.post("/internal/diagnostics/read-only/events", async ({ request }) => {
        fixedSkillRequests.push(await request.json());
        return HttpResponse.text(sseFromEvents(readOnlyDiagnosticEvents), {
          headers: { "Content-Type": "text/event-stream" },
        });
      }),
    );
    const user = userEvent.setup();

    const { container } = renderPage();

    expect(await screen.findByText("提交后由服务端路由")).toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: "任务目标" }), "检查 node-a 健康状态");
    const sendButton = screen.getByRole("button", { name: "发送任务" });
    await waitFor(() => expect(sendButton).toBeEnabled());
    await user.click(sendButton);

    await waitFor(() =>
      expect(container.querySelectorAll('[data-message-tone="agent"]')).toHaveLength(1),
    );
    const agentMessages = container.querySelectorAll('[data-message-tone="agent"]');
    expect(agentMessages).toHaveLength(1);
    expect(agentMessages[0]).toHaveTextContent(
      "AGENT_RUNTIME_DISABLED: Agent runtime is disabled for this environment.",
    );
    expect(await screen.findByText("Agent 诊断请求失败。")).toBeInTheDocument();
    expect(screen.queryByText("当前输入")).not.toBeInTheDocument();
    expect(screen.getAllByText("检查 node-a 健康状态")).toHaveLength(1);
    expect(screen.queryByLabelText("当前 Agent 诊断任务")).not.toBeInTheDocument();
    expect(fixedSkillRequests).toEqual([]);
  });

  test("keeps main Agent task submission available when routing preview is unavailable", async () => {
    server.use(
      http.post("/internal/routing/skills/search", () =>
        HttpResponse.json(
          { code: "POLICY_DENIED", message: "role is not sufficient" },
          { status: 403 },
        ),
      ),
    );
    const user = userEvent.setup();

    renderPage();

    expect(await screen.findByText("提交后由服务端路由")).toBeInTheDocument();
    expect(screen.queryByText("unavailable")).not.toBeInTheDocument();
    await user.type(screen.getByRole("textbox", { name: "任务目标" }), "检查 node-a 健康状态");
    const sendButton = screen.getByRole("button", { name: "发送任务" });
    await waitFor(() => expect(sendButton).toBeEnabled());
    await user.click(sendButton);

    await waitFor(() => expect(diagnosticRequests).toHaveLength(1));
    expect(await screen.findByText("已完成只读诊断，未发现阻塞风险。")).toBeInTheDocument();
    expect(screen.queryByLabelText("当前 Agent 诊断任务")).not.toBeInTheDocument();
  });

  test("does not render template message role avatars before a real exchange", async () => {
    const { container } = renderPage();

    expect(await screen.findByText("ops.reader")).toBeInTheDocument();
    expect(container.querySelectorAll('[class*="messageRoleIcon"] svg')).toHaveLength(0);
  });

  test("renders the workday countdown as a compact creative timer", () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(2026, 5, 16, 19, 0, 0));

    renderPage();

    const workdayCountdown = screen.getByRole("timer", { name: "下班倒计时：00:00:00" });

    expect(screen.queryByRole("img", { name: "下班倒计时创意头像" })).not.toBeInTheDocument();
    expect(workdayCountdown).toHaveAttribute("data-creative-timer", "workday-countdown");
    expect(workdayCountdown).toHaveTextContent("下班倒计时");
    expect(workdayCountdown).toHaveTextContent("00:00:00");
    expect(workdayCountdown.children).toHaveLength(2);
    expect(workspaceStatusBarCss).toContain(".workdayCountdown");
    expect(workspaceStatusBarCss).toContain(".countdownGlyph");
    expect(workspaceStatusBarCss).not.toContain(".countdownTrack");
    expect(workspaceStatusBarCss).not.toContain(".workdayCountdown::before");
    expect(workspaceStatusBarCss).not.toContain(".workdayCountdown::after");
    expect(workspaceStatusBarCss).not.toContain(".workdayCountdownAvatar");
  });

  test("keeps long operator IDs contained and inspectable in the operator actions", async () => {
    const longOperatorId =
      "operator-central-observability-readonly-user-20260616-abcdef1234567890";
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: true,
          subject: longOperatorId,
          username: "ops.reader",
          roles: ["ROLE_agent-reader"],
          authenticationType: "built-in",
        }),
      ),
    );

    const { container } = renderPage();

    const operatorIdText = await screen.findByTitle(`ID ${longOperatorId}`);
    const operatorProfile = container.querySelector("[data-operator-profile]");
    const operatorDockRule =
      workspaceStatusBarCss.match(/[.]operatorDock\s*[{][^}]+[}]/u)?.[0] ?? "";
    const operatorProfileRule =
      workspaceStatusBarCss.match(/[.]operatorProfile\s*[{][^}]+[}]/u)?.[0] ?? "";
    const operatorAvatarRule =
      workspaceStatusBarCss.match(/[.]operatorAvatar\s*[{][^}]+[}]/u)?.[0] ?? "";
    const operatorIdentityRule =
      workspaceStatusBarCss.match(/[.]operatorIdentity\s*[{][^}]+[}]/u)?.[0] ?? "";
    const workdayCountdownRule =
      workspaceStatusBarCss.match(/[.]workdayCountdown\s*[{][^}]+[}]/u)?.[0] ?? "";
    const countdownGlyphRule =
      workspaceStatusBarCss.match(/[.]countdownGlyph\s*[{][^}]+[}]/u)?.[0] ?? "";
    const logoutButtonRule =
      workspaceStatusBarCss.match(/[.]logoutButton\s*[{][^}]+[}]/u)?.[0] ?? "";
    const logoutIconBadgeRule =
      [...workspaceStatusBarCss.matchAll(/[.]logoutIconBadge\s*[{][^}]+[}]/gu)].at(-1)?.[0] ?? "";
    const actionIconBaseRule =
      workspaceStatusBarCss.match(/[.]countdownGlyph,\s*\n[.]logoutIconBadge\s*[{][^}]+[}]/u)?.[0] ?? "";
    const operatorIdentityTextRule =
      workspaceStatusBarCss.match(/[.]operatorIdentity strong,\s*\n[.]operatorIdentity small\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(operatorIdText).toHaveTextContent(`ID ${longOperatorId}`);
    expect(operatorProfile).toBeInTheDocument();
    expect(operatorDockRule).toContain("min-width: 0");
    expect(operatorDockRule).toContain("grid-template-columns: 132px minmax(150px, 190px) 92px");
    expect(operatorDockRule).toContain("gap: 9px");
    expect(operatorDockRule).not.toContain("backdrop-filter");
    expect(operatorProfileRule).toContain("min-width: 0");
    expect(operatorProfileRule).toContain("grid-template-columns: 36px minmax(0, 1fr)");
    expect(operatorProfileRule).toContain("height: 48px");
    expect(operatorProfileRule).toContain("border: 1px solid var(--toolbar-line)");
    expect(operatorProfileRule).toContain("border-radius: 13px");
    expect(operatorAvatarRule).toContain("width: 34px");
    expect(operatorAvatarRule).toContain("height: 34px");
    expect(operatorIdentityRule).toContain("min-width: 0");
    expect(operatorIdentityRule).not.toContain("border-right");
    expect(workdayCountdownRule).toContain("width: 132px");
    expect(workdayCountdownRule).toContain("height: 48px");
    expect(logoutButtonRule).toContain("width: 92px");
    expect(logoutButtonRule).toContain("height: 48px");
    expect(logoutButtonRule).toContain("border: 1px solid oklch");
    expect(logoutButtonRule).toContain("border-radius: 13px");
    expect(actionIconBaseRule).toContain("width: 34px");
    expect(actionIconBaseRule).toContain("height: 34px");
    expect(actionIconBaseRule).toContain("border-radius: 11px");
    expect(countdownGlyphRule).toContain("var(--toolbar-blue)");
    expect(logoutIconBadgeRule).toContain("var(--toolbar-red)");
    expect(operatorIdentityTextRule).toContain("text-overflow: ellipsis");
  });

  test("renders the operator avatar as a restrained icon instead of text initials", async () => {
    const { container } = renderPage();

    expect(await screen.findByText("ops.reader")).toBeInTheDocument();

    const operatorAvatar = container.querySelector("[class*='operatorAvatar']");

    expect(operatorAvatar).toBeInTheDocument();
    expect(operatorAvatar?.textContent).toBe("");
    expect(operatorAvatar?.querySelector("svg")).toBeInTheDocument();
    expect(workspaceStatusBarCss).not.toContain(".operatorAvatarCore");
    expect(workspaceStatusBarCss).not.toContain(".operatorAvatarOrbit");
  });

  test("keeps the outer glass frame separated from inner card borders", async () => {
    renderPage();

    expect(await screen.findByText("Agent 工作区")).toBeInTheDocument();

    const agentCanvasRule =
      agentWorkspaceCss.match(/[.]agentCanvas\s*[{][^}]+[}]/u)?.[0] ?? "";
    const frameRule =
      workspaceFrameCss.match(/[.]workspaceFrame\s*[{][^}]+[}]/u)?.[0] ?? "";
    const frameMetricRule =
      workspaceFrameCss.match(/[.]workspaceFrame\[data-workspace-frame="true"\]\s*[{][^}]+[}]/u)?.[0] ??
      "";

    expect(frameRule).toContain("box-sizing: border-box");
    expect(agentCanvasRule).not.toContain("box-sizing: border-box");
    expect(agentCanvasRule).not.toContain("padding: 9px");
    expect(frameRule).toContain("border: 1px solid rgba(166, 64, 92, 0.18)");
    expect(frameRule).toContain("border-radius: 24px");
    expect(frameRule).toContain("0 18px 38px rgba(166, 64, 92, 0.08)");
    expect(frameRule).toContain("inset 0 1px 0 rgba(255, 255, 255, 0.72)");
    expect(frameMetricRule).toContain("padding: 9px");
    expect(agentCanvasRule).not.toContain("border: 1px solid rgba(166, 64, 92, 0.18)");
    expect(agentCanvasRule).not.toContain("border-radius: 24px");
    expect(agentCanvasRule).not.toContain("box-shadow:");
    expect(agentWorkspaceCss).not.toContain(".agentCanvas::before");
    expect(agentWorkspaceCss).not.toContain(".agentCanvas::after");
  });

  test("delegates outer workspace sizing to the shared frame", () => {
    const agentCanvasRule =
      agentWorkspaceCss.match(/[.]agentCanvas\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(agentCanvasRule).toContain("--agent-layout-gap: var(--workspace-layout-gap)");
    expect(agentCanvasRule).not.toContain("--agent-shell-left");
    expect(agentCanvasRule).not.toContain("--agent-canvas-left");
    expect(agentCanvasRule).not.toContain("width: calc(100vw");
    expect(agentCanvasRule).not.toContain("height: calc(100vh");
    expect(agentCanvasRule).not.toContain("margin-left: 0");
    expect(agentCanvasRule).not.toContain("margin-top: 0");
    expect(agentCanvasRule).not.toContain("margin-bottom: 0");
  });

  test("keeps the work session height fixed and scrolls overflowing conversation content", async () => {
    renderPage();

    expect(await screen.findByText("工作会话")).toBeInTheDocument();

    const agentCanvasRule =
      agentWorkspaceCss.match(/[.]agentCanvas\s*[{][^}]+[}]/u)?.[0] ?? "";
    const frameRule =
      workspaceFrameCss.match(/[.]workspaceFrame\s*[{][^}]+[}]/u)?.[0] ?? "";
    const agentLayoutRule =
      agentWorkspaceCss.match(/(?:^|\n)[.]agentLayout\s*[{][^}]+[}]/u)?.[0] ?? "";
    const exchangeWindowRule =
      agentWorkspaceCss.match(/[.]exchangeWindow\s*[{][^}]+[}]/u)?.[0] ?? "";
    const exchangeBodyRule =
      agentWorkspaceCss.match(/[.]exchangeBody\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(agentCanvasRule).not.toContain("height: calc(100vh");
    expect(agentLayoutRule).toContain("height: 100%");
    expect(agentLayoutRule).toContain("min-height: 0");
    expect(exchangeWindowRule).toContain("height: 100%");
    expect(exchangeWindowRule).toContain("min-height: 0");
    expect(exchangeWindowRule).toContain("grid-template-rows: auto minmax(0, 1fr) auto");
    expect(exchangeWindowRule).toContain("overflow: hidden");
    expect(exchangeBodyRule).toContain("min-height: 0");
    expect(exchangeBodyRule).toContain("overflow-y: auto");
    expect(frameRule).toContain("overflow: hidden");
    expect(agentCanvasRule).not.toContain("overflow: clip");
    expect(agentCanvasRule).not.toContain("overflow-clip-margin");
    expect(agentCanvasRule).not.toContain("overflow: hidden");
  });

  test("uses the shared dialog standard for detail overlays without nested JSON scrolling", async () => {
    renderPage();

    expect(await screen.findByText("工作会话")).toBeInTheDocument();

    const backdropRule =
      dialogCss.match(/[.]dialogBackdrop\s*[{][^}]+[}]/u)?.[0] ?? "";
    const dialogRule =
      dialogCss.match(/[.]dialogSurface\s*[{][^}]+[}]/u)?.[0] ?? "";
    const wideDialogRule =
      dialogCss.match(/[.]dialogSurface\[data-dialog-size="wide"\]\s*[{][^}]+[}]/u)?.[0] ?? "";
    const preRule =
      agentWorkspaceCss.match(/[.]ioBlock pre\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(backdropRule).toContain("position: fixed");
    expect(backdropRule).toContain("inset: 0");
    expect(backdropRule).toContain("align-items: center");
    expect(backdropRule).toContain("justify-items: center");
    expect(dialogRule).toContain("grid-template-rows: auto minmax(0, 1fr)");
    expect(wideDialogRule).toContain("width: min(1180px, calc(100vw - 80px))");
    expect(wideDialogRule).toContain("max-height: calc(100vh - 64px)");
    expect(agentWorkspaceSource).toContain("from \"../../components/primitives/Dialog.jsx\"");
    expect(agentWorkspaceSource).toContain("iconByPanel");
    expect(agentWorkspaceSource).toContain("icon={iconByPanel[activePanel]}");
    expect(agentWorkspaceSource).not.toContain('eyebrow="P1 只读诊断"');
    expect(agentWorkspaceSource).not.toContain("createPortal");
    expect(agentWorkspaceCss).not.toContain(".detailDialogBackdrop");
    expect(agentWorkspaceCss).not.toContain(".dialogCloseButton");
    expect(preRule).not.toContain("max-height");
    expect(preRule).toContain("overflow: hidden");
  });

  test("renders unified badge icons for side panel headings", async () => {
    const { container } = renderPage();

    expect(await screen.findByText("对话执行状态")).toBeInTheDocument();
    expect(container.querySelectorAll('[class*="panelIcon"] svg')).toHaveLength(3);
  });

  test("logs out through the browser authentication endpoint and returns to login", async () => {
    const user = userEvent.setup();
    /** @type {string[]} */
    const calls = [];
    server.use(
      http.get("/auth/logout", ({ request }) => {
        calls.push(new URL(request.url).pathname);
        return HttpResponse.text("<!doctype html><title>Operator console</title>", {
          headers: { "Content-Type": "text/html" },
        });
      }),
    );
    window.history.pushState({}, "", "/overview");

    renderPage();

    await user.click(await screen.findByRole("button", { name: "登出当前账号" }));

    expect(calls).toEqual(["/auth/logout"]);
    await waitFor(() => expect(window.location.pathname).toBe("/login"));
  });

  test("ignores route preview refusal without disabling main Agent submission", async () => {
    server.use(
      http.post("/internal/routing/skills/search", () =>
        HttpResponse.json(
          { code: "POLICY_DENIED", message: "role is not sufficient" },
          { status: 403 },
        ),
      ),
    );

    renderPage();

    expect(await screen.findByText("提交后由服务端路由")).toBeInTheDocument();
    expect(screen.queryByText("unavailable")).not.toBeInTheDocument();
    expect(screen.getAllByText("等待发送").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "发送任务" })).toBeDisabled();
    await userEvent.type(
      screen.getByRole("textbox", { name: "任务目标" }),
      "检查 node-a 健康状态",
    );
    expect(screen.getByRole("button", { name: "发送任务" })).toBeEnabled();
  });

  test("ignores an empty route preview state without disabling main Agent submission", async () => {
    server.use(
      http.post("/internal/routing/skills/search", () =>
        HttpResponse.json({ total: 0, candidates: [] }),
      ),
    );

    renderPage();

    expect(await screen.findByText("提交后由服务端路由")).toBeInTheDocument();
    expect(screen.queryByText("无候选")).not.toBeInTheDocument();
    expect(screen.getAllByText("等待发送").length).toBeGreaterThan(0);
    expect(screen.getByRole("button", { name: "发送任务" })).toBeDisabled();
    await userEvent.type(
      screen.getByRole("textbox", { name: "任务目标" }),
      "检查 node-a 健康状态",
    );
    expect(screen.getByRole("button", { name: "发送任务" })).toBeEnabled();
  });

  test("renders a login-aligned glass treatment as an alternate Agent page", async () => {
    const { container } = renderPage();

    expect(await screen.findByText("工作会话")).toBeInTheDocument();
    expect(container.querySelectorAll("[data-agent-ion]")).toHaveLength(12);
    expect(container.querySelector("[class*='agentIonField']")).toHaveAttribute("aria-hidden", "true");

    const appCapsuleRule =
      workspaceStatusBarCss.match(/[.]appCapsule\s*[{][^}]+[}]/u)?.[0] ?? "";
    const brandPlateRule =
      workspaceStatusBarCss.match(/[.]brandPlate\s*[{][^}]+[}]/u)?.[0] ?? "";
    const brandPlateBeforeRule =
      workspaceStatusBarCss.match(/[.]brandPlate::before\s*[{][^}]+[}]/u)?.[0] ?? "";
    const brandPlateAfterRule =
      workspaceStatusBarCss.match(/[.]brandPlate::after\s*[{][^}]+[}]/u)?.[0] ?? "";
    const brandNameRule =
      workspaceStatusBarCss.match(/[.]brandName\s*[{][^}]+[}]/u)?.[0] ?? "";
    const workspaceContextRule =
      workspaceStatusBarCss.match(/[.]workspaceContext\s*[{][^}]+[}]/u)?.[0] ?? "";
    const signalRailRule =
      workspaceStatusBarCss.match(/[.]signalRail\s*[{][^}]+[}]/u)?.[0] ?? "";
    const operatorDockRule =
      workspaceStatusBarCss.match(/[.]operatorDock\s*[{][^}]+[}]/u)?.[0] ?? "";
    const agentCanvasRule =
      agentWorkspaceCss.match(/[.]agentCanvas\s*[{][^}]+[}]/u)?.[0] ?? "";
    const workspaceFrameRule =
      workspaceFrameCss.match(/[.]workspaceFrame\s*[{][^}]+[}]/u)?.[0] ?? "";
    const capsuleHeadingRule =
      workspaceStatusBarCss.match(/[.]capsuleHeading\s*[{][^}]+[}]/u)?.[0] ?? "";
    const exchangeWindowRule =
      agentWorkspaceCss.match(/[.]exchangeWindow\s*[{][^}]+[}]/u)?.[0] ?? "";
    const agentPanelRule =
      agentWorkspaceCss.match(/[.]agentPanel\s*[{][^}]+[}]/u)?.[0] ?? "";
    const composerBoxRule =
      agentWorkspaceCss.match(/[.]composerBox\s*[{][^}]+[}]/u)?.[0] ?? "";
    const composerInputRule =
      agentWorkspaceCss.match(/[.]composerInput\s*[{][^}]+[}]/u)?.[0] ?? "";
    const composerInputFocusRule =
      agentWorkspaceCss.match(/[.]composerInput:focus,\s*[.]composerInput:focus-visible\s*[{][^}]+[}]/u)?.[0] ?? "";
    const composerBoxFocusWithinRule =
      agentWorkspaceCss.match(/[.]composerBox:focus-within\s*[{][^}]+[}]/u)?.[0] ?? "";
    const agentIonFieldRule =
      agentWorkspaceCss.match(/[.]agentIonField\s*[{][^}]+[}]/u)?.[0] ?? "";
    const pageToolbarButtonRule =
      pageToolbarCss.match(/[.]button\s*[{][^}]+[}]/u)?.[0] ?? "";
    const pageToolbarSurfaceRule =
      pageToolbarCss.match(/[.]surface\s*[{][^}]+[}]/u)?.[0] ?? "";
    const pageToolbarPrimaryRule =
      pageToolbarCss.match(/[.]primary\s*[{][^}]+[}]/u)?.[0] ?? "";
    const sendButtonRule =
      agentWorkspaceCss.match(/[.]sendButton\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(agentWorkspaceCss).toContain("--agent-bg-base: #f6f7f9;");
    expect(agentWorkspaceCss).toContain("--agent-red: #d31145;");
    expect(agentWorkspaceCss).not.toContain("--agent-lime");
    expect(agentWorkspaceCss).toContain("backdrop-filter: blur(18px)");
    expect(agentWorkspaceCss).toContain("@keyframes agent-ion-drift");
    expect(agentWorkspaceCss).toContain("@keyframes frame-glass-sheen");
    expect(workspaceFrameCss).toContain("radial-gradient(circle at 78% 14%, rgba(14, 165, 183, 0.14), transparent 18rem)");
    expect(workspaceFrameRule).toContain("border: 1px solid rgba(166, 64, 92, 0.18)");
    expect(workspaceFrameRule).toContain("border-radius: 24px");
    expect(workspaceFrameRule).toContain("0 18px 38px rgba(166, 64, 92, 0.08)");
    expect(workspaceFrameRule).toContain("inset 0 1px 0 rgba(255, 255, 255, 0.72)");
    expect(agentWorkspaceCss).not.toContain(".agentCanvas::before");
    expect(agentWorkspaceCss).not.toContain(".agentCanvas::after");
    expect(agentCanvasRule).not.toContain("border: 1px solid rgba(166, 64, 92, 0.18)");
    expect(agentCanvasRule).not.toContain("border-radius: 24px");
    expect(agentCanvasRule).not.toContain("box-shadow:");
    expect(workspaceFrameRule).toContain("overflow: hidden");
    expect(agentCanvasRule).not.toContain("overflow: clip");
    expect(agentCanvasRule).not.toContain("overflow-clip-margin");
    expect(agentCanvasRule).not.toContain("overflow: hidden");
    expect(workspaceFrameRule).toContain("background: var(--agent-bg-base)");
    expect(workspaceFrameRule).toContain("font-family: var(--agent-font-sans)");
    expect(agentCanvasRule).not.toContain("background: var(--agent-bg-base)");
    expect(agentCanvasRule).not.toContain("font-family: var(--agent-font-sans)");
    expect(agentIonFieldRule).toContain("pointer-events: none");
    expect(appCapsuleRule).toContain("min-height: 84px");
    expect(appCapsuleRule).toContain("grid-template-columns: minmax(260px, 360px) max-content minmax(0, 1fr) max-content max-content");
    expect(appCapsuleRule).toContain("border: 1px solid oklch");
    expect(appCapsuleRule).toContain("border-radius: 18px");
    expect(appCapsuleRule).toContain("background: oklch");
    expect(appCapsuleRule).not.toContain("backdrop-filter");
    expect(brandPlateRule).toContain("grid-template-columns: 58px minmax(0, 1fr)");
    expect(brandPlateRule).toContain("position: relative");
    expect(brandPlateRule).toContain("isolation: isolate");
    expect(brandPlateRule).toContain("overflow: hidden");
    expect(brandPlateRule).toContain("radial-gradient");
    expect(brandPlateRule).not.toContain("repeating-linear-gradient");
    expect(brandPlateBeforeRule).toContain("radial-gradient");
    expect(brandPlateBeforeRule).toContain("mask-image: linear-gradient");
    expect(brandPlateBeforeRule).not.toContain("repeating-linear-gradient");
    expect(brandPlateBeforeRule).not.toContain("linear-gradient(90deg, transparent 0 60px");
    expect(brandPlateAfterRule).toContain("height: 1px");
    expect(brandPlateAfterRule).toContain("opacity: 0.32");
    expect(brandNameRule).toContain("font-size: 0.73rem");
    expect(brandNameRule).toContain("font-weight: 830");
    expect(brandNameRule).not.toContain("font-weight: 950");
    expect(workspaceContextRule).toContain("width: max-content");
    expect(workspaceContextRule).toContain("grid-template-columns: 38px max-content max-content 68px");
    expect(signalRailRule).toContain("min-width: 68px");
    expect(operatorDockRule).toContain("grid-template-columns: 132px minmax(150px, 190px) 92px");
    expect(capsuleHeadingRule).toContain("font-family: var(--font-heading");
    expect(capsuleHeadingRule).toContain("font-size: 1.06rem");
    expect(capsuleHeadingRule).toContain("font-synthesis-weight: none");
    expect(capsuleHeadingRule).toContain("font-weight: 680");
    expect(capsuleHeadingRule).toContain("line-height: 1.16");
    expect(capsuleHeadingRule).toContain("-webkit-font-smoothing: antialiased");
    expect(capsuleHeadingRule).toContain("white-space: nowrap");
    expect(workspaceStatusBarCss).not.toContain(".brandLockup");
    expect(workspaceStatusBarCss).not.toContain(".workspaceTrail");
    expect(workspaceStatusBarCss).not.toContain(".trailItem");
    expect(workspaceStatusBarCss).not.toContain("frame-glass-sheen");
    expect(agentWorkspaceCss).not.toContain(".primaryAction");
    expect(agentWorkspaceCss).not.toContain(".workspaceExpand");
    expect(agentWorkspaceSource).toContain("PageToolbar.module.css");
    expect(agentWorkspaceSource).toContain("toolbarStyles.surface");
    expect(agentWorkspaceSource).not.toContain("onToggleWorkspace");
    expect(agentWorkspaceCss).not.toContain(".appCapsule,\n  .conversationToolbar");
    expect(pageToolbarSurfaceRule).toContain("border-radius: 14px");
    expect(pageToolbarSurfaceRule).toContain("radial-gradient(circle at 15% 0%, rgba(216, 11, 70, 0.06), transparent 12rem)");
    expect(pageToolbarSurfaceRule).toContain("box-shadow:");
    expect(pageToolbarSurfaceRule).toContain("backdrop-filter: blur(18px)");
    expect(pageToolbarButtonRule).toContain("height: 38px");
    expect(pageToolbarButtonRule).toContain("border-radius: 11px");
    expect(pageToolbarButtonRule).toContain("white-space: nowrap");
    expect(pageToolbarPrimaryRule).toContain("linear-gradient(90deg, var(--page-toolbar-primary");
    expect(exchangeWindowRule).toContain("rgba(255, 255, 255, 0.68)");
    expect(exchangeWindowRule).toContain("backdrop-filter: blur(18px)");
    expect(agentPanelRule).toContain("rgba(255, 255, 255, 0.66)");
    expect(agentPanelRule).toContain("backdrop-filter: blur(18px)");
    expect(composerBoxRule).toContain("background: rgba(255, 255, 255, 0.86)");
    expect(composerBoxRule).toContain("border-radius: 8px");
    expect(composerBoxRule).toContain("grid-template-columns: minmax(0, 1fr) auto");
    expect(composerBoxRule).toContain("min-height: 124px");
    expect(composerBoxRule).toContain("border: 1px solid rgba(37, 132, 169, 0.14)");
    expect(composerBoxRule).toContain("box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.76)");
    expect(composerInputRule).toContain("min-height: 96px");
    expect(composerInputFocusRule).toContain("outline: none");
    expect(composerInputFocusRule).toContain("outline-width: 0");
    expect(composerInputFocusRule).toContain("outline-color: transparent");
    expect(composerInputFocusRule).toContain("box-shadow: none");
    expect(composerBoxFocusWithinRule).toContain("border-color: rgba(37, 132, 169, 0.22)");
    expect(composerBoxFocusWithinRule).toContain("0 0 0 1px rgba(37, 132, 169, 0.1)");
    expect(agentWorkspaceCss).not.toContain(".composerFooter");
    expect(agentWorkspaceCss).not.toContain(".composerTags");
    expect(sendButtonRule).toContain("linear-gradient(90deg, var(--agent-red), #e01851 48%, var(--agent-red-dark))");
    expect(sendButtonRule).toContain("border-radius: 999px");
  });
});

const registeredSkill = {
  descriptor: {
    skillId: "node-health-read",
    version: "1.1.0",
    displayName: "节点健康检查",
    description: "读取节点 CPU、内存和磁盘健康指标。",
    category: "INFRASTRUCTURE_DIAGNOSTICS",
    riskLevel: "READ_ONLY",
    executor: "HTTP",
    outputType: "JSON",
    readOnly: true,
    timeoutSeconds: 30,
    owner: "platform-observability",
    requiredRoles: ["ROLE_agent-reader"],
    tags: ["health", "node"],
    interceptors: ["AUTHORIZATION", "AUDIT"],
    parameters: [
      {
        name: "nodeName",
        displayName: "节点名称",
        description: "受控开发环境节点名。",
        type: "STRING",
        required: true,
        allowedValues: [],
        defaultValue: null,
      },
    ],
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

const defaultHandlers = [
  http.get("/auth/session", () =>
    HttpResponse.json({
      authenticated: true,
      subject: "operator-1",
      username: "ops.reader",
      roles: ["ROLE_agent-reader"],
      authenticationType: "built-in",
    }),
  ),
  http.post("/logout", () => new HttpResponse(null, { status: 204 })),
  http.post("/internal/routing/skills/search", async ({ request }) => {
    expect(await request.json()).toEqual({
      skillId: null,
      category: null,
      maxRiskLevel: "READ_ONLY",
      requiredParameters: [],
      requiredTags: [],
      requestContextTags: [],
      publicationStatusRequired: "VALIDATED",
    });

    return HttpResponse.json({
      total: 1,
      candidates: [
        {
          skill: registeredSkill,
          releaseSnapshot: {
            skillId: "node-health-read",
            version: "1.1.0",
            stage: "GENERAL_AVAILABLE",
            rolloutPercentage: 100,
            targetContextTags: ["p1", "read-only"],
            reason: "P1 read-only diagnostic baseline",
            updatedAt: "2026-06-14T00:00:00Z",
          },
          score: 98,
          matchedRules: ["risk:READ_ONLY", "publication:VALIDATED", "role:agent-reader"],
        },
      ],
    });
  }),
  http.post("/api/v1/agent/diagnostics", async ({ request }) => {
    diagnosticRequests.push(await request.json());

    return HttpResponse.json(agentTaskResult);
  }),
  http.post("/internal/diagnostics/read-only/events", async ({ request }) => {
    diagnosticRequests.push(await request.json());

    return HttpResponse.text(sseFromEvents(readOnlyDiagnosticEvents), {
      headers: { "Content-Type": "text/event-stream" },
    });
  }),
];

const agentTaskResult = {
  schemaVersion: "1.0",
  taskId: "task-0001",
  workflowId: "00000000-0000-4000-8000-000000000301",
  status: "SUCCEEDED",
  summary: "已完成只读诊断，未发现阻塞风险。",
  toolCallCount: 1,
  completedAt: "2026-06-23T08:00:00Z",
  toolResults: [
    {
      schemaVersion: "1.0",
      toolCallId: "tool-call-weather-1",
      taskId: "task-0001",
      workflowId: "00000000-0000-4000-8000-000000000301",
      status: "SUCCEEDED",
      outputSchemaId: "weather-current-read:1.0.0:output",
      output: {
        location: "Shanghai",
        condition: "Sunny",
        temperatureCelsius: 31.2,
        observedAt: "2026-06-24T10:00:00+08:00",
      },
      errorCode: null,
      errorMessage: null,
      completedAt: "2026-06-23T08:00:00Z",
    },
  ],
};

const workflowId = "00000000-0000-4000-8000-000000000101";

const readOnlyDiagnosticEvents = [
  {
    contractVersion: "1.0",
    eventId: "00000000-0000-4000-8000-000000000201",
    workflowId,
    sequence: 1,
    timestamp: "2026-06-16T10:00:00+08:00",
    type: "WORKFLOW_STARTED",
    payload: {
      payloadType: "WORKFLOW_STARTED",
      commandId: "cmd-node-health-1",
      operatorId: "operator-1",
    },
  },
  {
    contractVersion: "1.0",
    eventId: "00000000-0000-4000-8000-000000000202",
    workflowId,
    sequence: 2,
    timestamp: "2026-06-16T10:00:01+08:00",
    type: "SKILL_ROUTED",
    payload: {
      payloadType: "SKILL_ROUTED",
      skillId: "node-health-read",
      skillVersion: "1.1.0",
    },
  },
  {
    contractVersion: "1.0",
    eventId: "00000000-0000-4000-8000-000000000203",
    workflowId,
    sequence: 3,
    timestamp: "2026-06-16T10:00:02+08:00",
    type: "WORKER_ACCEPTED",
    payload: {
      payloadType: "WORKER_ACCEPTED",
      executionRequestId: "exec-node-health-1",
    },
  },
  {
    contractVersion: "1.0",
    eventId: "00000000-0000-4000-8000-000000000204",
    workflowId,
    sequence: 4,
    timestamp: "2026-06-16T10:00:03+08:00",
    type: "WORKFLOW_COMPLETED",
    payload: {
      payloadType: "WORKFLOW_COMPLETED",
      outputSchemaId: "node-health-output-v1",
      output: {
        nodeName: "node-a",
        status: "HEALTHY",
        cpuUsagePercent: 18,
        memoryUsagePercent: 42,
        diskUsagePercent: 37,
        lastHeartbeatAt: "2026-06-16T10:00:00+08:00",
      },
    },
  },
];

/**
 * @param {Array<Record<string, unknown>>} events
 */
function sseFromEvents(events) {
  return events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join("");
}
