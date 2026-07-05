package com.company.opsagent.executionworker.release;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class ReleaseWorkerRequestSignature {

  private static final String SIGNATURE_VERSION = "ops-agent-worker-signature-v1";

  private ReleaseWorkerRequestSignature() {
  }

  public static String canonicalPayload(String keyId, String timestamp, ReleaseWorkerRequest request) {
    requireText(keyId, "keyId");
    requireText(timestamp, "timestamp");
    if (request == null || request.command() == null) {
      throw new IllegalArgumentException("request is required");
    }
    ReleaseWorkerRequest.ReleaseCommand command = request.command();
    ReleaseWorkerRequest.ReleaseArtifactReference artifact = command.artifact();
    return String.join("\n",
        SIGNATURE_VERSION,
        "release-worker-execution-v1",
        keyId,
        timestamp,
        value(request.contractVersion()),
        value(request.executionRequestId()),
        value(request.authorizedAt()),
        value(request.expiresAt()),
        value(command.contractVersion()),
        value(command.releaseId()),
        value(command.workflowId()),
        value(command.idempotencyKey()),
        value(command.operation()),
        value(command.targetEnvironment()),
        value(command.applicationId()),
        value(artifact == null ? null : artifact.artifactId()),
        value(artifact == null ? null : artifact.type()),
        value(artifact == null ? null : artifact.checksum()),
        value(artifact == null ? null : artifact.storageKey()),
        sha256Hex(command.nodes() == null ? "" : command.nodes().toString()),
        value(command.operator() == null ? null : command.operator().operatorId()),
        command.operator() == null ? "" : String.join(",", command.operator().roles()),
        value(command.policyDecision() == null ? null : command.policyDecision().decisionId()),
        value(command.policyDecision() == null ? null : command.policyDecision().policyVersion()),
        value(command.policyDecision() == null ? null : command.policyDecision().decision()),
        value(command.trace() == null ? null : command.trace().traceId()),
        value(command.trace() == null ? null : command.trace().requestId()));
  }

  private static String sha256Hex(String value) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
      StringBuilder builder = new StringBuilder(bytes.length * 2);
      for (byte current : bytes) {
        builder.append(String.format("%02x", current));
      }
      return builder.toString();
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
  }

  private static String value(Object value) {
    return value == null ? "" : value.toString();
  }
}
