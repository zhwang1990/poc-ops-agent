package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.required;

/**
 * Control-plane request for a P2 controlled SQL DML commit.
 */
public record SqlDmlCommitRequest(
    String contractVersion,
    SqlQueryRequest query,
    SqlDmlConfirmation confirmation) {

  public SqlDmlCommitRequest {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    query = required(query, "query");
    if (query.action() != SqlQueryAction.COMMIT_DML) {
      throw new IllegalArgumentException("query action must be COMMIT_DML");
    }
  }
}
