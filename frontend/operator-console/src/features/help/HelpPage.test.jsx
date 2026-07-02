import { http, HttpResponse } from "msw";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, it } from "vitest";
import { MemoryRouter } from "react-router-dom";

import { AppProviders } from "../../app/providers.jsx";
import { server } from "../../test/server.js";
import { HelpPage } from "./HelpPage.jsx";

beforeEach(() => {
  server.use(
    http.get("/auth/session", () =>
      HttpResponse.json({
        authenticated: true,
        subject: "operator-1",
        username: "ops.reader",
        roles: ["ROLE_ops-reader"],
        authenticationType: "built-in",
      }),
    ),
  );
});

function renderHelpPage() {
  return render(
    <AppProviders Router={MemoryRouter} routerProps={{ initialEntries: ["/help"] }}>
      <HelpPage />
    </AppProviders>,
  );
}

describe("HelpPage", () => {
  it("渲染帮助产品手册工作区", async () => {
    renderHelpPage();

    expect(await screen.findByRole("heading", { name: "帮助" })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "帮助章节目录" })).toBeInTheDocument();
    expect(screen.getByRole("searchbox", { name: "搜索场景、页面、错误或权限问题" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "帮助正文" })).toBeInTheDocument();
    expect(screen.getByRole("complementary", { name: "本章速览" })).toBeInTheDocument();
    expect(screen.getByText("用 Agent 排查服务错误")).toBeInTheDocument();
    expect(screen.getByText(/只读诊断，不执行生产写操作。/)).toBeInTheDocument();
  });

  it("搜索并打开结果", async () => {
    const user = userEvent.setup();
    renderHelpPage();

    await user.type(
      await screen.findByRole("searchbox", { name: "搜索场景、页面、错误或权限问题" }),
      "权限拒绝",
    );

    const results = screen.getByRole("list", { name: "帮助搜索结果" });
    const resultButton = within(results).getByRole("button", { name: /解释按钮不可用/ });
    expect(resultButton).toBeInTheDocument();

    await user.click(resultButton);
    await new Promise((resolve) => window.requestAnimationFrame(resolve));

    expect(screen.getByRole("searchbox", { name: "搜索场景、页面、错误或权限问题" })).toHaveValue("");
    expect(screen.getByRole("button", { name: "权限与安全边界" })).toHaveAttribute("aria-current", "page");
    expect(screen.getByRole("article", { name: "解释按钮不可用" })).toBeInTheDocument();
  });

  it("无命中状态不生成回答", async () => {
    const user = userEvent.setup();
    renderHelpPage();

    await user.type(
      await screen.findByRole("searchbox", { name: "搜索场景、页面、错误或权限问题" }),
      "一个不存在的关键词",
    );

    expect(screen.getByRole("status")).toHaveTextContent("未找到匹配手册内容");
    expect(screen.queryByRole("button", { name: "提交 RAG 问题" })).not.toBeInTheDocument();
    expect(screen.queryByText("正在生成")).not.toBeInTheDocument();
  });
});
