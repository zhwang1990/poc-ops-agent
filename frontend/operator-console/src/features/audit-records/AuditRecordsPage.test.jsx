import { http, HttpResponse } from "msw";
import { render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, describe, expect, test } from "vitest";

import { AppProviders } from "../../app/providers.jsx";
import { server } from "../../test/server.js";
import { AuditRecordsPage } from "./AuditRecordsPage.jsx";

beforeEach(() => {
  server.use(
    http.get("/auth/session", () =>
      HttpResponse.json({
        authenticated: true,
        subject: "operator-1",
        username: "ops.auditor",
        roles: ["ROLE_ops-auditor"],
        authenticationType: "built-in",
      }),
    ),
    http.get("/internal/audit/events", () =>
      HttpResponse.json({
        total: auditEvents.length,
        events: auditEvents,
      }),
    ),
  );
});

function renderPage() {
  return render(
    <AppProviders>
      <AuditRecordsPage />
    </AppProviders>,
  );
}

describe("AuditRecordsPage", () => {
  test("renders the real audit workspace without prototype summary sections", async () => {
    renderPage();

    expect(await screen.findByRole("search", { name: "审计记录筛选" })).toBeInTheDocument();
    expect(screen.getByRole("region", { name: "审计账本" })).toBeInTheDocument();
    expect(screen.queryByRole("heading", { name: "审计证据链" })).not.toBeInTheDocument();
    expect(screen.queryByText("SESSION_AUTHORIZED")).not.toBeInTheDocument();
  });

  test("loads recent audit records from the control plane", async () => {
    renderPage();

    const skillAuditPanel = await screen.findByLabelText("最近 Skill 执行审计");
    const ledger = screen.getByRole("region", { name: "审计账本" });

    expect(within(skillAuditPanel).getByText("internal.agent.tool.execute")).toBeInTheDocument();
    expect(within(ledger).getByText("internal.agent.tool.execute")).toBeInTheDocument();
    expect(screen.getAllByText("weather-current-read:1.0.0")).toHaveLength(2);
    expect(screen.getAllByText("ALLOW").length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText("trace-weather-1")).toHaveLength(2);
  });

  test("surfaces recent Skill execution audits above noisy read events", async () => {
    server.use(
      http.get("/internal/audit/events", () =>
        HttpResponse.json({
          total: 4,
          events: [
            {
              eventId: "audit-read-1",
              requestId: "request-read-1",
              traceId: "trace-read-1",
              subject: "operator-1",
              action: "internal.audit.read",
              resource: "/internal/audit/events",
              policyVersion: "rbac-v1",
              result: "ALLOW",
              reason: "role is allowed",
              timestamp: "2026-06-24T10:00:03Z",
            },
            ...auditEvents,
          ],
        }),
      ),
    );

    renderPage();

    const skillAuditPanel = await screen.findByLabelText("最近 Skill 执行审计");

    expect(skillAuditPanel).toHaveTextContent("internal.agent.tool.execute");
    expect(skillAuditPanel).toHaveTextContent("weather-current-read:1.0.0");
    expect(skillAuditPanel).toHaveTextContent("ALLOW");
    expect(skillAuditPanel).toHaveTextContent("trace-weather-1");
  });

  test("shows an empty state instead of prototype records when no audit events exist", async () => {
    server.use(
      http.get("/internal/audit/events", () =>
        HttpResponse.json({
          total: 0,
          events: [],
        }),
      ),
    );

    renderPage();

    expect(await screen.findByText("暂无审计记录")).toBeInTheDocument();
    expect(screen.queryByText("SESSION_AUTHORIZED")).not.toBeInTheDocument();
    expect(screen.queryByText("AUDIT_SEALED")).not.toBeInTheDocument();
  });

  test("filters records by operator, action, result, trace, and time range", async () => {
    const user = userEvent.setup();
    renderPage();

    await screen.findAllByText("weather-current-read:1.0.0");
    const ledger = screen.getByRole("region", { name: "审计账本" });
    await user.type(screen.getByPlaceholderText("搜索操作者、action、resource、traceId"), "trace-agent");

    expect(within(ledger).queryByText("weather-current-read:1.0.0")).not.toBeInTheDocument();
    expect(within(ledger).getByText("/api/v1/agent/diagnostics")).toBeInTheDocument();

    await user.clear(screen.getByPlaceholderText("搜索操作者、action、resource、traceId"));
    await user.selectOptions(screen.getByLabelText("结果筛选"), "DENY");

    expect(within(ledger).getByText("release.plan.execute")).toBeInTheDocument();
    expect(within(ledger).queryByText("weather-current-read:1.0.0")).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("结果筛选"), "全部结果");
    await user.selectOptions(screen.getByLabelText("Action 筛选"), "internal.agent.tool.execute");

    expect(within(ledger).getByText("weather-current-read:1.0.0")).toBeInTheDocument();
    expect(within(ledger).queryByText("release:orders:dev")).not.toBeInTheDocument();

    await user.selectOptions(screen.getByLabelText("时间筛选"), "最近 24 小时");

    expect(within(ledger).getByText("暂无匹配记录")).toBeInTheDocument();
  });
});

const auditEvents = [
  {
    eventId: "audit-weather-1",
    requestId: "request-weather-1",
    traceId: "trace-weather-1",
    subject: "operator-1",
    action: "internal.agent.tool.execute",
    resource: "weather-current-read:1.0.0",
    policyVersion: "rbac-v1",
    result: "ALLOW",
    reason: "role is allowed",
    timestamp: "2026-06-24T10:00:01Z",
  },
  {
    eventId: "audit-agent-1",
    requestId: "request-agent-1",
    traceId: "trace-agent-1",
    subject: "operator-1",
    action: "internal.agent.diagnostics.read",
    resource: "/api/v1/agent/diagnostics",
    policyVersion: "rbac-v1",
    result: "ALLOW",
    reason: "role is allowed",
    timestamp: "2026-06-24T10:00:00Z",
  },
  {
    eventId: "audit-release-deny-1",
    requestId: "request-release-1",
    traceId: "trace-release-1",
    subject: "release.operator",
    action: "release.plan.execute",
    resource: "release:orders:dev",
    policyVersion: "rbac-v1",
    result: "DENY",
    reason: "",
    timestamp: "2026-06-23T09:00:00Z",
  },
];
