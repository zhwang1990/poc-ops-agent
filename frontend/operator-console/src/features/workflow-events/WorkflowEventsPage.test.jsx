import { http, HttpResponse } from "msw";
import { render, screen } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, test } from "vitest";

import { AppProviders } from "../../app/providers.jsx";
import { server } from "../../test/server.js";
import { WorkflowEventsPage } from "./WorkflowEventsPage.jsx";

const workflowId = "00000000-0000-4000-8000-000000000301";

beforeEach(() => {
  installLocalStorageMock();
  window.localStorage.setItem("ops-agent:last-workflow-id", workflowId);
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
    http.get(`/internal/diagnostics/read-only/workflows/${workflowId}/events`, () =>
      HttpResponse.text(sseFromEvents(agentToolEvents), {
        headers: { "Content-Type": "text/event-stream" },
      }),
    ),
  );
});

afterEach(() => {
  window.localStorage.clear();
});

function renderPage() {
  return render(
    <AppProviders>
      <WorkflowEventsPage />
    </AppProviders>,
  );
}

function installLocalStorageMock() {
  /** @type {Map<string, string>} */
  const values = new Map();
  Object.defineProperty(window, "localStorage", {
    configurable: true,
    value: {
      clear: () => values.clear(),
      /** @param {string} key */
      getItem: (key) => values.get(key) ?? null,
      /** @param {string} key */
      removeItem: (key) => values.delete(key),
      /** @param {string} key @param {string} value */
      setItem: (key, value) => values.set(key, String(value)),
    },
  });
}

describe("WorkflowEventsPage", () => {
  test("loads persisted Agent Tool events for the last workflow", async () => {
    renderPage();

    expect(await screen.findByText("AGENT_TOOL_CALL_REQUESTED")).toBeInTheDocument();
    expect(await screen.findByText("AGENT_TOOL_CALL_COMPLETED")).toBeInTheDocument();
    expect(await screen.findByText("AGENT_RUNTIME_PROGRESS")).toBeInTheDocument();
    expect(screen.getByText("MODEL_CALL_COMPLETED: model call completed")).toBeInTheDocument();
    expect(await screen.findAllByText("weather-current-read@1.0.0")).toHaveLength(2);
    expect(screen.getByText(workflowId)).toBeInTheDocument();
  });
});

const agentToolEvents = [
  {
    contractVersion: "1.0",
    eventId: "00000000-0000-4000-8000-000000000401",
    workflowId,
    sequence: 1,
    timestamp: "2026-06-24T10:00:00+08:00",
    type: "AGENT_TOOL_CALL_REQUESTED",
    payload: {
      payloadType: "AGENT_TOOL_CALL_REQUESTED",
      toolCallId: "tool-call-weather-1",
      stepSequence: 1,
      skillId: "weather-current-read",
      skillVersion: "1.0.0",
      parameterSchemaId: "weather-current-read:1.0.0:input",
      targetEnvironment: "development",
      parametersHash: "sha256:weather",
    },
  },
  {
    contractVersion: "1.0",
    eventId: "00000000-0000-4000-8000-000000000402",
    workflowId,
    sequence: 2,
    timestamp: "2026-06-24T10:00:01+08:00",
    type: "AGENT_TOOL_CALL_COMPLETED",
    payload: {
      payloadType: "AGENT_TOOL_CALL_COMPLETED",
      toolCallId: "tool-call-weather-1",
      stepSequence: 1,
      skillId: "weather-current-read",
      skillVersion: "1.0.0",
      status: "SUCCEEDED",
      outputSchemaId: "weather-current-read:1.0.0:output",
    },
  },
  {
    contractVersion: "1.0",
    eventId: "00000000-0000-4000-8000-000000000403",
    workflowId,
    sequence: 3,
    timestamp: "2026-06-24T10:00:02+08:00",
    type: "AGENT_RUNTIME_PROGRESS",
    payload: {
      payloadType: "AGENT_RUNTIME_PROGRESS",
      progressKind: "MODEL_CALL_COMPLETED",
      message: "model call completed",
      replyId: "reply-1",
      blockId: null,
      toolCallId: null,
      toolName: null,
      agentId: "agent-1",
      sessionId: "session-1",
      subagentId: null,
      inputTokens: 10,
      outputTokens: 20,
      totalTokens: 30,
      modelTimeSeconds: 1.25,
      sensitiveContentSuppressed: true,
    },
  },
];

/**
 * @param {Array<Record<string, unknown>>} events
 */
function sseFromEvents(events) {
  return events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join("");
}
