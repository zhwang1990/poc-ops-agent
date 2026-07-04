import { readFileSync } from "node:fs";
import { http, HttpResponse } from "msw";
import { EditorView } from "@codemirror/view";
import { MemoryRouter } from "react-router-dom";
import { beforeEach, describe, expect, test, vi } from "vitest";
import { fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";

import App from "../../app/App.jsx";
import { AppProviders } from "../../app/providers.jsx";
import { server } from "../../test/server.js";

const sqlWorkbenchCss = readFileSync("src/features/sql-workbench/SqlWorkbenchPage.module.css", "utf8");

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

/**
 * @param {ReturnType<typeof userEvent.setup>} user
 * @param {string} sql
 */
async function replaceSqlText(user, sql) {
  const editor = await screen.findByLabelText("SQL 文本");
  if (editor instanceof HTMLTextAreaElement) {
    await user.clear(editor);
    await user.type(editor, sql);
    return;
  }

  const view = EditorView.findFromDOM(editor);
  if (!view) {
    throw new Error("Expected a CodeMirror SQL editor");
  }
  view.dispatch({
    changes: {
      from: 0,
      insert: sql,
      to: view.state.doc.length,
    },
  });
  await waitFor(() => expect(view.state.doc.toString()).toBe(sql));
}

function readSqlText() {
  const editor = screen.getByLabelText("SQL 文本");
  if (editor instanceof HTMLTextAreaElement) {
    return editor.value;
  }

  const view = EditorView.findFromDOM(editor);
  if (view) {
    return view.state.doc.toString();
  }
  return editor.textContent ?? "";
}

/**
 * @param {ReturnType<typeof userEvent.setup>} user
 * @param {number} index
 */
async function clickRunSqlButton(user, index = 0) {
  const runButtons = await screen.findAllByRole("button", { name: "执行此 SQL" });
  await user.click(runButtons[index]);
}

function expectDirectSqlToolbarActionsHidden() {
  expect(screen.queryByRole("button", { name: "校验" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "执行 SELECT" })).not.toBeInTheDocument();
  expect(screen.queryByRole("button", { name: "DML 预检" })).not.toBeInTheDocument();
}

function expectExecutionFactsErrorFieldsHidden() {
  expect(screen.getByText("执行事实")).toBeInTheDocument();
  expect(screen.queryByText("错误码")).not.toBeInTheDocument();
  expect(screen.queryByText("错误信息")).not.toBeInTheDocument();
}

/**
 * @param {string} className
 */
function cssRule(className) {
  return Array.from(
    sqlWorkbenchCss.matchAll(new RegExp(`\\.${className}\\s*\\{([^}]*)\\}`, "gu")),
    (match) => match[1],
  ).join("\n");
}

beforeEach(() => {
  server.use(
    http.get("/auth/session", () =>
      HttpResponse.json({
        authenticated: true,
        subject: "operator-1",
        username: "ops.reader",
        roles: ["ROLE_agent-reader"],
        authenticationType: "built-in",
      }),
    ),
  );
});

describe("SqlWorkbenchPage", () => {
  test("keeps AI SQL assistant overflow scrollable inside the panel", () => {
    const infoPanelRule = cssRule("infoPanel");
    const panelRule = cssRule("aiPanel");
    const actionsRule = cssRule("aiActions");

    expect(infoPanelRule).toContain("grid-template-rows: auto minmax(0, 1fr)");
    expect(infoPanelRule).toContain("min-height: 0");
    expect(infoPanelRule).toContain("padding: 10px 10px 9px");
    expect(panelRule).toContain("height: 100%");
    expect(panelRule).toContain("max-width: 100%");
    expect(panelRule).toContain("min-height: 0");
    expect(panelRule).toContain("overflow-x: auto");
    expect(panelRule).toContain("overflow-y: auto");
    expect(actionsRule).toContain("overflow-x: auto");
    expect(actionsRule).toContain("flex-wrap: nowrap");
    expect(actionsRule).toContain("min-width: 0");
  });

  test("omits duplicated validation panel from the SQL side panel", async () => {
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
    );

    renderAt("/sql");

    expect(await screen.findByText("AI SQL 助手")).toBeInTheDocument();
    expect(screen.queryByText("服务端校验")).not.toBeInTheDocument();
    expect(
      screen.queryByText("助手只生成解释、优化和错误分析建议；建议应用后必须重新校验。"),
    ).not.toBeInTheDocument();
  });

  test("keeps SQL file actions aligned to the top of the editor toolbar", () => {
    const headerRule = cssRule("editorHeader");
    const tabsRule = cssRule("sessionModeTabs");
    const toolbarRule = cssRule("editorToolbar");

    expect(headerRule).toContain("grid-template-columns: minmax(0, 1fr) max-content");
    expect(headerRule).toContain("align-items: flex-start");
    expect(headerRule).toContain("border-bottom:");
    expect(tabsRule).not.toContain("padding: 7px 7px 0");
    expect(toolbarRule).toContain("align-items: flex-start");
    expect(toolbarRule).toContain("justify-content: flex-end");
    expect(toolbarRule).not.toContain("min-height: 46px");
  });

  test("stacks connection capability labels above capability chips", () => {
    const capabilitiesRule = cssRule("formCapabilities");
    const capabilityLabelRule = Array.from(
      sqlWorkbenchCss.matchAll(/\.formCapabilities\s*>\s*span\s*\{([^}]*)\}/gu),
      (match) => match[1],
    ).join("\n");

    expect(capabilitiesRule).toContain("grid-template-columns: repeat(4, max-content)");
    expect(capabilitiesRule).toContain("align-items: start");
    expect(capabilitiesRule).toContain("align-content: start");
    expect(capabilitiesRule).not.toContain("align-items: end");
    expect(capabilityLabelRule).toContain("grid-column: 1 / -1");
  });

  test("keeps the object browser schema area compact and object types icon-only", () => {
    const drawerRule = cssRule("objectDrawer");
    const schemaRule = cssRule("schemaTree");
    const schemaSectionRule = cssRule("schemaSection");
    const metadataListRule = cssRule("metadataList");
    const metadataObjectRule = cssRule("metadataObject");
    const metadataObjectHeaderRule = cssRule("metadataObjectHeader");
    const metadataObjectHeaderStrongRule = Array.from(
      sqlWorkbenchCss.matchAll(/\.metadataObjectHeader\s+strong\s*\{([^}]*)\}/gu),
      (match) => match[1],
    ).join("\n");
    const objectTypeIconRule = cssRule("metadataObjectTypeIcon");

    expect(drawerRule).toContain("grid-template-rows: auto auto minmax(0, 1fr)");
    expect(schemaRule).toContain("align-content: stretch");
    expect(schemaRule).toContain("grid-template-rows: auto max-content minmax(0, 1fr)");
    expect(schemaSectionRule).toContain("grid-auto-rows: max-content");
    expect(metadataListRule).toContain("height: 100%");
    expect(metadataListRule).toContain("max-height: none");
    expect(metadataListRule).toContain("min-height: 0");
    expect(metadataListRule).toContain("gap: 2px");
    expect(metadataObjectRule).toContain("gap: 4px");
    expect(metadataObjectRule).toContain("padding: 5px 0");
    expect(metadataObjectHeaderRule).toContain("padding: 3px 5px");
    expect(metadataObjectHeaderStrongRule).toContain("padding: 3px 8px");
    expect(objectTypeIconRule).toContain("width: 26px");
    expect(objectTypeIconRule).toContain("place-items: center");
  });

  test("shows an empty connection state without runtime mock data", async () => {
    server.use(
      http.get("/internal/sql-workbench/connections", () => HttpResponse.json([])),
    );

    renderAt("/sql");

    expect(await screen.findByText("尚未配置 SQL 连接")).toBeInTheDocument();
    expect(screen.getByText("无可用连接")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "管理连接" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "对象浏览器" })).toBeDisabled();
    expectDirectSqlToolbarActionsHidden();
    expect(screen.queryByText("as400-development")).not.toBeInTheDocument();
    expect(screen.queryByText("ORDERS.ORDERS")).not.toBeInTheDocument();
    expect(
      screen.queryByText("校验通过的单条 SELECT 执行后，分页结果会显示在这里。"),
    ).not.toBeInTheDocument();
  });

  test("renders the P1 workbench with top connection context and collapsible objects", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/queries/validate", () =>
        HttpResponse.json(validatedSelectReport),
      ),
      http.get("/internal/sql-workbench/connections/:connectionId/metadata", ({ params, request }) => {
        expect(params.connectionId).toBe("as400-development");
        expect(new URL(request.url).searchParams.get("schema")).toBe("ORDERS");
        return HttpResponse.json(sqlMetadataResponse);
      }),
    );

    renderAt("/sql");

    expect(await screen.findByRole("heading", { name: "SQL 工作台" })).toBeInTheDocument();
    expect(screen.getByRole("navigation", { name: "主导航" })).toBeInTheDocument();
    expect(screen.getByLabelText("当前工作台")).toBeInTheDocument();
    expect(screen.queryByRole("navigation", { name: "SQL 工作台导航" })).not.toBeInTheDocument();

    const contextBar = await screen.findByLabelText("SQL 工作区连接上下文");
    expect(within(contextBar).getByText("已连接 · development")).toBeInTheDocument();
    expect(within(contextBar).getByText("as400-development")).toBeInTheDocument();
    expect(within(contextBar).getByText("ORDERS")).toBeInTheDocument();
    expect(within(contextBar).getByText("maxRows 500")).toBeInTheDocument();
    expect(within(contextBar).getByRole("button", { name: "管理连接" })).toBeEnabled();
    expect(within(contextBar).getByRole("button", { name: "展开 SQL 工作区" })).toBeEnabled();

    expect(screen.queryByRole("complementary", { name: "SQL 连接目录" })).not.toBeInTheDocument();
    expect(screen.queryByLabelText("数据库对象浏览器")).not.toBeInTheDocument();
    expect(screen.queryByText("执行边界")).not.toBeInTheDocument();
    expect(screen.getByLabelText("SQL 信息面板")).toBeInTheDocument();
    const fileActions = screen.getByRole("group", { name: "SQL 文件操作" });
    expect(within(fileActions).getByText("导入 .sql")).toBeInTheDocument();
    expect(within(fileActions).getByRole("button", { name: "导出 .sql" })).toBeInTheDocument();
    expect(within(fileActions).getByRole("button", { name: "停止" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "对象浏览器" }));
    expect(screen.getByLabelText("数据库对象浏览器")).toBeInTheDocument();
    const ordersObjectButton = await screen.findByRole("button", { name: "ORDERS TABLE" });
    expect(ordersObjectButton).toHaveAttribute("aria-expanded", "false");
    expect(ordersObjectButton).not.toHaveTextContent("TABLE");
    expect(ordersObjectButton.querySelector("svg")).toBeInTheDocument();
    expect(screen.queryByText("ORDER_ID")).not.toBeInTheDocument();
    expect(screen.queryByText("STATUS")).not.toBeInTheDocument();
    await user.click(ordersObjectButton);
    expect(ordersObjectButton).toHaveAttribute("aria-expanded", "true");
    expect(screen.getByText("ORDER_ID")).toBeInTheDocument();
    expect(screen.getByText("STATUS")).toBeInTheDocument();
    expect(screen.getByText(/PRIMARY_KEY_ORDERS/u)).toBeInTheDocument();
    expect(screen.queryByText("对象目录尚未接入真实元数据")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "展开 SQL 工作区" }));
    expect(screen.queryByLabelText("数据库对象浏览器")).not.toBeInTheDocument();
    expect(screen.queryByLabelText("SQL 信息面板")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "退出 SQL 展开" })).toBeInTheDocument();
  });

  test("renders metadata objects in scroll pages with collapsed object details", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.get("/internal/sql-workbench/connections/:connectionId/metadata", () =>
        HttpResponse.json(buildSqlMetadataResponse(45)),
      ),
    );

    const { container } = renderAt("/sql");

    await screen.findByText("as400-development");
    const objectBrowserButton = container.querySelector('[class*="connectionActions"] button');
    if (!objectBrowserButton) {
      throw new Error("Expected object browser button");
    }
    await user.click(objectBrowserButton);

    const metadataList = await screen.findByLabelText("Database metadata objects");
    expect(screen.getByRole("button", { name: "TABLE_001 TABLE" })).toHaveAttribute("aria-expanded", "false");
    expect(screen.getByRole("button", { name: "TABLE_030 TABLE" })).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "TABLE_031 TABLE" })).not.toBeInTheDocument();
    expect(screen.queryByText("COL_001")).not.toBeInTheDocument();

    fireEvent.scroll(metadataList, { target: { scrollTop: 10000 } });

    expect(await screen.findByRole("button", { name: "TABLE_045 TABLE" })).toBeInTheDocument();
    expect(screen.queryByText("COL_045")).not.toBeInTheDocument();
  });

  test("requires second confirmation before committing UPDATE without WHERE", async () => {
    const user = userEvent.setup();
    const confirmSpy = vi.spyOn(window, "confirm").mockImplementation(() => {
      throw new Error("Browser confirm should not be used for DML risk confirmation");
    });
    /** @type {unknown[]} */
    const commitRequests = [];
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/queries/validate", () =>
        HttpResponse.json({
          contractVersion: "1.0",
          statementType: "UPDATE",
          validationLevel: "PARTIAL",
          sqlHash: "sha256:update-without-where",
          referencedObjects: ["ORDERS.ORDERS"],
          risks: ["UPDATE_WITHOUT_WHERE"],
          rejectionReasons: [],
          unverifiedItems: ["impact count and masked sample require live read-only preflight"],
        }),
      ),
      http.post("/internal/sql-workbench/queries/commit", async ({ request }) => {
        commitRequests.push(await request.json());
        return HttpResponse.json({
          contractVersion: "1.0",
          executionRequestId: "execution-dml-1",
          workflowId: "workflow-dml-1",
          status: "SUCCEEDED",
          resultId: null,
          errorCode: null,
          errorMessage: null,
          affectedRows: 4,
        });
      }),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");
    await replaceSqlText(user, "update ORDERS.ORDERS set status = 'READY'");
    const transactionControls = screen.getByRole("group", { name: "SQL 事务控制" });
    const transactionModeButton = within(transactionControls).getByRole("button", {
      name: "事务模式",
    });
    const manualCommitButton = within(transactionControls).getByRole("button", {
      name: "手动提交",
    });

    expect(transactionModeButton).toHaveAttribute("aria-pressed", "false");
    expect(manualCommitButton).toBeDisabled();

    await user.click(transactionModeButton);
    expect(transactionModeButton).toHaveAttribute("aria-pressed", "true");
    expect(manualCommitButton).toBeEnabled();
    await user.click(manualCommitButton);

    const riskDialog = await screen.findByRole("dialog", { name: "确认 DML 风险" });
    expect(confirmSpy).not.toHaveBeenCalled();
    expect(within(riskDialog).getByText("UPDATE_WITHOUT_WHERE")).toBeInTheDocument();
    expect(within(riskDialog).getByText("sha256:update-without-where")).toBeInTheDocument();
    expect(commitRequests).toHaveLength(0);

    await user.click(within(riskDialog).getByRole("button", { name: "确认提交" }));

    await waitFor(() => expect(commitRequests).toHaveLength(1));
    expect(commitRequests[0]).toMatchObject({
      contractVersion: "1.0",
      query: {
        action: "COMMIT_DML",
        sql: "update ORDERS.ORDERS set status = 'READY'",
      },
      confirmation: {
        sqlHash: "sha256:update-without-where",
        confirmedRisks: ["UPDATE_WITHOUT_WHERE"],
        confirmationCode: "CONFIRM_SQL_DML_RISK",
      },
    });
    expect(await screen.findByText("DML 提交完成，影响 4 行。")).toBeInTheDocument();
  });

  test("shows SQL session modes as functional tabs", async () => {
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");

    expect(screen.getByRole("tab", { name: "SQL" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "自然语言" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "Compare" })).toBeInTheDocument();
  });

  test("imports a .sql file into the current session only after overwrite confirmation", async () => {
    const user = userEvent.setup();
    const confirmSpy = vi.spyOn(window, "confirm");
    confirmSpy.mockReturnValueOnce(false).mockReturnValueOnce(true);
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");
    await replaceSqlText(user, "select * from ORDERS.ORDERS");

    const importedFile = new File(
      ["select ORDER_ID, STATUS from ORDERS.ORDERS"],
      "orders.sql",
      { type: "application/sql" },
    );
    const importInput = screen.getByLabelText("导入 .sql");

    await user.upload(importInput, importedFile);
    expect(readSqlText()).toBe("select * from ORDERS.ORDERS");

    await user.upload(importInput, importedFile);
    await waitFor(() =>
      expect(readSqlText()).toBe("select ORDER_ID, STATUS from ORDERS.ORDERS"),
    );
    expect(confirmSpy).toHaveBeenCalledWith("导入会覆盖当前 SQL，是否继续？");
  });

  test("generates SELECT from natural language as a draft before applying it", async () => {
    const user = userEvent.setup();
    /** @type {unknown[]} */
    const assistantRequests = [];
    /** @type {unknown[]} */
    const validationRequests = [];
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/assistant", async ({ request }) => {
        assistantRequests.push(await request.json());
        return HttpResponse.json({
          ...sqlAssistantResponse,
          assistantAction: "GENERATE_SELECT",
          summary: "已生成只读 SELECT 草稿。",
          suggestions: [
            {
              title: "未完成订单查询",
              rationale: "用户要求查询未完成订单，限定为 SELECT。",
              suggestedSql:
                "select ORDER_ID, STATUS from ORDERS.ORDERS where STATUS <> 'DONE'",
            },
          ],
          safetyNotes: ["应用后必须重新执行服务端校验。"],
        });
      }),
      http.post("/internal/sql-workbench/queries/validate", async ({ request }) => {
        validationRequests.push(await request.json());
        return HttpResponse.json(validatedSelectReport);
      }),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");
    await user.click(screen.getByRole("tab", { name: "自然语言" }));
    expect(screen.getByRole("heading", { name: "生成结果" })).toBeInTheDocument();
    expect(screen.getByLabelText("自然语言快捷录入")).toBeInTheDocument();
    expect(screen.queryByLabelText("目标库")).not.toBeInTheDocument();
    await user.type(
      screen.getByLabelText("自然语言需求"),
      "查询未完成订单 @ORDERS #ORDERS $ORDER_ID,STATUS",
    );
    await user.click(screen.getByRole("button", { name: "生成 SELECT" }));

    expect(
      await screen.findByText(
        "select ORDER_ID, STATUS from ORDERS.ORDERS where STATUS <> 'DONE'",
      ),
    ).toBeInTheDocument();
    await waitFor(() => expect(assistantRequests).toHaveLength(1));
    expect(assistantRequests[0]).toMatchObject({
      assistantAction: "GENERATE_SELECT",
      sql: "SELECT 1",
    });
    const diagnosticContext = String(
      /** @type {Record<string, unknown>} */ (assistantRequests[0]).diagnosticContext,
    );
    expect(diagnosticContext).toContain("naturalLanguage=查询未完成订单");
    expect(diagnosticContext).toContain("targetLibrary=ORDERS");
    expect(diagnosticContext).toContain("targetTable=ORDERS");
    expect(diagnosticContext).toContain("requestedFields=ORDER_ID,STATUS");

    await user.click(screen.getByRole("button", { name: "应用到编辑器并校验" }));

    await waitFor(() =>
      expect(readSqlText()).toBe(
        "select ORDER_ID, STATUS from ORDERS.ORDERS where STATUS <> 'DONE'",
      ),
    );
    await waitFor(() => expect(validationRequests).toHaveLength(1));
    expect(validationRequests[0]).toMatchObject({
      action: "VALIDATE",
      sql: "select ORDER_ID, STATUS from ORDERS.ORDERS where STATUS <> 'DONE'",
    });
  });

  test("compares the same table across two libraries and asks AI for a summary", async () => {
    const user = userEvent.setup();
    /** @type {unknown[]} */
    const runRequests = [];
    /** @type {unknown[]} */
    const assistantRequests = [];
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json([
          {
            ...sqlConnections[0],
            allowedSchemas: ["ORDERS", "ST1", "ST2"],
          },
        ]),
      ),
      http.post("/internal/sql-workbench/queries/run", async ({ request }) => {
        const body = await request.json();
        runRequests.push(body);
        const sqlText = String(/** @type {{sql?: unknown}} */ (body).sql ?? "");
        return HttpResponse.json({
          ...queryRunResult,
          resultId: sqlText.includes("ST1.") ? "compare-base" : "compare-target",
        });
      }),
      http.get("/internal/sql-workbench/results/:resultId", ({ params }) => {
        return HttpResponse.json({
          ...resultPage,
          resultId: String(params.resultId),
          columns: [
            { name: "REFKEY", type: "VARCHAR", masked: false },
            { name: "REFVAL", type: "VARCHAR", masked: false },
          ],
          rows:
            params.resultId === "compare-base"
              ? [
                  ["A", "1"],
                  ["B", "2"],
                ]
              : [
                  ["A", "1"],
                  ["B", "3"],
                  ["C", "4"],
                ],
        });
      }),
      http.post("/internal/sql-workbench/assistant", async ({ request }) => {
        assistantRequests.push(await request.json());
        return HttpResponse.json({
          ...sqlAssistantResponse,
          assistantAction: "COMPARE_SUMMARY",
          summary: "ST1 与 ST2 存在 1 行字段差异，且 ST2 多 1 行。",
          suggestions: [],
          safetyNotes: ["摘要仅基于确定性 diff，不执行 SQL。"],
        });
      }),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");
    await user.click(screen.getByRole("tab", { name: "Compare" }));
    await user.clear(screen.getByLabelText("基准库"));
    await user.type(screen.getByLabelText("基准库"), "ST1");
    await user.clear(screen.getByLabelText("对比库"));
    await user.type(screen.getByLabelText("对比库"), "ST2");
    await user.type(screen.getByLabelText("目标表"), "ELADREF");
    await user.type(screen.getByLabelText("主键字段"), "REFKEY");
    await user.type(screen.getByLabelText("比较字段"), "REFVAL");
    await user.click(screen.getByRole("button", { name: "执行对比" }));

    expect(await screen.findByText("字段差异 1")).toBeInTheDocument();
    expect(screen.getByText("仅在对比存在 1")).toBeInTheDocument();
    expect(
      await screen.findByText("ST1 与 ST2 存在 1 行字段差异，且 ST2 多 1 行。"),
    ).toBeInTheDocument();

    await waitFor(() => expect(runRequests).toHaveLength(2));
    expect(runRequests[0]).toMatchObject({
      action: "RUN_READ_ONLY",
      schema: "ST1",
      sql: "select REFKEY, REFVAL from ST1.ELADREF",
    });
    expect(runRequests[1]).toMatchObject({
      action: "RUN_READ_ONLY",
      schema: "ST2",
      sql: "select REFKEY, REFVAL from ST2.ELADREF",
    });
    await waitFor(() => expect(assistantRequests).toHaveLength(1));
    expect(assistantRequests[0]).toMatchObject({
      assistantAction: "COMPARE_SUMMARY",
    });
    expect(String(/** @type {Record<string, unknown>} */ (assistantRequests[0]).diagnosticContext))
      .toContain("mismatchedRows=1");
  });

  test("switches the active connection from the top connection selector", async () => {
    const user = userEvent.setup();
    const connections = [
      sqlConnections[0],
      {
        ...sqlConnections[1],
        maxRowsDefault: 250,
      },
    ];
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(connections),
      ),
    );

    renderAt("/sql");

    const contextBar = await screen.findByLabelText("SQL 工作区连接上下文");
    const connectionSelect = within(contextBar).getByRole("combobox", {
      name: "选择 SQL 连接",
    });
    await waitFor(() => expect(connectionSelect).toHaveValue("as400-development"));
    expect(within(contextBar).getByText("ORDERS")).toBeInTheDocument();
    expect(within(contextBar).getByText("maxRows 500")).toBeInTheDocument();

    await user.selectOptions(connectionSelect, "as400-test");

    expect(connectionSelect).toHaveValue("as400-test");
    expect(within(contextBar).getByText("ORDERS_QA")).toBeInTheDocument();
    expect(within(contextBar).getByText("maxRows 250")).toBeInTheDocument();
  });

  test("creates a connection from the connection manager with credentialAlias metadata and no password field", async () => {
    const user = userEvent.setup();
    /** @type {unknown[]} */
    const requests = [];
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/connections", async ({ request }) => {
        requests.push(await request.json());
        return HttpResponse.json(createdConnection);
      }),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");
    await user.click(screen.getByRole("button", { name: "管理连接" }));

    const dialog = screen.getByRole("dialog", { name: "管理连接" });
    await user.click(within(dialog).getByRole("button", { name: "新建连接" }));
    expect(within(dialog).getByText("连接身份")).toBeInTheDocument();
    expect(within(dialog).getByText("目标端点")).toBeInTheDocument();
    expect(within(dialog).getByText("Schema 与限制")).toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/密码/u)).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/password/i)).not.toBeInTheDocument();
    expect(within(dialog).queryByLabelText(/JDBC URL/u)).not.toBeInTheDocument();

    await user.clear(within(dialog).getByLabelText("连接名称"));
    await user.type(within(dialog).getByLabelText("连接名称"), "H2 Lab");
    await user.selectOptions(within(dialog).getByLabelText("目标环境"), "sit");
    await user.selectOptions(within(dialog).getByLabelText("平台类型"), "H2");
    expect(within(dialog).getByLabelText("端口")).toHaveValue("9092");
    expect(within(dialog).getByLabelText("默认 Schema")).toHaveValue("");
    expect(within(dialog).getByLabelText("允许 Schema")).toHaveValue("");
    await user.clear(within(dialog).getByLabelText("主机"));
    await user.type(within(dialog).getByLabelText("主机"), "localhost");
    await user.clear(within(dialog).getByLabelText("端口"));
    await user.type(within(dialog).getByLabelText("端口"), "9092");
    await user.clear(within(dialog).getByLabelText("默认 Schema"));
    await user.type(within(dialog).getByLabelText("默认 Schema"), "PUBLIC");
    await user.clear(within(dialog).getByLabelText("允许 Schema"));
    await user.type(within(dialog).getByLabelText("允许 Schema"), "PUBLIC");
    await user.clear(within(dialog).getByLabelText("凭据别名 credentialAlias"));
    await user.type(
      within(dialog).getByLabelText("凭据别名 credentialAlias"),
      "h2-lab-readonly",
    );
    await user.click(within(dialog).getByRole("button", { name: "创建连接" }));

    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0]).toMatchObject({
      contractVersion: "1.0",
      displayName: "H2 Lab",
      targetEnvironment: "sit",
      platformType: "H2",
      host: "localhost",
      port: 9092,
      defaultSchema: "PUBLIC",
      allowedSchemas: ["PUBLIC"],
      capabilities: ["VALIDATE", "RUN_READ_ONLY", "PREFLIGHT_DML", "COMMIT_DML"],
      credentialAlias: "h2-lab-readonly",
      maxRowsDefault: 500,
      timeoutSecondsDefault: 30,
    });
    expect(JSON.stringify(requests[0]).toLowerCase()).not.toContain("password");
    expect(JSON.stringify(requests[0]).toLowerCase()).not.toContain("jdbc");
    await waitFor(() => expect(screen.getByLabelText("选择 SQL 连接")).toHaveValue("as400-lab"));
    expect(screen.getByText("LABORDERS")).toBeInTheDocument();
  });

  test("updates and deletes connections from the connection manager", async () => {
    const user = userEvent.setup();
    /** @type {{connectionId: string, body: unknown}[]} */
    const updateRequests = [];
    /** @type {string[]} */
    const deleteRequests = [];
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.put("/internal/sql-workbench/connections/:connectionId", async ({ params, request }) => {
        updateRequests.push({
          connectionId: String(params.connectionId),
          body: await request.json(),
        });
        return HttpResponse.json({
          ...sqlConnections[0],
          displayName: "AS/400 Reporting",
          targetEnvironment: "sit",
          defaultSchema: "REPORTING",
          allowedSchemas: ["REPORTING"],
          credentialAlias: "as400-reporting-readonly",
          status: "PENDING_WORKER_BINDING",
          maxRowsDefault: 250,
        });
      }),
      http.delete("/internal/sql-workbench/connections/:connectionId", ({ params }) => {
        deleteRequests.push(String(params.connectionId));
        return new HttpResponse(null, { status: 204 });
      }),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");
    await user.click(screen.getByRole("button", { name: "管理连接" }));

    const dialog = screen.getByRole("dialog", { name: "管理连接" });
    expect(within(dialog).getByRole("button", { name: "as400-development" }))
      .toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "as400-test" }))
      .toBeInTheDocument();

    await user.clear(within(dialog).getByLabelText("连接名称"));
    await user.type(within(dialog).getByLabelText("连接名称"), "AS/400 Reporting");
    await user.selectOptions(within(dialog).getByLabelText("目标环境"), "sit");
    await user.clear(within(dialog).getByLabelText("默认 Schema"));
    await user.type(within(dialog).getByLabelText("默认 Schema"), "REPORTING");
    await user.clear(within(dialog).getByLabelText("允许 Schema"));
    await user.type(within(dialog).getByLabelText("允许 Schema"), "REPORTING");
    await user.clear(within(dialog).getByLabelText("maxRows"));
    await user.type(within(dialog).getByLabelText("maxRows"), "250");
    await user.clear(within(dialog).getByLabelText("凭据别名 credentialAlias"));
    await user.type(
      within(dialog).getByLabelText("凭据别名 credentialAlias"),
      "as400-reporting-readonly",
    );
    await user.click(within(dialog).getByRole("button", { name: "保存修改" }));

    await waitFor(() => expect(updateRequests).toHaveLength(1));
    expect(updateRequests[0]).toMatchObject({
      connectionId: "as400-development",
      body: {
        contractVersion: "1.0",
        displayName: "AS/400 Reporting",
        targetEnvironment: "sit",
        defaultSchema: "REPORTING",
        allowedSchemas: ["REPORTING"],
        credentialAlias: "as400-reporting-readonly",
        maxRowsDefault: 250,
      },
    });
    expect(screen.getByText("REPORTING")).toBeInTheDocument();
    expect(screen.getByText("maxRows 250")).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "删除连接" }));
    await user.click(within(dialog).getByRole("button", { name: "确认删除" }));

    await waitFor(() => expect(deleteRequests).toEqual(["as400-development"]));
    await waitFor(() => expect(screen.getByLabelText("选择 SQL 连接")).toHaveValue("as400-test"));
    expect(screen.getByText("ORDERS_QA")).toBeInTheDocument();
  });

  test("keeps SQL text and execution results isolated per session tab", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/queries/run", async ({ request }) => {
        const body = await request.json();
        const sql = typeof body === "object" && body !== null && "sql" in body
          ? String(/** @type {{sql: unknown}} */ (body).sql)
          : "";
        const resultId = sql.includes("SESSION_ONE")
          ? "result-session-one"
          : "result-session-two";
        return HttpResponse.json({
          ...queryRunResult,
          resultId,
        });
      }),
      http.get("/internal/sql-workbench/results/:resultId", ({ params }) =>
        HttpResponse.json({
          ...resultPage,
          resultId: String(params.resultId),
          rows: [[String(params.resultId).toUpperCase(), "OK"]],
        }),
      ),
    );

    renderAt("/sql");

    await replaceSqlText(user, "SELECT * FROM ORDERS.SESSION_ONE");
    await clickRunSqlButton(user);
    expect(await screen.findByText("RESULT-SESSION-ONE")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "+ 新建会话" }));
    expect(screen.getByRole("tab", { name: "SQL 2" })).toHaveAttribute("aria-selected", "true");
    expect(readSqlText()).not.toBe("SELECT * FROM ORDERS.SESSION_ONE");
    expect(screen.queryByText("RESULT-SESSION-ONE")).not.toBeInTheDocument();

    await replaceSqlText(user, "SELECT * FROM ORDERS.SESSION_TWO");
    await clickRunSqlButton(user);
    expect(await screen.findByText("RESULT-SESSION-TWO")).toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "SQL 1" }));
    expect(readSqlText()).toBe("SELECT * FROM ORDERS.SESSION_ONE");
    expect(screen.getByText("RESULT-SESSION-ONE")).toBeInTheDocument();
    expect(screen.queryByText("RESULT-SESSION-TWO")).not.toBeInTheDocument();
  });

  test("executes SELECT through the editor gutter and hides direct SQL toolbar actions", async () => {
    const user = userEvent.setup();
    /** @type {unknown[]} */
    const runRequests = [];
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/queries/run", async ({ request }) => {
        runRequests.push(await request.json());
        return HttpResponse.json(queryRunResult);
      }),
      http.get("/internal/sql-workbench/results/result-001", () =>
        HttpResponse.json(resultPage),
      ),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");
    expectDirectSqlToolbarActionsHidden();
    expectExecutionFactsErrorFieldsHidden();

    await replaceSqlText(user, "SELECT * FROM ORDERS.ORDERS");
    expect(screen.getByRole("button", { name: "执行此 SQL" })).toBeEnabled();

    await clickRunSqlButton(user);
    expect(await screen.findByText("OD-10500")).toBeInTheDocument();
    expect(screen.getAllByText("result-001").length).toBeGreaterThanOrEqual(1);

    await waitFor(() => expect(runRequests).toHaveLength(1));
    expect(runRequests[0]).toMatchObject({
      contractVersion: "1.0",
      connectionId: "as400-development",
      targetEnvironment: "development",
      schema: "ORDERS",
      action: "RUN_READ_ONLY",
      sql: "SELECT * FROM ORDERS.ORDERS",
    });
    expect(runRequests[0]).not.toHaveProperty("validationHash");

    await replaceSqlText(user, "UPDATE ORDERS.ORDERS SET status = 'X'");
    expect(screen.getByRole("button", { name: "执行此 SQL" })).toBeDisabled();
    expectDirectSqlToolbarActionsHidden();
    expect(runRequests).toHaveLength(1);
  });

  test("shows an execution animation while SELECT is running", async () => {
    const user = userEvent.setup();
    /** @type {() => void} */
    let releaseRun = () => {};
    /** @type {() => void} */
    let markRunStarted = () => {};
    const runGate = new Promise((resolve) => {
      releaseRun = () => resolve(undefined);
    });
    const runStarted = new Promise((resolve) => {
      markRunStarted = () => resolve(undefined);
    });

    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/queries/run", async () => {
        markRunStarted();
        await runGate;
        return HttpResponse.json(queryRunResult);
      }),
      http.get("/internal/sql-workbench/results/result-001", () =>
        HttpResponse.json(resultPage),
      ),
    );

    renderAt("/sql");

    await replaceSqlText(user, "SELECT * FROM ORDERS.ORDERS");
    await clickRunSqlButton(user);
    await runStarted;

    expect(screen.getByRole("status")).toHaveTextContent("正在执行 SELECT 查询");
    expect(screen.getByText("控制面正在提交只读执行请求")).toBeInTheDocument();

    releaseRun();
    expect(await screen.findByText("OD-10500")).toBeInTheDocument();
    await waitFor(() =>
      expect(screen.queryByText("正在执行 SELECT 查询")).not.toBeInTheDocument(),
    );
  });

  test("shows concurrent SQL execution results as switchable result tabs", async () => {
    const user = userEvent.setup();
    /** @type {() => void} */
    let releaseFirstRun = () => {};
    /** @type {() => void} */
    let releaseSecondRun = () => {};
    const runGates = {
      first: new Promise((resolve) => {
        releaseFirstRun = () => resolve(undefined);
      }),
      second: new Promise((resolve) => {
        releaseSecondRun = () => resolve(undefined);
      }),
    };
    /** @type {string[]} */
    const runSqlTexts = [];

    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/queries/run", async ({ request }) => {
        const body = await request.json();
        const sql = String(/** @type {{sql: unknown}} */ (body).sql);
        runSqlTexts.push(sql);
        if (sql.includes("ORDERS.ONE")) {
          await runGates.first;
          return HttpResponse.json({
            ...queryRunResult,
            executionRequestId: "exec-one",
            resultId: "result-one",
            workflowId: "wf-one",
          });
        }
        await runGates.second;
        return HttpResponse.json({
          ...queryRunResult,
          executionRequestId: "exec-two",
          resultId: "result-two",
          workflowId: "wf-two",
        });
      }),
      http.get("/internal/sql-workbench/results/:resultId", ({ params }) =>
        HttpResponse.json({
          ...resultPage,
          resultId: String(params.resultId),
          rows: [[String(params.resultId).toUpperCase(), "OK"]],
        }),
      ),
    );

    renderAt("/sql");

    await replaceSqlText(user, "SELECT * FROM ORDERS.ONE;\nSELECT * FROM ORDERS.TWO;");
    const runStatementButtons = await screen.findAllByRole("button", {
      name: "执行此 SQL",
    });
    expect(runStatementButtons).toHaveLength(2);

    await user.click(runStatementButtons[0]);
    await user.click(runStatementButtons[1]);
    await waitFor(() => expect(runSqlTexts).toHaveLength(2));
    expect(screen.getByRole("tab", { name: "结果 1 · 执行中" })).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "结果 2 · 执行中" })).toBeInTheDocument();

    releaseSecondRun();
    expect(await screen.findByText("RESULT-TWO")).toBeInTheDocument();
    expect(screen.getByRole("tab", { name: "结果 2 · SUCCEEDED" })).toHaveAttribute(
      "aria-selected",
      "true",
    );

    releaseFirstRun();
    await screen.findByRole("tab", { name: "结果 1 · SUCCEEDED" });
    await user.click(screen.getByRole("tab", { name: "结果 1 · SUCCEEDED" }));
    expect(await screen.findByText("RESULT-ONE")).toBeInTheDocument();
    expect(screen.queryByText("RESULT-TWO")).not.toBeInTheDocument();

    await user.click(screen.getByRole("tab", { name: "结果 2 · SUCCEEDED" }));
    expect(await screen.findByText("RESULT-TWO")).toBeInTheDocument();
    expect(screen.queryByText("RESULT-ONE")).not.toBeInTheDocument();
  });

  test("highlights line comments and keeps commented SELECT runnable", async () => {
    const user = userEvent.setup();
    /** @type {unknown[]} */
    const runRequests = [];
    const commentedSql = "-- run this read-only smoke check\nSELECT * FROM ORDERS.ORDERS";
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/queries/run", async ({ request }) => {
        runRequests.push(await request.json());
        return HttpResponse.json(queryRunResult);
      }),
      http.get("/internal/sql-workbench/results/result-001", () =>
        HttpResponse.json(resultPage),
      ),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");
    await replaceSqlText(user, commentedSql);

    const editor = screen.getByLabelText("SQL 文本");
    expect(editor).toHaveClass("cm-content");
    expect(editor.querySelector(".cm-sql-comment")).toHaveTextContent(
      "-- run this read-only smoke check",
    );
    expect(screen.getByRole("button", { name: "执行此 SQL" })).toBeEnabled();

    await clickRunSqlButton(user);

    await waitFor(() => expect(runRequests).toHaveLength(1));
    expect(runRequests[0]).toMatchObject({
      action: "RUN_READ_ONLY",
      sql: "SELECT * FROM ORDERS.ORDERS",
    });
  });

  test("runs only the statement next to a CodeMirror gutter run button", async () => {
    const user = userEvent.setup();
    /** @type {unknown[]} */
    const runRequests = [];
    const multiStatementSql = [
      "-- first query",
      "SELECT * FROM ORDERS.ORDERS;",
      "",
      "SELECT * FROM INVENTORY.ITEMS",
    ].join("\n");
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/queries/run", async ({ request }) => {
        runRequests.push(await request.json());
        return HttpResponse.json(queryRunResult);
      }),
      http.get("/internal/sql-workbench/results/result-001", () =>
        HttpResponse.json(resultPage),
      ),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");
    await replaceSqlText(user, multiStatementSql);

    const runStatementButtons = await screen.findAllByRole("button", {
      name: "执行此 SQL",
    });
    expect(runStatementButtons).toHaveLength(2);
    expectDirectSqlToolbarActionsHidden();

    await user.click(runStatementButtons[1]);

    await waitFor(() => expect(runRequests).toHaveLength(1));
    expect(runRequests[0]).toMatchObject({
      action: "RUN_READ_ONLY",
      sql: "SELECT * FROM INVENTORY.ITEMS",
    });
    expect(String(/** @type {{sql: unknown}} */ (runRequests[0]).sql)).not.toContain(
      "ORDERS.ORDERS",
    );
  });

  test("disables gutter run buttons for non-read-only SQL statements", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");
    await replaceSqlText(
      user,
      "UPDATE ORDERS.ORDERS SET STATUS = 'X';\nSELECT * FROM ORDERS.ORDERS",
    );

    const runStatementButtons = await screen.findAllByRole("button", {
      name: "执行此 SQL",
    });
    expect(runStatementButtons).toHaveLength(2);
    expect(runStatementButtons[0]).toBeDisabled();
    expect(runStatementButtons[1]).toBeEnabled();
  });

  test("shows server-side validation diagnostics when SELECT execution is rejected", async () => {
    const user = userEvent.setup();
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/queries/run", () =>
        HttpResponse.json(
          {
            code: "INVALID_ARGUMENT",
            message: [
              "SELECT 执行未通过服务端只读校验。",
              "statementType=UNSUPPORTED",
              "validationLevel=REJECTED",
              "rejectionReasons=SQL syntax is not supported",
              "sqlHash=sha256:bad",
            ].join("\n"),
          },
          { status: 400 },
        ),
      ),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");
    await replaceSqlText(user, "SELECT * FROM ORDERS.ORDERS");
    await clickRunSqlButton(user);

    const alerts = await screen.findAllByRole("alert");
    const alert = alerts.find((element) =>
      element.textContent?.includes("statementType=UNSUPPORTED"),
    );
    if (!alert) {
      throw new Error("Expected a SQL execution validation alert");
    }
    expect(alert.textContent).toContain("SELECT 执行未通过服务端只读校验。");
    expect(within(alert).getByText("排查线索")).toBeInTheDocument();
    expect(within(alert).getByText("statementType=UNSUPPORTED")).toBeInTheDocument();
    expect(within(alert).getByText("validationLevel=REJECTED")).toBeInTheDocument();
    expect(within(alert).getByText("rejectionReasons=SQL syntax is not supported")).toBeInTheDocument();
    expect(within(alert).getByText("sqlHash=sha256:bad")).toBeInTheDocument();
    expect(screen.getAllByText("排查线索")).toHaveLength(1);
  });

  test("automatically asks AI assistant for syntax-error analysis after rejected SELECT execution", async () => {
    const user = userEvent.setup();
    /** @type {unknown[]} */
    const validationRequests = [];
    /** @type {unknown[]} */
    const assistantRequests = [];
    const syntaxSql = "select ORDER_ID, STATUS\nform PUBLIC.ORDERS";
    const syntaxAssistantResponse = {
      ...sqlAssistantResponse,
      assistantAction: "ANALYZE_ERROR",
      summary: "SQL 语法错误：FORM 应改为 FROM。",
      suggestions: [
        {
          title: "修正 FROM 关键字",
          rationale: "第二行的 form 不是 SQL 查询子句关键字。",
          suggestedSql: "select ORDER_ID, STATUS\nfrom PUBLIC.ORDERS",
        },
      ],
      safetyNotes: ["修正后必须重新执行服务端校验。"],
    };
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/queries/run", () =>
        HttpResponse.json(
          {
            code: "INVALID_ARGUMENT",
            message: "query must pass read-only validation before execution",
          },
          { status: 400 },
        ),
      ),
      http.post("/internal/sql-workbench/queries/validate", async ({ request }) => {
        validationRequests.push(await request.json());
        return HttpResponse.json(rejectedSyntaxReport);
      }),
      http.post("/internal/sql-workbench/assistant", async ({ request }) => {
        assistantRequests.push(await request.json());
        return HttpResponse.json(syntaxAssistantResponse);
      }),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");
    await replaceSqlText(user, syntaxSql);
    await clickRunSqlButton(user);

    expect(await screen.findByText("rejectionReasons=SQL syntax is not supported")).toBeInTheDocument();
    expect(await screen.findByText("SQL 语法错误：FORM 应改为 FROM。")).toBeInTheDocument();
    expect(screen.getByText("修正 FROM 关键字")).toBeInTheDocument();

    await waitFor(() => expect(validationRequests).toHaveLength(1));
    expect(validationRequests[0]).toMatchObject({
      action: "RUN_READ_ONLY",
      sql: syntaxSql,
    });
    await waitFor(() => expect(assistantRequests).toHaveLength(1));
    expect(assistantRequests[0]).toMatchObject({
      assistantAction: "ANALYZE_ERROR",
      sql: syntaxSql,
    });
    const assistantRequest = /** @type {Record<string, unknown>} */ (assistantRequests[0]);
    expect(String(assistantRequest.diagnosticContext)).toContain(
      "statementType=UNSUPPORTED",
    );
    expect(String(assistantRequest.diagnosticContext)).toContain(
      "SQL syntax is not supported",
    );
  });

  test("automatically asks AI assistant for failed SELECT execution result", async () => {
    const user = userEvent.setup();
    /** @type {unknown[]} */
    const assistantRequests = [];
    const failedSql = "select * from eladrefp";
    const failedExecution = {
      ...queryRunResult,
      status: "FAILED",
      resultId: null,
      errorCode: "SQL_EXECUTION_FAILED",
      errorMessage: "SQL query execution failed",
    };
    const executionAssistantResponse = {
      ...sqlAssistantResponse,
      assistantAction: "ANALYZE_ERROR",
      summary: "SQL 执行失败：请先确认表名 eladrefp 是否存在并在当前 Schema 可访问。",
      suggestions: [
        {
          title: "核对对象名",
          rationale: "该 SQL 语法上可解析，但执行阶段找不到或无法访问目标对象时会失败。",
          suggestedSql: "select * from PUBLIC.eladrefp",
        },
      ],
      safetyNotes: ["AI 分析只提供参考，修正后必须重新执行服务端校验。"],
    };
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/queries/run", () =>
        HttpResponse.json(failedExecution),
      ),
      http.post("/internal/sql-workbench/assistant", async ({ request }) => {
        assistantRequests.push(await request.json());
        return HttpResponse.json(executionAssistantResponse);
      }),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");
    await user.click(screen.getByRole("button", { name: "展开 SQL 工作区" }));
    expect(screen.queryByLabelText("SQL 信息面板")).not.toBeInTheDocument();
    await replaceSqlText(user, failedSql);
    await clickRunSqlButton(user);

    expect(await screen.findByText("SQL_EXECUTION_FAILED: SQL query execution failed")).toBeInTheDocument();
    expect(screen.getAllByRole("alert")).toHaveLength(1);
    expect(
      await screen.findByText("SQL 执行失败：请先确认表名 eladrefp 是否存在并在当前 Schema 可访问。"),
    ).toBeInTheDocument();

    await waitFor(() => expect(assistantRequests).toHaveLength(1));
    expect(assistantRequests[0]).toMatchObject({
      assistantAction: "ANALYZE_ERROR",
      sql: failedSql,
    });
    const assistantRequest = /** @type {Record<string, unknown>} */ (assistantRequests[0]);
    expect(String(assistantRequest.diagnosticContext)).toContain(
      "executionErrorCode=SQL_EXECUTION_FAILED",
    );
    expect(String(assistantRequest.diagnosticContext)).toContain(
      "executionErrorMessage=SQL query execution failed",
    );
  });

  test("paginates SQL result pages with explicit cursor navigation", async () => {
    const user = userEvent.setup();
    /** @type {(string | null)[]} */
    const pageTokens = [];
    const firstPage = {
      ...resultPage,
      rows: [["OD-10500", "PENDING"]],
      nextCursor: "cursor-page-2",
    };
    const secondPage = {
      ...resultPage,
      rows: [["OD-10501", "READY"]],
      nextCursor: null,
    };

    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/queries/run", () =>
        HttpResponse.json(queryRunResult),
      ),
      http.get("/internal/sql-workbench/results/result-001", ({ request }) => {
        const pageToken = new URL(request.url).searchParams.get("pageToken");
        pageTokens.push(pageToken);
        return HttpResponse.json(pageToken === "cursor-page-2" ? secondPage : firstPage);
      }),
    );

    renderAt("/sql");

    await screen.findByText("已连接 · development");
    await replaceSqlText(user, "SELECT * FROM ORDERS.ORDERS");
    await clickRunSqlButton(user);

    expect(await screen.findByText("OD-10500")).toBeInTheDocument();
    expect(screen.getByText("第 1 页")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "上一页" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "下一页" })).toBeEnabled();

    await user.click(screen.getByRole("button", { name: "下一页" }));
    expect(await screen.findByText("OD-10501")).toBeInTheDocument();
    expect(screen.getByText("第 2 页")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "上一页" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "下一页" })).toBeDisabled();

    await user.click(screen.getByRole("button", { name: "上一页" }));
    expect(await screen.findByText("OD-10500")).toBeInTheDocument();
    expect(screen.getByText("第 1 页")).toBeInTheDocument();
    expect(pageTokens).toContain("cursor-page-2");
  });

  test("resizes the SQL editor and result split with keyboard controls", async () => {
    const user = userEvent.setup();

    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
    );

    renderAt("/sql");

    const separator = await screen.findByRole("separator", { name: /SQL/ });
    expect(separator).toHaveAttribute("aria-valuenow", "72");

    separator.focus();
    await user.keyboard("{ArrowUp}");
    await waitFor(() => expect(separator).toHaveAttribute("aria-valuenow", "68"));

    await user.keyboard("{Home}");
    await waitFor(() => expect(separator).toHaveAttribute("aria-valuenow", "42"));

    await user.keyboard("{End}");
    await waitFor(() => expect(separator).toHaveAttribute("aria-valuenow", "84"));
  });

  test("blocks production connection data with DML capabilities at the contract boundary", async () => {
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json([
          {
            ...sqlConnections[0],
            connectionId: "as400-production",
            displayName: "AS/400 Production",
            targetEnvironment: "production",
          },
        ]),
      ),
    );

    renderAt("/sql");

    expect(await screen.findByText("SQL 连接契约不兼容")).toBeInTheDocument();
    expect(screen.getByLabelText("当前工作台")).toBeInTheDocument();
    expect(screen.queryByText("as400-production")).not.toBeInTheDocument();
    expect(screen.queryByText("AS/400 Production")).not.toBeInTheDocument();
  });

  test("uses the AI SQL assistant as advisory input that is revalidated on execution", async () => {
    const user = userEvent.setup();
    /** @type {unknown[]} */
    const assistantRequests = [];
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/queries/validate", () =>
        HttpResponse.json(validatedSelectReport),
      ),
      http.post("/internal/sql-workbench/assistant", async ({ request }) => {
        assistantRequests.push(await request.json());
        return HttpResponse.json(sqlAssistantResponse);
      }),
    );

    renderAt("/sql");

    await replaceSqlText(user, "SELECT * FROM ORDERS.ORDERS");
    expectDirectSqlToolbarActionsHidden();

    await user.click(screen.getByRole("button", { name: "优化建议" }));
    expect(await screen.findByText("Use explicit columns.")).toBeInTheDocument();
    expect(screen.getByText("Limit columns")).toBeInTheDocument();

    await waitFor(() => expect(assistantRequests).toHaveLength(1));
    expect(assistantRequests[0]).toMatchObject({
      contractVersion: "1.0",
      connectionId: "as400-development",
      targetEnvironment: "development",
      schema: "ORDERS",
      assistantAction: "OPTIMIZE_SQL",
      sql: "SELECT * FROM ORDERS.ORDERS",
    });

    await user.click(screen.getByRole("button", { name: "应用建议到编辑器" }));
    expect(readSqlText()).toBe("select order_id, status from ORDERS.ORDERS");
    expect(screen.queryByText("VALIDATED")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "执行此 SQL" })).toBeEnabled();
  });

  test("shows an AI SQL assistant loading animation while advice is pending", async () => {
    const user = userEvent.setup();
    /** @type {() => void} */
    let releaseAssistant = () => {};
    /** @type {() => void} */
    let markAssistantStarted = () => {};
    const assistantGate = new Promise((resolve) => {
      releaseAssistant = () => resolve(undefined);
    });
    const assistantStarted = new Promise((resolve) => {
      markAssistantStarted = () => resolve(undefined);
    });
    server.use(
      http.get("/internal/sql-workbench/connections", () =>
        HttpResponse.json(sqlConnections),
      ),
      http.post("/internal/sql-workbench/assistant", async () => {
        markAssistantStarted();
        await assistantGate;
        return HttpResponse.json(sqlAssistantResponse);
      }),
    );

    renderAt("/sql");

    await replaceSqlText(user, "SELECT * FROM ORDERS.ORDERS");
    await user.click(screen.getByRole("button", { name: "分析错误" }));
    await assistantStarted;

    expect(screen.getByRole("status")).toHaveTextContent("正在请求 AI SQL 助手");
    expect(screen.getByText("服务端模型正在生成参考建议")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "解释 SQL" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "优化建议" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "分析错误" })).toBeDisabled();

    releaseAssistant();
    expect(await screen.findByText("Use explicit columns.")).toBeInTheDocument();
  });
});

const sqlConnections = [
  {
    contractVersion: "1.0",
    connectionId: "as400-development",
    displayName: "AS/400 Development",
    targetEnvironment: "development",
    platformType: "DB2_FOR_I",
    host: "as400-dev.internal",
    port: 446,
    status: "READY",
    defaultSchema: "ORDERS",
    allowedSchemas: ["ORDERS", "INVENTORY"],
    capabilities: ["VALIDATE", "RUN_READ_ONLY", "PREFLIGHT_DML", "COMMIT_DML"],
    credentialAlias: "as400-development-readonly",
    maxRowsDefault: 500,
    timeoutSecondsDefault: 30,
  },
  {
    contractVersion: "1.0",
    connectionId: "as400-test",
    displayName: "AS/400 Test",
    targetEnvironment: "test",
    platformType: "DB2_FOR_I",
    host: "as400-test.internal",
    port: 446,
    status: "READY",
    defaultSchema: "ORDERS_QA",
    allowedSchemas: ["ORDERS_QA"],
    capabilities: ["VALIDATE", "RUN_READ_ONLY", "PREFLIGHT_DML", "COMMIT_DML"],
    credentialAlias: "as400-test-readonly",
    maxRowsDefault: 500,
    timeoutSecondsDefault: 30,
  },
];

const createdConnection = {
  contractVersion: "1.0",
  connectionId: "as400-lab",
  displayName: "AS/400 Lab",
  targetEnvironment: "sit",
  platformType: "DB2_FOR_I",
  status: "PENDING_WORKER_BINDING",
  defaultSchema: "LABORDERS",
  allowedSchemas: ["LABORDERS", "INVENTORY_QA"],
  capabilities: ["VALIDATE", "RUN_READ_ONLY", "PREFLIGHT_DML", "COMMIT_DML"],
  maxRowsDefault: 500,
  timeoutSecondsDefault: 30,
};

const validatedSelectReport = {
  contractVersion: "1.0",
  statementType: "SELECT",
  validationLevel: "VALIDATED",
  sqlHash: "sha256:readonly",
  validationHash: "sha256:validation-readonly",
  referencedObjects: ["ORDERS.ORDERS"],
  risks: [],
  rejectionReasons: [],
  unverifiedItems: [],
};

const sqlMetadataResponse = {
  contractVersion: "1.0",
  connectionId: "as400-development",
  schema: "ORDERS",
  objects: [
    {
      schema: "ORDERS",
      name: "ORDERS",
      type: "TABLE",
      columns: [
        {
          name: "ORDER_ID",
          type: "INTEGER",
          nullable: false,
          ordinalPosition: 1,
          masked: false,
        },
        {
          name: "STATUS",
          type: "VARCHAR",
          nullable: false,
          ordinalPosition: 2,
          masked: false,
        },
      ],
      indexes: [
        {
          name: "PRIMARY_KEY_ORDERS",
          unique: true,
          columns: ["ORDER_ID"],
        },
      ],
    },
  ],
  truncated: false,
  refreshedAt: "2026-06-27T10:15:31Z",
};

/**
 * @param {number} count
 */
function buildSqlMetadataResponse(count) {
  return {
    ...sqlMetadataResponse,
    objects: Array.from({ length: count }, (_, index) => {
      const ordinal = String(index + 1).padStart(3, "0");
      return {
        schema: "ORDERS",
        name: `TABLE_${ordinal}`,
        type: "TABLE",
        columns: [
          {
            name: `COL_${ordinal}`,
            type: "VARCHAR",
            nullable: false,
            ordinalPosition: 1,
            masked: false,
          },
        ],
        indexes: [],
      };
    }),
  };
}

const rejectedSyntaxReport = {
  contractVersion: "1.0",
  statementType: "UNSUPPORTED",
  validationLevel: "REJECTED",
  sqlHash: "sha256:syntax",
  validationHash: "sha256:validation-syntax",
  referencedObjects: [],
  risks: [],
  rejectionReasons: ["SQL syntax is not supported"],
  unverifiedItems: [],
};

const queryRunResult = {
  contractVersion: "1.0",
  executionRequestId: "exec-001",
  workflowId: "wf-001",
  resultId: "result-001",
  status: "SUCCEEDED",
};

const resultPage = {
  contractVersion: "1.0",
  resultId: "result-001",
  columns: [
    { name: "order_id", type: "VARCHAR", masked: false },
    { name: "status", type: "VARCHAR", masked: false },
  ],
  rows: [["OD-10500", "PENDING"]],
  nextCursor: null,
  truncated: false,
  expiresAt: "2026-06-27T09:10:00Z",
};

const sqlAssistantResponse = {
  contractVersion: "1.0",
  status: "SUCCEEDED",
  assistantAction: "OPTIMIZE_SQL",
  summary: "Use explicit columns.",
  suggestions: [
    {
      title: "Limit columns",
      rationale: "The current projection fetches every column.",
      suggestedSql: "select order_id, status from ORDERS.ORDERS",
    },
  ],
  safetyNotes: ["Validate before execution."],
  validationRequired: true,
  modelProviderFingerprint: "provider:fingerprint",
};
