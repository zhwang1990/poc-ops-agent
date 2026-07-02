package com.company.opsagent.controlplane.bootstrap.service;

import com.company.opsagent.contracts.workflow.WorkerExecutionRequest;
import com.company.opsagent.contracts.workflow.WorkerExecutionResult;
import com.company.opsagent.contracts.workflow.WorkerRequestSignature;
import com.company.opsagent.contracts.workflow.WorkerTransportHeaders;
import com.company.opsagent.controlplane.bootstrap.config.WorkerProperties;
import com.company.opsagent.controlplane.modules.workflow.WorkerGateway;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

/**
 * 通过非阻塞 HTTP 调用独立执行 Worker 的控制面适配器。
 */
public class WebClientWorkerGateway implements WorkerGateway {

  private final WebClient webClient;
  private final WorkerProperties workerProperties;
  private final Clock clock;

  public WebClientWorkerGateway(WebClient webClient, WorkerProperties workerProperties, Clock clock) {
    this.webClient = webClient;
    this.workerProperties = workerProperties;
    this.clock = clock;
  }

  @Override
  public Mono<WorkerExecutionResult> execute(WorkerExecutionRequest request) {
    return webClient.post()
        .uri("/internal/executions/read-only")
        .contentType(MediaType.APPLICATION_JSON)
        .headers(headers -> sign(headers, request))
        .bodyValue(request)
        .retrieve()
        .bodyToMono(WorkerExecutionResult.class);
  }

  private void sign(HttpHeaders headers, WorkerExecutionRequest request) {
    WorkerProperties.TransportAuth transportAuth = workerProperties.getTransportAuth();
    if (!transportAuth.isEnabled()) {
      return;
    }
    String timestamp = OffsetDateTime.now(clock).toString();
    String keyId = requireText(transportAuth.getKeyId(), "worker transport key id");
    String sharedSecret = requireText(transportAuth.getSharedSecret(), "worker transport shared secret");
    String payload = WorkerRequestSignature.canonicalPayload(keyId, timestamp, request);
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
}
