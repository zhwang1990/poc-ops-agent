package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.required;
import static com.company.opsagent.contracts.ContractValues.requiredText;

import java.util.List;

/**
 * SQL 工作台提交给控制面的版本化查询请求。
 */
public record SqlQueryRequest(
    String contractVersion,
    String connectionId,
    String targetEnvironment,
    String schema,
    SqlQueryAction action,
    String sql,
    List<SqlTypedParameter> parameters,
    SqlQueryLimits limits,
    String idempotencyKey) {

  public SqlQueryRequest {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    connectionId = requiredText(connectionId, "connectionId");
    targetEnvironment = SqlTargetEnvironments.normalize(targetEnvironment);
    schema = requiredText(schema, "schema");
    action = required(action, "action");
    if (SqlTargetEnvironments.isProduction(targetEnvironment)
        && (action == SqlQueryAction.PREFLIGHT_DML || action == SqlQueryAction.COMMIT_DML)) {
      throw new IllegalArgumentException("production SQL workbench requests only allow query actions");
    }
    sql = requiredText(sql, "sql");
    parameters = parameters == null ? List.of() : List.copyOf(parameters);
    limits = required(limits, "limits");
    idempotencyKey = requiredText(idempotencyKey, "idempotencyKey");
  }
}
