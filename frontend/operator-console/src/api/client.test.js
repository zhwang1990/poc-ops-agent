import { http, HttpResponse } from "msw";
import { describe, expect, test, vi } from "vitest";
import { z } from "zod";

import {
  getLogoutUrl,
  getBrowserSession,
  getLoginUrl,
  loginWithPassword,
  logout,
} from "./auth-api.js";
import {
  runAgentDiagnosticTask,
  searchSkillCandidates,
  streamReadOnlyDiagnosticEvents,
} from "./agent-api.js";
import { ApiError, SESSION_EXPIRED_EVENT, requestJson } from "./client.js";
import { getSkill, listSkills } from "./skill-api.js";
import { listSqlConnections, validateSqlQuery } from "./sql-api.js";
import {
  listReleaseScriptProfiles,
  listReleaseApplications,
  rotateReleaseCredential,
  saveReleaseScriptProfile,
  streamReleasePlanEvents,
  uploadTomcatWar,
} from "./release-center-api.js";
import { server } from "../test/server.js";

describe("requestJson", () => {
  test("classifies a structured 403 response without inferring status from its message", async () => {
    server.use(
      http.get("/forbidden", () =>
        HttpResponse.json(
          {
            code: "POLICY_DENIED",
            message: "The display text says unauthorized, but HTTP is authoritative.",
          },
          { status: 403 },
        ),
      ),
    );

    await expect(
      requestJson("/forbidden", { schema: z.object({ ok: z.boolean() }) }),
    ).rejects.toMatchObject({
      name: "ApiError",
      status: 403,
      kind: "forbidden",
      code: "POLICY_DENIED",
      message: "The display text says unauthorized, but HTTP is authoritative.",
    });
  });

  test("classifies fetch failures as network errors", async () => {
    server.use(http.get("/offline", () => HttpResponse.error()));

    await expect(
      requestJson("/offline", { schema: z.object({ ok: z.boolean() }) }),
    ).rejects.toMatchObject({
      name: "ApiError",
      status: 0,
      kind: "network",
    });
  });

  test("notifies the shell when a JSON API response is unauthorized", async () => {
    const onSessionExpired = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
    server.use(http.get("/expired-session", () => new HttpResponse(null, { status: 401 })));

    await expect(
      requestJson("/expired-session", { schema: z.object({ ok: z.boolean() }) }),
    ).rejects.toMatchObject({
      name: "ApiError",
      status: 401,
      kind: "unauthorized",
    });

    expect(onSessionExpired).toHaveBeenCalledTimes(1);
    window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
  });

  test("treats login HTML returned to a JSON API call as an expired session", async () => {
    const onSessionExpired = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
    server.use(
      http.get("/html-login-redirect", () =>
        HttpResponse.text("<!doctype html><title>Operator console</title>", {
          headers: { "Content-Type": "text/html" },
        }),
      ),
    );

    await expect(
      requestJson("/html-login-redirect", { schema: z.object({ ok: z.boolean() }) }),
    ).rejects.toMatchObject({
      name: "ApiError",
      status: 200,
      kind: "unauthorized",
      message: "Browser session expired",
    });

    expect(onSessionExpired).toHaveBeenCalledTimes(1);
    window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
  });

  test("classifies invalid successful responses as contract errors", async () => {
    server.use(http.get("/invalid-contract", () => HttpResponse.json({ ok: "yes" })));

    await expect(
      requestJson("/invalid-contract", { schema: z.object({ ok: z.boolean() }) }),
    ).rejects.toMatchObject({
      name: "ApiError",
      status: 200,
      kind: "contract",
    });
  });

  test("classifies malformed successful JSON as a contract error", async () => {
    server.use(
      http.get(
        "/malformed-contract",
        () =>
          new HttpResponse("{", {
            status: 200,
            headers: { "Content-Type": "application/json" },
          }),
      ),
    );

    await expect(
      requestJson("/malformed-contract", { schema: z.object({ ok: z.boolean() }) }),
    ).rejects.toMatchObject({
      name: "ApiError",
      status: 200,
      kind: "contract",
    });
  });

  test("includes browser credentials and JSON accept headers", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      new Response(JSON.stringify({ ok: true }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await requestJson("/credential-check", {
      schema: z.object({ ok: z.boolean() }),
    });

    expect(fetchMock).toHaveBeenCalledWith(
      "/credential-check",
      expect.objectContaining({
        credentials: "include",
        headers: expect.objectContaining({ Accept: "application/json" }),
      }),
    );
    vi.unstubAllGlobals();
  });

  test("ApiError preserves its stable fields", () => {
    const error = new ApiError({
      status: 400,
      kind: "request",
      code: "INVALID_ARGUMENT",
      message: "Invalid request",
    });

    expect(error).toMatchObject({
      name: "ApiError",
      status: 400,
      kind: "request",
      code: "INVALID_ARGUMENT",
      message: "Invalid request",
    });
  });
});

describe("feature API modules", () => {
  test("maps authentication endpoints to the browser session and built-in login contract", async () => {
    /** @type {unknown[]} */
    const loginRequests = [];
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
      http.post("/auth/login", async ({ request }) => {
        loginRequests.push(await request.json());
        return HttpResponse.json({
          authenticated: true,
          subject: "alice-id",
          username: "alice",
          roles: ["ROLE_ops-reader"],
          passwordChangeRequired: false,
        });
      }),
      http.get("/auth/logout", () => new HttpResponse(null, { status: 204 })),
    );

    await expect(getBrowserSession()).resolves.toMatchObject({ username: "alice" });
    await expect(
      loginWithPassword({ username: "alice", password: "Start#2026" }),
    ).resolves.toMatchObject({ username: "alice" });
    await expect(logout()).resolves.toBeUndefined();
    expect(loginRequests).toEqual([{ username: "alice", password: "Start#2026" }]);
    expect(getLoginUrl()).toBe("/auth/login");
    expect(getLogoutUrl()).toBe("/auth/logout");
  });

  test("treats redirected browser logout HTML as a completed logout", async () => {
    server.use(
      http.get("/auth/logout", () =>
        HttpResponse.text("<!doctype html><title>Operator console</title>", {
          headers: { "Content-Type": "text/html" },
        }),
      ),
    );

    await expect(logout()).resolves.toBeUndefined();
  });

  test("maps skill, routing, and SQL endpoints to their real control-plane paths", async () => {
    /** @type {Array<[string, string] | [string, string, unknown]>} */
    const calls = [];
    server.use(
      http.get("/internal/skills", ({ request }) => {
        calls.push([request.method, new URL(request.url).pathname]);
        return HttpResponse.json({ total: 0, skills: [] });
      }),
      http.get("/internal/skills/node-health-read", ({ request }) => {
        calls.push([request.method, new URL(request.url).pathname]);
        return HttpResponse.json({ skill: registeredSkill });
      }),
      http.post("/internal/routing/skills/search", async ({ request }) => {
        calls.push([request.method, new URL(request.url).pathname, await request.json()]);
        return HttpResponse.json({ total: 0, candidates: [] });
      }),
      http.get("/internal/sql-workbench/connections", ({ request }) => {
        calls.push([request.method, new URL(request.url).pathname]);
        return HttpResponse.json([]);
      }),
      http.post("/internal/sql-workbench/queries/validate", async ({ request }) => {
        calls.push([request.method, new URL(request.url).pathname, await request.json()]);
        return HttpResponse.json(validationReport);
      }),
    );

    await listSkills();
    await getSkill("node-health-read");
    await searchSkillCandidates(routingRequest);
    await listSqlConnections();
    await validateSqlQuery(sqlRequest);

    expect(calls).toEqual([
      ["GET", "/internal/skills"],
      ["GET", "/internal/skills/node-health-read"],
      ["POST", "/internal/routing/skills/search", routingRequest],
      ["GET", "/internal/sql-workbench/connections"],
      ["POST", "/internal/sql-workbench/queries/validate", sqlRequest],
    ]);
  });

  test("posts main AgentScope diagnostic tasks to the primary control-plane endpoint", async () => {
    /** @type {Array<[string, string, unknown]>} */
    const calls = [];
    server.use(
      http.post("/api/v1/agent/diagnostics", async ({ request }) => {
        calls.push([request.method, new URL(request.url).pathname, await request.json()]);
        return HttpResponse.json(agentTaskResult);
      }),
    );

    await expect(runAgentDiagnosticTask(agentDiagnosticRequest)).resolves.toEqual(agentTaskResult);

    expect(calls).toEqual([
      ["POST", "/api/v1/agent/diagnostics", agentDiagnosticRequest],
    ]);
  });

  test("maps release center configuration endpoints to control-plane paths", async () => {
    /** @type {Array<[string, string, unknown?]>} */
    const calls = [];
    server.use(
      http.get("/internal/release-center/applications", ({ request }) => {
        calls.push([request.method, new URL(request.url).pathname]);
        return HttpResponse.json([releaseApplication]);
      }),
      http.get("/internal/release-center/script-profiles", ({ request }) => {
        calls.push([request.method, new URL(request.url).pathname]);
        return HttpResponse.json([releaseScriptProfile]);
      }),
      http.post("/internal/release-center/script-profiles", async ({ request }) => {
        calls.push([request.method, new URL(request.url).pathname, await request.json()]);
        return HttpResponse.json(releaseScriptProfile);
      }),
      http.post("/internal/release-center/credentials", async ({ request }) => {
        calls.push([request.method, new URL(request.url).pathname, await request.json()]);
        return HttpResponse.json(releaseCredentialSummary);
      }),
    );

    await listReleaseApplications();
    await listReleaseScriptProfiles();
    await saveReleaseScriptProfile(releaseScriptProfile);
    await rotateReleaseCredential({
      credentialAlias: "tomcat-dev",
      serverType: "TOMCAT",
      secret: "secret-value",
    });

    expect(calls).toEqual([
      ["GET", "/internal/release-center/applications"],
      ["GET", "/internal/release-center/script-profiles"],
      ["POST", "/internal/release-center/script-profiles", releaseScriptProfile],
      [
        "POST",
        "/internal/release-center/credentials",
        { credentialAlias: "tomcat-dev", serverType: "TOMCAT", secret: "secret-value" },
      ],
    ]);
  });

  test("uploads Tomcat WAR artifacts as multipart form data", async () => {
    const fetchMock = vi.fn().mockImplementation(async (url, options) => {
      const body = /** @type {FormData} */ (options.body);
      const file = /** @type {File} */ (body.get("file"));
      expect(url).toBe("/internal/release-center/artifacts/tomcat-war");
      expect(options.method).toBe("POST");
      expect(body).toBeInstanceOf(FormData);
      expect(body.get("applicationId")).toBe("orders");
      expect(body.get("targetEnvironment")).toBe("dev");
      expect(file.name).toBe("orders.war");
      return new Response(JSON.stringify(releaseArtifact), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      });
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      uploadTomcatWar({
        applicationId: "orders",
        targetEnvironment: "dev",
        file: new File(["war"], "orders.war", { type: "application/java-archive" }),
      }),
    ).resolves.toMatchObject({ artifactId: "artifact-1" });

    expect(fetchMock).toHaveBeenCalledTimes(1);
    vi.unstubAllGlobals();
  });

  test("streams release plan events and notifies parsed log events", async () => {
    /** @type {Array<[string, string, string | null]>} */
    const calls = [];
    server.use(
      http.get("/internal/release-center/plans/:releaseId/events", ({ request, params }) => {
        const url = new URL(request.url);
        calls.push([
          request.method,
          `${url.pathname}?afterSequence=${url.searchParams.get("afterSequence")}`,
          request.headers.get("accept"),
        ]);
        expect(params.releaseId).toBe("rel-1");
        return new HttpResponse(`data: ${JSON.stringify(releaseNodeLogEvent)}\n\n`, {
          status: 200,
          headers: { "Content-Type": "text/event-stream" },
        });
      }),
    );
    const onEvent = vi.fn();

    const result = await streamReleasePlanEvents("rel-1", {
      afterSequence: 2,
      onEvent,
    });

    expect(calls).toEqual([
      ["GET", "/internal/release-center/plans/rel-1/events?afterSequence=2", "text/event-stream"],
    ]);
    expect(onEvent).toHaveBeenCalledTimes(1);
    expect(onEvent.mock.calls[0][0].type).toBe("RELEASE_NODE_LOG");
    expect(result[0].payload.payloadType).toBe("RELEASE_NODE_LOG");
  });

  test("surfaces a disabled AgentScope runtime response without client fallback", async () => {
    server.use(
      http.post("/api/v1/agent/diagnostics", () =>
        HttpResponse.json(
          {
            code: "AGENT_RUNTIME_DISABLED",
            message: "Agent runtime is disabled for this environment.",
          },
          { status: 503 },
        ),
      ),
    );

    await expect(runAgentDiagnosticTask(agentDiagnosticRequest)).rejects.toMatchObject({
      name: "ApiError",
      status: 503,
      kind: "request",
      code: "AGENT_RUNTIME_DISABLED",
      message: "Agent runtime is disabled for this environment.",
    });
  });

  test("streams read-only diagnostic events and notifies each parsed semantic event", async () => {
    /** @type {Array<[string, string, unknown, string | null, string | null]>} */
    const calls = [];
    const events = [
      workflowStartedEvent,
      skillRoutedEvent,
      workerAcceptedEvent,
      workflowCompletedEvent,
    ];
    server.use(
      http.post("/internal/diagnostics/read-only/events", async ({ request }) => {
        calls.push([
          request.method,
          new URL(request.url).pathname,
          await request.json(),
          request.headers.get("accept"),
          request.headers.get("content-type"),
        ]);
        return new HttpResponse(
          events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join(""),
          {
            status: 200,
            headers: { "Content-Type": "text/event-stream" },
          },
        );
      }),
    );
    const onEvent = vi.fn();

    const result = await streamReadOnlyDiagnosticEvents(readOnlyDiagnosticRequest, {
      onEvent,
    });

    expect(calls).toEqual([
      [
        "POST",
        "/internal/diagnostics/read-only/events",
        readOnlyDiagnosticRequest,
        "text/event-stream",
        "application/json",
      ],
    ]);
    expect(onEvent).toHaveBeenCalledTimes(4);
    expect(onEvent.mock.calls.map(([event]) => event.type)).toEqual([
      "WORKFLOW_STARTED",
      "SKILL_ROUTED",
      "WORKER_ACCEPTED",
      "WORKFLOW_COMPLETED",
    ]);
    expect(result.map((event) => event.type)).toEqual([
      "WORKFLOW_STARTED",
      "SKILL_ROUTED",
      "WORKER_ACCEPTED",
      "WORKFLOW_COMPLETED",
    ]);
  });

  test("classifies read-only diagnostic policy denials as forbidden ApiErrors", async () => {
    server.use(
      http.post("/internal/diagnostics/read-only/events", () =>
        HttpResponse.json(
          {
            code: "POLICY_DENIED",
            message: "Operator is not allowed to run this read-only diagnostic.",
          },
          { status: 403 },
        ),
      ),
    );

    await expect(
      streamReadOnlyDiagnosticEvents(readOnlyDiagnosticRequest),
    ).rejects.toMatchObject({
      name: "ApiError",
      status: 403,
      kind: "forbidden",
      code: "POLICY_DENIED",
      message: "Operator is not allowed to run this read-only diagnostic.",
    });
  });

  test("notifies session expiry when an event stream endpoint is unauthorized", async () => {
    const onSessionExpired = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
    server.use(
      http.post(
        "/internal/diagnostics/read-only/events",
        () => new HttpResponse(null, { status: 401 }),
      ),
    );

    await expect(
      streamReadOnlyDiagnosticEvents(readOnlyDiagnosticRequest),
    ).rejects.toMatchObject({
      name: "ApiError",
      status: 401,
      kind: "unauthorized",
    });

    expect(onSessionExpired).toHaveBeenCalledTimes(1);
    window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
  });

  test("treats login HTML returned to an event stream call as an expired session", async () => {
    const onSessionExpired = vi.fn();
    window.addEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
    server.use(
      http.post("/internal/diagnostics/read-only/events", () =>
        HttpResponse.text("<!doctype html><title>Operator console</title>", {
          headers: { "Content-Type": "text/html" },
        }),
      ),
    );

    await expect(
      streamReadOnlyDiagnosticEvents(readOnlyDiagnosticRequest),
    ).rejects.toMatchObject({
      name: "ApiError",
      status: 200,
      kind: "unauthorized",
      message: "Browser session expired",
    });

    expect(onSessionExpired).toHaveBeenCalledTimes(1);
    window.removeEventListener(SESSION_EXPIRED_EVENT, onSessionExpired);
  });
});

const registeredSkill = {
  descriptor: {
    skillId: "node-health-read",
    version: "1.1.0",
    displayName: "Node health",
    description: "Reads node health",
    category: "INFRASTRUCTURE_DIAGNOSTICS",
    riskLevel: "READ_ONLY",
    executor: "HTTP",
    outputType: "JSON",
    readOnly: true,
    timeoutSeconds: 30,
    owner: "platform-observability",
    requiredRoles: ["ROLE_ops-reader"],
    tags: ["health"],
    interceptors: ["AUTHORIZATION", "AUDIT"],
    parameters: [],
  },
  publication: {
    publishedBy: "platform-observability",
    publishedAt: "2026-06-14T00:00:00Z",
    checksumSha256: "a".repeat(64),
    signatureAlgorithm: "HmacSHA256",
    signature: "signed",
  },
  publicationStatus: "VALIDATED",
  manifestPath: "node-health/manifest.json",
};

const routingRequest = {
  skillId: null,
  category: null,
  maxRiskLevel: "READ_ONLY",
  requiredParameters: [],
  requiredTags: [],
  requestContextTags: [],
  publicationStatusRequired: "VALIDATED",
};

const sqlRequest = {
  contractVersion: "1.0",
  connectionId: "as400-development",
  targetEnvironment: "development",
  schema: "ORDERS",
  action: "VALIDATE",
  sql: "select * from ORDERS.ORDERS",
  parameters: [],
  limits: { maxRows: 500, maxBytes: 5000000, timeoutSeconds: 30 },
  idempotencyKey: "sql-validate-1",
};

const validationReport = {
  contractVersion: "1.0",
  statementType: "SELECT",
  validationLevel: "VALIDATED",
  sqlHash: "sha256:query",
  referencedObjects: ["ORDERS.ORDERS"],
  risks: [],
  rejectionReasons: [],
  unverifiedItems: [],
};

const releaseApplication = {
  applicationId: "orders",
  displayName: "订单服务",
  artifactType: "WAR",
  healthCheckPath: "/health",
  enabled: true,
};

const releaseCredentialSummary = {
  credentialAlias: "tomcat-dev",
  fingerprint: "sha256:abc123",
  updatedAt: "2026-07-01T00:00:00Z",
};

const releaseScriptProfile = {
  profileId: "liberty-war-deploy",
  displayName: "Liberty WAR deploy",
  executablePath: "C:\\ops\\scripts\\liberty-war-deploy.cmd",
  workingDirectory: "C:\\ops-agent\\work\\release",
  arguments: ["{{param.serverName}}", "{{param.applicationName}}", "{{param.artifactPath}}"],
  successExitCodes: [0],
  timeoutSeconds: 600,
  approved: true,
  enabled: true,
};

const releaseArtifact = {
  artifactId: "artifact-1",
  applicationId: "orders",
  targetEnvironment: "dev",
  artifactType: "WAR",
  checksum: "sha256:abc123",
  originalFilename: "orders.war",
  storageKey: "artifact-1.war",
  byteSize: 3,
  uploadedBy: "alice",
  sourceType: "TOMCAT_UPLOAD",
  enabled: true,
};

const releaseNodeLogEvent = {
  contractVersion: "1.0",
  eventId: "88888888-8888-4888-8888-888888888888",
  workflowId: "99999999-9999-4999-8999-999999999999",
  releaseId: "rel-1",
  sequence: 3,
  timestamp: "2026-07-02T00:00:00Z",
  type: "RELEASE_NODE_LOG",
  payload: {
    payloadType: "RELEASE_NODE_LOG",
    nodeId: "node-1",
    stream: "STDOUT",
    message: "deploy started",
    emittedAt: "2026-07-02T00:00:00Z",
  },
  audit: {
    action: "RELEASE_NODE_LOG",
    resource: "release:rel-1",
    policyVersion: "release-center-policy-v1",
    result: "LOG",
    reason: "release node script output",
    traceId: "trace:rel-1",
    requestId: "request:rel-1",
  },
};

const readOnlyDiagnosticRequest = {
  skillId: "node-health-read",
  targetEnvironment: "development",
  idempotencyKey: "agent-workspace-node-health-00000000-0000-4000-8000-000000000001",
  parameters: { nodeName: "node-a" },
};

const agentDiagnosticRequest = {
  targetEnvironment: "development",
  idempotencyKey: "agent-workspace-task-00000000-0000-4000-8000-000000000001",
  userIntent: "检查 node-a 健康状态并总结风险",
  inputParameters: {},
};

const agentTaskResult = {
  schemaVersion: "1.0",
  taskId: "task-0001",
  workflowId: "00000000-0000-4000-8000-000000000301",
  status: "SUCCEEDED",
  summary: "已完成只读诊断，未发现阻塞风险。",
  toolCallCount: 1,
  completedAt: "2026-06-23T08:00:00Z",
  toolResults: [],
};

const workflowId = "11111111-1111-4111-8111-111111111111";
const commandId = "22222222-2222-4222-8222-222222222222";
const executionRequestId = "33333333-3333-4333-8333-333333333333";

const workflowStartedEvent = {
  contractVersion: "1.0",
  eventId: "44444444-4444-4444-8444-444444444444",
  workflowId,
  sequence: 1,
  timestamp: "2026-06-23T08:00:00Z",
  type: "WORKFLOW_STARTED",
  payload: {
    payloadType: "WORKFLOW_STARTED",
    commandId,
    operatorId: "alice",
  },
};

const skillRoutedEvent = {
  contractVersion: "1.0",
  eventId: "55555555-5555-4555-8555-555555555555",
  workflowId,
  sequence: 2,
  timestamp: "2026-06-23T08:00:01Z",
  type: "SKILL_ROUTED",
  payload: {
    payloadType: "SKILL_ROUTED",
    skillId: "node-health-read",
    skillVersion: "1.1.0",
  },
};

const workerAcceptedEvent = {
  contractVersion: "1.0",
  eventId: "66666666-6666-4666-8666-666666666666",
  workflowId,
  sequence: 3,
  timestamp: "2026-06-23T08:00:02Z",
  type: "WORKER_ACCEPTED",
  payload: {
    payloadType: "WORKER_ACCEPTED",
    executionRequestId,
  },
};

const workflowCompletedEvent = {
  contractVersion: "1.0",
  eventId: "77777777-7777-4777-8777-777777777777",
  workflowId,
  sequence: 4,
  timestamp: "2026-06-23T08:00:03Z",
  type: "WORKFLOW_COMPLETED",
  payload: {
    payloadType: "WORKFLOW_COMPLETED",
    outputSchemaId: "node-health-output-v1",
    output: {
      nodeName: "node-a",
      status: "HEALTHY",
      cpuUsagePercent: 12,
      memoryUsagePercent: 48,
      diskUsagePercent: 61,
      lastHeartbeatAt: "2026-06-23T08:00:00Z",
    },
  },
};
