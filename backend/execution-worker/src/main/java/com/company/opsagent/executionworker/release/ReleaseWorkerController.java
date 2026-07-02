package com.company.opsagent.executionworker.release;

import com.company.opsagent.executionworker.WorkerTransportAuthenticator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/internal/release")
public class ReleaseWorkerController {

  private static final Set<String> ALLOWED_ENVIRONMENTS = Set.of("dev", "sit", "uat");

  private final ReleaseAdapterRegistry adapterRegistry;
  private final WorkerTransportAuthenticator authenticator;
  private final Clock clock;

  public ReleaseWorkerController(
      ReleaseAdapterRegistry adapterRegistry,
      WorkerTransportAuthenticator authenticator,
      Clock workerClock) {
    this.adapterRegistry = adapterRegistry;
    this.authenticator = authenticator;
    this.clock = workerClock;
  }

  @PostMapping("/execute")
  public Mono<ReleaseWorkerResult> execute(
      @RequestHeader HttpHeaders headers,
      @RequestBody ReleaseWorkerRequest request) {
    return Mono.defer(() -> {
      authenticator.authenticateCanonical(
          headers,
          (keyId, timestamp) -> ReleaseWorkerRequestSignature.canonicalPayload(keyId, timestamp, request));
      return validateAndExecuteWithEvents(request)
          .filter(event -> event.eventType() == ReleaseWorkerExecutionEvent.EventType.RESULT)
          .map(ReleaseWorkerExecutionEvent::result)
          .last();
    }).subscribeOn(Schedulers.boundedElastic());
  }

  @PostMapping(value = "/execute/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<ReleaseWorkerExecutionEvent>> executeEvents(
      @RequestHeader HttpHeaders headers,
      @RequestBody ReleaseWorkerRequest request) {
    return Flux.defer(() -> {
      authenticator.authenticateCanonical(
          headers,
          (keyId, timestamp) -> ReleaseWorkerRequestSignature.canonicalPayload(keyId, timestamp, request));
      return validateAndExecuteWithEvents(request);
    }).map(event -> ServerSentEvent.builder(event)
        .event(event.eventType().name())
        .id(event.eventType() == ReleaseWorkerExecutionEvent.EventType.RESULT
            ? "result"
            : event.nodeId() + "-" + event.timestamp().toInstant().toEpochMilli())
        .build())
        .subscribeOn(Schedulers.boundedElastic());
  }

  private Flux<ReleaseWorkerExecutionEvent> validateAndExecuteWithEvents(ReleaseWorkerRequest request) {
    ReleaseWorkerRequest.ReleaseCommand command = request == null ? null : request.command();
    if (request == null || command == null) {
      return Flux.just(ReleaseWorkerExecutionEvent.result(
          reject(request, "RELEASE_REQUEST_INVALID", "release worker request is invalid")));
    }
    if (request.expiresAt() == null || !request.expiresAt().isAfter(OffsetDateTime.now(clock))) {
      return Flux.just(ReleaseWorkerExecutionEvent.result(
          reject(request, "RELEASE_REQUEST_EXPIRED", "release worker request is expired")));
    }
    if (!ALLOWED_ENVIRONMENTS.contains(command.targetEnvironment())) {
      return Flux.just(ReleaseWorkerExecutionEvent.result(
          reject(request, "TARGET_ENVIRONMENT_NOT_ALLOWED", "target environment is not allowed")));
    }
    if (command.nodes() == null || command.nodes().size() != 1) {
      return Flux.just(ReleaseWorkerExecutionEvent.result(
          reject(request, "RELEASE_WORKER_SINGLE_NODE_REQUIRED", "release worker request must target one node")));
    }
    ReleaseWorkerRequest.ReleaseNodeTarget node = command.nodes().get(0);
    if ("DISABLED".equals(node.managementMode())) {
      return Flux.just(ReleaseWorkerExecutionEvent.result(
          reject(request, "SERVER_MANAGEMENT_MODE_DISABLED", "server management mode is disabled")));
    }
    return adapterRegistry.find(node.managementMode())
        .map(adapter -> executeAdapterWithEvents(adapter, request))
        .orElseGet(() -> Flux.just(ReleaseWorkerExecutionEvent.result(
            reject(
                request,
                "SERVER_MANAGEMENT_MODE_NOT_CONFIGURED",
                "server management mode is not configured"))));
  }

  private Flux<ReleaseWorkerExecutionEvent> executeAdapterWithEvents(ReleaseAdapter adapter, ReleaseWorkerRequest request) {
    String operation = request.command().operation();
    return switch (operation) {
      case "PRECHECK" -> adapter.precheckWithEvents(request);
      case "DEPLOY" -> adapter.deployWithEvents(request);
      case "START" -> adapter.startWithEvents(request);
      case "STOP" -> adapter.stopWithEvents(request);
      case "ROLLBACK" -> adapter.rollbackWithEvents(request);
      case "HEALTHCHECK" -> adapter.healthcheckWithEvents(request);
      case "COLLECT_LOGS" -> adapter.collectLogsWithEvents(request);
      default -> Flux.just(ReleaseWorkerExecutionEvent.result(
          reject(request, "RELEASE_OPERATION_NOT_SUPPORTED", "release operation is not supported")));
    };
  }

  private ReleaseWorkerResult reject(ReleaseWorkerRequest request, String errorCode, String errorMessage) {
    return ReleaseWorkerResult.rejected(request, errorCode, errorMessage, clock);
  }
}
