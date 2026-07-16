package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.workflow.WorkerRequestSignature;
import com.company.opsagent.contracts.workflow.WorkerTransportHeaders;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * SQL Worker HTTP 边界的应用层传输认证器。
 */
public class SqlWorkerTransportAuthenticator {

  private final SqlWorkerTransportAuthProperties properties;
  private final Clock clock;

  public SqlWorkerTransportAuthenticator(SqlWorkerTransportAuthProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  public void authenticateSqlExecution(HttpHeaders headers, SqlQueryExecutionRequest request) {
    if (!properties.isEnabled()) {
      return;
    }
    String keyId = acceptedKeyId(headers);
    String timestamp = acceptedTimestamp(headers);
    String payload = WorkerRequestSignature.canonicalSqlPayload(keyId, timestamp, request);
    assertSignature(headers, payload);
  }

  public void authenticateSqlDmlPreflight(
      HttpHeaders headers,
      SqlDmlPreflightExecutionRequest request) {
    if (!properties.isEnabled()) {
      return;
    }
    String keyId = acceptedKeyId(headers);
    String timestamp = acceptedTimestamp(headers);
    String payload = WorkerRequestSignature.canonicalSqlDmlPreflightPayload(keyId, timestamp, request);
    assertSignature(headers, payload);
  }

  public void authenticateControlledSqlDml(
      HttpHeaders headers,
      SqlControlledDmlExecutionRequest request) {
    if (!properties.isEnabled()) {
      return;
    }
    String keyId = acceptedKeyId(headers);
    String timestamp = acceptedTimestamp(headers);
    String payload = WorkerRequestSignature.canonicalControlledSqlDmlPayload(keyId, timestamp, request);
    assertSignature(headers, payload);
  }

  public void authenticateSqlResultRead(HttpHeaders headers, String resultId) {
    if (!properties.isEnabled()) {
      return;
    }
    String keyId = acceptedKeyId(headers);
    String timestamp = acceptedTimestamp(headers);
    String payload = WorkerRequestSignature.canonicalSqlResultReadPayload(keyId, timestamp, resultId);
    assertSignature(headers, payload);
  }

  public void authenticateSqlConnectionProbe(HttpHeaders headers, SqlConnectionSummary connection) {
    if (!properties.isEnabled()) {
      return;
    }
    String keyId = acceptedKeyId(headers);
    String timestamp = acceptedTimestamp(headers);
    String payload = WorkerRequestSignature.canonicalSqlConnectionProbePayload(keyId, timestamp, connection);
    assertSignature(headers, payload);
  }

  public void authenticateSqlMetadataRead(HttpHeaders headers, SqlConnectionSummary connection, String schema) {
    if (!properties.isEnabled()) {
      return;
    }
    String keyId = acceptedKeyId(headers);
    String timestamp = acceptedTimestamp(headers);
    String payload = WorkerRequestSignature.canonicalSqlMetadataPayload(keyId, timestamp, connection, schema);
    assertSignature(headers, payload);
  }

  private String acceptedKeyId(HttpHeaders headers) {
    ensureConfigured();
    String keyId = requireHeader(headers, WorkerTransportHeaders.KEY_ID);
    if (!properties.getKeyId().equals(keyId)) {
      reject("worker transport key id is not accepted");
    }
    return keyId;
  }

  private String acceptedTimestamp(HttpHeaders headers) {
    String timestamp = requireHeader(headers, WorkerTransportHeaders.TIMESTAMP);
    OffsetDateTime signedAt = parseTimestamp(timestamp);
    Duration skew = Duration.between(signedAt.toInstant(), OffsetDateTime.now(clock).toInstant()).abs();
    if (skew.compareTo(properties.getMaxClockSkew()) > 0) {
      reject("worker transport signature timestamp is outside allowed skew");
    }
    return timestamp;
  }

  private void assertSignature(HttpHeaders headers, String canonicalPayload) {
    String signature = requireHeader(headers, WorkerTransportHeaders.SIGNATURE);
    String expected = WorkerRequestSignature.sign(properties.getSharedSecret(), canonicalPayload);
    if (!WorkerRequestSignature.matches(expected, signature)) {
      reject("worker transport signature is invalid");
    }
  }

  private void ensureConfigured() {
    if (isBlank(properties.getKeyId()) || isBlank(properties.getSharedSecret())) {
      throw new IllegalStateException("worker transport auth is enabled but key id or shared secret is missing");
    }
  }

  private String requireHeader(HttpHeaders headers, String name) {
    String value = headers.getFirst(name);
    if (isBlank(value)) {
      reject("worker transport authentication header is missing: " + name);
    }
    return value;
  }

  private OffsetDateTime parseTimestamp(String timestamp) {
    try {
      return OffsetDateTime.parse(timestamp);
    } catch (DateTimeParseException exception) {
      reject("worker transport timestamp is invalid");
      throw exception;
    }
  }

  private void reject(String reason) {
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, reason);
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
