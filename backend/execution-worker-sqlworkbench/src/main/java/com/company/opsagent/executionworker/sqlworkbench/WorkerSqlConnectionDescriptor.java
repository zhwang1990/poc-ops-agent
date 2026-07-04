package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlTargetEnvironments;
import java.util.Set;

/**
 * Worker-local SQL connection metadata. This stores no cleartext credentials.
 */
public record WorkerSqlConnectionDescriptor(
    String connectionId,
    String targetEnvironment,
    String platformType,
    String host,
    int port,
    String credentialAlias,
    String username,
    boolean enabled) {

  private static final Set<String> SUPPORTED_PLATFORM_TYPES = Set.of("DB2_FOR_I", "H2", "MYSQL");

  public WorkerSqlConnectionDescriptor(
      String connectionId,
      String targetEnvironment,
      String host,
      int port,
      String credentialAlias,
      boolean enabled) {
    this(connectionId, targetEnvironment, "DB2_FOR_I", host, port, credentialAlias, credentialAlias, enabled);
  }

  public WorkerSqlConnectionDescriptor(
      String connectionId,
      String targetEnvironment,
      String host,
      int port,
      String credentialAlias,
      String username,
      boolean enabled) {
    this(connectionId, targetEnvironment, "DB2_FOR_I", host, port, credentialAlias, username, enabled);
  }

  public WorkerSqlConnectionDescriptor {
    connectionId = requiredText(connectionId, "connectionId");
    targetEnvironment = SqlTargetEnvironments.normalize(targetEnvironment);
    platformType = requiredText(platformType, "platformType").toUpperCase();
    if (!SUPPORTED_PLATFORM_TYPES.contains(platformType)) {
      throw new IllegalArgumentException("platformType must be one of DB2_FOR_I, H2, MYSQL");
    }
    host = requiredText(host, "host").toLowerCase();
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port must be between 1 and 65535");
    }
    credentialAlias = requiredText(credentialAlias, "credentialAlias");
    username = requiredText(username, "username");
  }

  public WorkerSqlEgressTarget target() {
    return new WorkerSqlEgressTarget(host, port);
  }

  private static String requiredText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }
}
