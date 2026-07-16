package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.required;

/**
 * SQL DML 静态校验和只读影响预览的版本化结果。
 */
public record SqlDmlPreflightResult(
    String contractVersion,
    SqlValidationReport validation,
    SqlDmlImpactPreview impactPreview,
    SqlDmlPreflightReceipt receipt) {

  public SqlDmlPreflightResult {
    if (!"1.0".equals(contractVersion) && !"1.1".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0 or 1.1");
    }
    validation = required(validation, "validation");
    if (requiresImpactPreview(validation) && impactPreview == null) {
      throw new IllegalArgumentException("impactPreview is required for validated DML");
    }
    if ("1.1".equals(contractVersion) && receipt == null) {
      throw new IllegalArgumentException("receipt is required for contractVersion 1.1");
    }
  }

  public SqlDmlPreflightResult(
      String contractVersion,
      SqlValidationReport validation,
      SqlDmlImpactPreview impactPreview) {
    this(contractVersion, validation, impactPreview, null);
  }

  private static boolean requiresImpactPreview(SqlValidationReport validation) {
    return validation.validationLevel() != SqlValidationLevel.REJECTED
        && (validation.statementType() == SqlStatementType.INSERT
            || validation.statementType() == SqlStatementType.UPDATE
            || validation.statementType() == SqlStatementType.DELETE);
  }
}
