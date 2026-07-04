import { readFileSync } from "node:fs";

import { http, HttpResponse } from "msw";
import { MemoryRouter } from "react-router-dom";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, test } from "vitest";

import { AppProviders } from "../../app/providers.jsx";
import { server } from "../../test/server.js";
import { ThirdPartyLicensesPage } from "./ThirdPartyLicensesPage.jsx";

const thirdPartyLicensesCss = readFileSync(
  "src/features/third-party-licenses/ThirdPartyLicensesPage.module.css",
  "utf8",
);

function renderThirdPartyLicensesPage() {
  return render(
    <AppProviders Router={MemoryRouter} routerProps={{ initialEntries: ["/third-party-licenses"] }}>
      <ThirdPartyLicensesPage />
    </AppProviders>,
  );
}

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

describe("ThirdPartyLicensesPage", () => {
  test("renders the third party license declaration without backend license APIs", async () => {
    const user = userEvent.setup();
    let licenseApiRequestCount = 0;
    server.use(
      http.get("/internal/third-party-licenses", () => {
        licenseApiRequestCount += 1;
        return HttpResponse.json({ licenses: [] });
      }),
    );

    renderThirdPartyLicensesPage();

    expect(await screen.findByRole("heading", { name: "第三方组件声明" })).toBeInTheDocument();
    expect(screen.getByRole("main", { name: "第三方组件声明工作区" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "开源组件合规声明" })).not.toBeInTheDocument();
    expect(screen.queryByText("声明随平台分发的前端与后端直接运行依赖，保留版权与许可证信息。")).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "声明范围" })).not.toBeInTheDocument();
    expect(screen.queryByRole("region", { name: "声明摘要" })).not.toBeInTheDocument();
    expect(screen.getByText("前端操作台 14 项")).toBeInTheDocument();
    expect(screen.getByText("后端服务 16 项")).toBeInTheDocument();
    expect(screen.getByText("5 种许可证")).toBeInTheDocument();
    expect(screen.getByText("第 1 / 3 页")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "上一页" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "下一页" })).toBeEnabled();

    const componentList = screen.getByRole("region", { name: "第三方组件清单" });
    expect(within(componentList).queryByRole("heading", { level: 3 })).not.toBeInTheDocument();
    const firstReactRow = within(componentList).getByRole("row", { name: /React\s+19[.]2[.]7/u });
    expect(within(componentList).getAllByText("前端操作台")).toHaveLength(10);
    const firstReactUnitCell = within(firstReactRow).getAllByRole("cell")[0];
    expect(within(firstReactUnitCell).getAllByText("前端操作台")).toHaveLength(1);
    expect(within(componentList).queryByRole("row", { name: /JT400\s+21[.]0[.]6/u })).not.toBeInTheDocument();
    expect(componentList).not.toHaveTextContent(/queFork/u);

    const reactRow = firstReactRow;
    const reactLicenseLink = within(reactRow).getByRole("link", { name: "React 许可证" });
    expect(reactLicenseLink).toHaveAttribute("href", "/third-party-licenses/react");
    expect(within(reactLicenseLink).queryByText("许可证")).not.toBeInTheDocument();
    expect(within(reactRow).getByRole("link", { name: "React 项目主页" })).toHaveAttribute("href", "https://react.dev/");
    await user.click(screen.getByRole("button", { name: "下一页" }));
    expect(screen.getByText("第 2 / 3 页")).toBeInTheDocument();
    expect(within(componentList).getAllByText("前端操作台")).toHaveLength(4);
    expect(within(componentList).getAllByText("后端服务")).toHaveLength(6);
    expect(within(componentList).getByRole("row", { name: /Zod\s+4[.]4[.]3/u })).toHaveTextContent("Zod");
    expect(within(within(componentList).getByRole("row", { name: /Zod\s+4[.]4[.]3/u })).getByRole("link", { name: "Zod 许可证" })).toHaveAttribute(
      "href",
      "/third-party-licenses/zod",
    );

    await user.click(screen.getByRole("button", { name: "下一页" }));
    expect(screen.getByText("第 3 / 3 页")).toBeInTheDocument();
    expect(within(componentList).getAllByText("后端服务")).toHaveLength(10);
    expect(screen.getByRole("button", { name: "下一页" })).toBeDisabled();
    expect(within(componentList).getByRole("row", { name: /AgentScope Java\s+2[.]0[.]0-RC4/u })).toHaveTextContent(
      "Apache License 2.0",
    );
    expect(within(componentList).getByRole("row", { name: /JT400\s+21[.]0[.]6/u })).toHaveTextContent(
      "IBM Public License 1.0",
    );
    expect(screen.queryByRole("region", { name: "许可证全文" })).not.toBeInTheDocument();

    expect(screen.queryByRole("button", { name: /执行|提交|授权/u })).not.toBeInTheDocument();
    expect(licenseApiRequestCount).toBe(0);
  });

  test("keeps scrolling inside the declaration workspace and gives the list most height", () => {
    const canvasRule = thirdPartyLicensesCss.match(/[.]licenseCanvas\s*[{][^}]+[}]/u)?.[0] ?? "";
    const bodyRule = thirdPartyLicensesCss.match(/[.]licenseBody\s*[{][^}]+[}]/u)?.[0] ?? "";
    const componentPanelRule =
      thirdPartyLicensesCss.match(/[.]componentPanel\s*[{][^}]+[}]/u)?.[0] ?? "";
    const componentPanelRules = Array.from(
      thirdPartyLicensesCss.matchAll(/[^{}]*[.]componentPanel[^{}]*[{][^}]+[}]/gu),
      ([match]) => match,
    ).join("\n");
    const tableWrapRule =
      thirdPartyLicensesCss.match(/[.]componentTableWrap\s*[{][^}]+[}]/u)?.[0] ?? "";
    const paginationRule =
      thirdPartyLicensesCss.match(/[.]paginationBar\s*[{][^}]+[}]/u)?.[0] ?? "";
    const mediumViewportRule =
      thirdPartyLicensesCss.match(/@media \(max-width: 1180px\)\s*\{[\s\S]*?\}/u)?.[0] ?? "";

    expect(canvasRule).toContain("grid-template-rows: auto minmax(0, 1fr)");
    expect(canvasRule).toContain("overflow: hidden");
    expect(bodyRule).toContain("min-height: 0");
    expect(bodyRule).toContain("grid-template-rows: auto minmax(0, 1fr)");
    expect(bodyRule).toContain("overflow: hidden");
    expect(bodyRule).toContain("border: 1px solid var(--license-border)");
    expect(bodyRule).toContain("border-radius: 14px");
    expect(componentPanelRule).toContain("min-height: 0");
    expect(componentPanelRule).toContain("grid-template-rows: minmax(0, 1fr) auto");
    expect(componentPanelRules).not.toContain("border:");
    expect(componentPanelRules).not.toContain("border-radius:");
    expect(componentPanelRules).not.toContain("background:");
    expect(componentPanelRules).not.toContain("box-shadow:");
    expect(componentPanelRules).not.toContain("padding:");
    expect(tableWrapRule).toContain("overflow: auto");
    expect(paginationRule).toContain("justify-content: space-between");
    expect(thirdPartyLicensesCss).not.toContain(".sectionHeading");
    expect(thirdPartyLicensesCss).not.toContain(".scopePanel");
    expect(thirdPartyLicensesCss).not.toContain(".summaryGrid");
    expect(thirdPartyLicensesCss).not.toContain(".componentGrid");
    expect(mediumViewportRule).not.toContain("height: auto");
  });

  test("uses the detail workspace width for metadata and license text", () => {
    const detailGridRule =
      thirdPartyLicensesCss.match(/[.]detailContentGrid\s*[{][^}]+[}]/u)?.[0] ?? "";
    const noticeRule = thirdPartyLicensesCss.match(/[.]licenseNotice\s*[{][^}]+[}]/u)?.[0] ?? "";
    const mediumViewportRule =
      thirdPartyLicensesCss.match(/@media \(max-width: 1180px\)\s*\{[\s\S]*?\}/u)?.[0] ?? "";

    expect(detailGridRule).toContain("grid-template-columns: minmax(280px, 0.42fr) minmax(0, 1fr)");
    expect(detailGridRule).toContain("align-items: start");
    expect(noticeRule).toContain("min-height: 420px");
    expect(mediumViewportRule).toContain(".detailContentGrid");
    expect(mediumViewportRule).toContain("grid-template-columns: minmax(0, 1fr)");
  });

  test("does not reserve vertical space for redundant license detail intro copy", () => {
    const panelRules = Array.from(
      thirdPartyLicensesCss.matchAll(/[.]detailPanel\s*[{][^}]+[}]/gu),
      ([match]) => match,
    );

    expect(panelRules.some((rule) => rule.includes("gap: 12px"))).toBe(true);
    expect(thirdPartyLicensesCss).not.toContain(".detailHeader");
    expect(thirdPartyLicensesCss).not.toContain(".detailHero");
  });
});
