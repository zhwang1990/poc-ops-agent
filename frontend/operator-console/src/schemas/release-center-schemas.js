import { z } from "zod";

/**
 * @typedef {z.infer<typeof releaseApplicationSchema>} ReleaseApplication
 * @typedef {z.infer<typeof releaseServerSchema>} ReleaseServer
 * @typedef {z.infer<typeof releaseArtifactSchema>} ReleaseArtifact
 * @typedef {z.infer<typeof releasePlanSchema>} ReleasePlan
 * @typedef {z.infer<typeof releaseWorkflowEventSchema>} ReleaseWorkflowEvent
 * @typedef {z.infer<typeof releaseCredentialSummarySchema>} ReleaseCredentialSummary
 * @typedef {z.infer<typeof releaseScriptProfileDefinitionSchema>} ReleaseScriptProfileDefinition
 */

const nonBlankString = z.string().trim().min(1);
const sha256String = nonBlankString.regex(/^sha256:[a-fA-F0-9]{3,}$/);
const scriptParameterNameSchema = nonBlankString
  .regex(/^[A-Za-z][A-Za-z0-9_.-]{0,63}$/)
  .refine((value) => !/(password|secret|token)/i.test(value), {
    message: "script profile parameters must not contain secret material",
  });

export const targetEnvironmentSchema = z
  .enum(["dev", "sit", "uat", "DEV", "SIT", "UAT"])
  .transform((value) => value.toLowerCase());

export const serverTypeSchema = z.enum(["TOMCAT", "LIBERTY"]);

export const managementModeSchema = z.enum([
  "LIBERTY_HTTPS",
  "LIBERTY_SCRIPT_PROFILE",
  "TOMCAT_WAR_UPLOAD",
  "TOMCAT_MANAGER_API",
  "NODE_AGENT_HTTPS",
  "CONTROLLED_SSH_TEMPLATE",
  "DISABLED",
]);

export const releaseScriptParameterSchema = z
  .object({
    name: scriptParameterNameSchema,
    value: nonBlankString.max(500),
  })
  .strict();

export const releaseScriptProfileSchema = z
  .object({
    profileId: nonBlankString.regex(/^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$/),
    parameters: z.array(releaseScriptParameterSchema).max(40),
  })
  .strict();

export const releaseScriptProfileDefinitionSchema = z
  .object({
    profileId: nonBlankString.regex(/^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$/),
    displayName: nonBlankString.max(160),
    executablePath: nonBlankString.max(500),
    workingDirectory: nonBlankString.max(500),
    arguments: z.array(nonBlankString.max(500)).min(1).max(40),
    successExitCodes: z.array(z.number().int().min(0).max(255)).min(1).max(20),
    timeoutSeconds: z.number().int().min(1).max(7200),
    approved: z.boolean(),
    enabled: z.boolean(),
  })
  .strict();

export const releaseScriptProfileDefinitionListSchema = z.array(releaseScriptProfileDefinitionSchema);

export const releaseApplicationSchema = z
  .object({
    applicationId: nonBlankString,
    displayName: nonBlankString,
    artifactType: z.literal("WAR"),
    healthCheckPath: nonBlankString,
    enabled: z.boolean(),
  })
  .strict();

export const releaseApplicationListSchema = z.array(releaseApplicationSchema);

export const releaseApplicationSaveRequestSchema = z
  .object({
    applicationId: nonBlankString,
    displayName: nonBlankString,
    artifactType: z.literal("WAR"),
    healthCheckPath: nonBlankString,
    enabled: z.boolean(),
  })
  .strict();

export const releaseServerSchema = z
  .object({
    nodeId: nonBlankString,
    targetEnvironment: targetEnvironmentSchema,
    serverType: serverTypeSchema,
    managementMode: managementModeSchema,
    managementEndpoint: nonBlankString,
    applicationPath: nonBlankString.nullable().optional(),
    credentialAlias: nonBlankString.nullable().optional(),
    scriptProfile: releaseScriptProfileSchema.nullable().optional(),
    enabled: z.boolean(),
  })
  .strict();

export const releaseServerListSchema = z.array(releaseServerSchema);
export const releaseServerDeleteResponseSchema = z.undefined();

export const releaseArtifactSchema = z
  .object({
    artifactId: nonBlankString,
    applicationId: nonBlankString,
    targetEnvironment: targetEnvironmentSchema,
    artifactType: z.literal("WAR"),
    checksum: sha256String,
    originalFilename: nonBlankString,
    storageKey: nonBlankString,
    byteSize: z.number().int().nonnegative(),
    uploadedBy: nonBlankString,
    sourceType: nonBlankString,
    enabled: z.boolean(),
  })
  .strict();

export const releaseArtifactListSchema = z.array(releaseArtifactSchema);

export const releaseCredentialSummarySchema = z
  .object({
    credentialAlias: nonBlankString,
    fingerprint: nonBlankString,
    updatedAt: z.iso.datetime({ offset: true }),
  })
  .strict();

export const releaseCredentialRotateRequestSchema = z
  .object({
    credentialAlias: nonBlankString,
    serverType: serverTypeSchema,
    secret: nonBlankString,
  })
  .strict();

export const releaseNodeStepSchema = z
  .object({
    nodeId: nonBlankString,
    serverType: serverTypeSchema,
    managementMode: managementModeSchema,
    sequence: z.number().int().positive(),
    status: z.enum(["PENDING", "RUNNING", "SUCCEEDED", "FAILED", "SKIPPED"]),
    statusReason: nonBlankString.nullable().optional(),
    startedAt: z.iso.datetime({ offset: true }).nullable().optional(),
    completedAt: z.iso.datetime({ offset: true }).nullable().optional(),
  })
  .strict();

export const releaseConfirmationSchema = z
  .object({
    confirmationId: nonBlankString,
    parametersHash: sha256String,
    confirmedBy: nonBlankString,
    confirmedAt: z.iso.datetime({ offset: true }),
  })
  .strict();

export const releasePlanSchema = z
  .object({
    releaseId: nonBlankString,
    applicationId: nonBlankString,
    targetEnvironment: targetEnvironmentSchema,
    artifactId: nonBlankString.nullable().optional(),
    status: z.enum([
      "DRAFT",
      "WAIT_CONFIRM",
      "READY",
      "RUNNING",
      "SUCCEEDED",
      "SUCCEEDED_WITH_WARNINGS",
      "PARTIAL_FAILED",
      "FAILED",
      "ROLLING_BACK",
      "ROLLED_BACK",
      "ROLLBACK_FAILED",
      "MANUAL_INTERVENTION",
    ]),
    nodes: z.array(releaseNodeStepSchema).min(1),
    parametersHash: sha256String,
    confirmation: releaseConfirmationSchema.nullable().optional(),
    stopOnNodeFailure: z.boolean().optional(),
    createdAt: z.iso.datetime({ offset: true }).optional(),
    updatedAt: z.iso.datetime({ offset: true }).optional(),
  })
  .strict();

export const releasePlanListSchema = z.array(releasePlanSchema);

export const releasePlanCreateRequestSchema = z
  .object({
    applicationId: nonBlankString,
    targetEnvironment: targetEnvironmentSchema,
    artifactId: nonBlankString.optional(),
    nodeIds: z.array(nonBlankString).min(1),
    parametersHash: sha256String.optional(),
  })
  .strict();

export const releasePlanConfirmRequestSchema = z
  .object({
    confirmationId: nonBlankString,
    parametersHash: sha256String,
  })
  .strict();

export const releaseConnectionTestResultSchema = z
  .object({
    nodeId: nonBlankString,
    status: nonBlankString,
    message: nonBlankString,
    checkedAt: z.iso.datetime({ offset: true }),
  })
  .strict();

const releaseEventTypeSchema = z.enum([
  "RELEASE_CREATED",
  "RELEASE_CONFIRMED",
  "RELEASE_NODE_STARTED",
  "RELEASE_NODE_LOG",
  "RELEASE_NODE_COMPLETED",
  "RELEASE_NODE_FAILED",
  "RELEASE_PARTIAL_FAILED",
  "RELEASE_ROLLBACK_STARTED",
  "RELEASE_ROLLBACK_FAILED",
  "RELEASE_MANUAL_INTERVENTION_REQUIRED",
]);

const releaseCreatedPayloadSchema = z
  .object({
    payloadType: z.literal("RELEASE_CREATED"),
    applicationId: nonBlankString,
    targetEnvironment: targetEnvironmentSchema,
    operation: z.enum(["DEPLOY", "START", "STOP", "RESTART", "ROLLBACK"]),
    artifactType: z.enum(["WAR", "SCRIPT_PROFILE"]),
    artifactChecksum: sha256String,
    nodeIds: z.array(nonBlankString).min(1),
    operatorId: nonBlankString,
    policyDecisionId: nonBlankString,
  })
  .strict();

const releaseConfirmedPayloadSchema = z
  .object({
    payloadType: z.literal("RELEASE_CONFIRMED"),
    confirmationId: nonBlankString,
    confirmedBy: nonBlankString,
    confirmedAt: z.iso.datetime({ offset: true }),
    parametersHash: sha256String,
  })
  .strict();

const releaseNodeStartedPayloadSchema = z
  .object({
    payloadType: z.literal("RELEASE_NODE_STARTED"),
    nodeId: nonBlankString,
    serverType: serverTypeSchema,
    managementMode: managementModeSchema,
    startedAt: z.iso.datetime({ offset: true }),
  })
  .strict();

const releaseNodeLogPayloadSchema = z
  .object({
    payloadType: z.literal("RELEASE_NODE_LOG"),
    nodeId: nonBlankString,
    stream: z.enum(["STDOUT", "STDERR"]),
    message: nonBlankString.max(1000),
    emittedAt: z.iso.datetime({ offset: true }),
  })
  .strict();

const releaseNodeCompletedPayloadSchema = z
  .object({
    payloadType: z.literal("RELEASE_NODE_COMPLETED"),
    nodeId: nonBlankString,
    status: z.literal("SUCCEEDED"),
    currentVersion: nonBlankString.nullable().optional(),
    targetVersion: nonBlankString.nullable().optional(),
    completedAt: z.iso.datetime({ offset: true }),
  })
  .strict();

const releaseNodeFailedPayloadSchema = z
  .object({
    payloadType: z.literal("RELEASE_NODE_FAILED"),
    nodeId: nonBlankString,
    errorCode: nonBlankString,
    message: nonBlankString.max(500),
    failedAt: z.iso.datetime({ offset: true }),
  })
  .strict();

const releasePartialFailedPayloadSchema = z
  .object({
    payloadType: z.literal("RELEASE_PARTIAL_FAILED"),
    failedNodeId: nonBlankString,
    completedNodeIds: z.array(nonBlankString),
    message: nonBlankString.max(500),
  })
  .strict();

const releaseRollbackStartedPayloadSchema = z
  .object({
    payloadType: z.literal("RELEASE_ROLLBACK_STARTED"),
    reason: nonBlankString.max(500),
    startedAt: z.iso.datetime({ offset: true }),
  })
  .strict();

const releaseRollbackFailedPayloadSchema = z
  .object({
    payloadType: z.literal("RELEASE_ROLLBACK_FAILED"),
    errorCode: nonBlankString,
    message: nonBlankString.max(500),
    failedAt: z.iso.datetime({ offset: true }),
  })
  .strict();

const releaseManualInterventionRequiredPayloadSchema = z
  .object({
    payloadType: z.literal("RELEASE_MANUAL_INTERVENTION_REQUIRED"),
    reason: nonBlankString.max(500),
    lastCompletedStep: nonBlankString.nullable().optional(),
    failedStep: nonBlankString.nullable().optional(),
    recommendedAction: nonBlankString.max(500).nullable().optional(),
  })
  .strict();

export const releaseEventPayloadSchema = z.discriminatedUnion("payloadType", [
  releaseCreatedPayloadSchema,
  releaseConfirmedPayloadSchema,
  releaseNodeStartedPayloadSchema,
  releaseNodeLogPayloadSchema,
  releaseNodeCompletedPayloadSchema,
  releaseNodeFailedPayloadSchema,
  releasePartialFailedPayloadSchema,
  releaseRollbackStartedPayloadSchema,
  releaseRollbackFailedPayloadSchema,
  releaseManualInterventionRequiredPayloadSchema,
]);

const releaseAuditContextSchema = z
  .object({
    action: nonBlankString,
    resource: nonBlankString,
    policyVersion: nonBlankString,
    result: nonBlankString,
    reason: nonBlankString.max(500),
    traceId: nonBlankString,
    requestId: nonBlankString,
  })
  .strict();

export const releaseWorkflowEventSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    eventId: nonBlankString,
    workflowId: nonBlankString,
    releaseId: nonBlankString,
    sequence: z.number().int().positive(),
    timestamp: z.iso.datetime({ offset: true }),
    type: releaseEventTypeSchema,
    payload: releaseEventPayloadSchema,
    audit: releaseAuditContextSchema,
  })
  .strict()
  .refine((event) => event.type === event.payload.payloadType, {
    message: "release event type must match payloadType",
  });
