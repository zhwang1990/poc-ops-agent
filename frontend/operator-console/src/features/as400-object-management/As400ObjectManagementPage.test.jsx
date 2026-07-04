import { readFileSync } from "node:fs";

import { http, HttpResponse } from "msw";
import { useState } from "react";
import { MemoryRouter } from "react-router-dom";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, test } from "vitest";

import { AppProviders } from "../../app/providers.jsx";
import { WorkspaceLayoutContext } from "../../components/layout/WorkspaceLayoutContext.jsx";
import { server } from "../../test/server.js";
import { As400ObjectManagementPage } from "./As400ObjectManagementPage.jsx";

const as400Source = readFileSync(
  "src/features/as400-object-management/As400ObjectManagementPage.jsx",
  "utf8",
);
const as400Css = readFileSync(
  "src/features/as400-object-management/As400ObjectManagementPage.module.css",
  "utf8",
);
const pageToolbarCss = readFileSync(
  "src/components/layout/PageToolbar.module.css",
  "utf8",
);

beforeEach(() => {
  server.use(
    http.get("/auth/session", () =>
      HttpResponse.json({
        authenticated: true,
        subject: "demo-admin",
        username: "admin",
        roles: ["ROLE_ADMIN"],
        authenticationType: "password",
      }),
    ),
  );
});

describe("As400ObjectManagementPage toolbar", () => {
  test("uses shared page toolbar styles without adding a local workspace expander", () => {
    const modeToolbarRule =
      as400Css.match(/[.]modeToolbar\s*[{][^}]+[}]/u)?.[0] ?? "";
    const pageToolbarButtonRule =
      pageToolbarCss.match(/[.]button\s*[{][^}]+[}]/u)?.[0] ?? "";
    const pageToolbarSurfaceRule =
      pageToolbarCss.match(/[.]surface\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(as400Source).toContain("PageToolbar.module.css");
    expect(as400Source).toContain("styles.modeToolbar} ${toolbarStyles.surface}");
    expect(as400Source).not.toContain("onToggleWorkspace");
    expect(as400Source).not.toContain("expandAction");
    expect(as400Css).not.toMatch(/(?:^|\n)[.]expandAction\s*[{,]/u);
    expect(as400Css).not.toMatch(/(?:^|\n)[.]modeTab\s*[{,]/u);
    expect(as400Css).not.toMatch(/(?:^|\n)[.]primaryDisabled\s*[{,]/u);
    expect(modeToolbarRule).toContain("--page-toolbar-accent: var(--agent-blue)");
    expect(pageToolbarCss).toContain(".actionGroup");
    expect(pageToolbarSurfaceRule).toContain("border-radius: 14px");
    expect(pageToolbarSurfaceRule).toContain("backdrop-filter: blur(18px)");
    expect(pageToolbarButtonRule).toContain("height: 38px");
    expect(pageToolbarButtonRule).toContain("border-radius: 11px");
  });

  test("keeps AS400 workspace expansion controlled by the top toolbar", async () => {
    const user = userEvent.setup();

    renderAs400Page();

    const objectWorkspace = await screen.findByRole("region", {
      name: "AS400 数据对象管理工作区",
    });
    expect(within(objectWorkspace).queryByRole("button", { name: "展开工作区" }))
      .not.toBeInTheDocument();
    expect(screen.getByRole("tab", { name: /在线设计/u })).toHaveAttribute(
      "aria-selected",
      "true",
    );
    expect(screen.getByRole("complementary", { name: "AS400 对象草稿状态" }))
      .toBeInTheDocument();

    const workspaceToolbar = within(screen.getByRole("toolbar", { name: "工作区工具栏" }));
    await user.click(workspaceToolbar.getByRole("button", { name: "展开工作区" }));

    expect(workspaceToolbar.getByRole("button", { name: "退出展开" })).toHaveAttribute(
      "aria-pressed",
      "true",
    );
    expect(screen.queryByRole("complementary", { name: "AS400 对象草稿状态" }))
      .not.toBeInTheDocument();
  });
});

function renderAs400Page() {
  return render(
    <AppProviders Router={MemoryRouter}>
      <WorkspaceLayoutHarness>
        <As400ObjectManagementPage />
      </WorkspaceLayoutHarness>
    </AppProviders>,
  );
}

/**
 * @param {{children: import("react").ReactNode}} props
 */
function WorkspaceLayoutHarness({ children }) {
  const [showMenuLabels, setShowMenuLabels] = useState(true);
  const [isWorkspaceExpanded, setWorkspaceExpanded] = useState(false);

  return (
    <WorkspaceLayoutContext.Provider
      value={{
        showMenuLabels,
        setShowMenuLabels,
        isWorkspaceExpanded,
        setWorkspaceExpanded,
        toggleMenuLabels: () => setShowMenuLabels((current) => !current),
        toggleWorkspaceExpanded: () => setWorkspaceExpanded((current) => !current),
      }}
    >
      {children}
    </WorkspaceLayoutContext.Provider>
  );
}
