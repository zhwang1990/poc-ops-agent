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
  releaseServerListSchema,
  releaseServerSchema,
} from "../schemas/release-center-schemas.js";
import { requestJson } from "./client.js";

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
