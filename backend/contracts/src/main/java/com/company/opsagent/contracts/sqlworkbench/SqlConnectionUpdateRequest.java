package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.requiredList;
import static com.company.opsagent.contracts.ContractValues.requiredText;

import java.util.EnumSet;
import java.util.List;
import java.util.Set;

/**
 * SQL 工作台连接目录更新请求，仅允许非敏感元数据和 Worker 侧凭据别名。
 */
public record SqlConnectionUpdateRequest(
    String contractVersion,
    String displayName,
    String targetEnvironment,
    String platformType,
    String host,
    int port,
    String defaultSchema,
    List<String> allowedSchemas,
    List<SqlQueryAction> capabilities,
    String credentialAlias,
    int maxRowsDefault,
    int timeoutSecondsDefault) {

  private static final Set<SqlQueryAction> P1_UPDATE_CAPABILITIES = EnumSet.of(
      SqlQueryAction.VALIDATE,
      SqlQueryAction.RUN_READ_ONLY,
      SqlQueryAction.PREFLIGHT_DML,
      SqlQueryAction.COMMIT_DML);

  public SqlConnectionUpdateRequest {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    displayName = requiredText(displayName, "displayName");
    targetEnvironment = SqlTargetEnvironments.normalize(targetEnvironment);
    platformType = SqlPlatformTypes.normalize(platformType);
    host = validateHost(host);
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
    if (!P1_UPDATE_CAPABILITIES.containsAll(capabilities)) {
      throw new IllegalArgumentException("capabilities contain actions outside SQL workbench");
    }
    if (SqlTargetEnvironments.isProduction(targetEnvironment)
        && capabilities.stream().anyMatch(SqlConnectionUpdateRequest::isDmlCapability)) {
      throw new IllegalArgumentException("production SQL connections only allow query capabilities");
    }
    credentialAlias = requiredText(credentialAlias, "credentialAlias");
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

  private static String validateHost(String host) {
    String normalized = requiredText(host, "host").trim().toLowerCase();
    if (normalized.contains("://") || normalized.startsWith("jdbc:")
        || normalized.contains("@") || normalized.contains("password")
        || normalized.contains("user=")) {
      throw new IllegalArgumentException("host must not contain JDBC URLs or credential material");
    }
    return normalized;
  }
}
