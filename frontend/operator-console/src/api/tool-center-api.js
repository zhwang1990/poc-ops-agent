import { jsonRepairAssistantRequestSchema, jsonRepairAssistantResponseSchema } from "../schemas/tool-center-schemas.js";
import { requestJson } from "./client.js";

/**
 * @param {unknown} input
 * @returns {Promise<import("../schemas/tool-center-schemas.js").JsonRepairAssistantResponse>}
 */
export function repairJsonWithAssistant(input) {
  const request = jsonRepairAssistantRequestSchema.parse(input);
  return requestJson("/internal/tool-center/json-assistant/repair", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    schema: jsonRepairAssistantResponseSchema,
  });
}
