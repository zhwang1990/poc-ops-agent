import {
  releaseApplicationListSchema,
  releaseApplicationSaveRequestSchema,
  releaseApplicationSchema,
  releaseArtifactListSchema,
  releaseArtifactSchema,
  releaseConnectionTestResultSchema,
  releaseCredentialRotateRequestSchema,
  releaseCredentialSummarySchema,
  releasePlanConfirmRequestSchema,
  releasePlanCreateRequestSchema,
  releasePlanListSchema,
  releasePlanSchema,
  releaseWorkflowEventSchema,
  releaseScriptProfileDefinitionListSchema,
  releaseScriptProfileDefinitionSaveRequestSchema,
  releaseScriptProfileDefinitionSchema,
  releaseServerDeleteResponseSchema,
  releaseServerListSchema,
  releaseServerSchema,
} from "../schemas/release-center-schemas.js";
import {
  ApiError,
  createApiErrorFromResponse,
  createSessionExpiredApiError,
  isLoginHtmlResponse,
  requestJson,
} from "./client.js";

export function listReleaseApplications() {
  return requestJson("/internal/release-center/applications", {
    schema: releaseApplicationListSchema,
  });
}

/**
 * @param {unknown} input
 */
export function saveReleaseApplication(input) {
  const request = releaseApplicationSaveRequestSchema.parse(input);
  return requestJson("/internal/release-center/applications", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    schema: releaseApplicationSchema,
  });
}

/**
 * @param {string} targetEnvironment
 */
export function listReleaseServers(targetEnvironment) {
  return requestJson(
    `/internal/release-center/servers?targetEnvironment=${encodeURIComponent(targetEnvironment)}`,
    { schema: releaseServerListSchema },
  );
}

/**
 * @param {unknown} input
 */
export function saveReleaseServer(input) {
  const request = releaseServerSchema.parse(input);
  return requestJson("/internal/release-center/servers", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    schema: releaseServerSchema,
  });
}

/**
 * @param {string} nodeId
 */
export function deleteReleaseServer(nodeId) {
  return requestJson(`/internal/release-center/servers/${encodeURIComponent(nodeId)}`, {
    method: "DELETE",
    schema: releaseServerDeleteResponseSchema,
  });
}

export function listReleaseScriptProfiles() {
  return requestJson("/internal/release-center/script-profiles", {
    schema: releaseScriptProfileDefinitionListSchema,
  });
}

/**
 * @param {unknown} input
 */
export function saveReleaseScriptProfile(input) {
  const request = releaseScriptProfileDefinitionSaveRequestSchema.parse(input);
  return requestJson("/internal/release-center/script-profiles", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    schema: releaseScriptProfileDefinitionSchema,
  });
}

/**
 * @param {{profileId: string}} input
 */
export function deleteReleaseScriptProfile(input) {
  return requestJson(
    `/internal/release-center/script-profiles/${encodeURIComponent(input.profileId)}`,
    {
      method: "DELETE",
      schema: releaseServerDeleteResponseSchema,
    },
  );
}

export function listReleasePlans() {
  return requestJson("/internal/release-center/plans", {
    schema: releasePlanListSchema,
  });
}

/**
 * @param {string} targetEnvironment
 */
export function listReleaseArtifacts(targetEnvironment) {
  return requestJson(
    `/internal/release-center/artifacts?targetEnvironment=${encodeURIComponent(targetEnvironment)}`,
    { schema: releaseArtifactListSchema },
  );
}

/**
 * @param {unknown} input
 */
export function createReleasePlan(input) {
  const request = releasePlanCreateRequestSchema.parse(input);
  return requestJson("/internal/release-center/plans", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    schema: releasePlanSchema,
  });
}

/**
 * @param {{releaseId: string, input: unknown}} params
 */
export function confirmReleasePlan(params) {
  const request = releasePlanConfirmRequestSchema.parse(params.input);
  return requestJson(`/internal/release-center/plans/${encodeURIComponent(params.releaseId)}/confirm`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    schema: releasePlanSchema,
  });
}

/**
 * @param {string} releaseId
 */
export function executeReleasePlan(releaseId) {
  return requestJson(`/internal/release-center/plans/${encodeURIComponent(releaseId)}/execute`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    schema: releasePlanSchema,
  });
}

/**
 * @param {string} releaseId
 * @param {{afterSequence?: number, onEvent?: (event: import("../schemas/release-center-schemas.js").ReleaseWorkflowEvent) => void, signal?: AbortSignal}} [options]
 * @returns {Promise<import("../schemas/release-center-schemas.js").ReleaseWorkflowEvent[]>}
 */
export async function streamReleasePlanEvents(releaseId, options = {}) {
  const normalizedReleaseId = normalizeReleaseId(releaseId);
  const afterSequence = normalizeAfterSequence(options.afterSequence);
  let response;
  try {
    response = await fetch(
      `/internal/release-center/plans/${encodeURIComponent(normalizedReleaseId)}/events?afterSequence=${afterSequence}`,
      {
        credentials: "include",
        headers: { Accept: "text/event-stream" },
        signal: options.signal,
      },
    );
  } catch (cause) {
    throw new ApiError({
      status: 0,
      kind: "network",
      message: "Network request failed",
      cause,
    });
  }

  if (!response.ok) {
    throw await createApiErrorFromResponse(response);
  }

  return readReleaseEventStream(response, options);
}

/**
 * @param {{applicationId: string, targetEnvironment: string, file: File}} input
 */
export function uploadTomcatWar(input) {
  const formData = new FormData();
  formData.append("applicationId", input.applicationId);
  formData.append("targetEnvironment", input.targetEnvironment);
  formData.append("file", input.file);
  return requestJson("/internal/release-center/artifacts/tomcat-war", {
    method: "POST",
    body: formData,
    schema: releaseArtifactSchema,
  });
}

/**
 * @param {unknown} input
 */
export function rotateReleaseCredential(input) {
  const request = releaseCredentialRotateRequestSchema.parse(input);
  return requestJson("/internal/release-center/credentials", {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(request),
    schema: releaseCredentialSummarySchema,
  });
}

/**
 * @param {string} nodeId
 */
export function testReleaseServer(nodeId) {
  return requestJson(`/internal/release-center/servers/${encodeURIComponent(nodeId)}/test`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    schema: releaseConnectionTestResultSchema,
  });
}

/**
 * @param {Response} response
 * @param {{onEvent?: (event: import("../schemas/release-center-schemas.js").ReleaseWorkflowEvent) => void}} options
 */
async function readReleaseEventStream(response, options) {
  if (isLoginHtmlResponse(response)) {
    throw createSessionExpiredApiError(response);
  }

  /** @type {import("../schemas/release-center-schemas.js").ReleaseWorkflowEvent[]} */
  const events = [];
  try {
    if (!response.body) {
      parseReleaseEventText(await response.text(), events, options);
      return events;
    }
    const reader = response.body.getReader();
    const decoder = new TextDecoder();
    let buffer = "";
    while (true) {
      const { done, value } = await reader.read();
      if (done) {
        break;
      }
      buffer += decoder.decode(value, { stream: true });
      buffer = consumeReleaseEventFrames(buffer, events, options);
    }
    buffer += decoder.decode();
    if (buffer.trim()) {
      consumeReleaseEventFrames(`${buffer}\n\n`, events, options);
    }
    return events;
  } catch (cause) {
    if (isAbortError(cause)) {
      throw cause;
    }
    throw new ApiError({
      status: response.status,
      kind: "contract",
      message: "Release event stream did not match the expected contract",
      cause,
    });
  }
}

/**
 * @param {string} text
 * @param {import("../schemas/release-center-schemas.js").ReleaseWorkflowEvent[]} events
 * @param {{onEvent?: (event: import("../schemas/release-center-schemas.js").ReleaseWorkflowEvent) => void}} options
 */
function parseReleaseEventText(text, events, options) {
  consumeReleaseEventFrames(text.endsWith("\n\n") ? text : `${text}\n\n`, events, options);
}

/**
 * @param {string} text
 * @param {import("../schemas/release-center-schemas.js").ReleaseWorkflowEvent[]} events
 * @param {{onEvent?: (event: import("../schemas/release-center-schemas.js").ReleaseWorkflowEvent) => void}} options
 * @returns {string}
 */
function consumeReleaseEventFrames(text, events, options) {
  let remaining = text.replace(/\r\n/g, "\n");
  let separatorIndex = remaining.indexOf("\n\n");
  while (separatorIndex !== -1) {
    const frame = remaining.slice(0, separatorIndex);
    parseReleaseEventFrame(frame, events, options);
    remaining = remaining.slice(separatorIndex + 2);
    separatorIndex = remaining.indexOf("\n\n");
  }
  return remaining;
}

/**
 * @param {string} frame
 * @param {import("../schemas/release-center-schemas.js").ReleaseWorkflowEvent[]} events
 * @param {{onEvent?: (event: import("../schemas/release-center-schemas.js").ReleaseWorkflowEvent) => void}} options
 */
function parseReleaseEventFrame(frame, events, options) {
  const data = frame
    .split(/\n/)
    .filter((line) => line.startsWith("data:"))
    .map((line) => line.slice("data:".length).trimStart())
    .join("\n");
  if (!data) {
    return;
  }
  const event = releaseWorkflowEventSchema.parse(JSON.parse(data));
  events.push(event);
  options.onEvent?.(event);
}

/**
 * @param {unknown} value
 */
function normalizeReleaseId(value) {
  if (typeof value !== "string" || !value.trim()) {
    throw new ApiError({
      status: 0,
      kind: "contract",
      message: "Release id did not match the expected contract",
    });
  }
  return value.trim();
}

/**
 * @param {unknown} value
 */
function normalizeAfterSequence(value) {
  return typeof value === "number" && Number.isInteger(value) && value >= 0 ? value : 0;
}

/**
 * @param {unknown} cause
 */
function isAbortError(cause) {
  return cause instanceof DOMException && cause.name === "AbortError";
}
