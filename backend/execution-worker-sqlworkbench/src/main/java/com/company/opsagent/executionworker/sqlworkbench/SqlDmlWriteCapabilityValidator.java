package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;

/**
 * 在受控 DML 前验证 Worker 本地出口目标和专用写能力。
 */
public interface SqlDmlWriteCapabilityValidator {

  void assertPreflightAllowed(SqlDmlPreflightExecutionRequest request);

  void assertCommitAllowed(SqlControlledDmlExecutionRequest request);

  static SqlDmlWriteCapabilityValidator noOp() {
    return new SqlDmlWriteCapabilityValidator() {
      @Override
      public void assertPreflightAllowed(SqlDmlPreflightExecutionRequest request) {
      }

      @Override
      public void assertCommitAllowed(SqlControlledDmlExecutionRequest request) {
      }
    };
  }
}
