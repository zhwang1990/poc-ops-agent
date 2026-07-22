import { readFileSync } from "node:fs";

import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, test } from "vitest";

import App from "../../app/App.jsx";
import { AppProviders } from "../../app/providers.jsx";
import { server } from "../../test/server.js";

const loginCss = readFileSync("src/features/auth/LoginPage.module.css", "utf8");

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

function runtimeLoginInput() {
  return String(Date.now()) + String(Math.random());
}

function useAnonymousSession() {
  server.use(
    http.get("/auth/session", () =>
      HttpResponse.json(
        {
          authenticated: false,
          subject: null,
          username: null,
          roles: [],
          authenticationType: "anonymous",
        },
        { status: 401 },
      ),
    ),
  );
}

function useAuthenticatedSession() {
  server.use(
    http.get("/auth/session", () =>
      HttpResponse.json({
        authenticated: true,
        subject: "alice-id",
        username: "alice",
        roles: ["ROLE_ops-reader"],
        authenticationType: "built-in",
      }),
    ),
  );
}

beforeEach(() => {
  server.use(...defaultHandlers);
});

describe("LoginPage", () => {
  test("shows the login action for an anonymous session", async () => {
    useAnonymousSession();

    renderAt("/login");

    expect(
      await screen.findByRole("heading", { name: "用户登录" }),
    ).toBeInTheDocument();
    expect(screen.getByLabelText("用户名")).toBeInTheDocument();
    expect(screen.getByLabelText("密码")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "登录" })).toBeInTheDocument();
    expect(screen.getByText("P1 只读模式")).toBeInTheDocument();
  });

  test("starts with an empty username field", async () => {
    useAnonymousSession();

    renderAt("/login");

    expect(await screen.findByLabelText("用户名")).toHaveValue("");
  });

  test("keeps the password login entry visible for authenticated browser sessions", async () => {
    useAuthenticatedSession();

    renderAt("/login");

    expect(
      await screen.findByRole("heading", { name: "用户登录" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "登录" })).toBeInTheDocument();
  });

  test("keeps the password field masked without a visibility toggle", async () => {
    useAnonymousSession();

    renderAt("/login");

    const passwordInput = await screen.findByLabelText("密码");
    expect(passwordInput).toHaveAttribute("type", "password");
    expect(screen.queryByRole("button", { name: "显示密码" })).not.toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "隐藏密码" })).not.toBeInTheDocument();
    expect(loginCss).not.toContain(".passwordToggle");
  });

  test("uses a subtle focus treatment for username and password inputs", () => {
    const loginInputFocusRule =
      loginCss.match(/[.]loginInput:focus-visible\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(loginInputFocusRule).toContain("border-color: rgba(34, 126, 166, 0.34)");
    expect(loginInputFocusRule).toContain("outline: 1px solid rgba(34, 126, 166, 0.16)");
    expect(loginInputFocusRule).toContain("box-shadow:");
    expect(loginInputFocusRule).toContain("0 0 0 3px rgba(34, 126, 166, 0.08)");
    expect(loginInputFocusRule).not.toContain("outline: 3px solid");
  });

  test("submits the built-in identity login contract and enters the overview after success", async () => {
    const user = userEvent.setup();
    const submittedPassword = runtimeLoginInput();
    /** @type {unknown[]} */
    const requests = [];
    let authenticated = false;
    server.use(
      http.get("/auth/session", () =>
        authenticated
          ? HttpResponse.json({
              authenticated: true,
              subject: "operator-1",
              username: "alice",
              roles: ["ROLE_ops-reader"],
              authenticationType: "built-in",
            })
          : HttpResponse.json(
              {
                authenticated: false,
                subject: null,
                username: null,
                roles: [],
                authenticationType: "anonymous",
              },
              { status: 401 },
            ),
      ),
      http.post("/auth/login", async ({ request }) => {
        requests.push(await request.json());
        // 登录成功后，受保护路由会重新读取浏览器会话；测试必须模拟控制面已经建立会话。
        authenticated = true;
        return HttpResponse.json({
          authenticated: true,
          subject: "operator-1",
          username: "alice",
          roles: ["ROLE_ops-reader"],
          passwordChangeRequired: false,
        });
      }),
    );

    renderAt("/login");

    await user.clear(screen.getByLabelText("用户名"));
    await user.type(screen.getByLabelText("用户名"), "alice");
    await user.type(screen.getByLabelText("密码"), submittedPassword);
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(requests).toEqual([{ username: "alice", password: submittedPassword }]);
    expect(
      await screen.findByRole("heading", { name: "平台总览" }),
    ).toBeInTheDocument();
  });

  test("stays on the login page when the control plane rejects credentials", async () => {
    const user = userEvent.setup();
    const rejectedPassword = runtimeLoginInput();
    server.use(
      http.post("/auth/login", () =>
        HttpResponse.json(
          {
            errorCode: "INVALID_CREDENTIALS",
            message: "Invalid username or password",
          },
          { status: 401 },
        ),
      ),
    );

    renderAt("/login");

    await user.type(screen.getByLabelText("用户名"), "alice");
    await user.type(screen.getByLabelText("密码"), rejectedPassword);
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(
      await screen.findByRole("alert", { name: "登录失败" }),
    ).toHaveTextContent("用户名或密码不正确");
    expect(screen.getByRole("heading", { name: "用户登录" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "平台总览" })).not.toBeInTheDocument();
  });

  test("explains backend connectivity failures without entering the overview", async () => {
    const user = userEvent.setup();
    const unavailablePassword = runtimeLoginInput();
    server.use(
      http.post("/auth/login", () =>
        HttpResponse.json(
          {
            message: "Bad Gateway",
          },
          { status: 502 },
        ),
      ),
    );

    renderAt("/login");

    await user.type(screen.getByLabelText("用户名"), "alice");
    await user.type(screen.getByLabelText("密码"), unavailablePassword);
    await user.click(screen.getByRole("button", { name: "登录" }));

    expect(
      await screen.findByRole("alert", { name: "登录失败" }),
    ).toHaveTextContent("控制面服务暂时不可用，请确认后端服务已启动后再重试。");
    expect(screen.queryByRole("heading", { name: "平台总览" })).not.toBeInTheDocument();
  });

  test("keeps the login card height fixed without a blank reserved error slot", () => {
    const loginCardRule = loginCss.match(/[.]loginCard\s*[{][^}]+[}]/u)?.[0] ?? "";
    const loginCardWithErrorRule =
      loginCss.match(/[.]loginCardWithError\s*[{][^}]+[}]/u)?.[0] ?? "";
    const loginErrorRule = loginCss.match(/[.]loginError\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(loginCardRule).toMatch(/\n\s+height:\s*344px/u);
    expect(loginCardRule).not.toContain("min-height: 344px");
    expect(loginCss).not.toContain("loginErrorSlot");
    expect(loginCardWithErrorRule).toContain("padding: 28px 36px 24px");
    expect(loginErrorRule).not.toContain("position: absolute");
    expect(loginErrorRule).not.toContain("bottom: calc(100% + 12px)");
  });

  test("keeps login labels stacked above inputs", () => {
    const loginFieldRule = loginCss.match(/[.]loginField\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(loginFieldRule).not.toContain("grid-template-columns");
    expect(loginFieldRule).toContain("display: grid");
  });

  test("does not render the small task flow capsule above the login form", () => {
    renderAt("/login");

    expect(screen.queryByText("提任务")).not.toBeInTheDocument();
    expect(screen.queryByText("选 Skill")).not.toBeInTheDocument();
    expect(screen.queryByText("留痕")).not.toBeInTheDocument();
    expect(loginCss).not.toContain(".capabilityFlow");
  });

  test("does not render the introductory operator audience copy", () => {
    renderAt("/login");

    expect(
      screen.queryByText(
        "面向研发、DBA 与运维团队，通过受控的只读诊断链路定位服务、数据库与基础设施问题。",
      ),
    ).not.toBeInTheDocument();
    expect(loginCss).not.toContain(".loginCopy > p");
  });

  test("keeps only Chinese labels on the diagnostic node cards", () => {
    renderAt("/login");

    expect(screen.getByText("会话确权")).toBeInTheDocument();
    expect(screen.getByText("服务端授权")).toBeInTheDocument();
    expect(screen.getByText("只读候选")).toBeInTheDocument();
    expect(screen.getByText("受限执行")).toBeInTheDocument();
    expect(screen.queryByText("Identity")).not.toBeInTheDocument();
    expect(screen.queryByText("Policy")).not.toBeInTheDocument();
    expect(screen.queryByText("Skill")).not.toBeInTheDocument();
    expect(screen.queryByText("Worker")).not.toBeInTheDocument();
  });

  test("renders four diagnostic node icons with stable label spacing", () => {
    const { container } = renderAt("/login");
    const icons = Array.from(container.querySelectorAll("[data-node-icon]"));
    const routes = new Set(icons.map((icon) => icon.getAttribute("data-node-icon")));
    const opsNodeRule = loginCss.match(/[.]opsNode\s*[{][^}]+[}]/u)?.[0] ?? "";
    const nodeIconRule = loginCss.match(/[.]nodeIcon\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(icons).toHaveLength(4);
    expect(routes).toEqual(new Set(["identity", "policy", "skill", "worker"]));
    expect(opsNodeRule).toContain("row-gap: 9px");
    expect(opsNodeRule).toContain("align-content: center");
    expect(nodeIconRule).toContain("place-items: center");
    expect(nodeIconRule).not.toContain("margin-bottom");
    expect(loginCss).toContain(".nodeIconGlyph");
    expect(loginCss).not.toContain(".nodeRobot");
    expect(loginCss).not.toContain("identityRobot");
    expect(loginCss).not.toContain("policyRobot");
    expect(loginCss).not.toContain("skillRobot");
    expect(loginCss).not.toContain("workerRobot");
  });

  test("does not draw connector lines inside the diagnostic visual", () => {
    const opsVisualBeforeRule =
      loginCss.match(/[.]opsVisual::before\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(opsVisualBeforeRule).toBe("");
    expect(loginCss).not.toContain("188px 258px");
    expect(loginCss).not.toContain("91px 232px");
  });

  test("renders diagonal background bands but masks them behind the login shell", () => {
    const screenRule = loginCss.match(/[.]screen\s*[{][^}]+[}]/u)?.[0] ?? "";
    const loginShellFrameRule =
      loginCss.match(/[.]loginShell::before\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(screenRule).toContain("linear-gradient(116deg");
    expect(screenRule).toContain("linear-gradient(139deg");
    expect(loginShellFrameRule).toContain("rgba(246, 247, 249, 0.96)");
    expect(loginShellFrameRule).not.toContain("background: transparent");
  });

  test("trims the login shell frame height without moving the top anchor", () => {
    const screenRule = loginCss.match(/[.]screen\s*[{][^}]+[}]/u)?.[0] ?? "";
    const loginShellRule = loginCss.match(/[.]loginShell\s*[{][^}]+[}]/u)?.[0] ?? "";
    const loginShellFrameRule =
      loginCss.match(/[.]loginShell::before\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(screenRule).toContain("--login-frame-anchor-height: min(720px, calc(100vh - 132px))");
    expect(screenRule).toContain("--login-frame-height: min(640px, calc(100vh - 132px))");
    expect(screenRule).toContain(
      "--login-frame-y: calc((100vh - var(--login-frame-anchor-height)) / 2 - var(--login-frame-top))",
    );
    expect(loginShellRule).toContain("--frame-height: var(--login-frame-height)");
    expect(loginShellFrameRule).toContain("height: var(--frame-height)");
  });

  test("uses a restrained cool frame instead of the previous red shell border", () => {
    const loginShellFrameRule =
      loginCss.match(/[.]loginShell::before\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(loginShellFrameRule).toContain("border: 1px solid rgba(76, 112, 136, 0.2)");
    expect(loginShellFrameRule).not.toContain("rgba(166, 64, 92");
  });

  test("keeps the agent animation above the login shell mask", () => {
    const loginHeroEffectRule =
      loginCss.match(/[.]loginHeroEffect\s*[{][^}]+[}]/u)?.[0] ?? "";
    const loginShellRule = loginCss.match(/[.]loginShell\s*[{][^}]+[}]/u)?.[0] ?? "";
    const loginShellFrameRule =
      loginCss.match(/[.]loginShell::before\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(loginShellRule).toContain("z-index: 2");
    expect(loginShellFrameRule).toContain("rgba(246, 247, 249, 0.96)");
    expect(loginHeroEffectRule).toContain("z-index: 3");
    expect(loginHeroEffectRule).toContain("transform: translateY(-5px)");
  });

  test("aligns the diagnostic visual and login form bottoms", () => {
    const opsVisualRule = loginCss.match(/[.]opsVisual\s*[{][^}]+[}]/u)?.[0] ?? "";
    const loginCardRule = loginCss.match(/[.]loginCard\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(opsVisualRule).toContain("margin-top: 52px");
    expect(loginCardRule).toContain("height: 344px");
    expect(loginCardRule).toContain("margin: 271px 0 0");
  });

  test("redirects anonymous operators away from menu pages", async () => {
    useAnonymousSession();

    renderAt("/overview");

    expect(
      await screen.findByRole("heading", { name: "用户登录" }),
    ).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "平台总览" })).not.toBeInTheDocument();
  });

  test("does not block the login entry when the session contract is invalid", async () => {
    server.use(
      http.get("/auth/session", () =>
        HttpResponse.json({
          authenticated: "yes",
          subject: null,
          username: null,
          roles: [],
          authenticationType: "anonymous",
        }),
      ),
    );

    renderAt("/login");

    expect(
      await screen.findByRole("heading", { name: "用户登录" }),
    ).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "登录" })).toBeInTheDocument();
    expect(screen.queryByRole("alert", { name: "会话状态暂不可用" })).not.toBeInTheDocument();
  });

  test("shows the session subject and logout button in the workspace status bar", async () => {
    useAuthenticatedSession();

    renderAt("/agent");

    expect(await screen.findByText("alice")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "登出当前账号" })).toBeEnabled();
  });
});

const defaultHandlers = [
  http.get("/internal/skills", () =>
    HttpResponse.json({
      skills: [],
    }),
  ),
  http.post("/internal/routing/skills/search", () =>
    HttpResponse.json({
      total: 0,
      candidates: [],
    }),
  ),
];
