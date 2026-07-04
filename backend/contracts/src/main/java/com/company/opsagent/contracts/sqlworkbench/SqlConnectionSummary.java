package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.requiredList;
import static com.company.opsagent.contracts.ContractValues.requiredText;

import java.util.List;

/**
 * 前端可发现的受控数据库连接摘要，不包含连接串或凭据。
 */
public record SqlConnectionSummary(
    String contractVersion,
    String connectionId,
    String displayName,
    String targetEnvironment,
    String platformType,
    String host,
    int port,
    String defaultSchema,
    List<String> allowedSchemas,
    List<SqlQueryAction> capabilities,
    String credentialAlias,
    String status,
    int maxRowsDefault,
    int timeoutSecondsDefault) {

  public SqlConnectionSummary(
      String contractVersion,
      String connectionId,
      String displayName,
      String targetEnvironment,
      String platformType,
      List<String> allowedSchemas,
      List<SqlQueryAction> capabilities) {
    this(
        contractVersion,
        connectionId,
        displayName,
        targetEnvironment,
        platformType,
        "not-configured.local",
        446,
        requiredList(allowedSchemas, "allowedSchemas").getFirst(),
        allowedSchemas,
        capabilities,
        connectionId,
        "READY",
        500,
        30);
  }

  public SqlConnectionSummary {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    connectionId = requiredText(connectionId, "connectionId");
    displayName = requiredText(displayName, "displayName");
    targetEnvironment = SqlTargetEnvironments.normalize(targetEnvironment);
    platformType = SqlPlatformTypes.normalize(platformType);
    host = requiredText(host, "host");
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException("port must be between 1 and 65535");
    }
    defaultSchema = requiredText(defaultSchema, "defaultSchema");
    allowedSchemas = requiredList(allowedSchemas, "allowedSchemas");
    String normalizedDefaultSchema = defaultSchema;
    if (allowedSchemas.stream().noneMatch(schema -> schema.equalsIgnoreCase(normalizedDefaultSchema))) {
      throw new IllegalArgumentException("defaultSchema must be present in allowedSchemas");
    }
    capabilities = requiredList(capabilities, "capabilities");
    if (SqlTargetEnvironments.isProduction(targetEnvironment)
        && capabilities.stream().anyMatch(SqlConnectionSummary::isDmlCapability)) {
      throw new IllegalArgumentException("production SQL connections only allow query capabilities");
    }
    credentialAlias = requiredText(credentialAlias, "credentialAlias");
    status = requiredText(status, "status");
    if (maxRowsDefault < 1 || maxRowsDefault > 10_000) {
      throw new IllegalArgumentException("maxRowsDefault must be between 1 and 10000");
    }
    if (timeoutSecondsDefault < 1 || timeoutSecondsDefault > 300) {
      throw new IllegalArgumentException("timeoutSecondsDefault must be between 1 and 300");
    }
  }

  private static boolean isDmlCapability(SqlQueryAction action) {
    return action == SqlQueryAction.PREFLIGHT_DML || action == SqlQueryAction.COMMIT_DML;
  }
}
