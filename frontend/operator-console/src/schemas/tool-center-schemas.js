import { z } from "zod";

/**
 * @typedef {z.infer<typeof jsonRepairAssistantRequestSchema>} JsonRepairAssistantRequest
 * @typedef {z.infer<typeof jsonRepairAssistantResponseSchema>} JsonRepairAssistantResponse
 */

const nonBlankString = z.string().trim().min(1);

const jsonRepairAssistantActionSchema = z.enum(["REPAIR_JSON"]);

export const jsonRepairAssistantRequestSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    assistantAction: jsonRepairAssistantActionSchema,
    source: nonBlankString.max(30_000),
    parseError: z.string().trim().min(1).max(1_000).optional(),
    idempotencyKey: nonBlankString.max(200),
  })
  .strict();

export const jsonRepairAssistantResponseSchema = z
  .object({
    contractVersion: z.literal("1.0"),
    status: z.enum(["SUCCEEDED", "MODEL_NOT_CONFIGURED", "FAILED", "REJECTED", "NOT_REPAIRABLE"]),
    assistantAction: jsonRepairAssistantActionSchema,
    summary: nonBlankString.max(4_000),
    repairedJson: z.string().trim().min(1).max(30_000).optional().nullable(),
    failureReason: z.string().trim().min(1).max(2_000).optional().nullable(),
    safetyNotes: z.array(nonBlankString.max(1_000)),
    validationRequired: z.literal(true),
    skillId: nonBlankString.max(200),
    modelProviderFingerprint: z.string().trim().min(1).max(200).optional().nullable(),
  })
  .strict();
