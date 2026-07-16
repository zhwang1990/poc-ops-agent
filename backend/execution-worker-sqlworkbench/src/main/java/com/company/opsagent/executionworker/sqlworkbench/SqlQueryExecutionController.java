package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlImpactPreview;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;
import com.company.opsagent.contracts.sqlworkbench.SqlResultPage;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionProbeResult;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * SQL 查询 Worker 入口；阻塞 JDBC 执行被隔离到 boundedElastic。
 */
@RestController
@RequestMapping("/internal/executions/sql-query")
public class SqlQueryExecutionController {

  private final RestrictedSqlQueryExecutionWorker worker;
  private final SqlResultStore resultStore;
  private final SqlWorkerTransportAuthenticator authenticator;
  private final SqlConnectionProbeWorker probeWorker;
  private final SqlMetadataReader metadataReader;

  public SqlQueryExecutionController(
      RestrictedSqlQueryExecutionWorker worker,
      SqlResultStore resultStore,
      SqlWorkerTransportAuthenticator authenticator,
      SqlConnectionProbeWorker probeWorker,
      SqlMetadataReader metadataReader) {
    this.worker = worker;
    this.resultStore = resultStore;
    this.authenticator = authenticator;
    this.probeWorker = probeWorker;
    this.metadataReader = metadataReader;
  }

  @PostMapping
  public Mono<SqlQueryExecutionResult> execute(
      @RequestHeader HttpHeaders headers,
      @RequestBody SqlQueryExecutionRequest request) {
    return Mono.fromCallable(() -> {
      authenticator.authenticateSqlExecution(headers, request);
      return worker.execute(request);
    }).subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping("/dml-preflight")
  public Mono<SqlDmlImpactPreview> preflightDml(
      @RequestHeader HttpHeaders headers,
      @RequestBody SqlDmlPreflightExecutionRequest request) {
    return Mono.defer(() -> {
      authenticator.authenticateSqlDmlPreflight(headers, request);
      return worker.preflightDml(request);
    })
        .onErrorMap(WorkerSqlEgressException.class, this::preflightDenied)
        .subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping("/dml-commit")
  public Mono<SqlQueryExecutionResult> executeControlledDml(
      @RequestHeader HttpHeaders headers,
      @RequestBody SqlControlledDmlExecutionRequest request) {
    return Mono.fromCallable(() -> {
      authenticator.authenticateControlledSqlDml(headers, request);
      return worker.executeControlledDml(request);
    }).subscribeOn(Schedulers.boundedElastic());
  }

  @GetMapping("/results/{resultId}")
  public Mono<SqlResultPage> readResult(
      @RequestHeader HttpHeaders headers,
      @PathVariable("resultId") String resultId) {
    return Mono.fromCallable(() -> {
      authenticator.authenticateSqlResultRead(headers, resultId);
      return resultStore.find(resultId)
          .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "SQL result page is not available"));
    }).subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping("/connections/{connectionId}/probe")
  public Mono<SqlConnectionProbeResult> probeConnection(
      @RequestHeader HttpHeaders headers,
      @PathVariable("connectionId") String connectionId,
      @RequestBody SqlConnectionSummary connection) {
    return Mono.fromCallable(() -> {
      if (!connectionId.equals(connection.connectionId())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SQL connection id does not match path");
      }
      authenticator.authenticateSqlConnectionProbe(headers, connection);
      return probeWorker.probe(connection);
    }).subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping("/connections/{connectionId}/metadata")
  public Mono<SqlDatabaseMetadata> readMetadata(
      @RequestHeader HttpHeaders headers,
      @PathVariable("connectionId") String connectionId,
      @RequestParam("schema") String schema,
      @RequestBody SqlConnectionSummary connection) {
    return Mono.fromCallable(() -> {
      if (!connectionId.equals(connection.connectionId())) {
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "SQL connection id does not match path");
      }
      authenticator.authenticateSqlMetadataRead(headers, connection, schema);
      return metadataReader.read(connection, schema);
    }).subscribeOn(Schedulers.boundedElastic());
  }

  private ResponseStatusException preflightDenied(WorkerSqlEgressException exception) {
    ResponseStatusException response =
        new ResponseStatusException(HttpStatus.FORBIDDEN, exception.errorCode());
    response.getBody().setProperty("errorCode", exception.errorCode());
    return response;
  }
}
