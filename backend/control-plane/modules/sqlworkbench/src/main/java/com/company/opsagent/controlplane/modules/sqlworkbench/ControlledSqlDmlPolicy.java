package com.company.opsagent.controlplane.modules.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreviewSelection;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationLevel;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationReport;
import java.util.Objects;

/** 服务端受控 DML 白名单决策点。 */
public final class ControlledSqlDmlPolicy {

  private final ControlledSqlDmlProperties properties;
  private final CalciteSqlDmlAnalysis analysis;

  public ControlledSqlDmlPolicy(
      ControlledSqlDmlProperties properties,
      CalciteSqlDmlAnalysis analysis) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.analysis = Objects.requireNonNull(analysis, "analysis");
    properties.validate();
  }

  public SqlDmlPreviewSelection authorize(SqlQueryRequest request, SqlValidationReport report) {
    Objects.requireNonNull(request, "request");
    Objects.requireNonNull(report, "report");
    if (request.action() != SqlQueryAction.PREFLIGHT_DML
        && request.action() != SqlQueryAction.COMMIT_DML) {
      throw denied();
    }
    if (!properties.isEnabledFor(request.targetEnvironment())) {
      throw new SqlWorkbenchException(
          "SQL_DML_DISABLED",
          "DML execution is disabled for the target environment");
    }
    if (report.validationLevel() == SqlValidationLevel.REJECTED) {
      throw new SqlWorkbenchException(
          "SQL_DML_STATIC_ANALYSIS_REJECTED",
          "DML did not pass static validation");
    }
    CalciteSqlDmlAnalysis.DmlStatement statement = analysis.inspect(request.sql());
    if (report.statementType() != statement.statementType()
        || !isDml(statement.statementType())) {
      throw new SqlWorkbenchException(
          "SQL_DML_STATIC_ANALYSIS_REJECTED",
          "DML statement type does not match static validation");
    }
    ControlledSqlDmlProperties.Rule rule = properties.getRules().stream()
        .filter(candidate -> candidate.matchesTarget(
            request.connectionId(),
            request.schema(),
            statement))
        .filter(candidate -> statement.changedColumns().equals(candidate.canonicalChangedColumns()))
        .filter(candidate -> statement.predicateColumns().equals(candidate.canonicalPredicateColumns()))
        .filter(candidate -> statement.operators().equals(candidate.canonicalOperators()))
        .findFirst()
        .orElseThrow(ControlledSqlDmlPolicy::denied);
    statement.verifyAllowedBy(rule);
    return rule.previewSelection();
  }

  private static boolean isDml(SqlStatementType statementType) {
    return statementType == SqlStatementType.INSERT
        || statementType == SqlStatementType.UPDATE
        || statementType == SqlStatementType.DELETE;
  }

  private static SqlWorkbenchException denied() {
    return new SqlWorkbenchException("SQL_DML_POLICY_DENIED", "No matching DML policy rule");
  }
}
