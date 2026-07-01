package com.company.opsagent.executionworker.release;

import com.company.opsagent.executionworker.WorkerTransportAuthenticator;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import org.springframework.http.HttpHeaders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
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
      return validateAndExecute(request);
    }).subscribeOn(Schedulers.boundedElastic());
  }

  private Mono<ReleaseWorkerResult> validateAndExecute(ReleaseWorkerRequest request) {
    ReleaseWorkerRequest.ReleaseCommand command = request == null ? null : request.command();
    if (request == null || command == null) {
      return Mono.just(reject(request, "RELEASE_REQUEST_INVALID", "release worker request is invalid"));
    }
    if (request.expiresAt() == null || !request.expiresAt().isAfter(OffsetDateTime.now(clock))) {
      return Mono.just(reject(request, "RELEASE_REQUEST_EXPIRED", "release worker request is expired"));
    }
    if (!ALLOWED_ENVIRONMENTS.contains(command.targetEnvironment())) {
      return Mono.just(reject(request, "TARGET_ENVIRONMENT_NOT_ALLOWED", "target environment is not allowed"));
    }
    if (command.nodes() == null || command.nodes().size() != 1) {
      return Mono.just(reject(request, "RELEASE_WORKER_SINGLE_NODE_REQUIRED", "release worker request must target one node"));
    }
    ReleaseWorkerRequest.ReleaseNodeTarget node = command.nodes().get(0);
    if ("DISABLED".equals(node.managementMode())) {
      return Mono.just(reject(request, "SERVER_MANAGEMENT_MODE_DISABLED", "server management mode is disabled"));
    }
    return adapterRegistry.find(node.managementMode())
        .map(adapter -> executeAdapter(adapter, request))
        .orElseGet(() -> Mono.just(reject(
            request,
            "SERVER_MANAGEMENT_MODE_NOT_CONFIGURED",
            "server management mode is not configured")));
  }

  private Mono<ReleaseWorkerResult> executeAdapter(ReleaseAdapter adapter, ReleaseWorkerRequest request) {
    String operation = request.command().operation();
    return switch (operation) {
      case "PRECHECK" -> adapter.precheck(request);
      case "DEPLOY" -> adapter.deploy(request);
      case "START" -> adapter.start(request);
      case "STOP" -> adapter.stop(request);
      case "ROLLBACK" -> adapter.rollback(request);
      case "HEALTHCHECK" -> adapter.healthcheck(request);
      case "COLLECT_LOGS" -> adapter.collectLogs(request);
      default -> Mono.just(reject(request, "RELEASE_OPERATION_NOT_SUPPORTED", "release operation is not supported"));
    };
  }

  private ReleaseWorkerResult reject(ReleaseWorkerRequest request, String errorCode, String errorMessage) {
    return ReleaseWorkerResult.rejected(request, errorCode, errorMessage, clock);
  }
}
