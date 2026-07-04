import { z } from "zod";

/**
 * @typedef {z.infer<typeof sqlConnectionSchema>} SqlConnectionSummary
 * @typedef {z.infer<typeof sqlValidationReportSchema>} SqlValidationReport
 * @typedef {z.infer<typeof sqlConnectionCreateRequestSchema>} SqlConnectionCreateRequest
 * @typedef {z.infer<typeof sqlConnectionUpdateRequestSchema>} SqlConnectionUpdateRequest
 * @typedef {z.infer<typeof sqlConnectionProbeResultSchema>} SqlConnectionProbeResult
 * @typedef {z.infer<typeof sqlQueryRunRequestSchema>} SqlQueryRunRequest
 * @typedef {z.infer<typeof sqlQueryRunResultSchema>} SqlQueryRunResult
 * @typedef {z.infer<typeof sqlDmlCommitRequestSchema>} SqlDmlCommitRequest
 * @typedef {z.infer<typeof sqlResultPageSchema>} SqlResultPage
 * @typedef {z.infer<typeof sqlMetadataResponseSchema>} SqlMetadataResponse
 * @typedef {z.infer<typeof sqlAssistantRequestSchema>} SqlAssistantRequest
 * @typedef {z.infer<typeof sqlAssistantResponseSchema>} SqlAssistantResponse
 */

const nonBlankString = z.string().trim().min(1);
const targetEnvironmentSchema = z.enum([
  "dev",
  "sit",
  "uat",
  "production",
  "development",
  "test",
]);
const sqlPlatformTypeSchema = z.enum(["DB2_FOR_I", "H2", "MYSQL"]);
const sqlConnectionStatusSchema = z.enum([
  "READY",
  "PENDING_WORKER_BINDING",
  "DISABLED",
  "PROBE_FAILED",
]);
const sqlProbeStatusSchema = z.enum([
  "READY",
  "CREDENTIAL_ALIAS_NOT_FOUND",
  "CREDENTIAL_LOCKED",
  "EGRESS_NOT_ALLOWED",
  "READ_ONLY_ACCOUNT_CHECK_FAILED",
  "PROBE_FAILED",
]);
const sqlQueryActionSchema = z.enum([
  "VALIDATE",
  "EXPLAIN",
  "RUN_READ_ONLY",
  "PREFLIGHT_DML",
  "COMMIT_DML",
]);
const sqlAssistantActionSchema = z.enum([
  "EXPLAIN_SQL",
  "OPTIMIZE_SQL",
  "ANALYZE_ERROR",
  "GENERATE_SELECT",
  "COMPARE_SUMMARY",
]);
const productionWriteActions = new Set(["PREFLIGHT_DML", "COMMIT_DML"]);

/**
 * @param {string} targetEnvironment
 */
function isProductionEnvironment(targetEnvironment) {
  return targetEnvironment === "production";
}

/**
 * @param {unknown[]} capabilities
 * @param {import("zod").RefinementCtx} context
 */
function rejectProductionDmlCapabilities(capabilities, context) {
  const firstDmlIndex = capabilities.findIndex((capability) =>
    productionWriteActions.has(String(capability)),
  );
  if (firstDmlIndex >= 0) {
    context.addIssue({
      code: "custom",
      message: "production SQL connections only allow query capabilities",
      path: ["capabilities", firstDmlIndex],
    });
  }
}

/**
 * @param {unknown} action
 * @param {import("zod").RefinementCtx} context
 */
function rejectProductionDmlAction(action, context) {
  if (productionWriteActions.has(String(action))) {
    context.addIssue({
      code: "custom",
      message: "production SQL workbench requests only allow query actions",
      path: ["action"],
    });
  }
}

const sqlConnectionBaseSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    connectionId: nonBlankString,
    displayName: nonBlankString,
    targetEnvironment: targetEnvironmentSchema,
    platformType: sqlPlatformTypeSchema,
    host: nonBlankString.optional(),
    port: z.number().int().min(1).max(65_535).optional(),
    status: sqlConnectionStatusSchema.default("READY"),
    defaultSchema: nonBlankString.optional(),
    allowedSchemas: z.array(nonBlankString).min(1),
    capabilities: z.array(sqlQueryActionSchema).min(1),
    credentialAlias: nonBlankString.optional(),
    maxRowsDefault: z.number().int().min(1).max(10_000).default(500),
    timeoutSecondsDefault: z.number().int().min(1).max(300).default(30),
  })
  .strict();

const sqlConnectionSchema = sqlConnectionBaseSchema.superRefine((connection, context) => {
  if (isProductionEnvironment(connection.targetEnvironment)) {
    rejectProductionDmlCapabilities(connection.capabilities, context);
  }
});

export const sqlConnectionListSchema = z.array(sqlConnectionSchema);

const sqlConnectionCreateRequestBaseSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    displayName: nonBlankString,
    targetEnvironment: targetEnvironmentSchema,
    platformType: sqlPlatformTypeSchema,
    host: nonBlankString,
    port: z.number().int().min(1).max(65_535),
    defaultSchema: nonBlankString,
    allowedSchemas: z.array(nonBlankString).min(1),
    capabilities: z.array(sqlQueryActionSchema).min(1),
    credentialAlias: nonBlankString,
    maxRowsDefault: z.number().int().min(1).max(10_000),
    timeoutSecondsDefault: z.number().int().min(1).max(300),
  })
  .strict();

export const sqlConnectionCreateRequestSchema = sqlConnectionCreateRequestBaseSchema.superRefine(
  (connection, context) => {
    if (isProductionEnvironment(connection.targetEnvironment)) {
      rejectProductionDmlCapabilities(connection.capabilities, context);
    }
  },
);

export const sqlConnectionUpdateRequestSchema = sqlConnectionCreateRequestSchema;

export const sqlConnectionDeleteResponseSchema = z.undefined();

export const sqlConnectionProbeResultSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    connectionId: nonBlankString,
    status: sqlProbeStatusSchema,
    message: z.string().nullable().optional(),
    probedAt: nonBlankString,
    workerId: nonBlankString.optional(),
  })
  .strict();

const sqlTypedParameterSchema = z
  .object({
    name: nonBlankString,
    type: nonBlankString,
    value: z.json(),
  })
  .strict();

const sqlQueryLimitsSchema = z
  .object({
    maxRows: z.number().int().min(1).max(10_000),
    maxBytes: z.number().int().min(1).max(100_000_000),
    timeoutSeconds: z.number().int().min(1).max(300),
  })
  .strict();

const sqlQueryRequestBaseSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    connectionId: nonBlankString,
    targetEnvironment: targetEnvironmentSchema,
    schema: nonBlankString,
    action: sqlQueryActionSchema,
    sql: nonBlankString,
    parameters: z.array(sqlTypedParameterSchema),
    limits: sqlQueryLimitsSchema,
    idempotencyKey: nonBlankString,
  })
  .strict();

export const sqlQueryRequestSchema = sqlQueryRequestBaseSchema.superRefine((request, context) => {
  if (isProductionEnvironment(request.targetEnvironment)) {
    rejectProductionDmlAction(request.action, context);
  }
});

export const sqlQueryRunRequestSchema = sqlQueryRequestBaseSchema
  .extend({
    action: z.literal("RUN_READ_ONLY"),
  })
  .strict()
  .superRefine((request, context) => {
    if (isProductionEnvironment(request.targetEnvironment)) {
      rejectProductionDmlAction(request.action, context);
    }
  });

const sqlDmlConfirmationSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    sqlHash: nonBlankString,
    confirmedRisks: z.array(nonBlankString).min(1),
    confirmationCode: z.literal("CONFIRM_SQL_DML_RISK"),
  })
  .strict();

export const sqlDmlCommitRequestSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    query: sqlQueryRequestBaseSchema
      .extend({ action: z.literal("COMMIT_DML") })
      .strict()
      .superRefine((request, context) => {
        if (isProductionEnvironment(request.targetEnvironment)) {
          rejectProductionDmlAction(request.action, context);
        }
      }),
    confirmation: sqlDmlConfirmationSchema.nullable().optional(),
  })
  .strict();

export const sqlValidationReportSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    statementType: z.enum(["SELECT", "INSERT", "UPDATE", "DELETE", "UNSUPPORTED"]),
    validationLevel: z.enum(["VALIDATED", "PARTIAL", "REJECTED"]),
    sqlHash: nonBlankString,
    validationHash: nonBlankString.optional(),
    referencedObjects: z.array(nonBlankString),
    risks: z.array(nonBlankString),
    rejectionReasons: z.array(nonBlankString),
    unverifiedItems: z.array(nonBlankString),
  })
  .strict();

export const sqlAssistantRequestSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    connectionId: nonBlankString,
    targetEnvironment: targetEnvironmentSchema,
    schema: nonBlankString,
    assistantAction: sqlAssistantActionSchema,
    sql: nonBlankString.max(20_000),
    limits: sqlQueryLimitsSchema,
    diagnosticContext: z.string().trim().min(1).max(4_000).optional(),
    idempotencyKey: nonBlankString,
  })
  .strict();

const sqlAssistantSuggestionSchema = z
  .object({
    title: nonBlankString,
    rationale: nonBlankString,
    suggestedSql: z.string().trim().min(1).max(20_000).nullable().optional(),
  })
  .strict();

export const sqlAssistantResponseSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    status: z.enum(["SUCCEEDED", "MODEL_NOT_CONFIGURED", "FAILED", "REJECTED"]),
    assistantAction: sqlAssistantActionSchema,
    summary: nonBlankString,
    suggestions: z.array(sqlAssistantSuggestionSchema),
    safetyNotes: z.array(nonBlankString),
    validationRequired: z.literal(true),
    modelProviderFingerprint: z.string().trim().min(1).optional().nullable(),
  })
  .strict();

export const sqlQueryRunResultSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    executionRequestId: nonBlankString,
    workflowId: nonBlankString,
    status: z.enum(["QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "REJECTED", "EXPIRED"]),
    resultId: nonBlankString.nullable().optional(),
    errorCode: z.string().nullable().optional(),
    errorMessage: z.string().nullable().optional(),
    affectedRows: z.number().int().min(0).nullable().optional(),
  })
  .strict();

const sqlResultColumnSchema = z
  .object({
    name: nonBlankString,
    type: nonBlankString,
    masked: z.boolean().default(false),
  })
  .strict();

export const sqlResultPageSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    resultId: nonBlankString,
    columns: z.array(sqlResultColumnSchema),
    rows: z.array(z.array(z.json())),
    nextCursor: z.string().nullable(),
    truncated: z.boolean(),
    expiresAt: nonBlankString,
  })
  .strict();

const sqlMetadataColumnSchema = z
  .object({
    name: nonBlankString,
    type: nonBlankString,
    nullable: z.boolean(),
    ordinalPosition: z.number().int().min(1),
    masked: z.boolean().default(false),
  })
  .strict();

const sqlMetadataIndexSchema = z
  .object({
    name: nonBlankString,
    unique: z.boolean(),
    columns: z.array(nonBlankString).min(1),
  })
  .strict();

const sqlMetadataObjectSchema = z
  .object({
    schema: nonBlankString,
    name: nonBlankString,
    type: z.enum(["TABLE", "VIEW", "SYSTEM_TABLE"]),
    columns: z.array(sqlMetadataColumnSchema),
    indexes: z.array(sqlMetadataIndexSchema),
  })
  .strict();

export const sqlMetadataResponseSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    connectionId: nonBlankString,
    schema: nonBlankString,
    objects: z.array(sqlMetadataObjectSchema),
    truncated: z.boolean(),
    refreshedAt: nonBlankString,
  })
  .strict();
