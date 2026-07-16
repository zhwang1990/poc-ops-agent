package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.required;

/**
 * SQL DML 静态校验和只读影响预览的版本化结果。
 */
public record SqlDmlPreflightResult(
    String contractVersion,
    SqlValidationReport validation,
    SqlDmlImpactPreview impactPreview) {

  public SqlDmlPreflightResult {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    validation = required(validation, "validation");
    if (requiresImpactPreview(validation) && impactPreview == null) {
      throw new IllegalArgumentException("impactPreview is required for validated DML");
    }
  }

  private static boolean requiresImpactPreview(SqlValidationReport validation) {
    return validation.validationLevel() != SqlValidationLevel.REJECTED
        && (validation.statementType() == SqlStatementType.INSERT
            || validation.statementType() == SqlStatementType.UPDATE
            || validation.statementType() == SqlStatementType.DELETE);
  }
}
