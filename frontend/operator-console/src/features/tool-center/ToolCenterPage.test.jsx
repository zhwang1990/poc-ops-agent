import { fireEvent, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, test } from "vitest";

import App from "../../app/App.jsx";
import { AppProviders } from "../../app/providers.jsx";
import { server } from "../../test/server.js";

function renderToolCenter() {
  return render(
    <AppProviders Router={MemoryRouter} routerProps={{ initialEntries: ["/tools"] }}>
      <App />
    </AppProviders>,
  );
}

beforeEach(() => {
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
    expect(screen.getByRole("tab", { name: "JSON Formatter" })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByRole("tab", { name: "API Caller" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "API Caller 设置" })).toBeInTheDocument();

    await userEvent.click(screen.getByRole("tab", { name: "API Caller" }));
    expect(screen.getByRole("button", { name: "发送请求" })).toBeDisabled();

    await userEvent.click(screen.getByRole("tab", { name: "API Caller 设置" }));
    expect(screen.getByRole("button", { name: "保存 allowlist 草稿" })).toBeDisabled();
    expect(executeCalls).toBe(0);
  });

  test("formats and minifies JSON locally", async () => {
    const user = userEvent.setup();
    renderToolCenter();

    const input = await screen.findByLabelText("JSON 输入");
    await user.clear(input);
    fireEvent.change(input, { target: { value: '{"service":"queFork","enabled":true}' } });
    await user.click(screen.getByRole("button", { name: "格式化 JSON" }));

    expect(screen.getByLabelText("JSON 输出")).toHaveValue(
      '{\n  "service": "queFork",\n  "enabled": true\n}',
    );

    await user.click(screen.getByRole("button", { name: "压缩 JSON" }));
    expect(screen.getByLabelText("JSON 输出")).toHaveValue(
      '{"service":"queFork","enabled":true}',
    );
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
    await user.type(screen.getByLabelText("临时凭据"), "super-secret-token");

    expect(screen.getByText("https://api.quefork.internal:8443")).toBeInTheDocument();
    expect(
      screen.getByText("已输入 18 位临时凭据，本页不会在历史或预览中显示明文。"),
    ).toBeInTheDocument();
    expect(screen.queryByText("super-secret-token")).not.toBeInTheDocument();
  });

  test("validates administrator allowlist drafts", async () => {
    const user = userEvent.setup();
    renderToolCenter();

    await user.click(await screen.findByRole("tab", { name: "API Caller 设置" }));
    const settingsPanel = screen.getByRole("region", { name: "API Caller 管理员设置" });

    await user.clear(within(settingsPanel).getByLabelText("目标系统"));
    await user.type(within(settingsPanel).getByLabelText("目标系统"), "queFork");
    await user.clear(within(settingsPanel).getByLabelText("允许域名"));
    await user.type(within(settingsPanel).getByLabelText("允许域名"), "https://*.internal");
    await user.click(within(settingsPanel).getByRole("button", { name: "校验 allowlist 草稿" }));

    expect(within(settingsPanel).getByText("首版不允许配置通配域名。")).toBeInTheDocument();
  });
});
