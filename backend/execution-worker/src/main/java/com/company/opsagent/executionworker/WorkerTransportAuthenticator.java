package com.company.opsagent.executionworker;

import com.company.opsagent.contracts.workflow.WorkerExecutionRequest;
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
 * Worker HTTP 边界的应用层传输认证器。
 *
 * <p>该组件只验证请求确实来自持有共享密钥的控制面，不做授权决策，也不替代网络隔离或 mTLS。
 */
public class WorkerTransportAuthenticator {

  private final WorkerTransportAuthProperties properties;
  private final Clock clock;

  public WorkerTransportAuthenticator(WorkerTransportAuthProperties properties, Clock clock) {
    this.properties = properties;
    this.clock = clock;
  }

  /**
   * 验证请求头签名；认证关闭时仅用于本地回环开发。
   */
  public void authenticate(HttpHeaders headers, WorkerExecutionRequest request) {
    authenticateCanonical(headers, (keyId, timestamp) -> WorkerRequestSignature.canonicalPayload(keyId, timestamp, request));
  }

  public void authenticateCanonical(HttpHeaders headers, CanonicalPayloadFactory canonicalPayloadFactory) {
    if (!properties.isEnabled()) {
      return;
    }
    ensureConfigured();
    if (canonicalPayloadFactory == null) {
      throw new IllegalArgumentException("canonicalPayloadFactory is required");
    }
    String keyId = requireHeader(headers, WorkerTransportHeaders.KEY_ID);
    String timestamp = requireHeader(headers, WorkerTransportHeaders.TIMESTAMP);
    String signature = requireHeader(headers, WorkerTransportHeaders.SIGNATURE);
    if (!properties.getKeyId().equals(keyId)) {
      reject("worker transport key id is not accepted");
    }
    OffsetDateTime signedAt = parseTimestamp(timestamp);
    Duration skew = Duration.between(signedAt.toInstant(), OffsetDateTime.now(clock).toInstant()).abs();
    if (skew.compareTo(properties.getMaxClockSkew()) > 0) {
      reject("worker transport signature timestamp is outside allowed skew");
    }
    String payload = canonicalPayloadFactory.create(keyId, timestamp);
    String expected = WorkerRequestSignature.sign(properties.getSharedSecret(), payload);
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

  @FunctionalInterface
  public interface CanonicalPayloadFactory {
    String create(String keyId, String timestamp);
  }
}
