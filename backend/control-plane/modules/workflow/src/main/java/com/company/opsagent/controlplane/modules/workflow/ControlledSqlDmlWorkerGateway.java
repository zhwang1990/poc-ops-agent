package com.company.opsagent.controlplane.modules.workflow;

import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;

/** M05 向受限 M07 Worker 提交受控 SQL DML 的端口。 */
public interface ControlledSqlDmlWorkerGateway {

  SqlQueryExecutionResult execute(SqlControlledDmlExecutionRequest request);
}
