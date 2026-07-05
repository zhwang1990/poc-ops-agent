import { readFileSync } from "node:fs";
import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, test, vi } from "vitest";

import App from "../../app/App.jsx";
import { AppProviders } from "../../app/providers.jsx";
import { server } from "../../test/server.js";

const toolCenterCss = readFileSync("src/features/tool-center/ToolCenterPage.module.css", "utf8");
const clipboardWriteText = vi.fn(() => Promise.resolve());

function renderToolCenter(path = "/tools") {
  return render(
    <AppProviders Router={MemoryRouter} routerProps={{ initialEntries: [path] }}>
      <App />
    </AppProviders>,
  );
}

beforeEach(() => {
  vi.restoreAllMocks();
  clipboardWriteText.mockResolvedValue(undefined);
  Object.defineProperty(navigator, "clipboard", {
    configurable: true,
    value: {
      writeText: clipboardWriteText,
    },
  });

  server.use(
    http.get("/auth/session", () =>
      HttpResponse.json({
        authenticated: true,
        subject: "operator-1",
        username: "ops.admin",
        roles: ["ROLE_ops-admin"],
        authenticationType: "built-in",
      }),
    ),
  );
});

describe("ToolCenterPage", () => {
  test("renders the built-in tools without calling missing backend APIs", async () => {
    let executeCalls = 0;
    server.use(
      http.post("/internal/tool-center/api-caller/execute", () => {
        executeCalls += 1;
        return HttpResponse.json({ requestId: "blocked" });
      }),
      http.post("/internal/tool-center/api-caller/allowlist", () => {
        executeCalls += 1;
        return HttpResponse.json({ allowlistId: "blocked" });
      }),
    );

    renderToolCenter();

    expect(await screen.findByRole("heading", { name: "工具中心" })).toBeInTheDocument();
    const toolbar = screen.getByRole("toolbar", { name: "工具中心工具栏" });
    expect(
      screen.queryByRole("complementary", { name: "工具中心边界状态" }),
    ).not.toBeInTheDocument();
    const toolSwitch = within(toolbar).getByRole("tablist", { name: "工具中心工具切换" });
    expect(within(toolSwitch).getByRole("tab", { name: "Json Helper" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByRole("region", { name: "Json Helper 工作区" })).toBeInTheDocument();
    expect(within(toolSwitch).getByRole("tab", { name: "API Caller" })).toBeInTheDocument();
    expect(toolbar.children).toHaveLength(1);
    expect(toolbar.firstElementChild).toBe(toolSwitch);
    expect(within(toolSwitch).queryByRole("tab", { name: "API Caller 设置" })).not.toBeInTheDocument();
    for (const label of ["Json Helper", "API Caller"]) {
      expect(within(toolSwitch).getByRole("tab", { name: label }).querySelector("svg")).toBeInTheDocument();
    }
    expect(cssRule("toolWorkbench")).toContain("border: 0");
    expect(cssRule("toolWorkbench")).toContain("background: transparent");
    expect(cssRule("toolWorkbench")).toContain("box-shadow: none");
    expect(cssRule("toolWorkbench")).toContain("backdrop-filter: none");
    expect(cssRule("toolToolbar")).toContain("justify-content: flex-start");
    expect(cssRule("toolToolbar")).not.toContain("justify-content: flex-end");
    expect(cssRule("panelTitle")).toContain("align-items: center");
    expect(cssRule("panelTitleWithSubtitle")).toContain("align-items: start");
    expect(cssRule("actionRow")).toContain("justify-content: flex-end");
    expect(cssRule("jsonPanelHeader")).toContain("justify-content: space-between");
    expect(cssRule("jsonPanelHeader")).toContain("border-bottom: 1px solid var(--tool-border)");
    expect(cssRule("jsonPanelBody")).toContain("min-height: 0");
    expect(cssRule("jsonPanelFooter")).toContain("justify-content: flex-end");
    expect(cssRule("jsonPanelFooter")).toContain("border-top: 1px solid var(--tool-border)");
    expect(cssRule("jsonPanelFooterMessage")).toContain("text-overflow: ellipsis");
    expect(cssRule("jsonPanelHeaderActions")).toContain("justify-content: flex-end");
    expect(cssRule("jsonAssistantStatus")).toContain("justify-content: flex-end");
    expect(cssRule("jsonTransformIconButton")).toContain("width: 34px");
    expect(toolCenterCss).toMatch(/\.jsonHeroGrid \.textareaField textarea\s*\{[^}]*border: 0/);
    expect(toolCenterCss).toMatch(/\.jsonHeroGrid \.textareaField textarea\s*\{[^}]*background: transparent/);
    expect(cssRule("jsonHeroSearch")).toContain("position: relative");
    expect(cssRule("jsonHeroSearch input")).toContain("padding: 0 34px 0 10px");
    expect(cssRule("jsonHeroSearch svg")).toContain("right: 11px");
    expect(cssRule("jsonHeroHeader")).toContain("border-bottom: 1px solid var(--tool-border)");
    expect(cssRule("jsonHeroFooter")).toContain("justify-content: space-between");
    expect(cssRule("jsonHeroFooterSummary")).toContain("justify-content: flex-start");
    expect(cssRule("jsonHeroFooterStatus")).toContain("justify-content: flex-end");
    expect(cssRule("jsonHeroSummary")).toContain("justify-content: flex-start");
    expect(cssRule("jsonHeroActions")).toContain("justify-content: flex-end");
    expect(cssRulePattern("\\.jsonHeroActions button")).toContain("width: 30px");
    expect(cssRule("jsonHeroCopyStatus")).toContain("width: 18px");
    expect(cssRule("jsonHeroTree")).toContain("border: 0");
    expect(cssRule("jsonHeroTree")).toContain("background: transparent");
    expect(cssRule("jsonHeroGrid")).toContain("gap: 0 10px");
    expect(cssRule("jsonHeroGrid")).toContain("padding: 0");
    expect(cssRule("jsonHeroPanel")).toContain("grid-template-rows: auto minmax(0, 1fr) auto");
    expect(cssRule("jsonHeroGridExpanded")).toContain("grid-template-columns: minmax(0, 1fr)");
    expect(cssRule("expandedJsonPanel")).toContain("height: 100%");

    await userEvent.click(screen.getByRole("tab", { name: "API Caller" }));
    const apiCallerPanel = screen.getByRole("region", { name: "API Caller" });
    expect(apiCallerPanel.querySelector('[class*="editorPanel"]')).not.toBeInTheDocument();
    expect(apiCallerPanel.querySelector('[class*="sidePanel"]')).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Save" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Share" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "发送请求" })).toBeDisabled();
    expect(screen.queryByLabelText("请求路径")).not.toBeInTheDocument();
    const requestTabs = screen.getByRole("tablist", { name: "API Caller 请求配置" });
    for (const label of ["Params (2)", "Auth", "Headers (1)", "Body", "Scripts"]) {
      expect(within(requestTabs).getByRole("tab", { name: label })).toBeInTheDocument();
    }
    expect(within(requestTabs).queryByRole("tab", { name: "Settings" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "API Caller 管理员设置" })).not.toBeInTheDocument();
    expect(screen.getByRole("table", { name: "Params 键值表" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "添加 Params" })).not.toBeInTheDocument();
    expect(screen.queryByRole("columnheader", { name: "Bulk Edit" })).not.toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "启用 Param 1" })).not.toBeChecked();
    expect(screen.getByLabelText("Param 3 Key")).toHaveValue("");
    expect(screen.getByRole("tablist", { name: "API Caller 响应标签" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "API Caller Terminal" })).toBeInTheDocument();
    expect(screen.getByRole("complementary", { name: "API Caller AI 助手" })).toBeInTheDocument();
    expect(screen.queryByText("输入完整 URL；环境信息由域名规则在服务端识别。")).not.toBeInTheDocument();
    expect(screen.queryByText("真实调用由后端 EasyPostman 适配和 Worker 出口控制。")).not.toBeInTheDocument();
    expect(screen.queryByText("管理员维护域名级 allowlist；首版页面只校验草稿，不保存配置。")).not.toBeInTheDocument();
    expect(screen.queryByText("只允许域名级 allowlist，不配置单个接口路径。")).not.toBeInTheDocument();
    expect(screen.queryByText("不允许首版通配域名和默认本机地址。")).not.toBeInTheDocument();
    expect(screen.queryByText("保存、启停和审计事件由后端策略动作实现。")).not.toBeInTheDocument();
    expect(executeCalls).toBe(0);
  });

  test("expands a single JSON panel and restores the three panel layout", async () => {
    const user = userEvent.setup();
    renderToolCenter();

    expect(await screen.findByLabelText("JSON 输入")).toBeInTheDocument();
    expect(screen.getByLabelText("JSON 输出")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "JSON 结构浏览" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "展开输入面板" }));
    expect(screen.getByLabelText("JSON 输入")).toBeInTheDocument();
    expect(screen.queryByLabelText("JSON 输出")).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "JSON 结构浏览" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "恢复默认布局" }).textContent).toBe("");

    await user.click(screen.getByRole("button", { name: "恢复默认布局" }));
    expect(screen.getByLabelText("JSON 输入")).toBeInTheDocument();
    expect(screen.getByLabelText("JSON 输出")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "JSON 结构浏览" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "展开输出面板" }));
    expect(screen.queryByLabelText("JSON 输入")).not.toBeInTheDocument();
    expect(screen.getByLabelText("JSON 输出")).toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "JSON 结构浏览" })).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "恢复默认布局" }));
    await user.click(screen.getByRole("button", { name: "展开结构浏览面板" }));
    expect(screen.queryByLabelText("JSON 输入")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("JSON 输出")).not.toBeInTheDocument();
    expect(screen.getByRole("region", { name: "JSON 结构浏览" })).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "恢复默认布局" }));
    expect(screen.getByLabelText("JSON 输入")).toBeInTheDocument();
    expect(screen.getByLabelText("JSON 输出")).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "JSON 结构浏览" })).toBeInTheDocument();
  });

  test("formats and minifies JSON locally", async () => {
    const user = userEvent.setup();
    renderToolCenter();

    const input = await screen.findByLabelText("JSON 输入");
    const output = screen.getByLabelText("JSON 输出");

    expect(screen.queryByText("本地解析、格式化和压缩，不上传内容。")).not.toBeInTheDocument();
    expect(screen.queryByText("解析错误只在本地显示。")).not.toBeInTheDocument();
    expect(screen.queryByText("JSON 输入")).not.toBeInTheDocument();
    expect(screen.queryByText("JSON 输出")).not.toBeInTheDocument();
    const formatButton = screen.getByRole("button", { name: "格式化 JSON" });
    const minifyButton = screen.getByRole("button", { name: "压缩 JSON" });
    const inputPanel = input.closest('[class*="editorPanel"]');
    const outputPanel = output.closest('[class*="editorPanel"]');
    expect(inputPanel).not.toBeNull();
    expect(outputPanel).not.toBeNull();
    expect(within(/** @type {HTMLElement} */ (inputPanel)).getByRole("button", { name: "格式化 JSON" })).toBe(
      formatButton,
    );
    expect(within(/** @type {HTMLElement} */ (inputPanel)).queryByRole("button", { name: "压缩 JSON" })).not.toBeInTheDocument();
    expect(within(/** @type {HTMLElement} */ (outputPanel)).getByRole("button", { name: "压缩 JSON" })).toBe(
      minifyButton,
    );
    expect(within(/** @type {HTMLElement} */ (outputPanel)).queryByRole("button", { name: "格式化 JSON" })).not.toBeInTheDocument();
    expect(formatButton.textContent).toBe("");
    expect(minifyButton.textContent).toBe("");

    await user.clear(input);
    fireEvent.change(input, { target: { value: '{"service":"queFork","enabled":true}' } });
    await user.click(formatButton);

    expect(screen.getByLabelText("JSON 输出")).toHaveValue(
      '{\n  "service": "queFork",\n  "enabled": true\n}',
    );

    await user.click(minifyButton);
    expect(screen.getByLabelText("JSON 输出")).toHaveValue(
      '{"service":"queFork","enabled":true}',
    );
  });

  test("repairs JSON locally with jsonrepair without calling AI fallback", async () => {
    const user = userEvent.setup();
    let assistantCalls = 0;
    server.use(
      http.post("/internal/tool-center/json-assistant/repair", () => {
        assistantCalls += 1;
        return HttpResponse.json({ status: "blocked" });
      }),
    );
    renderToolCenter();

    const input = await screen.findByLabelText("JSON 输入");
    await user.clear(input);
    fireEvent.change(input, { target: { value: '{"service":"queFork","enabled":true,}' } });
    await user.click(screen.getByRole("button", { name: "本地修补 JSON" }));

    expect(input).toHaveValue('{\n  "service": "queFork",\n  "enabled": true\n}');
    expect(screen.getByLabelText("JSON 输出")).toHaveValue('{\n  "service": "queFork",\n  "enabled": true\n}');
    const inputPanel = input.closest('[class*="editorPanel"]');
    const inputFooter = inputPanel?.querySelector('[class*="jsonPanelFooter"]');
    expect(inputFooter).not.toBeNull();
    expect(within(/** @type {HTMLElement} */ (inputFooter)).getByRole("status")).toHaveTextContent(
      "JSON 已本地修补并通过校验。",
    );
    expect(assistantCalls).toBe(0);
  });

  test("uses AI fallback only after local parse and jsonrepair both fail", async () => {
    const user = userEvent.setup();
    let assistantCalls = 0;
    /** @type {unknown} */
    let requestBody = null;
    server.use(
      http.post("/internal/tool-center/json-assistant/repair", async ({ request }) => {
        assistantCalls += 1;
        requestBody = await request.json();
        return HttpResponse.json({
          contractVersion: "1.0",
          status: "SUCCEEDED",
          assistantAction: "REPAIR_JSON",
          summary: "已从 Java 字符串中提取候选 JSON。",
          repairedJson: '{"service":"queFork","enabled":true}',
          failureReason: null,
          safetyNotes: ["AI 兜底结果必须重新经过本地 JSON 校验。"],
          validationRequired: true,
          skillId: "json-repair-assistant-read",
          modelProviderFingerprint: "provider:fingerprint",
        });
      }),
    );
    renderToolCenter();

    const input = await screen.findByLabelText("JSON 输入");
    await user.clear(input);
    fireEvent.change(input, {
      target: { value: 'String json = "{\\"service\\":\\"queFork\\",\\"enabled\\":true}";' },
    });
    await user.click(screen.getByRole("button", { name: "AI 兜底修补 JSON" }));

    expect(await screen.findByText("已从 Java 字符串中提取候选 JSON。")).toBeInTheDocument();
    expect(input).toHaveValue('{\n  "service": "queFork",\n  "enabled": true\n}');
    expect(screen.getByLabelText("JSON 输出")).toHaveValue('{\n  "service": "queFork",\n  "enabled": true\n}');
    expect(assistantCalls).toBe(1);
    expect(requestBody).toMatchObject({
      contractVersion: "1.0",
      assistantAction: "REPAIR_JSON",
      source: 'String json = "{\\"service\\":\\"queFork\\",\\"enabled\\":true}";',
    });
  });

  test("browses parsed JSON with react-json-view-lite in a local structure view", async () => {
    const user = userEvent.setup();
    Object.defineProperty(navigator, "clipboard", {
      configurable: true,
      value: {
        writeText: clipboardWriteText,
      },
    });
    renderToolCenter();

    const input = await screen.findByLabelText("JSON 输入");
    await user.clear(input);
    fireEvent.change(input, {
      target: {
        value: '{"service":{"name":"queFork","enabled":true},"ports":[8080,null],"release-window":"night"}',
      },
    });

    const browser = screen.getByRole("region", { name: "JSON 结构浏览" });
    expect(within(browser).queryByText("本地树形查看，不发送内容。")).not.toBeInTheDocument();
    expect(browser.querySelector('[data-json-viewer="react-json-view-lite"]')).toBeInTheDocument();
    expect(within(browser).getAllByRole("button", { name: "collapse JSON" }).length).toBeGreaterThan(0);
    expect(within(browser).getAllByText("object").length).toBeGreaterThanOrEqual(1);
    expect(within(browser).getAllByText("3 fields").length).toBeGreaterThanOrEqual(1);
    const browserFooter = browser.querySelector('[class*="jsonPanelFooter"]');
    expect(browserFooter).not.toBeNull();
    expect(within(/** @type {HTMLElement} */ (browserFooter)).getByText("object")).toBeInTheDocument();
    expect(within(/** @type {HTMLElement} */ (browserFooter)).getByText("3 fields")).toBeInTheDocument();
    expect(within(browser).getByText(/^service:$/)).toBeInTheDocument();
    expect(within(browser).getByText(/^ports:$/)).toBeInTheDocument();
    expect(within(browser).getByText(/^release-window:$/)).toBeInTheDocument();
    expect(browser.querySelector('[class*="jsonHeroPathBar"]')).not.toBeInTheDocument();

    await user.type(within(browser).getByLabelText("搜索 JSON 结构"), "queFork");
    expect(within(browser).queryByText("1 个命中")).not.toBeInTheDocument();
    await user.click(within(browser).getByRole("button", { name: "选择 $.service.name string \"queFork\"" }));
    expect(within(browser).getAllByText("$.service.name").length).toBeGreaterThanOrEqual(1);

    const copyPathButton = within(browser).getByRole("button", { name: "复制路径" });
    const copyValueButton = within(browser).getByRole("button", { name: "复制值" });
    const expandAllButton = within(browser).getByRole("button", { name: "全部展开" });
    const collapseAllButton = within(browser).getByRole("button", { name: "全部折叠" });
    expect(copyPathButton.textContent).toBe("");
    expect(copyValueButton.textContent).toBe("");
    expect(expandAllButton.textContent).toBe("");
    expect(collapseAllButton.textContent).toBe("");

    await user.click(copyPathButton);
    expect(clipboardWriteText).toHaveBeenCalledWith("$.service.name");

    await user.click(copyValueButton);
    expect(clipboardWriteText).toHaveBeenCalledWith('"queFork"');
    expect(within(browser).getByRole("status", { name: "已复制值" })).toBeInTheDocument();
    expect(within(browser).queryByText("已复制值")).not.toBeInTheDocument();

    await user.clear(within(browser).getByLabelText("搜索 JSON 结构"));
    await user.type(within(browser).getByLabelText("搜索 JSON 结构"), "8080");
    expect(within(browser).queryByText("1 个命中")).not.toBeInTheDocument();
    expect(within(browser).getByRole("button", { name: "选择 $.ports[0] number 8080" })).toBeInTheDocument();
  });

  test("shows a local structure browser error for invalid JSON", async () => {
    const user = userEvent.setup();
    renderToolCenter();

    const input = await screen.findByLabelText("JSON 输入");
    await user.clear(input);
    fireEvent.change(input, { target: { value: '{"service":' } });

    const browser = screen.getByRole("region", { name: "JSON 结构浏览" });
    const browserFooter = browser.querySelector('[class*="jsonPanelFooter"]');
    expect(browserFooter).not.toBeNull();
    expect(within(/** @type {HTMLElement} */ (browserFooter)).getByRole("alert")).toHaveTextContent(
      "JSON 解析失败，请检查对象、数组、逗号和引号。",
    );
    expect(browser.querySelector('[class*="jsonPanelBody"] [role="alert"]')).not.toBeInTheDocument();
    expect(within(browser).queryByRole("button", { name: "复制值" })).not.toBeInTheDocument();
  });

  test("derives API Caller domain and hides temporary credential plaintext", async () => {
    const user = userEvent.setup();
    renderToolCenter();

    await user.click(await screen.findByRole("tab", { name: "API Caller" }));
    await user.clear(screen.getByLabelText("请求 URL"));
    await user.type(
      screen.getByLabelText("请求 URL"),
      "https://api.quefork.internal:8443/orders/42",
    );
    const requestTabs = screen.getByRole("tablist", { name: "API Caller 请求配置" });
    await user.click(within(requestTabs).getByRole("tab", { name: "Auth" }));
    await user.selectOptions(screen.getByLabelText("Auth Type"), "bearer");
    await user.type(screen.getByLabelText("Bearer Token"), "super-secret-token");

    expect(screen.getByText("https://api.quefork.internal:8443")).toBeInTheDocument();
    expect(
      screen.getByText("已输入 18 位 Bearer Token，本页不会在历史或预览中显示明文。"),
    ).toBeInTheDocument();
    expect(screen.queryByText("super-secret-token")).not.toBeInTheDocument();
  });

  test("supports only the selected API Caller auth types", async () => {
    const user = userEvent.setup();
    renderToolCenter();

    await user.click(await screen.findByRole("tab", { name: "API Caller" }));
    const requestTabs = screen.getByRole("tablist", { name: "API Caller 请求配置" });
    await user.click(within(requestTabs).getByRole("tab", { name: "Auth" }));

    const authType = screen.getByLabelText("Auth Type");
    expect(within(authType).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "No Auth",
      "Basic Auth",
      "Bearer Token",
      "API Key",
    ]);
    for (const unsupported of ["JWT Bearer", "Digest Auth", "OAuth 1.0", "OAuth 2.0", "AWS Signature", "临时凭据"]) {
      expect(within(authType).queryByRole("option", { name: unsupported })).not.toBeInTheDocument();
    }

    await user.selectOptions(authType, "basic");
    expect(screen.getByLabelText("Username")).toBeInTheDocument();
    expect(screen.getByLabelText("Username")).toHaveAttribute("placeholder", "Username");
    expect(screen.getByLabelText("Password")).toHaveAttribute("type", "password");
    expect(screen.getByLabelText("Password")).toHaveAttribute("placeholder", "Password");
    const basicAuthRegion = screen.getByRole("region", { name: "Auth" });
    expect(basicAuthRegion.querySelector('[class*="authCredentialGrid"]')).toBeInTheDocument();
    expect(basicAuthRegion.querySelector('[class*="authHelpText"]')).not.toBeInTheDocument();

    await user.selectOptions(authType, "bearer");
    expect(screen.getByLabelText("Bearer Token")).toHaveAttribute("type", "password");
    expect(screen.queryByLabelText("Username")).not.toBeInTheDocument();

    await user.selectOptions(authType, "api-key");
    const authRegion = screen.getByRole("region", { name: "Auth" });
    expect(authRegion.querySelector('[class*="authMethodColumn"]')).toBeInTheDocument();
    expect(authRegion.querySelector('[class*="authDetailColumn"]')).toBeInTheDocument();
    expect(authRegion.querySelector('[class*="authCredentialGrid"]')).toBeInTheDocument();
    expect(screen.queryByText("The authorization header will be automatically generated when you send the request.")).not.toBeInTheDocument();
    expect(screen.queryByText(/Learn more about/)).not.toBeInTheDocument();
    expect(screen.getByLabelText("API Key Name")).toBeInTheDocument();
    expect(screen.getByLabelText("API Key Name")).toHaveAttribute("placeholder", "Key");
    expect(screen.getByLabelText("API Key Value")).toHaveAttribute("type", "password");
    expect(screen.getByLabelText("API Key Value")).toHaveAttribute("placeholder", "Value");
    expect(screen.getByLabelText("Add API Key To")).toHaveValue("header");
    expect(cssRule("authPane")).toContain("grid-template-columns: minmax(320px, 486px) minmax(360px, 1fr)");
    expect(cssRule("authPane")).toContain("gap: 0");
    expect(cssRule("authPane")).toContain("padding: 0");
    expect(cssRule("authPane")).toContain("height: 100%");
    expect(cssRule("authPane")).toContain("align-content: stretch");
    expect(cssRule("authDetailColumn")).toContain("height: 100%");
    expect(cssRule("authCredentialGrid")).toContain("grid-template-columns: 84px minmax(260px, 280px)");
  });

  test("syncs checked Params rows into the request URL query string", async () => {
    const user = userEvent.setup();
    renderToolCenter();

    await user.click(await screen.findByRole("tab", { name: "API Caller" }));
    const urlInput = screen.getByLabelText("请求 URL");

    expect(urlInput).toHaveValue("https://api.quefork.internal/orders/42");
    await user.click(screen.getByRole("checkbox", { name: "启用 Param 1" }));
    expect(urlInput).toHaveValue("https://api.quefork.internal/orders/42?include_details=true");

    await user.click(screen.getByRole("checkbox", { name: "启用 Param 2" }));
    expect(urlInput).toHaveValue(
      "https://api.quefork.internal/orders/42?include_details=true&expand=customer%2Cpayment",
    );

    await user.click(screen.getByRole("checkbox", { name: "启用 Param 1" }));
    expect(urlInput).toHaveValue("https://api.quefork.internal/orders/42?expand=customer%2Cpayment");

    await user.clear(screen.getByLabelText("Param 2 Value"));
    await user.type(screen.getByLabelText("Param 2 Value"), "payment");
    expect(urlInput).toHaveValue("https://api.quefork.internal/orders/42?expand=payment");

    const emptyParamToggle = screen.getByRole("checkbox", { name: "启用 Param 3" });
    expect(emptyParamToggle).toBeDisabled();
    await user.click(emptyParamToggle);
    expect(emptyParamToggle).not.toBeChecked();

    await user.type(screen.getByLabelText("Param 3 Key"), "page");
    expect(emptyParamToggle).toBeEnabled();
    await user.type(screen.getByLabelText("Param 3 Value"), "1");
    expect(urlInput).toHaveValue("https://api.quefork.internal/orders/42?expand=payment");
    await user.click(screen.getByRole("checkbox", { name: "启用 Param 3" }));
    expect(urlInput).toHaveValue("https://api.quefork.internal/orders/42?expand=payment&page=1");
  });

  test("keeps API Caller headers and body fields in key value request drafts", async () => {
    const user = userEvent.setup();
    renderToolCenter();

    await user.click(await screen.findByRole("tab", { name: "API Caller" }));
    const requestTabs = screen.getByRole("tablist", { name: "API Caller 请求配置" });
    await user.click(within(requestTabs).getByRole("tab", { name: "Headers (1)" }));
    expect(screen.queryByLabelText("请求 Headers")).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "添加 Header" })).not.toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "启用 Header 1" })).toBeChecked();
    expect(screen.getByLabelText("Header 1 Key")).toHaveValue("Accept");
    expect(screen.getByLabelText("Header 1 Value")).toHaveValue("application/json");
    expect(screen.getByLabelText("Header 2 Key")).toHaveValue("");

    await user.type(screen.getByLabelText("Header 2 Key"), "X-Trace-Id");
    expect(screen.getByLabelText("Header 3 Key")).toHaveValue("");
    await user.type(screen.getByLabelText("Header 2 Value"), "trace-123");
    expect(within(requestTabs).getByRole("tab", { name: "Headers (2)" })).toBeInTheDocument();
    expect(screen.queryByText("trace-123")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "删除 Header 2" }));
    expect(screen.getByLabelText("Header 2 Key")).toHaveValue("");
    expect(screen.queryByLabelText("Header 3 Key")).not.toBeInTheDocument();
    expect(within(requestTabs).getByRole("tab", { name: "Headers (1)" })).toBeInTheDocument();

    await user.click(within(requestTabs).getByRole("tab", { name: "Body" }));
    expect(screen.queryByRole("tablist", { name: "请求体类型" })).not.toBeInTheDocument();
    const bodyTypeGroup = screen.getByRole("radiogroup", { name: "请求体类型" });
    expect(within(bodyTypeGroup).getAllByRole("radio").map((option) => option.closest("label")?.textContent)).toEqual([
      "none",
      "form-data",
      "x-www-form-urlencoded",
      "raw",
      "binary",
      "GraphQL",
    ]);
    expect(within(bodyTypeGroup).getByRole("radio", { name: "none" })).toBeChecked();
    expect(screen.getByText("This request does not have a body")).toBeInTheDocument();
    expect(screen.queryByLabelText("Raw Body 类型")).not.toBeInTheDocument();

    await user.click(within(bodyTypeGroup).getByRole("radio", { name: "x-www-form-urlencoded" }));
    expect(screen.queryByRole("button", { name: "添加 Body 字段" })).not.toBeInTheDocument();
    await user.type(screen.getByLabelText("Body 1 Key"), "dryRun");
    expect(screen.getByLabelText("Body 2 Key")).toHaveValue("");
    await user.type(screen.getByLabelText("Body 1 Value"), "true");

    expect(within(bodyTypeGroup).getByRole("radio", { name: "x-www-form-urlencoded" })).toBeChecked();
    expect(screen.getByText("请求体 1 个字段")).toBeInTheDocument();

    await user.click(within(bodyTypeGroup).getByRole("radio", { name: "raw" }));
    expect(screen.queryByText("请求 Body")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("请求 Body")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Body 内容")).toBeInTheDocument();
    const rawFormat = screen.getByLabelText("Raw Body 类型");
    expect(rawFormat).toHaveValue("json");
    expect(within(rawFormat).getAllByRole("option").map((option) => option.textContent)).toEqual([
      "Text",
      "JavaScript",
      "JSON",
      "HTML",
      "XML",
    ]);
    await user.selectOptions(rawFormat, "text");
    expect(rawFormat).toHaveValue("text");

    await user.click(within(bodyTypeGroup).getByRole("radio", { name: "binary" }));
    expect(screen.queryByLabelText("Raw Body 类型")).not.toBeInTheDocument();
    expect(cssRule("bodyPane")).toContain("grid-template-rows: auto minmax(0, 1fr)");
    expect(cssRule("bodyTypeRadioGroup")).toContain("display: flex");
  });

  test("aligns API Caller key value table headers and rows on the same grid", () => {
    const tableRowRule = cssRulePattern("\\.keyValueTableHead,\\s*\\.keyValueRow");

    expect(tableRowRule).toContain("grid-template-columns: var(--key-value-columns)");
    expect(tableRowRule).toContain("gap: 0");
    expect(cssRule("keyValueTable")).toContain("border: 1px solid var(--tool-border)");
    expect(cssRule("keyValueTable")).not.toContain("border-bottom: 1px solid var(--tool-border)");
    expect(cssRule("keyValueTable")).not.toContain("112px");
    expect(toolCenterCss).not.toMatch(/@media \(max-width: 980px\)[\s\S]*?\.keyValueRow\s*\{/);
  });

  test("validates administrator allowlist drafts", async () => {
    const user = userEvent.setup();
    renderToolCenter("/tools/api-caller-settings");

    expect(await screen.findByRole("heading", { level: 1, name: "API Caller 设置" })).toBeInTheDocument();
    expect(screen.queryByRole("tablist", { name: "API Caller 请求配置" })).not.toBeInTheDocument();
    const settingsPanel = screen.getByRole("region", { name: "API Caller 管理员设置" });

    await user.clear(within(settingsPanel).getByLabelText("目标系统"));
    await user.type(within(settingsPanel).getByLabelText("目标系统"), "queFork");
    await user.clear(within(settingsPanel).getByLabelText("允许域名"));
    await user.type(within(settingsPanel).getByLabelText("允许域名"), "https://*.internal");
    await user.click(within(settingsPanel).getByRole("button", { name: "校验 allowlist 草稿" }));

    expect(within(settingsPanel).getByText("首版不允许配置通配域名。")).toBeInTheDocument();
  });
});

/**
 * @param {string} className
 */
function cssRule(className) {
  const escapedClassName = className.replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const match = new RegExp(`\\.${escapedClassName}\\s*\\{([^}]*)\\}`).exec(toolCenterCss);
  return match?.[1] ?? "";
}

/**
 * @param {string} selectorPattern
 */
function cssRulePattern(selectorPattern) {
  const match = new RegExp(`${selectorPattern}\\s*\\{([^}]*)\\}`).exec(toolCenterCss);
  return match?.[1] ?? "";
}
