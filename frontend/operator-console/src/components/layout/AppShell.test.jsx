import { readFileSync } from "node:fs";

import { describe, expect, it } from "vitest";

const appShellCss = readFileSync(
  "src/components/layout/AppShell.module.css",
  "utf8",
);
const appShellSource = readFileSync("src/components/layout/AppShell.jsx", "utf8");

describe("AppShell styles", () => {
  it("lifts the whole sidebar navigation stack by three pixels", () => {
    const navRule = appShellCss.match(/[.]nav\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(navRule).toContain("transform: translateY(-3px)");
  });

  it("renders sidebar menu buttons with login-aligned glass highlights", () => {
    const sidebarRule = appShellCss.match(/[.]sidebar\s*[{][^}]+[}]/u)?.[0] ?? "";
    const navLinkRule = appShellCss.match(/[.]navLink\s*[{][^}]+[}]/u)?.[0] ?? "";
    const navLinkSheenRule = appShellCss.match(/[.]navLink::after\s*[{][^}]+[}]/u)?.[0] ?? "";
    const activeRule = appShellCss.match(/[.]active\s*[{][^}]+[}]/u)?.[0] ?? "";
    const hoverRule = appShellCss.match(/[.]navLink:hover\s*[{][^}]+[}]/u)?.[0] ?? "";

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
    expect(hoverRule).toContain("rgba(255, 255, 255, 0.82)");
    expect(hoverRule).toContain("0 14px 26px rgba(31, 45, 61, 0.08)");
  });

  it("uses larger tactile navigation icons with refined symbol layers", () => {
    const linkRule = appShellCss.match(/[.]navLink\s*[{][^}]+[}]/u)?.[0] ?? "";
    const iconRule = appShellCss.match(/[.]navIcon\s*[{][^}]+[}]/u)?.[0] ?? "";
    const iconBeforeRule = appShellCss.match(/[.]navIcon::before\s*[{][^}]+[}]/u)?.[0] ?? "";
    const iconAfterRule =
      [...appShellCss.matchAll(/[.]navIcon::after\s*[{][^}]+[}]/gu)].at(-1)?.[0] ?? "";
    const symbolRule = appShellCss.match(/[.]navSymbol\s*[{][^}]+[}]/u)?.[0] ?? "";
    const symbolBeforeRule =
      appShellCss.match(/[.]navSymbol::before\s*[{][^}]+[}]/u)?.[0] ?? "";
    const symbolAfterRule =
      [...appShellCss.matchAll(/[.]navSymbol::after\s*[{][^}]+[}]/gu)].at(-1)?.[0] ?? "";
    const glyphRule = appShellCss.match(/[.]navGlyph\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(appShellSource).toContain("className={styles.navSymbol}");
    expect(linkRule).toContain("grid-template-columns: 54px minmax(0, 1fr) 12px");
    expect(iconRule).toContain("width: 44px");
    expect(iconRule).toContain("height: 44px");
    expect(iconRule).toContain("box-shadow:");
    expect(iconRule).toContain("inset 0 1px 0");
    expect(appShellCss).toContain(".navIcon::before,\n.navIcon::after");
    expect(appShellCss).toContain("content: \"\"");
    expect(iconBeforeRule).toContain("inset: 7px");
    expect(iconAfterRule).toContain("border-bottom-color");
    expect(symbolRule).toContain("width: 25px");
    expect(symbolRule).toContain("height: 25px");
    expect(symbolRule).toContain("background: var(--nav-mark)");
    expect(symbolBeforeRule).toContain("border:");
    expect(symbolAfterRule).toContain("box-shadow:");
    expect(appShellCss.match(/--nav-mark:/gu)?.length ?? 0).toBeGreaterThanOrEqual(9);
    expect(glyphRule).toContain("width: 22px");
    expect(glyphRule).toContain("height: 22px");
    expect(glyphRule).toContain("z-index: 2");
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
      appShellSource.indexOf('label: "Skill 注册中心"'),
    );
    expect(appShellSource).toContain('label: "Agent 工作区"');
    expect(appShellSource).toContain('label: "RAG 问答"');
    expect(appShellSource).toContain('label: "SQL 工作区"');
    expect(appShellSource).toContain('label: "Skill 注册中心"');
    expect(appShellSource).toContain("会议录制纪要");
    expect(appShellSource).toContain("AS400对象管理");
    expect(appShellSource).not.toContain("快捷连接");
  });

  it("keeps the sidebar footer preview aligned to the navigation stack", () => {
    const previewRule =
      appShellCss.match(/[.]sidebarPreview\s*[{][^}]+[}]/u)?.[0] ?? "";
    const previewRailRule =
      appShellCss.match(/[.]sidebarPreview::before,[\s\S]*?[.]sidebarPreview::after\s*[{][^}]+[}]/u)?.[0] ??
      "";
    const previewFieldRule =
      appShellCss.match(/[.]sidebarPreview::before\s*[{][^}]+[}]/u)?.[0] ?? "";
    const previewSweepRule =
      [...appShellCss.matchAll(/[.]sidebarPreview::after\s*[{][^}]+[}]/gu)].at(-1)?.[0] ??
      "";
    const previewOrbitRule =
      appShellCss.match(/[.]sidebarPreviewOrbit\s*[{][^}]+[}]/u)?.[0] ?? "";
    const previewCoreRule =
      appShellCss.match(/[.]sidebarPreviewCore\s*[{][^}]+[}]/u)?.[0] ?? "";
    const previewMenuNodeRule =
      appShellCss.match(/[.]sidebarPreviewMenuNode\s*[{][^}]+[}]/u)?.[0] ?? "";
    const previewActiveNodeRule =
      appShellCss.match(/[.]sidebarPreviewMenuNodeActive\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(appShellSource).toContain("className={styles.sidebarPreview}");
    expect(appShellSource).toContain("className={styles.sidebarPreviewOrbit}");
    expect(appShellSource).toContain("className={styles.sidebarPreviewCore}");
    expect(appShellSource).toContain("styles.sidebarPreviewMenuNode");
    expect(appShellSource).toContain("styles.sidebarPreviewMenuNodeActive");
    expect(appShellSource).not.toContain("styles.sidebarSearch");
    expect(appShellSource).not.toContain("styles.sidebarActions");
    expect(previewRule).toContain("display: grid");
    expect(previewRule).toContain("min-height: 110px");
    expect(previewRule).toContain("isolation: isolate");
    expect(previewRule).toContain("overflow: hidden");
    expect(previewRule).not.toContain("--preview-signal-start");
    expect(previewOrbitRule).toContain("grid-template-columns: repeat(5, minmax(0, 1fr))");
    expect(previewOrbitRule).toContain("gap: 12px 10px");
    expect(previewCoreRule).toContain("transform: translateY(-50%)");
    expect(previewMenuNodeRule).toContain("background: color-mix(in srgb, var(--nav-color)");
    expect(previewMenuNodeRule).toContain("animation: sidebar-preview-node");
    expect(previewActiveNodeRule).toContain("border-color: color-mix");
    expect(previewRailRule).toContain("height: 1px");
    expect(previewFieldRule).toContain("top: 16px");
    expect(previewSweepRule).not.toContain("conic-gradient");
    expect(appShellCss).toContain("@keyframes sidebar-preview-node");
    expect(appShellCss).not.toContain("@keyframes sidebar-radar-sweep");
    expect(appShellCss).not.toContain("sidebarPreviewMenuRail");
    expect(appShellCss).not.toContain("sidebar-menu-sync");
  });

  it("uses the short release center menu label", () => {
    expect(appShellSource).toContain('label: "发布中心"');
    expect(appShellSource).not.toContain("应用发布与运行控制");
  });
});
