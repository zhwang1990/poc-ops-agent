import { readFileSync } from "node:fs";

import { http, HttpResponse } from "msw";
import { render, screen, within } from "@testing-library/react";
import { describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";

import { AppProviders } from "../../app/providers.jsx";
import { server } from "../../test/server.js";
import { WorkspaceStatusBar } from "./WorkspaceStatusBar.jsx";

const source = readFileSync("src/components/layout/WorkspaceStatusBar.jsx", "utf8");
const css = readFileSync("src/components/layout/WorkspaceStatusBar.module.css", "utf8");
const tokensCss = readFileSync("src/styles/tokens.css", "utf8");

function renderStatusBar() {
  server.use(
    http.get("/auth/session", () =>
      HttpResponse.json({
        authenticated: true,
        subject: "account-admin-1",
        username: "admin",
        roles: ["ROLE_ops-admin"],
        authenticationType: "built-in",
      }),
    ),
  );

  return render(
    <AppProviders Router={MemoryRouter} routerProps={{ initialEntries: ["/agent"] }}>
      <WorkspaceStatusBar title="Agent 工作区" />
    </AppProviders>,
  );
}

describe("WorkspaceStatusBar", () => {
  it("renders a refined operator toolbar without module-chain labels", async () => {
    renderStatusBar();

    const statusBar = screen.getByLabelText("当前工作台");
    const workspaceStatus = within(statusBar).getByLabelText("工作台状态");
    const workspaceToolbar = within(statusBar).getByRole("toolbar", { name: "工作区工具栏" });
    const profile = await within(statusBar).findByLabelText("当前登录人");

    expect(within(statusBar).getByText("企业智能 Agent")).toBeInTheDocument();
    expect(within(statusBar).getByRole("heading", { name: "Agent 工作区" })).toBeInTheDocument();
    expect(within(statusBar).queryByRole("list", { name: "只读执行链路" })).not.toBeInTheDocument();
    expect(within(statusBar).queryByText("M01")).not.toBeInTheDocument();
    expect(within(statusBar).queryByText("M02")).not.toBeInTheDocument();
    expect(within(statusBar).queryByText("M05")).not.toBeInTheDocument();
    expect(within(statusBar).queryByText("M07")).not.toBeInTheDocument();
    expect(within(statusBar).queryByText("身份")).not.toBeInTheDocument();
    expect(within(statusBar).queryByText("策略")).not.toBeInTheDocument();
    expect(within(statusBar).queryByText("工作流")).not.toBeInTheDocument();
    expect(within(statusBar).queryByText("Worker")).not.toBeInTheDocument();
    expect(within(workspaceStatus).getByText("P1 只读控制台")).toBeInTheDocument();
    expect(within(workspaceStatus).getByText("会话在线")).toBeInTheDocument();
    expect(within(workspaceToolbar).getByRole("button", { name: "关闭菜单名称" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(within(workspaceToolbar).getByRole("button", { name: "展开工作区" })).toBeInTheDocument();
    expect(within(workspaceToolbar).queryByText("展示菜单名称")).not.toBeInTheDocument();
    expect(within(workspaceToolbar).queryByText("展开工作区")).not.toBeInTheDocument();
    expect(await within(profile).findByText("admin")).toBeInTheDocument();
    expect(within(statusBar).getByRole("timer", { name: /下班倒计时：/u })).toBeInTheDocument();
    expect(within(statusBar).getByRole("button", { name: "登出当前账号" })).toBeEnabled();
  });

  it("keeps the richer toolbar as one explicit product surface", () => {
    expect(source).toContain("className={styles.brandPlate}");
    expect(source).toContain("className={styles.workspaceContext}");
    expect(source).toContain("className={styles.signalRail}");
    expect(source).toContain("className={styles.operatorDock}");
    expect(source).not.toContain("READ_ONLY_TRAIL");
    expect(source).not.toContain("workspaceTrail");
    expect(source).not.toContain("trailItem");
    expect(css).toContain("grid-template-columns: minmax(260px, 360px) max-content minmax(0, 1fr) max-content max-content");
    expect(css).toContain("background: oklch");
    expect(css).toContain("border-radius: 18px");
    const brandPlateRule = css.match(/[.]brandPlate\s*[{][^}]+[}]/u)?.[0] ?? "";
    const brandPlateBeforeRule = css.match(/[.]brandPlate::before\s*[{][^}]+[}]/u)?.[0] ?? "";
    const brandPlateAfterRule = css.match(/[.]brandPlate::after\s*[{][^}]+[}]/u)?.[0] ?? "";
    const brandNameRule = css.match(/[.]brandName\s*[{][^}]+[}]/u)?.[0] ?? "";
    const headingRule = css.match(/[.]capsuleHeading\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(tokensCss).toContain('--font-heading: Inter, "HarmonyOS Sans SC", MiSans');
    expect(brandPlateRule).toContain("position: relative");
    expect(brandPlateRule).toContain("isolation: isolate");
    expect(brandPlateRule).toContain("overflow: hidden");
    expect(brandPlateRule).toContain("radial-gradient");
    expect(brandPlateRule).toContain("oklch(0.965 0.012 236 / 0.94)");
    expect(brandPlateRule).not.toContain("repeating-linear-gradient");
    expect(brandPlateBeforeRule).toContain("radial-gradient");
    expect(brandPlateBeforeRule).toContain("mask-image: linear-gradient");
    expect(brandPlateBeforeRule).not.toContain("repeating-linear-gradient");
    expect(brandPlateBeforeRule).not.toContain("linear-gradient(90deg, transparent 0 60px");
    expect(brandPlateAfterRule).toContain("height: 1px");
    expect(brandPlateAfterRule).toContain("opacity: 0.32");
    expect(brandPlateAfterRule).toContain("var(--toolbar-blue)");
    expect(brandNameRule).toContain("font-weight: 830");
    expect(headingRule).toContain("font-size: 1.06rem");
    expect(headingRule).toContain("font-family: var(--font-heading");
    expect(headingRule).toContain("font-synthesis-weight: none");
    expect(headingRule).toContain("font-weight: 680");
    expect(headingRule).toContain("line-height: 1.16");
    expect(headingRule).toContain("-webkit-font-smoothing: antialiased");
    expect(headingRule).not.toContain("font-weight: 520");
    expect(headingRule).not.toContain("font-weight: 600");
    expect(headingRule).not.toContain("font-weight: 880");
    expect(css).toContain("conic-gradient");
    expect(css).not.toContain(".logoMark::before");
    expect(css).toContain("animation: logo-mark-breathe");
    expect(css).toContain("animation: logo-mark-orbit");
    expect(css).toContain("@media (prefers-reduced-motion: reduce)");
    expect(css).not.toContain(".appCapsule::after");
    expect(css).not.toContain("brand-scan");
    expect(css).not.toContain("frame-glass-sheen");
  });

  it("keeps shared toolbar borders subtle for scaled desktop previews", () => {
    const appCapsuleRule = css.match(/[.]appCapsule\s*[{][^}]+[}]/u)?.[0] ?? "";
    const brandPlateRule = css.match(/[.]brandPlate\s*[{][^}]+[}]/u)?.[0] ?? "";
    const brandPlateAfterRule = css.match(/[.]brandPlate::after\s*[{][^}]+[}]/u)?.[0] ?? "";
    const workspaceContextRule =
      css.match(/[.]workspaceContext\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(appCapsuleRule).toContain("border: 1px solid oklch(0.86 0.018 236 / 0.62)");
    expect(appCapsuleRule).not.toContain("0 1px 0 oklch(1 0.004 236 / 0.86) inset");
    expect(brandPlateRule).toContain("border: 1px solid oklch(0.86 0.018 236 / 0.56)");
    expect(workspaceContextRule).toContain("border: 1px solid oklch(0.86 0.018 236 / 0.5)");
    expect(brandPlateAfterRule).toContain("height: 1px");
    expect(brandPlateAfterRule).toContain("opacity: 0.32");
  });

  it("keeps workspace controls in the same top capsule row", () => {
    const appCapsuleRule = css.match(/[.]appCapsule\s*[{][^}]+[}]/u)?.[0] ?? "";
    const toolbarRule = css.match(/[.]workspaceToolbar\s*[{][^}]+[}]/u)?.[0] ?? "";
    const workspaceContextRule =
      css.match(/[.]workspaceContext\s*[{][^}]+[}]/u)?.[0] ?? "";
    const toolbarControlRule =
      css.match(/[.]toolbarIconToggle,\s*[\r\n]+[.]toolbarButton\s*[{][^}]+[}]/u)?.[0] ?? "";
    const toolbarIconToggleRule =
      css.match(/[.]toolbarIconToggle\s*[{][^}]+[}]/u)?.[0] ?? "";
    const toolbarButtonRule =
      [...css.matchAll(/[.]toolbarButton\s*[{][^}]+[}]/gu)]
        .map((match) => match[0])
        .find((rule) => rule.includes("position: relative")) ?? "";
    const toolbarButtonBeforeRule =
      css.match(/[.]toolbarButton::before\s*[{][^}]+[}]/u)?.[0] ?? "";
    const toolbarButtonIconRule =
      css.match(/[.]toolbarButtonIcon\s*[{][^}]+[}]/u)?.[0] ?? "";
    const toolbarPressedRule =
      css.match(/[.]toolbarIconTogglePressed,\s*[\r\n]+[.]toolbarButtonPressed\s*[{][^}]+[}]/u)?.[0] ?? "";
    const toolbarPressedIconRule =
      css.match(/[.]toolbarIconTogglePressed [.]toolbarButtonIcon,\s*[\r\n]+[.]toolbarButtonPressed [.]toolbarButtonIcon\s*[{][^}]+[}]/u)?.[0] ??
      "";
    const logoutIconBadgeRule =
      [...css.matchAll(/[.]logoutIconBadge\s*[{][^}]+[}]/gu)]
        .map((match) => match[0])
        .find((rule) => rule.includes("linear-gradient(145deg")) ?? "";
    const operatorDockRule = css.match(/[.]operatorDock\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(source).toContain('aria-label="工作区工具栏"');
    expect(appCapsuleRule).toContain(
      "grid-template-columns: minmax(260px, 360px) max-content minmax(0, 1fr) max-content max-content",
    );
    expect(appCapsuleRule).toContain("minmax(0, 1fr)");
    expect(workspaceContextRule).toContain("width: max-content");
    expect(workspaceContextRule).toContain("grid-template-columns: 38px max-content max-content 68px");
    expect(workspaceContextRule).not.toContain("minmax(340px, 430px)");
    expect(workspaceContextRule).not.toContain("minmax(104px, 0.7fr)");
    expect(toolbarRule).toContain("grid-column: 4");
    expect(toolbarRule).toContain("grid-row: 1");
    expect(toolbarRule).toContain("gap: 8px");
    expect(toolbarRule).toContain("height: 48px");
    expect(toolbarRule).toContain("border: 0");
    expect(toolbarRule).toContain("background: transparent");
    expect(toolbarControlRule).toContain("width: 48px");
    expect(toolbarControlRule).toContain("height: 48px");
    expect(toolbarControlRule).toContain("grid-template-columns: 34px");
    expect(toolbarControlRule).toContain("border: 1px solid var(--toolbar-line)");
    expect(toolbarControlRule).toContain("border-radius: 13px");
    expect(toolbarControlRule).toContain("box-shadow: 0 9px 18px");
    expect(toolbarIconToggleRule).toContain("width: 48px");
    expect(toolbarButtonIconRule).toContain("width: 34px");
    expect(toolbarButtonIconRule).toContain("height: 34px");
    expect(toolbarButtonIconRule).toContain("border-radius: 11px");
    expect(toolbarButtonIconRule).toContain("linear-gradient(145deg, var(--toolbar-blue)");
    expect(toolbarButtonIconRule).toContain("color: oklch(0.985 0.006 236)");
    expect(toolbarPressedRule).toContain("border-color: oklch(0.82 0.052 18)");
    expect(toolbarPressedRule).toContain("linear-gradient(135deg, oklch(0.99 0.01 18 / 0.94), oklch(0.955 0.024 18 / 0.86))");
    expect(toolbarPressedRule).not.toContain("156");
    expect(toolbarPressedIconRule).toContain("border-color: oklch(0.78 0.09 18)");
    expect(toolbarPressedIconRule).toContain("linear-gradient(145deg, var(--toolbar-red), oklch(0.43 0.16 18))");
    expect(toolbarPressedIconRule).not.toContain("var(--toolbar-green)");
    expect(logoutIconBadgeRule).toContain("linear-gradient(145deg, var(--toolbar-red), oklch(0.43 0.16 18))");
    expect(source).not.toContain("toolbarButtonText");
    expect(source).not.toContain('role="switch"');
    expect(source).not.toContain("aria-checked");
    expect(css).not.toContain("toolbarSwitch");
    expect(css).not.toContain("toolbarSwitchTrack");
    expect(css).not.toContain(".toolbarButtonText");
    expect(toolbarButtonBeforeRule).toBe("");
    expect(toolbarButtonRule).toContain("position: relative");
    expect(operatorDockRule).toContain("grid-column: 5");
    expect(operatorDockRule).toContain("grid-row: 1");
    expect(operatorDockRule).toContain("grid-template-columns: 132px minmax(150px, 190px) 92px");
    expect(toolbarRule).not.toContain("grid-column: 1 / -1");
    expect(toolbarRule).not.toMatch(/(^|[\s;])order\s*:/u);
  });
});
