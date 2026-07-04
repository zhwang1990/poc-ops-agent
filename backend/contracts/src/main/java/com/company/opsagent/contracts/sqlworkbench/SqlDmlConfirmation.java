package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.requiredList;
import static com.company.opsagent.contracts.ContractValues.requiredText;

import java.util.List;

/**
 * Operator second confirmation for controlled SQL DML risks.
 */
public record SqlDmlConfirmation(
    String contractVersion,
    String sqlHash,
    List<String> confirmedRisks,
    String confirmationCode) {

  public static final String RISK_CONFIRMATION_CODE = "CONFIRM_SQL_DML_RISK";

  public SqlDmlConfirmation {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    sqlHash = requiredText(sqlHash, "sqlHash");
    confirmedRisks = requiredList(confirmedRisks, "confirmedRisks");
    confirmationCode = requiredText(confirmationCode, "confirmationCode");
  }
}
