import { describe, expect, test } from "vitest";

import { auditEventsResponseSchema } from "./audit-schemas.js";

describe("audit schemas", () => {
  test("accepts audit events with an empty reason", () => {
    const parsed = auditEventsResponseSchema.parse({
      total: 1,
      events: [
        {
          eventId: "audit-1",
          requestId: "request-1",
          traceId: "trace-1",
          subject: "operator-1",
          action: "internal.audit.read",
          resource: "/internal/audit/events",
          policyVersion: "rbac-v1",
          result: "ALLOW",
          reason: "",
          timestamp: "2026-07-05T12:00:00Z",
        },
      ],
    });

    expect(parsed.events[0].reason).toBe("");
  });
});
