import { describe, expect, test } from "vitest";

import { browserSessionSchema } from "./auth-schemas.js";
import {
  agentDiagnosticRequestSchema,
  agentTaskResultSchema,
  nodeHealthOutputSchema,
  readOnlyDiagnosticRequestSchema,
  semanticEventSchema,
  skillRoutingResponseSchema,
} from "./agent-schemas.js";
import { skillCatalogSchema, skillLookupSchema } from "./skill-schemas.js";
import {
  modelProviderCreateRequestSchema,
  modelProviderListSchema,
} from "./model-provider-schemas.js";
import {
  releaseArtifactSchema,
  releaseCredentialSummarySchema,
  releasePlanSchema,
  releaseWorkflowEventSchema,
  releaseScriptProfileDefinitionSchema,
  releaseScriptProfileDefinitionSaveRequestSchema,
  releaseServerSchema,
} from "./release-center-schemas.js";
import {
  sqlConnectionListSchema,
  sqlDmlCommitRequestSchema,
  sqlAssistantRequestSchema,
  sqlAssistantResponseSchema,
  sqlMetadataResponseSchema,
  sqlQueryRequestSchema,
  sqlValidationReportSchema,
} from "./sql-schemas.js";
import {
  jsonRepairAssistantRequestSchema,
  jsonRepairAssistantResponseSchema,
} from "./tool-center-schemas.js";

describe("browserSessionSchema", () => {
  test("accepts the current BrowserSessionResponse", () => {
    expect(
      browserSessionSchema.parse({
        authenticated: true,
        subject: "alice-id",
        username: "alice",
        roles: ["ROLE_ops-reader"],
        authenticationType: "built-in",
      }),
    ).toMatchObject({ authenticated: true, username: "alice" });
  });

  test("accepts only explicit known identity-session extensions", () => {
    expect(
      browserSessionSchema.parse({
        authenticated: true,
        subject: "alice-id",
        username: "alice",
        roles: ["ROLE_ops-reader"],
        authenticationType: "built-in",
        sessionExpiresAt: "2026-06-14T08:00:00Z",
        passwordChangeRequired: false,
        workspaces: [{ workspaceId: "operations", displayName: "Operations" }],
        currentWorkspaceId: "operations",
      }),
    ).toMatchObject({ currentWorkspaceId: "operations" });
  });

  test("rejects invalid or internally inconsistent sessions", () => {
    expect(() => browserSessionSchema.parse({ authenticated: "yes" })).toThrow();
    expect(() =>
      browserSessionSchema.parse({
        authenticated: false,
        subject: "alice-id",
        username: null,
        roles: [],
        authenticationType: "anonymous",
      }),
    ).toThrow();
  });
});

describe("skill schemas", () => {
  test("accepts registered skill descriptor and publication data", () => {
    expect(skillCatalogSchema.parse({ total: 1, skills: [registeredSkill] }).total).toBe(1);
    expect(skillLookupSchema.parse({ skill: registeredSkill }).skill.descriptor.readOnly).toBe(true);
  });

  test("strictly rejects a catalog total that disagrees with its skills", () => {
    expect(() => skillCatalogSchema.parse({ total: 1, skills: [] })).toThrow();
  });

  test("strictly rejects a routing total that disagrees with its candidates", () => {
    expect(() => skillRoutingResponseSchema.parse({ total: 1, candidates: [] })).toThrow();
  });
});

describe("SQL schemas", () => {
  test("accepts development and test DB2 for i connections", () => {
    expect(
      sqlConnectionListSchema.parse([
        {
          contractVersion: "1.0",
          connectionId: "as400-development",
          displayName: "AS/400 Development",
          targetEnvironment: "development",
          platformType: "DB2_FOR_I",
          allowedSchemas: ["ORDERS"],
          capabilities: ["VALIDATE", "RUN_READ_ONLY", "PREFLIGHT_DML", "COMMIT_DML"],
        },
      ]),
    ).toHaveLength(1);
  });

  test("accepts configured H2 and MySQL SQL workbench connections", () => {
    expect(
      sqlConnectionListSchema.parse([
        {
          contractVersion: "1.0",
          connectionId: "h2-development",
          displayName: "H2 Development",
          targetEnvironment: "development",
          platformType: "H2",
          allowedSchemas: ["PUBLIC"],
          capabilities: ["VALIDATE", "RUN_READ_ONLY", "PREFLIGHT_DML", "COMMIT_DML"],
        },
        {
          contractVersion: "1.0",
          connectionId: "mysql-test",
          displayName: "MySQL Test",
          targetEnvironment: "test",
          platformType: "MYSQL",
          allowedSchemas: ["orders"],
          capabilities: ["VALIDATE", "RUN_READ_ONLY", "PREFLIGHT_DML", "COMMIT_DML"],
        },
      ]),
    ).toHaveLength(2);
  });

  test("rejects unsupported SQL workbench platform types", () => {
    expect(() =>
      sqlConnectionListSchema.parse([
        {
          contractVersion: "1.0",
          connectionId: "postgres-development",
          displayName: "PostgreSQL Development",
          targetEnvironment: "development",
          platformType: "POSTGRESQL",
          allowedSchemas: ["public"],
          capabilities: ["VALIDATE"],
        },
      ]),
    ).toThrow();
  });

  test("accepts production SQL connections only with query capabilities", () => {
    expect(
      sqlConnectionListSchema.parse([
        {
          contractVersion: "1.0",
          connectionId: "as400-production",
          displayName: "AS/400 Production",
          targetEnvironment: "production",
          platformType: "DB2_FOR_I",
          allowedSchemas: ["ORDERS"],
          capabilities: ["VALIDATE", "RUN_READ_ONLY"],
        },
      ]),
    ).toHaveLength(1);

    expect(() =>
      sqlConnectionListSchema.parse([
        {
          contractVersion: "1.0",
          connectionId: "as400-production",
          displayName: "AS/400 Production",
          targetEnvironment: "production",
          platformType: "DB2_FOR_I",
          allowedSchemas: ["ORDERS"],
          capabilities: ["VALIDATE", "RUN_READ_ONLY", "COMMIT_DML"],
        },
      ]),
    ).toThrow();
  });

  test("accepts the real validation report fields", () => {
    expect(sqlValidationReportSchema.parse(validationReport)).toEqual(validationReport);
  });

  test("accepts production read-only SQL requests and rejects production DML", () => {
    expect(
      sqlQueryRequestSchema.parse({
        ...sqlRequest,
        targetEnvironment: "production",
        action: "RUN_READ_ONLY",
      }).targetEnvironment,
    ).toBe("production");

    expect(() =>
      sqlQueryRequestSchema.parse({
        ...sqlRequest,
        targetEnvironment: "production",
        action: "COMMIT_DML",
      }),
    ).toThrow();

    expect(() =>
      sqlDmlCommitRequestSchema.parse({
        contractVersion: "1.0",
        query: {
          ...sqlRequest,
          targetEnvironment: "production",
          action: "COMMIT_DML",
        },
        confirmation: null,
      }),
    ).toThrow();
  });

  test("accepts advisory SQL assistant responses and rejects secret fields", () => {
    expect(sqlAssistantResponseSchema.parse(sqlAssistantResponse).validationRequired).toBe(true);
    expect(() =>
      sqlAssistantResponseSchema.parse({
        ...sqlAssistantResponse,
        apiKey: "secret",
      }),
    ).toThrow();
    expect(() =>
      sqlAssistantRequestSchema.parse({
        ...sqlAssistantRequest,
        password: "secret",
      }),
    ).toThrow();
  });

  test("accepts database metadata responses and rejects secret fields", () => {
    expect(sqlMetadataResponseSchema.parse(sqlMetadataResponse).objects[0].columns[0].name).toBe("ORDER_ID");
    expect(() =>
      sqlMetadataResponseSchema.parse({
        ...sqlMetadataResponse,
        password: "secret",
      }),
    ).toThrow();
    expect(() =>
      sqlMetadataResponseSchema.parse({
        ...sqlMetadataResponse,
        objects: [
          {
            ...sqlMetadataResponse.objects[0],
            rows: [["OD-10500"]],
          },
        ],
      }),
    ).toThrow();
  });
});

describe("tool center schemas", () => {
  test("accepts JSON repair assistant contracts and rejects secret fields", () => {
    expect(jsonRepairAssistantRequestSchema.parse(jsonRepairAssistantRequest).assistantAction).toBe("REPAIR_JSON");
    expect(jsonRepairAssistantResponseSchema.parse(jsonRepairAssistantResponse).skillId).toBe(
      "json-repair-assistant-read",
    );
    expect(() =>
      jsonRepairAssistantRequestSchema.parse({
        ...jsonRepairAssistantRequest,
        apiKey: "secret",
      }),
    ).toThrow();
    expect(() =>
      jsonRepairAssistantResponseSchema.parse({
        ...jsonRepairAssistantResponse,
        rawProviderResponse: "secret",
      }),
    ).toThrow();
  });
});

describe("model provider schemas", () => {
  test("accepts safe model provider summaries", () => {
    expect(modelProviderListSchema.parse([modelProviderSummary])).toHaveLength(1);
  });

  test("rejects model provider summaries that include secret material", () => {
    expect(() =>
      modelProviderListSchema.parse([
        {
          ...modelProviderSummary,
          apiKeyCiphertext: "encrypted-secret",
        },
      ]),
    ).toThrow();
  });

  test("requires direct API Key input only for create requests", () => {
    expect(
      modelProviderCreateRequestSchema.parse({
        displayName: "OpenAI",
        baseUrl: "https://api.openai.com/v1",
        modelName: "gpt-4.1-mini",
        apiKey: "test-key",
        timeoutSeconds: 30,
        maxIterations: 5,
        maxToolCalls: 5,
        maxToolCallDurationSeconds: 30,
      }).apiKey,
    ).toBe("test-key");
  });
});

describe("release center schemas", () => {
  test("rejects production release plans", () => {
    expect(() =>
      releasePlanSchema.parse({
        ...releasePlan,
        targetEnvironment: "prod",
      }),
    ).toThrow();
  });

  test("accepts Liberty script release plans without artifacts", () => {
    const parsed = releasePlanSchema.parse({
      ...releasePlan,
      artifactId: null,
      nodes: [
        {
          nodeId: "liberty-script-1",
          serverType: "LIBERTY",
          managementMode: "LIBERTY_SCRIPT_PROFILE",
          sequence: 1,
          status: "PENDING",
        },
      ],
    });

    expect(parsed.artifactId).toBeNull();
    expect(parsed.nodes[0].managementMode).toBe("LIBERTY_SCRIPT_PROFILE");
  });

  test("rejects non-WAR Tomcat artifacts", () => {
    expect(() =>
      releaseArtifactSchema.parse({
        ...releaseArtifact,
        artifactType: "JAR",
      }),
    ).toThrow();
  });

  test("rejects credential summaries containing secret material", () => {
    expect(() =>
      releaseCredentialSummarySchema.parse({
        credentialAlias: "tomcat-dev",
        fingerprint: "sha256:abc123",
        updatedAt: "2026-07-01T00:00:00Z",
        secret: "plain-text",
      }),
    ).toThrow();
  });

  test("accepts Liberty script profile server parameters", () => {
    const parsed = releaseServerSchema.parse({
      nodeId: "dev-liberty-1",
      targetEnvironment: "dev",
      serverType: "LIBERTY",
      managementMode: "LIBERTY_SCRIPT_PROFILE",
      managementEndpoint: "https://liberty-dev.example",
      applicationPath: "/orders",
      credentialAlias: "liberty-dev",
      scriptProfile: {
        profileId: "liberty-war-deploy",
        parameters: [
          { name: "serverName", value: "defaultServer" },
          { name: "applicationName", value: "orders" },
        ],
      },
      enabled: true,
    });

    expect(parsed.scriptProfile?.profileId).toBe("liberty-war-deploy");
    expect(parsed.scriptProfile?.parameters[0].name).toBe("serverName");
  });

  test("accepts shared Liberty script profile definitions without node parameter declarations", () => {
    const parsed = releaseScriptProfileDefinitionSchema.parse({
      profileId: "liberty-war-deploy",
      displayName: "Liberty WAR deploy",
      executablePath: "C:\\ops\\scripts\\liberty-war-deploy.cmd",
      workingDirectory: "C:\\ops-agent\\work\\release",
      arguments: ["{{param.serverName}}", "{{param.applicationName}}", "{{param.artifactPath}}"],
      successExitCodes: [0],
      timeoutSeconds: 600,
      approved: true,
      enabled: true,
    });

    expect(parsed.profileId).toBe("liberty-war-deploy");
    expect(parsed.arguments[2]).toBe("{{param.artifactPath}}");
    expect(() =>
      releaseScriptProfileDefinitionSchema.parse({
        ...parsed,
        targetEnvironment: "dev",
      }),
    ).toThrow();
  });

  test("accepts script profile definitions without argument templates", () => {
    const parsed = releaseScriptProfileDefinitionSchema.parse({
      profileId: "liberty-war-deploy",
      displayName: "Liberty WAR deploy",
      executablePath: "C:\\ops\\scripts\\liberty-war-deploy.cmd",
      workingDirectory: "C:\\ops-agent\\work\\release",
      arguments: [],
      successExitCodes: [0],
      timeoutSeconds: 600,
      approved: true,
      enabled: true,
    });

    expect(parsed.arguments).toEqual([]);
  });

  test("rejects script profile governance fields in save requests", () => {
    const request = {
      profileId: "liberty-war-deploy",
      displayName: "Liberty WAR deploy",
      executablePath: "C:\\ops\\scripts\\liberty-war-deploy.cmd",
      workingDirectory: "C:\\ops-agent\\work\\release",
      arguments: [],
      successExitCodes: [0],
      timeoutSeconds: 600,
    };

    expect(releaseScriptProfileDefinitionSaveRequestSchema.parse(request).profileId).toBe("liberty-war-deploy");
    expect(() =>
      releaseScriptProfileDefinitionSaveRequestSchema.parse({
        ...request,
        approved: true,
        enabled: true,
      }),
    ).toThrow();
  });

  test("accepts release node log events and rejects mismatched payload types", () => {
    const parsed = releaseWorkflowEventSchema.parse(releaseNodeLogEvent);

    expect(parsed.type).toBe("RELEASE_NODE_LOG");
    expect(parsed.payload.payloadType).toBe("RELEASE_NODE_LOG");
    if (parsed.payload.payloadType !== "RELEASE_NODE_LOG") {
      throw new Error("expected release node log payload");
    }
    expect(parsed.payload.message).toBe("deploy started");
    expect(() =>
      releaseWorkflowEventSchema.parse({
        ...releaseNodeLogEvent,
        type: "RELEASE_NODE_STARTED",
      }),
    ).toThrow();
  });
});

describe("semanticEventSchema", () => {
  test("requires the event type to match its strongly typed payload", () => {
    expect(() =>
      semanticEventSchema.parse({
        contractVersion: "1.0",
        eventId: "9cf516e0-561e-4cbf-8f18-c0b36a54b4da",
        workflowId: "193b2852-cd76-46a2-a589-dd350d830e6a",
        sequence: 1,
        timestamp: "2026-06-14T00:00:00Z",
        type: "WORKFLOW_STARTED",
        payload: {
          payloadType: "SKILL_ROUTED",
          skillId: "node-health-read",
          skillVersion: "1.1.0",
        },
      }),
    ).toThrow();
  });

  test("accepts Agent Tool semantic event payloads from the shared contract", () => {
    const baseEvent = {
      contractVersion: "1.0",
      workflowId: "193b2852-cd76-46a2-a589-dd350d830e6a",
      timestamp: "2026-06-14T00:00:00Z",
    };

    expect(
      semanticEventSchema.parse({
        ...baseEvent,
        eventId: "9cf516e0-561e-4cbf-8f18-c0b36a54b4db",
        sequence: 1,
        type: "AGENT_TOOL_CALL_REQUESTED",
        payload: {
          payloadType: "AGENT_TOOL_CALL_REQUESTED",
          toolCallId: "tool-call-1",
          stepSequence: 1,
          skillId: "node-health-read",
          skillVersion: "1.1.0",
          parameterSchemaId: "node-health-read:1.1.0:input",
          targetEnvironment: "development",
          parametersHash: "sha256:abc123",
        },
      }).type,
    ).toBe("AGENT_TOOL_CALL_REQUESTED");

    expect(
      semanticEventSchema.parse({
        ...baseEvent,
        eventId: "9cf516e0-561e-4cbf-8f18-c0b36a54b4dc",
        sequence: 2,
        type: "AGENT_TOOL_CALL_COMPLETED",
        payload: {
          payloadType: "AGENT_TOOL_CALL_COMPLETED",
          toolCallId: "tool-call-1",
          stepSequence: 1,
          skillId: "node-health-read",
          skillVersion: "1.1.0",
          status: "SUCCEEDED",
          outputSchemaId: "node-health-read:1.1.0:output",
        },
      }).type,
    ).toBe("AGENT_TOOL_CALL_COMPLETED");

    expect(
      semanticEventSchema.parse({
        ...baseEvent,
        eventId: "9cf516e0-561e-4cbf-8f18-c0b36a54b4dd",
        sequence: 3,
        type: "AGENT_TOOL_CALL_REJECTED",
        payload: {
          payloadType: "AGENT_TOOL_CALL_REJECTED",
          toolCallId: "tool-call-2",
          stepSequence: 2,
          skillId: "node-restart",
          skillVersion: "1.0.0",
          errorCode: "POLICY_DENIED",
          message: "operator is not allowed",
          policyDecisionId: "policy-v1:workflow-1:tool-call-2",
        },
      }).type,
      ).toBe("AGENT_TOOL_CALL_REJECTED");
  });

  test("accepts Agent runtime progress payloads without SDK event names", () => {
    const event = {
      contractVersion: "1.0",
      eventId: "9cf516e0-561e-4cbf-8f18-c0b36a54b4de",
      workflowId: "193b2852-cd76-46a2-a589-dd350d830e6a",
      sequence: 10001,
      timestamp: "2026-07-01T00:00:00Z",
      type: "AGENT_RUNTIME_PROGRESS",
      payload: {
        payloadType: "AGENT_RUNTIME_PROGRESS",
        progressKind: "MODEL_CALL_COMPLETED",
        message: "model call completed",
        replyId: null,
        blockId: null,
        toolCallId: null,
        toolName: null,
        agentId: null,
        sessionId: null,
        subagentId: null,
        inputTokens: 12,
        outputTokens: 5,
        totalTokens: 17,
        modelTimeSeconds: 0.42,
        sensitiveContentSuppressed: false,
      },
    };

    const parsed = semanticEventSchema.parse(event);
    expect(parsed.payload.payloadType).toBe("AGENT_RUNTIME_PROGRESS");
    if (parsed.payload.payloadType !== "AGENT_RUNTIME_PROGRESS") {
      throw new Error("expected Agent runtime progress payload");
    }
    expect(parsed.payload.progressKind).toBe("MODEL_CALL_COMPLETED");
    expect(() =>
      semanticEventSchema.parse({
        ...event,
        payload: {
          ...event.payload,
          sourceEventType: "MODEL_CALL_END",
        },
      }),
    ).toThrow();
  });
});

describe("readOnlyDiagnosticRequestSchema", () => {
  test("accepts the fixed P1 node health request", () => {
    expect(readOnlyDiagnosticRequestSchema.parse(nodeHealthRequest)).toEqual(nodeHealthRequest);
  });

  test("rejects production diagnostic requests", () => {
    expect(() =>
      readOnlyDiagnosticRequestSchema.parse({
        ...nodeHealthRequest,
        targetEnvironment: "production",
      }),
    ).toThrow();
  });
});

describe("agent diagnostic schemas", () => {
  test("accepts a main AgentScope diagnostic task request", () => {
    expect(agentDiagnosticRequestSchema.parse(agentDiagnosticRequest)).toEqual(agentDiagnosticRequest);
  });

  test("rejects production or blank main Agent task requests", () => {
    expect(() =>
      agentDiagnosticRequestSchema.parse({
        ...agentDiagnosticRequest,
        targetEnvironment: "production",
      }),
    ).toThrow();
    expect(() =>
      agentDiagnosticRequestSchema.parse({
        ...agentDiagnosticRequest,
        userIntent: "   ",
      }),
    ).toThrow();
  });

  test("accepts the main Agent task result contract", () => {
    expect(agentTaskResultSchema.parse(agentTaskResult)).toEqual(agentTaskResult);
  });

  test("accepts all Agent task result statuses from the contract", () => {
    for (const status of [
      "SUCCEEDED",
      "FAILED_TERMINAL",
      "REJECTED",
      "AGENT_RUNTIME_DISABLED",
      "AGENT_RUNTIME_NOT_CONFIGURED",
      "AGENT_RUNTIME_FAILED",
    ]) {
      expect(agentTaskResultSchema.parse({ ...agentTaskResult, status }).status).toBe(status);
    }
  });

  test("rejects unsupported main Agent task result statuses", () => {
    expect(() =>
      agentTaskResultSchema.parse({
        ...agentTaskResult,
        status: "NEEDS_APPROVAL",
      }),
    ).toThrow();
  });
});

describe("nodeHealthOutputSchema", () => {
  test("accepts complete node health output", () => {
    expect(nodeHealthOutputSchema.parse(nodeHealthOutput)).toEqual(nodeHealthOutput);
  });

  test("rejects incomplete node health output", () => {
    const incompleteOutput = { ...nodeHealthOutput, diskUsagePercent: undefined };

    expect(() => nodeHealthOutputSchema.parse(incompleteOutput)).toThrow();
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
    parameters: [
      {
        name: "nodeName",
        displayName: "Node",
        description: "Node identifier",
        type: "STRING",
        required: true,
        allowedValues: [],
        defaultValue: null,
      },
    ],
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

const sqlAssistantRequest = {
  contractVersion: "1.0",
  connectionId: "as400-development",
  targetEnvironment: "development",
  schema: "ORDERS",
  assistantAction: "OPTIMIZE_SQL",
  sql: "select * from ORDERS.ORDERS",
  limits: { maxRows: 500, maxBytes: 5000000, timeoutSeconds: 30 },
  idempotencyKey: "sql-assistant-1",
};

const sqlAssistantResponse = {
  contractVersion: "1.0",
  status: "SUCCEEDED",
  assistantAction: "OPTIMIZE_SQL",
  summary: "Use explicit columns.",
  suggestions: [
    {
      title: "Limit columns",
      rationale: "Reduce returned data.",
      suggestedSql: "select order_id from ORDERS.ORDERS",
    },
  ],
  safetyNotes: ["Validate before execution."],
  validationRequired: true,
};

const jsonRepairAssistantRequest = {
  contractVersion: "1.0",
  assistantAction: "REPAIR_JSON",
  source: '{"service":"queFork",}',
  parseError: "Unexpected token }",
  idempotencyKey: "json-repair-1",
};

const jsonRepairAssistantResponse = {
  contractVersion: "1.0",
  status: "SUCCEEDED",
  assistantAction: "REPAIR_JSON",
  summary: "已移除尾随逗号。",
  repairedJson: '{"service":"queFork"}',
  failureReason: null,
  safetyNotes: ["修复结果必须重新经过本地 JSON 校验。"],
  validationRequired: true,
  skillId: "json-repair-assistant-read",
  modelProviderFingerprint: "provider:fingerprint",
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

const modelProviderSummary = {
  providerId: "provider-1",
  displayName: "OpenAI",
  providerType: "OPENAI_COMPATIBLE",
  baseUrl: "https://api.openai.com/v1",
  modelName: "gpt-4.1-mini",
  enabled: true,
  defaultProvider: true,
  timeout: "PT30S",
  maxIterations: 5,
  maxToolCalls: 5,
  maxToolCallDuration: "PT30S",
  apiKeyConfigured: true,
  apiKeyFingerprint: "fp_test",
  apiKeyLastRotatedAt: "2026-06-28T00:00:00Z",
  configVersion: 1,
  updatedAt: "2026-06-28T00:00:00Z",
};

const releaseArtifact = {
  artifactId: "artifact-1",
  applicationId: "orders",
  targetEnvironment: "dev",
  artifactType: "WAR",
  checksum: "sha256:abc123",
  originalFilename: "orders.war",
  storageKey: "artifact-1.war",
  byteSize: 1024,
  uploadedBy: "alice",
  sourceType: "TOMCAT_UPLOAD",
  enabled: true,
};

const releasePlan = {
  releaseId: "rel-1",
  applicationId: "orders",
  targetEnvironment: "dev",
  artifactId: "artifact-1",
  status: "DRAFT",
  parametersHash: "sha256:abc123",
  nodes: [
    {
      nodeId: "node-1",
      serverType: "TOMCAT",
      managementMode: "TOMCAT_WAR_UPLOAD",
      sequence: 1,
      status: "PENDING",
    },
  ],
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

const nodeHealthRequest = {
  skillId: "node-health-read",
  targetEnvironment: "development",
  parameters: {
    nodeName: "node-a",
  },
  idempotencyKey: "node-health-request-1",
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
  toolResults: [
    {
      schemaVersion: "1.0",
      toolCallId: "tool-call-weather-1",
      taskId: "task-0001",
      workflowId: "00000000-0000-4000-8000-000000000301",
      status: "SUCCEEDED",
      outputSchemaId: "weather-current-read:1.0.0:output",
      output: {
        location: "Shanghai",
        condition: "Sunny",
        temperatureCelsius: 31.2,
        observedAt: "2026-06-24T10:00:00+08:00",
      },
      errorCode: null,
      errorMessage: null,
      completedAt: "2026-06-23T08:00:00Z",
    },
  ],
};

const nodeHealthOutput = {
  nodeName: "node-a",
  status: "HEALTHY",
  cpuUsagePercent: 17,
  memoryUsagePercent: 43,
  diskUsagePercent: 68,
  lastHeartbeatAt: "2026-06-14T08:00:00+08:00",
};
