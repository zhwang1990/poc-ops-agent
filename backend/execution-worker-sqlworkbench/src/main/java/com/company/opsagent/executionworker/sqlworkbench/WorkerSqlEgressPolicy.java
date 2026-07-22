package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlTargetEnvironments;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Worker 本地 SQL 出口策略，只验证连接目录和网络目标 allowlist，不执行授权决策。
 */
public final class WorkerSqlEgressPolicy {

  private final Map<String, WorkerSqlConnectionDescriptor> descriptorsByConnectionId;
  private final Set<WorkerSqlEgressTarget> allowedTargets;

  public WorkerSqlEgressPolicy(
      List<WorkerSqlConnectionDescriptor> descriptors,
      List<WorkerSqlEgressTarget> allowedTargets) {
    this.descriptorsByConnectionId = indexDescriptors(descriptors);
    this.allowedTargets = Set.copyOf(allowedTargets);
  }

  public WorkerSqlConnectionDescriptor validate(SqlQueryExecutionRequest request) {
    return validateConnection(request.query().connectionId(), request.query().targetEnvironment());
  }

  public WorkerSqlConnectionDescriptor validateDmlConnection(
      String connectionId,
      String targetEnvironment) {
    return validateConnection(connectionId, targetEnvironment);
  }

  private WorkerSqlConnectionDescriptor validateConnection(
      String connectionId,
      String targetEnvironment) {
    WorkerSqlConnectionDescriptor descriptor = descriptorsByConnectionId.get(connectionId);
    if (descriptor == null) {
      throw rejected("SQL_CONNECTION_NOT_FOUND", "SQL connection is not configured for this worker");
    }
    if (!descriptor.enabled()) {
      throw rejected("SQL_CONNECTION_DISABLED", "SQL connection is disabled for this worker");
    }
    if (!SqlTargetEnvironments.same(descriptor.targetEnvironment(), targetEnvironment)) {
      throw rejected("SQL_ENVIRONMENT_MISMATCH", "SQL connection does not match the requested environment");
    }
    if (!allowedTargets.contains(descriptor.target())) {
      throw rejected("SQL_EGRESS_NOT_ALLOWED", "SQL egress target is not allowed for this worker");
    }
    return descriptor;
  }

  public WorkerSqlConnectionDescriptor validate(SqlConnectionSummary connection) {
    WorkerSqlConnectionDescriptor descriptor = descriptorsByConnectionId.get(connection.connectionId());
    if (descriptor == null) {
      throw rejected("SQL_CONNECTION_NOT_FOUND", "SQL connection is not configured for this worker");
    }
    if (!descriptor.enabled()) {
      throw rejected("SQL_CONNECTION_DISABLED", "SQL connection is disabled for this worker");
    }
    if (!SqlTargetEnvironments.same(descriptor.targetEnvironment(), connection.targetEnvironment())) {
      throw rejected("SQL_ENVIRONMENT_MISMATCH", "SQL connection does not match the requested environment");
    }
    if (!descriptor.host().equalsIgnoreCase(connection.host()) || descriptor.port() != connection.port()) {
      throw rejected("SQL_CONNECTION_METADATA_MISMATCH", "SQL connection metadata does not match worker binding");
    }
    if (!descriptor.platformType().equalsIgnoreCase(connection.platformType())) {
      throw rejected("SQL_CONNECTION_METADATA_MISMATCH", "SQL connection metadata does not match worker binding");
    }
    if (!descriptor.credentialAlias().equals(connection.credentialAlias())) {
      throw rejected("SQL_CREDENTIAL_ALIAS_MISMATCH", "SQL credential alias does not match worker binding");
    }
    if (!allowedTargets.contains(descriptor.target())) {
      throw rejected("SQL_EGRESS_NOT_ALLOWED", "SQL egress target is not allowed for this worker");
    }
    return descriptor;
  }

  private static Map<String, WorkerSqlConnectionDescriptor> indexDescriptors(
      List<WorkerSqlConnectionDescriptor> descriptors) {
    return descriptors.stream()
        .collect(Collectors.toMap(
            WorkerSqlConnectionDescriptor::connectionId,
            descriptor -> descriptor,
            (left, right) -> {
              throw new IllegalArgumentException("connectionId must be unique");
            },
            LinkedHashMap::new));
  }

  private static WorkerSqlEgressException rejected(String errorCode, String safeMessage) {
    return new WorkerSqlEgressException(errorCode, safeMessage);
  }
}
