import {
  sqlConnectionCreateRequestSchema,
  sqlConnectionDeleteResponseSchema,
  sqlConnectionListSchema,
  sqlConnectionProbeResultSchema,
  sqlConnectionUpdateRequestSchema,
  sqlDmlCommitRequestSchema,
  sqlDmlPreflightResultSchema,
  sqlAssistantRequestSchema,
  sqlAssistantResponseSchema,
  sqlQueryRunRequestSchema,
  sqlQueryRunResultSchema,
  sqlQueryRequestSchema,
  sqlMetadataResponseSchema,
  sqlResultPageSchema,
  sqlValidationReportSchema,
} from "../schemas/sql-schemas.js";
import { requestJson } from "./client.js";

export function listSqlConnections() {
  return requestJson("/internal/sql-workbench/connections", {
    schema: sqlConnectionListSchema,
  });
}

/**
 * @param {unknown} input
 */
export function createSqlConnection(input) {
  const request = sqlConnectionCreateRequestSchema.parse(input);
  return requestJson("/internal/sql-workbench/connections", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    schema: sqlConnectionListSchema.element,
  });
}

/**
 * @param {string} connectionId
 * @param {unknown} input
 */
export function updateSqlConnection(connectionId, input) {
  const request = sqlConnectionUpdateRequestSchema.parse(input);
  return requestJson(
    `/internal/sql-workbench/connections/${encodeURIComponent(connectionId)}`,
    {
      method: "PUT",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify(request),
      schema: sqlConnectionListSchema.element,
    },
  );
}

/**
 * @param {string} connectionId
 */
export async function deleteSqlConnection(connectionId) {
  await requestJson(
    `/internal/sql-workbench/connections/${encodeURIComponent(connectionId)}`,
    {
      method: "DELETE",
      schema: sqlConnectionDeleteResponseSchema,
    },
  );
  return connectionId;
}

/**
 * @param {string} connectionId
 */
export function probeSqlConnection(connectionId) {
  return requestJson(
    `/internal/sql-workbench/connections/${encodeURIComponent(connectionId)}/probe`,
    {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      schema: sqlConnectionProbeResultSchema,
    },
  );
}

/**
 * @param {unknown} input
 */
export function validateSqlQuery(input) {
  const request = sqlQueryRequestSchema.parse(input);
  return requestJson("/internal/sql-workbench/queries/validate", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    schema: sqlValidationReportSchema,
  });
}

/**
 * @param {unknown} input
 */
export function runReadOnlySqlQuery(input) {
  const request = sqlQueryRunRequestSchema.parse(input);
  return requestJson("/internal/sql-workbench/queries/run", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    schema: sqlQueryRunResultSchema,
  });
}

/**
 * @param {unknown} input
 */
export function commitControlledSqlDml(input) {
  const request = sqlDmlCommitRequestSchema.parse(input);
  return requestJson("/internal/sql-workbench/queries/commit", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    schema: sqlQueryRunResultSchema,
  });
}

/**
 * @param {unknown} input
 */
export function preflightControlledSqlDml(input) {
  const request = sqlQueryRequestSchema.parse(input);
  return requestJson("/internal/sql-workbench/queries/preflight", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    schema: sqlDmlPreflightResultSchema,
  });
}

/**
 * @param {unknown} input
 */
export function askSqlAssistant(input) {
  const request = sqlAssistantRequestSchema.parse(input);
  return requestJson("/internal/sql-workbench/assistant", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    schema: sqlAssistantResponseSchema,
  });
}

/**
 * @param {{resultId: string, pageToken?: string | null}} input
 */
export function readSqlResultPage(input) {
  const query = input.pageToken
    ? `?pageToken=${encodeURIComponent(input.pageToken)}`
    : "";
  return requestJson(
    `/internal/sql-workbench/results/${encodeURIComponent(input.resultId)}${query}`,
    {
      schema: sqlResultPageSchema,
    },
  );
}

/**
 * @param {{connectionId: string, schema: string}} input
 */
export function readSqlMetadata(input) {
  const query = `?schema=${encodeURIComponent(input.schema)}`;
  return requestJson(
    `/internal/sql-workbench/connections/${encodeURIComponent(input.connectionId)}/metadata${query}`,
    {
      schema: sqlMetadataResponseSchema,
    },
  );
}
