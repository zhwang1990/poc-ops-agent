package com.company.opsagent.executionworker.release;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ReleaseWorkerRequestTest {

  private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

  @Test
  void deserializesCanonicalUuidExecutionRequestId() throws Exception {
    ReleaseWorkerRequest request = objectMapper.readValue(
        requestJson("550e8400-e29b-41d4-a716-446655440001"),
        ReleaseWorkerRequest.class);

    assertEquals(UUID.fromString("550e8400-e29b-41d4-a716-446655440001"), request.executionRequestId());
  }

  @Test
  void rejectsNonUuidExecutionRequestIdDuringDeserialization() {
    assertThrows(
        JsonMappingException.class,
        () -> objectMapper.readValue(requestJson("release-worker-rel-1-1"), ReleaseWorkerRequest.class));
  }

  @Test
  void rejectsMissingCommandIdempotencyKeyDuringDeserialization() {
    assertThrows(
        JsonMappingException.class,
        () -> objectMapper.readValue(requestJsonWithoutIdempotencyKey(), ReleaseWorkerRequest.class));
  }

  private String requestJson(String executionRequestId) {
    return """
        {
          "contractVersion": "1.0",
          "executionRequestId": "%s",
          "authorizedAt": "2026-07-05T10:15:30Z",
          "expiresAt": "2026-07-05T10:16:30Z",
          "command": {
            "contractVersion": "1.0",
            "releaseId": "rel-1",
            "workflowId": "550e8400-e29b-41d4-a716-446655440000",
            "idempotencyKey": "release:rel-1:node:1",
            "operation": "DEPLOY",
            "targetEnvironment": "dev",
            "applicationId": "orders",
            "artifact": {
              "artifactId": "artifact-1",
              "type": "WAR",
              "checksum": "sha256:abc123"
            },
            "nodes": [
              {
                "nodeId": "node-1",
                "serverType": "TOMCAT",
                "managementMode": "TOMCAT_WAR_UPLOAD"
              }
            ],
            "operator": {
              "operatorId": "operator-release",
              "roles": ["ROLE_ops-release"]
            },
            "policyDecision": {
              "decisionId": "decision-release",
              "policyVersion": "policy-v1",
              "decision": "ALLOW"
            },
            "trace": {
              "traceId": "trace-release",
              "requestId": "request-release"
            },
            "requestedAt": "2026-07-05T10:15:30Z"
          }
        }
        """.formatted(executionRequestId);
  }

  private String requestJsonWithoutIdempotencyKey() {
    return requestJson("550e8400-e29b-41d4-a716-446655440001")
        .replace("\"idempotencyKey\": \"release:rel-1:node:1\",\n", "");
  }
}
