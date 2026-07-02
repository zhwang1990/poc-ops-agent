package com.company.opsagent.controlplane.modules.release;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.List;

public sealed interface ReleaseEventPayload permits
    ReleaseEventPayload.Created,
    ReleaseEventPayload.Confirmed,
    ReleaseEventPayload.NodeStarted,
    ReleaseEventPayload.NodeLog,
    ReleaseEventPayload.NodeCompleted,
    ReleaseEventPayload.NodeFailed,
    ReleaseEventPayload.PartialFailed,
    ReleaseEventPayload.ManualInterventionRequired {

  @JsonProperty("payloadType")
  ReleaseEventType payloadType();

  record Created(
      String applicationId,
      TargetEnvironment targetEnvironment,
      String operation,
      String artifactType,
      String artifactChecksum,
      List<String> nodeIds,
      String operatorId,
      String policyDecisionId) implements ReleaseEventPayload {

    public Created {
      applicationId = ReleaseValues.requiredText(applicationId, "applicationId");
      targetEnvironment = ReleaseValues.required(targetEnvironment, "targetEnvironment");
      operation = ReleaseValues.requiredText(operation, "operation");
      artifactType = ReleaseValues.requiredText(artifactType, "artifactType");
      artifactChecksum = ReleaseValues.sha256Checksum(artifactChecksum);
      nodeIds = List.copyOf(ReleaseValues.required(nodeIds, "nodeIds"));
      operatorId = ReleaseValues.requiredText(operatorId, "operatorId");
      policyDecisionId = ReleaseValues.requiredText(policyDecisionId, "policyDecisionId");
    }

    @Override
    public ReleaseEventType payloadType() {
      return ReleaseEventType.RELEASE_CREATED;
    }
  }

  record Confirmed(
      String confirmationId,
      String confirmedBy,
      Instant confirmedAt,
      String parametersHash) implements ReleaseEventPayload {

    public Confirmed {
      confirmationId = ReleaseValues.requiredText(confirmationId, "confirmationId");
      confirmedBy = ReleaseValues.requiredText(confirmedBy, "confirmedBy");
      if (confirmedAt == null) {
        throw new IllegalArgumentException("confirmedAt is required");
      }
      parametersHash = ReleaseValues.sha256Checksum(parametersHash);
    }

    @Override
    public ReleaseEventType payloadType() {
      return ReleaseEventType.RELEASE_CONFIRMED;
    }
  }

  record NodeStarted(
      String nodeId,
      ServerType serverType,
      ManagementMode managementMode,
      Instant startedAt) implements ReleaseEventPayload {

    public NodeStarted {
      nodeId = ReleaseValues.requiredText(nodeId, "nodeId");
      serverType = ReleaseValues.required(serverType, "serverType");
      managementMode = ReleaseValues.required(managementMode, "managementMode");
      if (startedAt == null) {
        throw new IllegalArgumentException("startedAt is required");
      }
    }

    @Override
    public ReleaseEventType payloadType() {
      return ReleaseEventType.RELEASE_NODE_STARTED;
    }
  }

  record NodeLog(
      String nodeId,
      String stream,
      String message,
      Instant emittedAt) implements ReleaseEventPayload {

    public NodeLog {
      nodeId = ReleaseValues.requiredText(nodeId, "nodeId");
      stream = ReleaseValues.requiredText(stream, "stream");
      message = ReleaseValues.requiredText(message, "message");
      if (emittedAt == null) {
        throw new IllegalArgumentException("emittedAt is required");
      }
    }

    @Override
    public ReleaseEventType payloadType() {
      return ReleaseEventType.RELEASE_NODE_LOG;
    }
  }

  record NodeCompleted(
      String nodeId,
      String status,
      Instant completedAt) implements ReleaseEventPayload {

    public NodeCompleted {
      nodeId = ReleaseValues.requiredText(nodeId, "nodeId");
      status = ReleaseValues.requiredText(status, "status");
      if (completedAt == null) {
        throw new IllegalArgumentException("completedAt is required");
      }
    }

    @Override
    public ReleaseEventType payloadType() {
      return ReleaseEventType.RELEASE_NODE_COMPLETED;
    }
  }

  record NodeFailed(
      String nodeId,
      String errorCode,
      String message,
      Instant failedAt) implements ReleaseEventPayload {

    public NodeFailed {
      nodeId = ReleaseValues.requiredText(nodeId, "nodeId");
      errorCode = ReleaseValues.requiredText(errorCode, "errorCode");
      message = ReleaseValues.requiredText(message, "message");
      if (failedAt == null) {
        throw new IllegalArgumentException("failedAt is required");
      }
    }

    @Override
    public ReleaseEventType payloadType() {
      return ReleaseEventType.RELEASE_NODE_FAILED;
    }
  }

  record PartialFailed(
      String failedNodeId,
      List<String> completedNodeIds,
      String message) implements ReleaseEventPayload {

    public PartialFailed {
      failedNodeId = ReleaseValues.requiredText(failedNodeId, "failedNodeId");
      completedNodeIds = List.copyOf(ReleaseValues.required(completedNodeIds, "completedNodeIds"));
      message = ReleaseValues.requiredText(message, "message");
    }

    @Override
    public ReleaseEventType payloadType() {
      return ReleaseEventType.RELEASE_PARTIAL_FAILED;
    }
  }

  record ManualInterventionRequired(
      String reason,
      String lastCompletedStep,
      String failedStep,
      String recommendedAction) implements ReleaseEventPayload {

    public ManualInterventionRequired {
      reason = ReleaseValues.requiredText(reason, "reason");
      lastCompletedStep = ReleaseValues.optionalText(lastCompletedStep);
      failedStep = ReleaseValues.optionalText(failedStep);
      recommendedAction = ReleaseValues.optionalText(recommendedAction);
    }

    @Override
    public ReleaseEventType payloadType() {
      return ReleaseEventType.RELEASE_MANUAL_INTERVENTION_REQUIRED;
    }
  }
}
