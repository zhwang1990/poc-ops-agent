package com.company.opsagent.controlplane.bootstrap.service;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionProbeResult;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;
import com.company.opsagent.contracts.sqlworkbench.SqlResultPage;
import com.company.opsagent.contracts.workflow.WorkerRequestSignature;
import com.company.opsagent.contracts.workflow.WorkerTransportHeaders;
import com.company.opsagent.controlplane.bootstrap.config.WorkerProperties;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlWorkbenchWorkerClient;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import org.springframework.web.server.ResponseStatusException;

/**
 * 控制面到 SQL 工作台 Worker 的 HTTP 适配器。
 */
public class WebClientSqlWorkbenchWorkerClient implements SqlWorkbenchWorkerClient {

  private static final Duration DEFAULT_WORKER_CALL_TIMEOUT = Duration.ofSeconds(35);
  private static final Duration EXECUTION_TIMEOUT_PADDING = Duration.ofSeconds(5);

  private final WebClient webClient;
  private final WorkerProperties workerProperties;
  private final Clock clock;

  public WebClientSqlWorkbenchWorkerClient(WebClient webClient, WorkerProperties workerProperties, Clock clock) {
    this.webClient = webClient;
    this.workerProperties = workerProperties;
    this.clock = clock;
  }

  @Override
  public SqlConnectionProbeResult probe(SqlConnectionSummary connection) {
    try {
      return webClient.post()
          .uri("/internal/executions/sql-query/connections/{connectionId}/probe", connection.connectionId())
          .headers(headers -> signSqlConnectionProbe(headers, connection))
          .bodyValue(connection)
          .retrieve()
          .onStatus(HttpStatusCode::is4xxClientError, response -> sqlWorkerRejected(response.statusCode()))
          .onStatus(HttpStatusCode::is5xxServerError, response -> sqlWorkerFailed())
          .bodyToMono(SqlConnectionProbeResult.class)
          .block(DEFAULT_WORKER_CALL_TIMEOUT);
    } catch (RuntimeException exception) {
      return probeFailed(connection);
    }
  }

  @Override
  public SqlQueryExecutionResult execute(SqlQueryExecutionRequest request) {
    return webClient.post()
        .uri("/internal/executions/sql-query")
        .headers(headers -> signSqlExecution(headers, request))
        .bodyValue(request)
        .retrieve()
        .onStatus(HttpStatusCode::is4xxClientError, response -> sqlWorkerRejected(response.statusCode()))
        .onStatus(HttpStatusCode::is5xxServerError, response -> sqlWorkerFailed())
        .bodyToMono(SqlQueryExecutionResult.class)
        .block(Duration.ofSeconds(request.query().limits().timeoutSeconds())
            .plus(EXECUTION_TIMEOUT_PADDING));
  }

  @Override
  public SqlResultPage readResultPage(String resultId) {
    return webClient.get()
        .uri("/internal/executions/sql-query/results/{resultId}", resultId)
        .headers(headers -> signSqlResultRead(headers, resultId))
        .retrieve()
        .onStatus(HttpStatusCode::is4xxClientError, response -> sqlWorkerRejected(response.statusCode()))
        .onStatus(HttpStatusCode::is5xxServerError, response -> sqlWorkerFailed())
        .bodyToMono(SqlResultPage.class)
        .block(DEFAULT_WORKER_CALL_TIMEOUT);
  }

  @Override
  public SqlDatabaseMetadata readMetadata(SqlConnectionSummary connection, String schema) {
    return webClient.post()
        .uri(uriBuilder -> uriBuilder
            .path("/internal/executions/sql-query/connections/{connectionId}/metadata")
            .queryParam("schema", schema)
            .build(connection.connectionId()))
        .headers(headers -> signSqlMetadataRead(headers, connection, schema))
        .bodyValue(connection)
        .retrieve()
        .onStatus(HttpStatusCode::is4xxClientError, response -> sqlWorkerRejected(response.statusCode()))
        .onStatus(HttpStatusCode::is5xxServerError, response -> sqlWorkerFailed())
        .bodyToMono(SqlDatabaseMetadata.class)
        .block(DEFAULT_WORKER_CALL_TIMEOUT);
  }

  private void signSqlExecution(HttpHeaders headers, SqlQueryExecutionRequest request) {
    WorkerProperties.TransportAuth transportAuth = workerProperties.getTransportAuth();
    if (!transportAuth.isEnabled()) {
      return;
    }
    String timestamp = OffsetDateTime.now(clock).toString();
    String keyId = requireText(transportAuth.getKeyId(), "worker transport key id");
    String sharedSecret = requireText(transportAuth.getSharedSecret(), "worker transport shared secret");
    String payload = WorkerRequestSignature.canonicalSqlPayload(keyId, timestamp, request);
    headers.set(WorkerTransportHeaders.KEY_ID, keyId);
    headers.set(WorkerTransportHeaders.TIMESTAMP, timestamp);
    headers.set(WorkerTransportHeaders.SIGNATURE, WorkerRequestSignature.sign(sharedSecret, payload));
  }

  private void signSqlConnectionProbe(HttpHeaders headers, SqlConnectionSummary connection) {
    WorkerProperties.TransportAuth transportAuth = workerProperties.getTransportAuth();
    if (!transportAuth.isEnabled()) {
      return;
    }
    String timestamp = OffsetDateTime.now(clock).toString();
    String keyId = requireText(transportAuth.getKeyId(), "worker transport key id");
    String sharedSecret = requireText(transportAuth.getSharedSecret(), "worker transport shared secret");
    String payload = WorkerRequestSignature.canonicalSqlConnectionProbePayload(keyId, timestamp, connection);
    headers.set(WorkerTransportHeaders.KEY_ID, keyId);
    headers.set(WorkerTransportHeaders.TIMESTAMP, timestamp);
    headers.set(WorkerTransportHeaders.SIGNATURE, WorkerRequestSignature.sign(sharedSecret, payload));
  }

  private void signSqlResultRead(HttpHeaders headers, String resultId) {
    WorkerProperties.TransportAuth transportAuth = workerProperties.getTransportAuth();
    if (!transportAuth.isEnabled()) {
      return;
    }
    String timestamp = OffsetDateTime.now(clock).toString();
    String keyId = requireText(transportAuth.getKeyId(), "worker transport key id");
    String sharedSecret = requireText(transportAuth.getSharedSecret(), "worker transport shared secret");
    String payload = WorkerRequestSignature.canonicalSqlResultReadPayload(keyId, timestamp, resultId);
    headers.set(WorkerTransportHeaders.KEY_ID, keyId);
    headers.set(WorkerTransportHeaders.TIMESTAMP, timestamp);
    headers.set(WorkerTransportHeaders.SIGNATURE, WorkerRequestSignature.sign(sharedSecret, payload));
  }

  private void signSqlMetadataRead(HttpHeaders headers, SqlConnectionSummary connection, String schema) {
    WorkerProperties.TransportAuth transportAuth = workerProperties.getTransportAuth();
    if (!transportAuth.isEnabled()) {
      return;
    }
    String timestamp = OffsetDateTime.now(clock).toString();
    String keyId = requireText(transportAuth.getKeyId(), "worker transport key id");
    String sharedSecret = requireText(transportAuth.getSharedSecret(), "worker transport shared secret");
    String payload = WorkerRequestSignature.canonicalSqlMetadataPayload(keyId, timestamp, connection, schema);
    headers.set(WorkerTransportHeaders.KEY_ID, keyId);
    headers.set(WorkerTransportHeaders.TIMESTAMP, timestamp);
    headers.set(WorkerTransportHeaders.SIGNATURE, WorkerRequestSignature.sign(sharedSecret, payload));
  }

  private String requireText(String value, String name) {
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(name + " is required when worker transport auth is enabled");
    }
    return value;
  }

  private Mono<? extends Throwable> sqlWorkerRejected(HttpStatusCode statusCode) {
    return Mono.error(new ResponseStatusException(statusCode, "SQL worker request was rejected"));
  }

  private Mono<? extends Throwable> sqlWorkerFailed() {
    return Mono.error(new IllegalStateException("SQL worker request failed"));
  }

  private SqlConnectionProbeResult probeFailed(SqlConnectionSummary connection) {
    return new SqlConnectionProbeResult(
        "1.0",
        connection.connectionId(),
        "PROBE_FAILED",
        "SQL worker probe failed",
        OffsetDateTime.now(clock));
  }
}
