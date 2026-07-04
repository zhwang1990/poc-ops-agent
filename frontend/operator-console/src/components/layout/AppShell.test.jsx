import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

const appShellCss = readFileSync(
  "src/components/layout/AppShell.module.css",
  "utf8",
);
const appShellSource = readFileSync("src/components/layout/AppShell.jsx", "utf8");

describe("AppShell styles", () => {
  it("reserves compact 9px gutters for the sidebar and workspace", () => {
    const shellRule = appShellCss.match(/[.]shell\s*[{][^}]+[}]/u)?.[0] ?? "";
    const iconOnlyRule = appShellCss.match(/[.]iconOnlyShell\s*[{][^}]+[}]/u)?.[0] ?? "";
    const sidebarRule = appShellCss.match(/[.]sidebar\s*[{][^}]+[}]/u)?.[0] ?? "";
    const contentRule = appShellCss.match(/[.]content\s*[{][^}]+[}]/u)?.[0] ?? "";
    const wideShellRule =
      appShellCss.match(/@media [(]min-width: 1680px[)]\s*[{][\s\S]*?[}]\s*[}]/u)?.[0] ?? "";

    expect(shellRule).toContain("--app-shell-gap: 9px");
    expect(shellRule).toContain("--app-sidebar-width: 238px");
    expect(shellRule).not.toContain("--app-sidebar-width: 250px");
    expect(shellRule).toContain(
      "--app-content-margin-left: calc(var(--app-sidebar-width) + (var(--app-shell-gap) * 2))",
    );
    expect(iconOnlyRule).toContain("--app-sidebar-width: 88px");
    expect(iconOnlyRule).not.toContain("--app-content-margin-left");
    expect(sidebarRule).toContain(
      "inset: var(--app-shell-gap) auto var(--app-shell-gap) var(--app-shell-gap)",
    );
    expect(sidebarRule).toContain("padding: 12px 10px 18px");
    expect(sidebarRule).not.toContain("padding: 24px");
    expect(contentRule).toContain("padding: var(--app-shell-gap) var(--app-shell-gap) var(--app-shell-gap) 0");
    expect(wideShellRule).not.toContain("--app-content-margin-left: 300px");
  });

  it("lifts the whole sidebar navigation stack by three pixels", () => {
    const navRule = appShellCss.match(/[.]nav\s*[{][^}]+[}]/u)?.[0] ?? "";
    const sidebarFooterRule = appShellCss.match(/[.]sidebarFooter\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(navRule).toContain("flex: 1 1 auto");
    expect(navRule).toContain("min-height: 0");
    expect(navRule).toContain("overflow-y: auto");
    expect(navRule).toContain("transform: translateY(-3px)");
    expect(sidebarFooterRule).toContain("flex: 0 0 auto");
  });

  it("renders sidebar menu buttons with login-aligned glass highlights", () => {
    const sidebarRule = appShellCss.match(/[.]sidebar\s*[{][^}]+[}]/u)?.[0] ?? "";
    const navLinkRule = appShellCss.match(/[.]navLink\s*[{][^}]+[}]/u)?.[0] ?? "";
    const navLinkSheenRule = appShellCss.match(/[.]navLink::after\s*[{][^}]+[}]/u)?.[0] ?? "";
    const activeRule = appShellCss.match(/[.]active\s*[{][^}]+[}]/u)?.[0] ?? "";
    const activeIconRule = appShellCss.match(/[.]active\s+[.]navIcon\s*[{][^}]+[}]/u)?.[0] ?? "";
    const activeSymbolRule = appShellCss.match(/[.]active\s+[.]navSymbol\s*[{][^}]+[}]/u)?.[0] ?? "";
    const hoverRule = appShellCss.match(/[.]navLink:hover:not[(][.]active[)]\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(sidebarRule).toContain("radial-gradient(circle at 18% 8%, rgba(211, 17, 69, 0.13), transparent 10rem)");
    expect(sidebarRule).toContain("radial-gradient(circle at 88% 86%, rgba(166, 64, 92, 0.1), transparent 11rem)");
    expect(sidebarRule).toContain("linear-gradient(180deg, rgba(255, 255, 255, 0.9), rgba(247, 250, 252, 0.74))");
    expect(sidebarRule).toContain("border: 1px solid rgba(166, 64, 92, 0.18)");
    expect(sidebarRule).toContain("0 18px 38px rgba(166, 64, 92, 0.08)");
    expect(sidebarRule).not.toContain("background: var(--color-surface)");
    expect(navLinkRule).toContain("overflow: hidden");
    expect(navLinkRule).toContain("isolation: isolate");
    expect(navLinkRule).toContain("rgba(255, 255, 255, 0.68)");
    expect(navLinkRule).toContain("rgba(211, 17, 69, 0.05)");
    expect(navLinkRule).toContain("backdrop-filter: blur(12px)");
    expect(appShellCss).toContain(".navLink::after");
    expect(navLinkSheenRule).toContain("linear-gradient(120deg");
    expect(navLinkSheenRule).toContain("rgba(255, 255, 255, 0.7)");
    expect(navLinkSheenRule).toContain("pointer-events: none");
    expect(activeRule).toContain("rgba(211, 17, 69, 0.1)");
    expect(activeRule).toContain("inset 0 1px 0 rgba(255, 255, 255, 0.88)");
    expect(activeIconRule).toContain("background: var(--nav-color)");
    expect(activeSymbolRule).toContain("background: #fff");
    expect(hoverRule).toContain("rgba(255, 255, 255, 0.82)");
    expect(hoverRule).toContain("0 14px 26px rgba(31, 45, 61, 0.08)");
  });

  it("recreates the legacy tactile navigation badge while keeping the svg crisp", () => {
    const linkRule = appShellCss.match(/[.]navLink\s*[{][^}]+[}]/u)?.[0] ?? "";
    const iconRule = appShellCss.match(/[.]navIcon\s*[{][^}]+[}]/u)?.[0] ?? "";
    const iconInnerLayerRule = appShellCss.match(/[.]navIcon::before\s*[{][^}]+[}]/u)?.[0] ?? "";
    const symbolRule = appShellCss.match(/[.]navSymbol\s*[{][^}]+[}]/u)?.[0] ?? "";
    const symbolDetailRule = appShellCss.match(/[.]navSymbol::before\s*[{][^}]+[}]/u)?.[0] ?? "";
    const symbolSparkRule =
      [...appShellCss.matchAll(/[.]navSymbol::after\s*[{][^}]+[}]/gu)]
        .map((match) => match[0])
        .find((rule) => rule.includes("top: var(--nav-spark-y)")) ?? "";
    const glyphRule = appShellCss.match(/[.]navGlyph\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(appShellSource).toContain("className={styles.navSymbol}");
    expect(linkRule).toContain("--nav-mark: var(--nav-color)");
    expect(linkRule).toContain("--nav-symbol-radius: 10px");
    expect(linkRule).toContain("grid-template-columns: 54px minmax(0, 1fr) 12px");
    expect(linkRule).toContain("min-height: 60px");
    expect(iconRule).toContain("width: 44px");
    expect(iconRule).toContain("height: 44px");
    expect(iconRule).toContain("box-shadow:");
    expect(iconRule).toContain("inset 0 1px 0");
    expect(iconInnerLayerRule).toContain("inset: 7px");
    expect(symbolRule).toContain("width: 25px");
    expect(symbolRule).toContain("height: 25px");
    expect(symbolRule).toContain("background: var(--nav-mark)");
    expect(symbolRule).toContain("inset 0 -3px 0 rgba(31, 45, 61, 0.16)");
    expect(symbolDetailRule).toContain("border: 2px solid rgba(255, 255, 255, 0.72)");
    expect(symbolSparkRule).toContain("top: var(--nav-spark-y)");
    expect(symbolSparkRule).toContain("left: var(--nav-spark-x)");
    expect(appShellCss).not.toContain("filter: drop-shadow");
    expect(glyphRule).toContain("width: 22px");
    expect(glyphRule).toContain("height: 22px");
    expect(glyphRule).toContain("shape-rendering: geometricPrecision");
    expect(appShellSource.indexOf('label: "总览"')).toBeLessThan(
      appShellSource.indexOf('label: "Agent 工作区"'),
    );
    expect(appShellSource.indexOf('label: "Agent 工作区"')).toBeLessThan(
      appShellSource.indexOf('label: "RAG 问答"'),
    );
    expect(appShellSource.indexOf('label: "RAG 问答"')).toBeLessThan(
      appShellSource.indexOf('label: "SQL 工作区"'),
    );
    expect(appShellSource.indexOf('label: "SQL 工作区"')).toBeLessThan(
      appShellSource.indexOf('label: "工具中心"'),
    );
    expect(appShellSource.indexOf('label: "工具中心"')).toBeLessThan(
      appShellSource.indexOf('label: "模型设置"'),
    );
    expect(appShellSource.indexOf('label: "SQL 工作区"')).toBeLessThan(
      appShellSource.indexOf('label: "Skill 注册中心"'),
    );
    expect(appShellSource).toContain('label: "Agent 工作区"');
    expect(appShellSource).toContain('label: "RAG 问答"');
    expect(appShellSource).toContain('label: "SQL 工作区"');
    expect(appShellSource).toContain('label: "工具中心"');
    expect(appShellSource).toContain('label: "Skill 注册中心"');
    expect(appShellSource).toContain("会议录制纪要");
    expect(appShellSource).toContain("AS400对象管理");
    expect(appShellSource).not.toContain("快捷连接");
  });

  it("omits the decorative sidebar footer preview component", () => {
    expect(appShellSource).not.toContain("className={styles.sidebarPreview}");
    expect(appShellSource).not.toContain("className={styles.sidebarPreviewOrbit}");
    expect(appShellSource).not.toContain("className={styles.sidebarPreviewCore}");
    expect(appShellSource).not.toContain("styles.sidebarPreviewMenuNode");
    expect(appShellSource).not.toContain("styles.sidebarPreviewMenuNodeActive");
    expect(appShellSource).not.toContain("styles.sidebarSearch");
    expect(appShellSource).not.toContain("styles.sidebarActions");
    expect(appShellCss).not.toContain(".sidebarPreview");
    expect(appShellCss).not.toContain("sidebar-preview-node");
    expect(appShellCss).not.toContain("@keyframes sidebar-radar-sweep");
    expect(appShellCss).not.toContain("sidebarPreviewMenuRail");
    expect(appShellCss).not.toContain("sidebar-menu-sync");
  });

  it("uses the short release center menu label", () => {
    expect(appShellSource).toContain('label: "发布中心"');
    expect(appShellSource).not.toContain("应用发布与运行控制");
  });
});
