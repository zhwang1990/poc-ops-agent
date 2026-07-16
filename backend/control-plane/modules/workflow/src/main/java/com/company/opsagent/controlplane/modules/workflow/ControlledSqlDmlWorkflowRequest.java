package com.company.opsagent.controlplane.modules.workflow;

import static com.company.opsagent.contracts.ContractValues.required;

import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlCommitRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlExecutionBinding;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationReport;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import java.time.OffsetDateTime;

/** 启动受控 SQL DML 工作流所需的可信服务端输入。 */
public record ControlledSqlDmlWorkflowRequest(
    SqlDmlCommitRequest commitRequest,
    SqlDmlExecutionBinding binding,
    SqlValidationReport validation,
    OperatorContext operator,
    PolicyDecisionReference policyDecision,
    TraceContext trace) {

  public ControlledSqlDmlWorkflowRequest {
    commitRequest = required(commitRequest, "commitRequest");
    binding = required(binding, "binding");
    validation = required(validation, "validation");
    operator = required(operator, "operator");
    policyDecision = required(policyDecision, "policyDecision");
    trace = required(trace, "trace");
  }

  public String sqlHash() {
    String value = validation.sqlHash();
    return value.startsWith("sha256:") ? value.substring("sha256:".length()) : value;
  }

  SqlControlledDmlExecutionRequest toWorkerRequest(
      String workflowId,
      String executionRequestId,
      OffsetDateTime expiresAt) {
    return new SqlControlledDmlExecutionRequest(
        "1.0",
        executionRequestId,
        workflowId,
        commitRequest,
        binding,
        operator,
        policyDecision,
        trace,
        expiresAt);
  }
}
