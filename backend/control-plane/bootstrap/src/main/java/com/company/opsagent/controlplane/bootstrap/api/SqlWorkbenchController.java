package com.company.opsagent.controlplane.bootstrap.api;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionCreateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionProbeResult;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionUpdateRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlCommitRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightResult;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlAssistantResponse;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlResultPage;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationReport;
import com.company.opsagent.contracts.workflow.OperatorContext;
import com.company.opsagent.contracts.workflow.PolicyDecisionReference;
import com.company.opsagent.contracts.workflow.TraceContext;
import com.company.opsagent.controlplane.bootstrap.security.PolicyEnforcementWebFilter;
import com.company.opsagent.controlplane.modules.audit.ExecutionContext;
import com.company.opsagent.controlplane.modules.sqlworkbench.SqlWorkbenchService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 受服务端身份、策略与审计保护的 SQL 工作台入口。
 */
@RestController
@RequestMapping("/internal/sql-workbench")
public class SqlWorkbenchController {

  private static final Set<String> CONNECTION_CREATE_FIELDS = Set.of(
      "contractVersion",
      "displayName",
      "targetEnvironment",
      "platformType",
      "host",
      "port",
      "defaultSchema",
      "allowedSchemas",
      "capabilities",
      "credentialAlias",
      "maxRowsDefault",
      "timeoutSecondsDefault");

  private static final Set<String> ASSISTANT_FIELDS = Set.of(
      "contractVersion",
      "connectionId",
      "targetEnvironment",
      "schema",
      "assistantAction",
      "sql",
      "limits",
      "diagnosticContext",
      "idempotencyKey");

  private static final Set<String> DML_COMMIT_FIELDS = Set.of(
      "contractVersion",
      "query",
      "confirmation");

  private final SqlWorkbenchService sqlWorkbenchService;
  private final ObjectMapper objectMapper;

  public SqlWorkbenchController(SqlWorkbenchService sqlWorkbenchService, ObjectMapper objectMapper) {
    this.sqlWorkbenchService = sqlWorkbenchService;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/connections")
  public Mono<List<SqlConnectionSummary>> connections() {
    return blocking(sqlWorkbenchService::listConnections);
  }

  @PostMapping("/connections")
  public Mono<SqlConnectionSummary> createConnection(@RequestBody JsonNode request) {
    return blocking(() -> sqlWorkbenchService.createConnection(parseConnectionCreateRequest(request)));
  }

  @PutMapping("/connections/{connectionId}")
  public Mono<SqlConnectionSummary> updateConnection(
      @PathVariable("connectionId") String connectionId,
      @RequestBody JsonNode request) {
    return blocking(() -> sqlWorkbenchService.updateConnection(connectionId, parseConnectionUpdateRequest(request)));
  }

  @DeleteMapping("/connections/{connectionId}")
  public Mono<Void> deleteConnection(@PathVariable("connectionId") String connectionId) {
    return blockingVoid(() -> sqlWorkbenchService.deleteConnection(connectionId));
  }

  @PostMapping("/connections/{connectionId}/probe")
  public Mono<SqlConnectionProbeResult> probeConnection(@PathVariable("connectionId") String connectionId) {
    return blocking(() -> sqlWorkbenchService.probeConnection(connectionId));
  }

  @GetMapping("/connections/{connectionId}/metadata")
  public Mono<SqlDatabaseMetadata> metadata(
      @PathVariable("connectionId") String connectionId,
      @RequestParam("schema") String schema) {
    return blocking(() -> sqlWorkbenchService.readMetadata(connectionId, schema));
  }

  @PostMapping("/queries/validate")
  public Mono<SqlValidationReport> validate(@RequestBody SqlQueryRequest request) {
    return blocking(() -> sqlWorkbenchService.validate(request));
  }

  @PostMapping("/assistant")
  public Mono<SqlAssistantResponse> assist(@RequestBody JsonNode request) {
    return blocking(() -> sqlWorkbenchService.assist(parseAssistantRequest(request)));
  }

  @PostMapping("/queries/run")
  public Mono<SqlQueryExecutionResult> run(
      @RequestBody SqlQueryRequest request,
      ServerWebExchange exchange) {
    ExecutionContext context = exchange.getRequiredAttribute(
        PolicyEnforcementWebFilter.EXECUTION_CONTEXT_ATTRIBUTE);
    return blocking(() -> sqlWorkbenchService.runReadOnlyQuery(
        request,
        new OperatorContext(context.subject(), context.roles()),
        new PolicyDecisionReference(context.requestId() + ":" + context.action(), context.policyVersion(), "ALLOW"),
        new TraceContext(context.traceId(), context.requestId())));
  }

  @PostMapping("/queries/preflight")
  public Mono<SqlDmlPreflightResult> preflight(
      @RequestBody SqlQueryRequest request,
      ServerWebExchange exchange) {
    ExecutionContext context = exchange.getRequiredAttribute(
        PolicyEnforcementWebFilter.EXECUTION_CONTEXT_ATTRIBUTE);
    return blocking(() -> sqlWorkbenchService.preflightControlledDml(
        request,
        new OperatorContext(context.subject(), context.roles()),
        new PolicyDecisionReference(
            context.requestId() + ":" + context.action(),
            context.policyVersion(),
            "ALLOW"),
        new TraceContext(context.traceId(), context.requestId())));
  }

  @PostMapping("/queries/commit")
  public Mono<SqlQueryExecutionResult> commit(
      @RequestBody JsonNode request,
      ServerWebExchange exchange) {
    ExecutionContext context = exchange.getRequiredAttribute(
        PolicyEnforcementWebFilter.EXECUTION_CONTEXT_ATTRIBUTE);
    return blocking(() -> sqlWorkbenchService.commitControlledDml(
        parseDmlCommitRequest(request),
        new OperatorContext(context.subject(), context.roles()),
        new PolicyDecisionReference(context.requestId() + ":" + context.action(), context.policyVersion(), "ALLOW"),
        new TraceContext(context.traceId(), context.requestId())));
  }

  @GetMapping("/results/{resultId}")
  public Mono<SqlResultPage> result(@PathVariable("resultId") String resultId) {
    return blocking(() -> sqlWorkbenchService.readResultPage(resultId));
  }

  private static <T> Mono<T> blocking(Supplier<T> supplier) {
    return Mono.fromSupplier(supplier).subscribeOn(Schedulers.boundedElastic());
  }

  private static Mono<Void> blockingVoid(Runnable runnable) {
    return Mono.fromRunnable(runnable).subscribeOn(Schedulers.boundedElastic()).then();
  }

  private SqlConnectionCreateRequest parseConnectionCreateRequest(JsonNode request) {
    if (request == null || !request.isObject()) {
      throw new IllegalArgumentException("SQL connection create request must be a JSON object");
    }
    Iterator<String> fieldNames = request.fieldNames();
    while (fieldNames.hasNext()) {
      String fieldName = fieldNames.next();
      if (!CONNECTION_CREATE_FIELDS.contains(fieldName)) {
        throw new IllegalArgumentException("unsupported SQL connection create field: " + fieldName);
      }
    }
    try {
      return objectMapper.treeToValue(request, SqlConnectionCreateRequest.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("SQL connection create request is invalid", exception);
    }
  }

  private SqlConnectionUpdateRequest parseConnectionUpdateRequest(JsonNode request) {
    if (request == null || !request.isObject()) {
      throw new IllegalArgumentException("SQL connection update request must be a JSON object");
    }
    Iterator<String> fieldNames = request.fieldNames();
    while (fieldNames.hasNext()) {
      String fieldName = fieldNames.next();
      if (!CONNECTION_CREATE_FIELDS.contains(fieldName)) {
        throw new IllegalArgumentException("unsupported SQL connection update field: " + fieldName);
      }
    }
    try {
      return objectMapper.treeToValue(request, SqlConnectionUpdateRequest.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("SQL connection update request is invalid", exception);
    }
  }

  private SqlAssistantRequest parseAssistantRequest(JsonNode request) {
    if (request == null || !request.isObject()) {
      throw new IllegalArgumentException("SQL assistant request must be a JSON object");
    }
    Iterator<String> fieldNames = request.fieldNames();
    while (fieldNames.hasNext()) {
      String fieldName = fieldNames.next();
      if (!ASSISTANT_FIELDS.contains(fieldName)) {
        throw new IllegalArgumentException("unsupported SQL assistant field: " + fieldName);
      }
    }
    try {
      return objectMapper.treeToValue(request, SqlAssistantRequest.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("SQL assistant request is invalid", exception);
    }
  }

  private SqlDmlCommitRequest parseDmlCommitRequest(JsonNode request) {
    if (request == null || !request.isObject()) {
      throw new IllegalArgumentException("SQL DML commit request must be a JSON object");
    }
    Iterator<String> fieldNames = request.fieldNames();
    while (fieldNames.hasNext()) {
      String fieldName = fieldNames.next();
      if (!DML_COMMIT_FIELDS.contains(fieldName)) {
        throw new IllegalArgumentException("unsupported SQL DML commit field: " + fieldName);
      }
    }
    try {
      return objectMapper.treeToValue(request, SqlDmlCommitRequest.class);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException("SQL DML commit request is invalid", exception);
    }
  }
}
