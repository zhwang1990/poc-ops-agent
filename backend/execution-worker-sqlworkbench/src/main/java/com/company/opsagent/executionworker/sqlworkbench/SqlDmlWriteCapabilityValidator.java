package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;

/**
 * 在受控 DML 前验证 Worker 本地出口目标和专用写能力。
 */
public interface SqlDmlWriteCapabilityValidator {

  void assertPreflightAllowed(SqlDmlPreflightExecutionRequest request);

  void assertCommitAllowed(SqlControlledDmlExecutionRequest request);

  static SqlDmlWriteCapabilityValidator rejecting() {
    return new SqlDmlWriteCapabilityValidator() {
      @Override
      public void assertPreflightAllowed(SqlDmlPreflightExecutionRequest request) {
        reject();
      }

      @Override
      public void assertCommitAllowed(SqlControlledDmlExecutionRequest request) {
        reject();
      }

      private void reject() {
        throw new WorkerSqlEgressException(
            "SQL_DML_WORKER_DISABLED",
            "SQL DML write capability validation is not configured");
      }
    };
  }
}
