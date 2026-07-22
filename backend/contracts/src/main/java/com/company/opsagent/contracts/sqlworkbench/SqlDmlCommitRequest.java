package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.required;

/**
 * Control-plane request for a P2 controlled SQL DML commit.
 */
public record SqlDmlCommitRequest(
    String contractVersion,
    SqlQueryRequest query,
    SqlDmlConfirmation confirmation,
    SqlDmlPreflightReceipt receipt) {

  public SqlDmlCommitRequest {
    if (!"1.0".equals(contractVersion) && !"1.1".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0 or 1.1");
    }
    query = required(query, "query");
    if (query.action() != SqlQueryAction.COMMIT_DML) {
      throw new IllegalArgumentException("query action must be COMMIT_DML");
    }
    if ("1.1".equals(contractVersion) && receipt == null) {
      throw new IllegalArgumentException("receipt is required for contractVersion 1.1");
    }
  }

  public SqlDmlCommitRequest(
      String contractVersion,
      SqlQueryRequest query,
      SqlDmlConfirmation confirmation) {
    this(contractVersion, query, confirmation, null);
  }
}
