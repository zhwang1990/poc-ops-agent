package com.company.opsagent.controlplane.modules.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlDmlImpactPreview;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightReceipt;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreviewSelection;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationReport;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.WorkerRequestSignature;
import com.company.opsagent.controlplane.modules.workflow.ControlledSqlDmlPreflightReceiptVerifier;
import com.company.opsagent.controlplane.modules.workflow.ControlledSqlDmlWorkflowRequest;
import com.company.opsagent.controlplane.modules.workflow.ControlledSqlDmlWorkflowService;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

/** 签发并验证只含摘要的服务器 DML 预检回执。 */
public final class SqlDmlPreflightReceiptService
    implements ControlledSqlDmlPreflightReceiptVerifier {

  private final SqlDmlPreflightReceiptProperties properties;
  private final Clock clock;

  public SqlDmlPreflightReceiptService(
      SqlDmlPreflightReceiptProperties properties,
      Clock clock) {
    this.properties = Objects.requireNonNull(properties, "properties");
    this.clock = Objects.requireNonNull(clock, "clock");
  }

  public boolean isAvailable() {
    return properties.isConfigured();
  }

  public SqlDmlPreflightReceipt issue(
      SqlQueryRequest request,
      SqlValidationReport validation,
      SqlDmlPreviewSelection previewSelection,
      SqlDmlImpactPreview impactPreview,
      OperatorContext operator,
      PolicyDecisionReference policyDecision) {
    requireAvailable();
    OffsetDateTime issuedAt = OffsetDateTime.now(clock);
    SqlDmlPreflightReceipt unsigned = new SqlDmlPreflightReceipt(
        "1.0",
        UUID.randomUUID().toString(),
        properties.getKeyId(),
        issuedAt,
        issuedAt.plus(properties.getTtl()),
        operator.operatorId(),
        WorkerRequestSignature.sqlDmlReceiptRequestDigest(request),
        request.connectionId(),
        request.targetEnvironment(),
        request.schema(),
        stripSha256Prefix(validation.sqlHash()),
        WorkerRequestSignature.sqlDmlParametersDigest(request.parameters()),
        policyDecision.policyVersion(),
        WorkerRequestSignature.sqlDmlPreviewSelectionDigest(previewSelection),
        WorkerRequestSignature.sqlDmlImpactPreviewDigest(impactPreview),
        "0".repeat(64),
        "pending");
    String preflightHash = WorkerRequestSignature.sqlDmlPreflightReceiptBindingDigest(unsigned);
    SqlDmlPreflightReceipt bound = new SqlDmlPreflightReceipt(
        unsigned.contractVersion(),
        unsigned.receiptId(),
        unsigned.keyId(),
        unsigned.issuedAt(),
        unsigned.expiresAt(),
        unsigned.operatorId(),
        unsigned.requestHash(),
        unsigned.connectionId(),
        unsigned.targetEnvironment(),
        unsigned.schema(),
        unsigned.sqlHash(),
        unsigned.parametersHash(),
        unsigned.policyVersion(),
        unsigned.policySelectionHash(),
        unsigned.impactPreviewHash(),
        preflightHash,
        unsigned.signature());
    return new SqlDmlPreflightReceipt(
        bound.contractVersion(),
        bound.receiptId(),
        bound.keyId(),
        bound.issuedAt(),
        bound.expiresAt(),
        bound.operatorId(),
        bound.requestHash(),
        bound.connectionId(),
        bound.targetEnvironment(),
        bound.schema(),
        bound.sqlHash(),
        bound.parametersHash(),
        bound.policyVersion(),
        bound.policySelectionHash(),
        bound.impactPreviewHash(),
        bound.preflightHash(),
        WorkerRequestSignature.sign(
            properties.getHmacSecret(),
            WorkerRequestSignature.canonicalSqlDmlPreflightReceiptPayload(
                properties.getKeyId(), bound)));
  }

  @Override
  public void verify(ControlledSqlDmlWorkflowRequest request) {
    SqlDmlPreflightReceipt receipt = request.commitRequest().receipt();
    if (receipt == null || !"1.1".equals(request.commitRequest().contractVersion())) {
      throw failure(
          "SQL_DML_PREFLIGHT_RECEIPT_REQUIRED",
          "A server-issued preflight receipt is required");
    }
    requireAvailable();
    if (!properties.getKeyId().equals(receipt.keyId())
        || !WorkerRequestSignature.matches(
            WorkerRequestSignature.sign(
                properties.getHmacSecret(),
                WorkerRequestSignature.canonicalSqlDmlPreflightReceiptPayload(
                    receipt.keyId(), receipt)),
            receipt.signature())) {
      throw invalidReceipt();
    }
    if (!receipt.expiresAt().isAfter(OffsetDateTime.now(clock))) {
      throw failure("SQL_DML_PREFLIGHT_RECEIPT_EXPIRED", "The preflight receipt has expired");
    }
    SqlQueryRequest query = request.commitRequest().query();
    if (!receipt.operatorId().equals(request.operator().operatorId())
        || !receipt.requestHash().equals(WorkerRequestSignature.sqlDmlReceiptRequestDigest(query))
        || !receipt.connectionId().equals(query.connectionId())
        || !receipt.targetEnvironment().equals(query.targetEnvironment())
        || !receipt.schema().equals(query.schema())
        || !receipt.sqlHash().equals(request.sqlHash())
        || !receipt.parametersHash().equals(
            WorkerRequestSignature.sqlDmlParametersDigest(query.parameters()))
        || !receipt.policyVersion().equals(request.policyDecision().policyVersion())
        || !receipt.policySelectionHash().equals(
            WorkerRequestSignature.sqlDmlPreviewSelectionDigest(request.previewSelection()))
        || !receipt.preflightHash().equals(
            WorkerRequestSignature.sqlDmlPreflightReceiptBindingDigest(receipt))
        || !receipt.parametersHash().equals(request.binding().parametersHash())
        || !receipt.preflightHash().equals(request.binding().preflightHash())) {
      throw invalidReceipt();
    }
  }

  private void requireAvailable() {
    if (!isAvailable()) {
      throw failure(
          "SQL_DML_PREFLIGHT_RECEIPT_UNAVAILABLE",
          "The server preflight receipt signer is not configured");
    }
  }

  private ControlledSqlDmlWorkflowService.WorkflowException invalidReceipt() {
    return failure("SQL_DML_PREFLIGHT_RECEIPT_INVALID", "The preflight receipt is invalid");
  }

  private ControlledSqlDmlWorkflowService.WorkflowException failure(String code, String message) {
    return new ControlledSqlDmlWorkflowService.WorkflowException(code, message);
  }

  private String stripSha256Prefix(String value) {
    return value.startsWith("sha256:") ? value.substring("sha256:".length()) : value;
  }
}
