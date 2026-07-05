import { z } from "zod";

const nonBlankString = z.string().trim().min(1);

export const auditEventSchema = z
  .object({
    eventId: nonBlankString,
    requestId: nonBlankString,
    traceId: nonBlankString,
    subject: nonBlankString,
    action: nonBlankString,
    resource: nonBlankString,
    policyVersion: nonBlankString,
    result: nonBlankString,
    reason: z.string(),
    timestamp: z.iso.datetime({ offset: true }),
  })
  .strict();

/**
 * @typedef {z.infer<typeof auditEventSchema>} AuditEvent
 */

export const auditEventsResponseSchema = z
  .object({
    total: z.number().int().nonnegative(),
    events: z.array(auditEventSchema),
  })
  .strict()
  .refine((response) => response.total >= response.events.length, {
    message: "Audit event total must not be smaller than returned events",
    path: ["total"],
  });

/**
 * @typedef {z.infer<typeof auditEventsResponseSchema>} AuditEventsResponse
 */
