import { readFileSync } from "node:fs";

import { http, HttpResponse } from "msw";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";

import App from "./App.jsx";
import { AppProviders } from "./providers.jsx";
import { server } from "../test/server.js";

const loginCss = readFileSync(
  "src/features/auth/LoginPage.module.css",
  "utf8",
);
const appShellCss = readFileSync(
  "src/components/layout/AppShell.module.css",
  "utf8",
);
const overviewCss = readFileSync(
  "src/features/overview/OverviewPage.module.css",
  "utf8",
);
const sqlWorkbenchCss = readFileSync(
  "src/features/sql-workbench/SqlWorkbenchPage.module.css",
  "utf8",
);
const as400ObjectCss = readFileSync(
  "src/features/as400-object-management/As400ObjectManagementPage.module.css",
  "utf8",
);
const skillRegistryCss = readFileSync(
  "src/features/skill-registry/SkillRegistryPage.module.css",
  "utf8",
);
const workspaceStatusBarCss = readFileSync(
  "src/components/layout/WorkspaceStatusBar.module.css",
  "utf8",
);

/**
 * @param {string} path
 */
function renderAt(path) {
  return render(
    <AppProviders Router={MemoryRouter} routerProps={{ initialEntries: [path] }}>
      <App />
    </AppProviders>,
  );
}

beforeEach(() => {
  server.use(...defaultHandlers);
});

describe("operator console routes", () => {
  it("redirects anonymous users away from protected pages", async () => {
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: false,
          subject: null,
          username: null,
          roles: [],
          authenticationType: "anonymous",
        }),
      ),
    );

    renderAt("/agent");

    expect(await screen.findByRole("heading", { name: "用户登录" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "Agent 工作区" })).not.toBeInTheDocument();
  });

  it("shows the operator navigation for protected pages", async () => {
    renderAt("/agent");

    expect(screen.getByRole("navigation", { name: "主导航" })).toBeVisible();
    expect(screen.getByRole("link", { name: "总览" })).toHaveAttribute("href", "/overview");
    expect(
      screen.getByRole("link", { name: "Agent 工作区" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "Skill 注册中心" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "SQL 工作区" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: "模型设置" }),
    ).toHaveAttribute("href", "/model-settings");
    expect(
      screen.getByRole("link", { name: "发布中心" }),
    ).toHaveAttribute("href", "/release");
    expect(screen.getByRole("link", { name: "RAG 问答" })).toHaveAttribute(
      "href",
      "/rag",
    );
    expect(screen.getByRole("link", { name: "工作流事件" })).toHaveAttribute(
      "href",
      "/workflow-events",
    );
    expect(screen.getByRole("link", { name: "审计记录" })).toHaveAttribute(
      "href",
      "/audit",
    );
    expect(screen.getByRole("link", { name: "帮助" })).toHaveAttribute("href", "/help");
    expect(
      screen.getByRole("link", { name: "审计记录" }),
    ).toBeInTheDocument();
    expect(await screen.findByText("会话 1")).toBeInTheDocument();
    expect(screen.getByText("执行链")).toBeInTheDocument();
  });

  it("navigates between implemented protected pages", async () => {
    const user = userEvent.setup();
    renderAt("/agent");

    await screen.findByText("会话 1");
    await user.click(screen.getByRole("link", { name: "SQL 工作区" }));

    expect(
      await screen.findByRole("heading", { name: "SQL 工作台" }),
    ).toBeInTheDocument();
    expect(
      screen.getByRole("navigation", { name: "主导航" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("当前工作台")).toBeInTheDocument();
  });

  it.each([
    ["/agent", "Agent 工作区"],
    ["/overview", "平台总览"],
    ["/rag", "RAG 问答"],
    ["/workflow-events", "工作流事件"],
    ["/audit", "审计记录"],
    ["/skills", "Skill 注册中心"],
    ["/meeting-notes", "会议录制纪要"],
    ["/as400-ddl", "AS400对象管理"],
    ["/quick-links", "快捷连接"],
    ["/sql", "SQL 工作台"],
    ["/model-settings", "模型设置"],
    ["/release", "发布中心"],
    ["/help", "帮助"],
  ])("renders shared navigation and status bar for %s", async (path, title) => {
    renderAt(path);

    expect(screen.getByRole("navigation", { name: "主导航" })).toBeVisible();
    expect(await screen.findByRole("heading", { name: title })).toBeInTheDocument();
    expect(screen.getByLabelText("当前工作台")).toBeInTheDocument();
  });

  it("renders the help product manual as a protected route", async () => {
    renderAt("/help");

    expect(screen.getByRole("navigation", { name: "主导航" })).toBeVisible();
    expect(await screen.findByRole("heading", { name: "帮助" })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "帮助章节目录" })).toBeInTheDocument();
    expect(screen.getByRole("searchbox", { name: "搜索场景、页面、错误或权限问题" })).toBeInTheDocument();
    expect(screen.getByText("用 Agent 排查服务错误")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "提交 RAG 问题" })).not.toBeInTheDocument();
  });

  it("renders the Skill registry inside the shared shell while replacing only the workspace body", async () => {
    renderAt("/skills");

    expect(screen.getByRole("navigation", { name: "主导航" })).toBeVisible();
    expect(await screen.findByRole("heading", { name: "Skill 注册中心" })).toBeInTheDocument();
    expect(screen.getByLabelText("当前工作台")).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "Skill 注册中心导航" })).not.toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "内置 Skill" })).toBeInTheDocument();
  });

  it("renders the overview React page for the overview navigation view", async () => {
    renderAt("/overview");
    expect(await screen.findByRole("heading", { name: "平台总览" })).toBeInTheDocument();
    const availableEntries = screen.getByRole("region", { name: "可用工作入口" });
    const capabilityMap = screen.getByRole("region", { name: "后续能力地图" });
    const overviewGuide = screen.getByRole("region", { name: "总览使用步骤" });

    expect(
      screen.queryByRole("heading", { name: "当前能用的功能" }),
    ).not.toBeInTheDocument();
    expect(
      screen.queryByText(
        "这里是总览页：先选择 Agent 或 SQL 工作区做只读排查；生产写入、脚本执行和绕过审批不会在这里开放。",
      ),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "怎么使用这个总览" })).not.toBeInTheDocument();
    expect(
      availableEntries,
    ).toBeInTheDocument();
    expect(overviewGuide).toBeInTheDocument();
    expect(within(overviewGuide).getByText("先选工作区")).toBeInTheDocument();
    expect(within(overviewGuide).getByText("只做诊断")).toBeInTheDocument();
    expect(within(overviewGuide).getByText("后续再接入")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "治理证据" })).not.toBeInTheDocument();
    expect(screen.queryByText("最近语义事件")).not.toBeInTheDocument();
    expect(screen.getByRole("region", { name: "只读诊断队列" })).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "运行信号" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "策略门禁矩阵" })).not.toBeInTheDocument();
    expect(screen.getByText("node-a 健康排查")).toBeInTheDocument();
    expect(screen.getByText("payment-api 依赖巡检")).toBeInTheDocument();
    expect(screen.getByText("订单库慢查询趋势")).toBeInTheDocument();
    expect(screen.queryByText("Skill 发布状态")).not.toBeInTheDocument();
    expect(screen.queryByText("SSE 事件通道")).not.toBeInTheDocument();
    expect(screen.queryByText("模型只提出计划")).not.toBeInTheDocument();
    expect(screen.queryByText("Worker 受限执行")).not.toBeInTheDocument();
    expect(screen.queryByText("WORKFLOW_STARTED")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "启动诊断" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "执行变更" })).not.toBeInTheDocument();
    expect(screen.queryByText("生产写入")).not.toBeInTheDocument();
    expect(within(availableEntries).getByRole("link", { name: /Agent 工作区/u })).toHaveAttribute("href", "/agent");
    expect(within(availableEntries).getByRole("link", { name: /SQL 工作区/u })).toHaveAttribute("href", "/sql");
    expect(within(capabilityMap).getByRole("link", { name: /RAG 问答/u })).toHaveAttribute("href", "/rag");
    expect(within(capabilityMap).getByRole("link", { name: /Skill 注册中心/u })).toHaveAttribute("href", "/skills");
    expect(within(capabilityMap).getByRole("link", { name: /会议录制纪要/u })).toHaveAttribute("href", "/meeting-notes");
    expect(within(capabilityMap).getByRole("link", { name: /AS400对象管理/u })).toHaveAttribute("href", "/as400-ddl");
    expect(within(capabilityMap).getByText("快捷连接")).toBeInTheDocument();
    expect(within(capabilityMap).getByText("后续切片")).toBeInTheDocument();
    expect(within(capabilityMap).queryByRole("link", { name: /快捷连接/u })).not.toBeInTheDocument();
    expect(within(capabilityMap).getByRole("link", { name: /工作流事件/u })).toHaveAttribute("href", "/workflow-events");
    expect(within(capabilityMap).getByRole("link", { name: /审计记录/u })).toHaveAttribute("href", "/audit");
    expect(screen.queryByText("服务端策略决策")).not.toBeInTheDocument();
    expect(screen.queryByText("工作流事实源")).not.toBeInTheDocument();
    expect(screen.queryByText("审计证据链")).not.toBeInTheDocument();
    expect(screen.queryByText("工作会话")).not.toBeInTheDocument();
  });

  it("renders the RAG question page from the prototype without enabling out-of-scope actions", async () => {
    renderAt("/rag");

    expect(await screen.findByRole("heading", { name: "RAG 问答" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "RAG 问答窗口" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "知识库问答" })).toBeInTheDocument();
    expect(
      screen.getByText("payment-api 最近 7 天错误率升高时，应该先查哪些 Runbook 和历史工单？"),
    ).toBeInTheDocument();
    expect(screen.getByText("引用已开启")).toBeInTheDocument();
    expect(screen.getByText("Runbook / 运维手册")).toBeInTheDocument();
    expect(screen.getByText("故障复盘 / 工单")).toBeInTheDocument();
    expect(screen.getByText("ADR / 架构决策")).toBeInTheDocument();
    expect(screen.getByText("发布记录 / 变更说明")).toBeInTheDocument();
    expect(screen.getByText("最高相似度")).toBeInTheDocument();
    expect(screen.getByText("0.86")).toBeInTheDocument();
    expect(screen.getByText("无引用回答")).toBeInTheDocument();
    expect(screen.getByText("禁止")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "提交 RAG 问题" })).toBeDisabled();
    expect(screen.queryByText("带引用的只读知识问答")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("RAG 检索状态")).not.toBeInTheDocument();
    expect(screen.queryByText("当前页面只展示 P1 只读范围内的占位入口，后续任务再接入真实接口。")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "执行生产写操作" })).not.toBeInTheDocument();
  });

  it("renders the meeting notes prototype instead of the placeholder page", async () => {
    renderAt("/meeting-notes");

    expect(await screen.findByRole("heading", { name: "会议录制纪要" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "会议纪要库" })).toBeInTheDocument();
    expect(screen.getByRole("search", { name: "会议纪要筛选" })).toBeInTheDocument();
    expect(screen.getByRole("complementary", { name: "会议纪要辅助区" })).toBeInTheDocument();
    expect(screen.getByRole("link", { name: "开始录制" })).toHaveAttribute(
      "href",
      "/meeting-notes/record/new",
    );
    expect(
      screen.queryByText("当前页面只展示 P1 只读范围内的占位入口，后续任务再接入真实接口。"),
    ).not.toBeInTheDocument();
  });

  it("renders the AS400 object management mock workspace", async () => {
    const user = userEvent.setup();
    renderAt("/as400-ddl");

    expect(await screen.findByRole("heading", { name: "AS400对象管理" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "AS400 数据对象管理工作区" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: /在线设计/u })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("region", { name: "在线设计模式" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "客户订单主表" })).toBeInTheDocument();
    expect(screen.getByText("ORDERS.CUSORD")).toBeInTheDocument();
    expect(screen.queryByText("在线字段设计")).not.toBeInTheDocument();
    expect(screen.queryByText("字段明细")).not.toBeInTheDocument();
    expect(screen.queryByText("M09 / UI ready")).not.toBeInTheDocument();
    expect(screen.queryByText("数据对象草稿")).not.toBeInTheDocument();
    expect(screen.queryByText("AS400_客户订单表_导入模板.xlsx")).not.toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "小数" })).toBeInTheDocument();
    expect(screen.getByLabelText("第 1 行小数位").className).toContain("decimalInput");
    expect(screen.getByRole("complementary", { name: "AS400 对象草稿状态" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "展开工作区" })).toHaveAttribute("aria-pressed", "false");
    await user.click(screen.getByRole("button", { name: "展开工作区" }));
    expect(screen.getByRole("button", { name: "退出展开" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.queryByRole("complementary", { name: "AS400 对象草稿状态" })).not.toBeInTheDocument();
    await user.click(screen.getByRole("button", { name: "退出展开" }));
    expect(screen.getByRole("button", { name: "展开工作区" })).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("complementary", { name: "AS400 对象草稿状态" })).toBeInTheDocument();
    await user.click(screen.getByRole("tab", { name: /上传 Excel/u }));
    expect(screen.getByRole("tab", { name: /上传 Excel/u })).toHaveAttribute("aria-selected", "true");
    expect(await screen.findByText("AS400_客户订单表_导入模板.xlsx")).toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Excel 字段映射" })).toBeInTheDocument();
    expect(screen.getByText("nullable")).toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: "字段类型*" })).not.toBeInTheDocument();
    expect(screen.queryByText("businessType")).not.toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "物理类型" })).toBeInTheDocument();
    expect(screen.getByLabelText("第 1 行物理类型").className).toContain("physicalInput");
    const firstPhysicalTypeSelect = screen.getByRole("combobox", { name: "第 1 行物理类型" });
    expect(firstPhysicalTypeSelect).toHaveValue("CHAR");
    expect(within(firstPhysicalTypeSelect).getByRole("option", { name: "VARGRAPHIC" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "是否可空" })).toBeInTheDocument();
    expect(screen.getByLabelText("第 1 行是否可空")).not.toBeChecked();
    expect(screen.getByRole("columnheader", { name: "是否主键" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "是否支持中文" })).toBeInTheDocument();
    expect(screen.getByRole("combobox", { name: "第 1 行缩写候选" })).toHaveValue("ORDNO");
    expect(screen.getByRole("option", { name: "ORDERID" })).toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: "操作" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "删除第 1 行字段" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "提交审批" })).toBeDisabled();
    expect(
      screen.queryByText("当前页面只展示 P1 只读范围内的占位入口，后续任务再接入真实接口。"),
    ).not.toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: /在线设计/u }));
    await user.click(screen.getByRole("button", { name: /新增字段/u }));

    expect(screen.getByRole("region", { name: "在线设计模式" })).toBeInTheDocument();
    expect(screen.getAllByDisplayValue("新字段")).toHaveLength(1);
    await user.click(screen.getByRole("button", { name: "删除第 5 行字段" }));

    expect(screen.queryByDisplayValue("新字段")).not.toBeInTheDocument();
  });

  it.each([
    ["/meeting-notes/recording-settings", "本机录制程序配置"],
    ["/meeting-notes/record/new", "开始录制会议"],
    ["/meeting-notes/meeting-payment-review", "支付链路故障复盘会"],
    ["/meeting-notes/meeting-payment-review/edit", "编辑会议纪要草稿"],
  ])("renders meeting notes sub-route %s", async (path, heading) => {
    renderAt(path);

    expect(await screen.findByRole("heading", { name: heading })).toBeInTheDocument();
    expect(screen.getByLabelText("当前工作台")).toBeInTheDocument();
  });

  it("renders the workflow events workspace from the prototype while keeping the shared shell", async () => {
    renderAt("/workflow-events");

    expect(screen.getByRole("navigation", { name: "主导航" })).toBeVisible();
    expect(await screen.findByRole("heading", { name: "工作流事件" })).toBeInTheDocument();
    expect(screen.getByLabelText("当前工作台")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "语义事件流" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "恢复检查" })).toBeInTheDocument();
    expect(screen.getByRole("search", { name: "工作流事件筛选" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "事件流主轴" })).toBeInTheDocument();
    expect(screen.getByRole("complementary", { name: "状态快照" })).toBeInTheDocument();
    expect(screen.getByText("13 条事件 / 0 gap")).toBeInTheDocument();
    expect(screen.getByText("WORKFLOW_STARTED")).toBeInTheDocument();
    expect(screen.getByText("POLICY_EVALUATED")).toBeInTheDocument();
    expect(screen.getByText("SKILL_ROUTED")).toBeInTheDocument();
    expect(screen.getByText("WORKER_ACCEPTED")).toBeInTheDocument();
    expect(screen.getByText("WORKFLOW_COMPLETED")).toBeInTheDocument();
    expect(screen.getByText("connected / lastEventId=005")).toBeInTheDocument();
    expect(screen.getByText("policy-v1 / READ_ONLY")).toBeInTheDocument();
    expect(
      screen.queryByText("当前页面只展示 P1 只读范围内的占位入口，后续任务再接入真实接口。"),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "回放事件" })).not.toBeInTheDocument();
  });

  it("renders the audit records workspace from the prototype while keeping the shared shell", async () => {
    renderAt("/audit");

    expect(screen.getByRole("navigation", { name: "主导航" })).toBeVisible();
    expect(await screen.findByRole("heading", { name: "审计记录" })).toBeInTheDocument();
    expect(screen.getByLabelText("当前工作台")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "审计记录工作区" })).toBeInTheDocument();
    expect(screen.queryByText("查看身份、策略、Skill、Worker 和结果的不可篡改证据链。")).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "审计证据链" })).not.toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "完整性校验" })).not.toBeInTheDocument();
    expect(screen.getByRole("search", { name: "审计记录筛选" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "审计账本" })).toBeInTheDocument();
    expect(screen.getByRole("complementary", { name: "证据详情" })).toBeInTheDocument();
    expect(screen.getByText("SESSION_AUTHORIZED")).toBeInTheDocument();
    expect(screen.getByText("POLICY_EVALUATED")).toBeInTheDocument();
    expect(screen.getByText("AUDIT_SEALED")).toBeInTheDocument();
    expect(screen.getByText("sha256:e91b")).toBeInTheDocument();
    expect(screen.queryByText("当前页面只展示 P1 只读范围内的占位入口，后续任务再接入真实接口。")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "执行写操作" })).not.toBeInTheDocument();
  });

  it("uses the redesigned shared workspace status bar on the overview page", async () => {
    const appCapsuleRule =
      workspaceStatusBarCss.match(/[.]appCapsule\s*[{][^}]+[}]/u)?.[0] ?? "";
    const brandPlateRule =
      workspaceStatusBarCss.match(/[.]brandPlate\s*[{][^}]+[}]/u)?.[0] ?? "";
    const workspaceContextRule =
      workspaceStatusBarCss.match(/[.]workspaceContext\s*[{][^}]+[}]/u)?.[0] ?? "";
    const signalRailRule =
      workspaceStatusBarCss.match(/[.]signalRail\s*[{][^}]+[}]/u)?.[0] ?? "";
    const operatorDockRule =
      workspaceStatusBarCss.match(/[.]operatorDock\s*[{][^}]+[}]/u)?.[0] ?? "";
    const operatorProfileRule =
      workspaceStatusBarCss.match(/[.]operatorProfile\s*[{][^}]+[}]/u)?.[0] ?? "";

    renderAt("/overview");

    expect(await screen.findByRole("heading", { name: "平台总览" })).toBeInTheDocument();
    const statusBar = screen.getByLabelText("当前工作台");
    expect(statusBar).toBeInTheDocument();
    expect(within(statusBar).getByLabelText("工作台状态")).toBeInTheDocument();
    expect(within(statusBar).getByText("P1 只读控制台")).toBeInTheDocument();
    expect(within(statusBar).getByText("会话在线")).toBeInTheDocument();
    expect(within(statusBar).queryByRole("list", { name: "只读执行链路" })).not.toBeInTheDocument();
    expect(within(statusBar).queryByText("M01")).not.toBeInTheDocument();
    expect(within(statusBar).queryByText("M02")).not.toBeInTheDocument();
    expect(within(statusBar).queryByText("M05")).not.toBeInTheDocument();
    expect(within(statusBar).queryByText("M07")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "启动诊断" })).not.toBeInTheDocument();
    expect(appCapsuleRule).toContain("min-height: 84px");
    expect(appCapsuleRule).toContain("grid-template-columns: minmax(260px, 360px) minmax(360px, 1fr) max-content");
    expect(appCapsuleRule).toContain("border-radius: 18px");
    expect(appCapsuleRule).toContain("background: oklch");
    expect(brandPlateRule).toContain("grid-template-columns: 58px minmax(0, 1fr)");
    expect(workspaceContextRule).toContain("grid-template-columns: 38px minmax(112px, 0.7fr) max-content minmax(118px, 1fr)");
    expect(signalRailRule).toContain("min-width: 118px");
    expect(operatorDockRule).toContain("grid-template-columns: minmax(150px, 190px) 132px 92px");
    expect(operatorProfileRule).toContain("min-width: 0");
    expect(workspaceStatusBarCss).not.toContain(".brandLockup");
    expect(workspaceStatusBarCss).not.toContain(".workspaceTrail");
    expect(workspaceStatusBarCss).not.toContain(".trailItem");
    expect(workspaceStatusBarCss).not.toContain("frame-glass-sheen");
  });

  it("redirects the legacy agent overview query to the top-level overview route", async () => {
    renderAt("/agent?view=overview");

    expect(
      await screen.findByRole("heading", { name: "平台总览" }),
    ).toBeInTheDocument();
    expect(screen.queryByText("工作会话")).not.toBeInTheDocument();
  });

  it.each([
    ["/agent?view=rag", "RAG 问答"],
    ["/agent?view=workflow", "工作流事件"],
    ["/agent?view=audit", "审计记录"],
  ])("redirects legacy agent query route %s to its menu page", async (path, title) => {
    renderAt(path);

    expect(await screen.findByRole("heading", { name: title })).toBeInTheDocument();
    expect(screen.getByLabelText("当前工作台")).toBeInTheDocument();
    expect(screen.queryByText("工作会话")).not.toBeInTheDocument();
  });

  it("uses the Agent workspace shell rhythm for every menu page", () => {
    const workspaceFrameCss = readFileSync(
      "src/components/layout/WorkspacePageFrame.module.css",
      "utf8",
    );
    const appContentRule = appShellCss.match(/[.]content\s*[{][^}]+[}]/u)?.[0] ?? "";
    const activeNavRule = appShellCss.match(/[.]active\s*[{][^}]+[}]/u)?.[0] ?? "";
    const frameRule = workspaceFrameCss.match(/[.]workspaceFrame\s*[{][^}]+[}]/u)?.[0] ?? "";
    const overviewSource = readFileSync("src/features/overview/OverviewPage.jsx", "utf8");
    const sqlSource = readFileSync("src/features/sql-workbench/SqlWorkbenchPage.jsx", "utf8");
    const skillSource = readFileSync("src/features/skill-registry/SkillRegistryPage.jsx", "utf8");
    const as400Source = readFileSync("src/features/as400-object-management/As400ObjectManagementPage.jsx", "utf8");

    expect(appContentRule).toContain("max-width: none");
    expect(appContentRule).not.toContain("max-width: var(--content-max)");
    expect(activeNavRule).toContain("--nav-color: var(--color-info)");
    expect(activeNavRule).toContain("--nav-mark: #207fa4");
    expect(activeNavRule).toContain("--nav-icon-radius: 13px");
    expect(activeNavRule).toContain("--nav-symbol-radius: 9px");
    expect(activeNavRule).toContain("--nav-detail-radius: 5px");
    expect(frameRule).toContain("--workspace-layout-gap: 24px");
    expect(frameRule).toContain("--workspace-sidebar-width: 250px");
    expect(frameRule).toContain("width: calc(100vw - var(--workspace-canvas-left) - var(--workspace-layout-gap))");
    expect(frameRule).toContain("min-height: calc(100vh - 48px)");
    expect(frameRule).toContain("gap: var(--workspace-layout-gap)");
    expect(frameRule).toContain("padding: 12px");
    expect(frameRule).toContain("border: 1px solid rgba(166, 64, 92, 0.18)");
    expect(frameRule).toContain("border-radius: 24px");
    expect(frameRule).toContain("0 18px 56px rgba(31, 41, 51, 0.055)");
    expect(overviewSource).toContain("WorkspacePageFrame");
    expect(sqlSource).toContain("WorkspacePageFrame");
    expect(skillSource).toContain("WorkspacePageFrame");
    expect(as400Source).toContain("WorkspacePageFrame");
  });

  it("keeps overview, SQL, and Skill inner grid gaps aligned to Agent workspace", () => {
    const canvasRule = overviewCss.match(/[.]overviewCanvas\s*[{][^}]+[}]/u)?.[0] ?? "";
    const overviewGridRule =
      overviewCss.match(/[.]overviewGrid\s*[{][^}]+[}]/u)?.[0] ?? "";
    const statusStripRule =
      overviewCss.match(/[.]statusStrip\s*[{][^}]+[}]/u)?.[0] ?? "";
    const entryGridRule =
      overviewCss.match(/[.]entryGrid\s*[{][^}]+[}]/u)?.[0] ?? "";
    const queueGridRule =
      overviewCss.match(/[.]queueGrid\s*[{][^}]+[}]/u)?.[0] ?? "";
    const guideListRule =
      overviewCss.match(/[.]guideList\s*[{][^}]+[}]/u)?.[0] ?? "";
    const capabilityMapRule =
      overviewCss.match(/[.]capabilityMap\s*[{][^}]+[}]/u)?.[0] ?? "";
    const capabilityRowRule =
      overviewCss.match(/[.]capabilityRow\s*[{][^}]+[}]/u)?.[0] ?? "";
    const sqlCanvasRule = sqlWorkbenchCss.match(/[.]sqlCanvas\s*[{][^}]+[}]/u)?.[0] ?? "";
    const workbenchGridRule =
      sqlWorkbenchCss.match(/[.]workbenchGrid\s*[{][^}]+[}]/u)?.[0] ?? "";
    const as400LayoutRule =
      as400ObjectCss.match(/[.]objectLayout\s*[{][^}]+[}]/u)?.[0] ?? "";
    const as400ToolbarRule =
      as400ObjectCss.match(/[.]modeToolbar\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(canvasRule).toContain("grid-template-rows: auto minmax(0, 1fr)");
    expect(overviewGridRule).toContain("grid-template-columns: minmax(0, 1fr)");
    expect(overviewGridRule).toContain("gap: var(--workspace-layout-gap)");
    expect(statusStripRule).toContain("grid-template-columns: repeat(4, minmax(0, 1fr))");
    expect(statusStripRule).toContain("gap: 10px");
    expect(guideListRule).toContain("grid-template-columns: repeat(3, minmax(0, 1fr))");
    expect(entryGridRule).toContain("grid-template-columns: repeat(2, minmax(0, 1fr))");
    expect(entryGridRule).toContain("gap: 14px");
    expect(queueGridRule).toContain("grid-template-columns: repeat(3, minmax(0, 1fr))");
    expect(queueGridRule).toContain("gap: 10px");
    expect(overviewCss).not.toContain(".signalGrid");
    expect(overviewCss).not.toContain(".policyMatrix");
    expect(overviewCss).not.toContain(".evidenceRail");
    expect(overviewCss).not.toContain(".eventTimeline");
    expect(capabilityMapRule).toContain("grid-template-columns: repeat(2, minmax(0, 1fr))");
    expect(capabilityMapRule).toContain("gap: 10px");
    expect(capabilityRowRule).toContain("grid-template-columns: 36px minmax(0, 1fr) auto");
    expect(sqlCanvasRule).toContain("grid-template-rows: auto auto minmax(0, 1fr)");
    expect(workbenchGridRule).toContain("gap: 12px");
    expect(as400LayoutRule).toContain("grid-template-columns: minmax(0, 1fr) 292px");
    expect(as400LayoutRule).toContain("gap: 12px");
    expect(as400ToolbarRule).toContain("border-radius: 18px");
    expect(as400ObjectCss).toContain(".candidateSelect");
    expect(as400ObjectCss).not.toContain(".candidateChip");
    expect(skillRegistryCss).toContain(".registryCanvas");
    expect(skillRegistryCss).toContain(".registryTable");
  });

  it("redirects the root route to login", () => {
    renderAt("/");

    expect(
      screen.getByRole("heading", { name: "企业智能运维工作台" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "用户登录" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "登录" })).toBeInTheDocument();
  });

  it("renders the prototype login entry without enabling out-of-scope actions", () => {
    renderAt("/login");

    expect(screen.getByText("SECURE OPERATOR ENTRY")).toBeInTheDocument();
    expect(screen.getByText("受控诊断链路")).toBeInTheDocument();
    expect(screen.queryByText("提任务")).not.toBeInTheDocument();
    expect(screen.queryByText("选 Skill")).not.toBeInTheDocument();
    expect(screen.queryByText("留痕")).not.toBeInTheDocument();
    expect(screen.queryByText("内建身份登录")).not.toBeInTheDocument();
    expect(
      screen.queryByText("身份确认后，权限仍由服务端策略独立判定。"),
    ).not.toBeInTheDocument();
    expect(screen.queryByText("执行 SQL")).not.toBeInTheDocument();
    expect(screen.queryByText("Commit")).not.toBeInTheDocument();
    expect(screen.queryByText("Rollback")).not.toBeInTheDocument();
  });

  it("allows the operator account to be edited before password login", async () => {
    const user = userEvent.setup();
    renderAt("/login");

    const accountInput = screen.getByLabelText("用户名");

    await user.clear(accountInput);
    await user.type(accountInput, "ops.admin@company.internal");

    expect(accountInput).toHaveValue("ops.admin@company.internal");
  });

  it("renders one ion from each node with the skill ion emphasized", () => {
    const { container } = renderAt("/login");

    const ions = Array.from(container.querySelectorAll("[data-node-ion]"));
    const routes = new Set(ions.map((ion) => ion.getAttribute("data-node-ion")));

    expect(ions).toHaveLength(4);
    expect(routes).toEqual(new Set(["identity", "policy", "skill", "worker"]));
    expect(container.querySelector('[data-node-ion="skill"]')).toHaveAttribute(
      "data-ion-emphasis",
      "primary",
    );
    expect(ions.every((ion) => ion.getAttribute("aria-hidden") === "true")).toBe(
      true,
    );
  });

  it("renders a restrained screen-wide ion field", () => {
    const { container } = renderAt("/login");

    const screenIons = Array.from(container.querySelectorAll("[data-screen-ion]"));

    expect(screenIons.length).toBeGreaterThanOrEqual(10);
    expect(container.querySelector("[data-screen-comet]")).not.toBeInTheDocument();
    expect(screenIons.every((ion) => ion.getAttribute("aria-hidden") === "true")).toBe(
      true,
    );
  });

  it("adds a lightweight frame without changing the existing login card layout", () => {
    const screenRule = loginCss.match(/[.]screen\s*[{][^}]+[}]/u)?.[0] ?? "";
    const screenBackdropRule =
      loginCss.match(/[.]screen::after\s*[{][^}]+[}]/u)?.[0] ?? "";
    const loginHeroEffectRule =
      loginCss.match(/[.]loginHeroEffect\s*[{][^}]+[}]/u)?.[0] ?? "";
    const taskCardRule = loginCss.match(/[.]taskCard\s*[{][^}]+[}]/u)?.[0] ?? "";
    const loginShellRule =
      loginCss.match(/[.]loginShell\s*[{][^}]+[}]/u)?.[0] ?? "";
    const loginShellFrameRule =
      loginCss.match(/[.]loginShell::before\s*[{][^}]+[}]/u)?.[0] ?? "";
    const loginModeStripRule =
      loginCss.match(/[.]loginModeStrip\s*[{][^}]+[}]/u)?.[0] ?? "";
    const frameActiveTrackRule = loginCss.match(/[.]frameActiveTrack\b/u)?.[0] ?? "";
    const loginCardRule = loginCss.match(/[.]loginCard\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(loginShellRule).toContain("isolation: isolate");
    expect(loginShellRule).toContain("grid-template-columns: 720px 560px");
    expect(loginShellRule).toContain("align-items: start");
    expect(loginShellRule).toContain("padding: 90px 40px 86px");
    expect(screenRule).toContain("--login-frame-anchor-height: min(720px, calc(100vh - 132px))");
    expect(screenRule).toContain("--login-frame-height: min(640px, calc(100vh - 132px))");
    expect(screenRule).toContain(
      "--login-frame-y: calc((100vh - var(--login-frame-anchor-height)) / 2 - var(--login-frame-top))",
    );
    expect(loginShellRule).toContain("transform: translate(-50%, var(--login-frame-y))");
    expect(loginHeroEffectRule).toContain(
      "top: calc(var(--login-frame-y) + var(--login-frame-top) - 2px)",
    );
    expect(loginHeroEffectRule).toContain("right: max(24px, calc(50% - 646px))");
    expect(loginHeroEffectRule).toContain("transform: translateY(-5px)");
    expect(taskCardRule).toContain("left: 320px");
    expect(taskCardRule).toContain("width: 176px");
    expect(loginCss).not.toContain(".capabilityFlow");
    expect(loginModeStripRule).toContain("transform: translateY(0)");
    expect(loginShellRule).toContain("--frame-height: var(--login-frame-height)");
    expect(loginShellFrameRule).toContain(
      "inset: var(--frame-top) var(--frame-inset-x) auto",
    );
    expect(loginShellFrameRule).toContain("height: var(--frame-height)");
    expect(loginShellFrameRule).toContain("border: 1px solid rgba(76, 112, 136, 0.2)");
    expect(loginShellFrameRule).not.toContain("rgba(166, 64, 92");
    expect(loginShellFrameRule).toContain("rgba(246, 247, 249, 0.96)");
    expect(loginShellFrameRule).not.toContain("background: transparent");
    expect(loginShellFrameRule).toContain("0 18px 56px rgba(31, 41, 51, 0.055)");
    expect(loginShellFrameRule).toContain("inset 0 1px 0 rgba(255, 255, 255, 0.7)");
    expect(loginShellFrameRule).not.toContain("no-repeat");
    expect(loginShellFrameRule).not.toContain("outline:");
    expect(loginShellFrameRule).not.toContain("mask-composite");
    expect(loginShellFrameRule).toContain("border-radius: 22px");
    expect(frameActiveTrackRule).toBe("");
    expect(loginCss).not.toContain("frame-active-track");
    expect(loginCss).not.toContain(".loginShell::after");
    expect(loginCss).not.toContain(".frameIonTail");
    expect(loginCss).not.toContain("frame-ion-track");
    expect(loginCss).not.toContain("frame-ion-tail");
    expect(screenBackdropRule).toContain("radial-gradient");
    expect(screenBackdropRule).toContain("linear-gradient(180deg");
    expect(loginCardRule).toContain("align-self: start");
    expect(loginCardRule).toContain("transform: translateY(2px)");
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
  http.get("/internal/sql-workbench/connections", () =>
    HttpResponse.json(sqlConnections),
  ),
  http.get("/internal/model-providers", () =>
    HttpResponse.json([modelProviderSummary]),
  ),
  http.get("/internal/skills", () =>
    HttpResponse.json({
      skills: [registeredSkill],
    }),
  ),
  http.get("/internal/audit/events", () =>
    HttpResponse.json({
      total: 0,
      events: [],
    }),
  ),
  http.post("/internal/routing/skills/search", () =>
    HttpResponse.json({
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
    }),
  ),
];

const sqlConnections = [
  {
    contractVersion: "1.0",
    connectionId: "as400-development",
    displayName: "AS/400 Development",
    targetEnvironment: "development",
    platformType: "DB2_FOR_I",
    allowedSchemas: ["ORDERS", "INVENTORY"],
    capabilities: ["VALIDATE", "RUN_READ_ONLY", "PREFLIGHT_DML"],
  },
];

const modelProviderSummary = {
  providerId: "provider-openai",
  displayName: "OpenAI",
  providerType: "OPENAI_COMPATIBLE",
  baseUrl: "https://api.openai.com/v1",
  modelName: "gpt-4.1-mini",
  enabled: true,
  defaultProvider: true,
  timeout: "PT30S",
  maxIterations: 5,
  maxToolCalls: 5,
  maxToolCallDuration: "PT30S",
  apiKeyConfigured: true,
  apiKeyFingerprint: "fp_openai",
  apiKeyLastRotatedAt: "2026-06-28T00:00:00Z",
  configVersion: 1,
  updatedAt: "2026-06-28T00:00:00Z",
};
