package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.required;
import static com.company.opsagent.contracts.ContractValues.requiredText;

import java.time.OffsetDateTime;
import java.util.regex.Pattern;

/**
 * 服务端在 DML Worker 预检成功后签发的短期授权回执。
 *
 * <p>回执只保存标识和摘要，不包含 SQL、参数值、预览样本或凭据。
 */
public record SqlDmlPreflightReceipt(
    String contractVersion,
    String receiptId,
    String keyId,
    OffsetDateTime issuedAt,
    OffsetDateTime expiresAt,
    String operatorId,
    String requestHash,
    String connectionId,
    String targetEnvironment,
    String schema,
    String sqlHash,
    String parametersHash,
    String policyVersion,
    String policySelectionHash,
    String impactPreviewHash,
    String preflightHash,
    String signature) {

  private static final Pattern SHA_256_HEX = Pattern.compile("[0-9a-f]{64}");

  public SqlDmlPreflightReceipt {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    receiptId = requiredText(receiptId, "receiptId");
    keyId = requiredText(keyId, "keyId");
    issuedAt = required(issuedAt, "issuedAt");
    expiresAt = required(expiresAt, "expiresAt");
    if (!expiresAt.isAfter(issuedAt)) {
      throw new IllegalArgumentException("expiresAt must be after issuedAt");
    }
    operatorId = requiredText(operatorId, "operatorId");
    requestHash = requiredHash(requestHash, "requestHash");
    connectionId = requiredText(connectionId, "connectionId");
    targetEnvironment = requiredText(targetEnvironment, "targetEnvironment");
    schema = requiredText(schema, "schema");
    sqlHash = requiredHash(sqlHash, "sqlHash");
    parametersHash = requiredHash(parametersHash, "parametersHash");
    policyVersion = requiredText(policyVersion, "policyVersion");
    policySelectionHash = requiredHash(policySelectionHash, "policySelectionHash");
    impactPreviewHash = requiredHash(impactPreviewHash, "impactPreviewHash");
    preflightHash = requiredHash(preflightHash, "preflightHash");
    signature = requiredText(signature, "signature");
  }

  private static String requiredHash(String value, String fieldName) {
    value = requiredText(value, fieldName);
    if (!SHA_256_HEX.matcher(value).matches()) {
      throw new IllegalArgumentException(fieldName + " must be a SHA-256 hex digest");
    }
    return value;
  }
}
