package com.company.opsagent.executionworker.release;

import com.company.opsagent.executionworker.WorkerHttpEgressException;
import com.company.opsagent.executionworker.WorkerHttpEgressPolicy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import reactor.core.publisher.Mono;

public class LibertyHttpsReleaseAdapter implements ReleaseAdapter {

  private final URI baseUri;
  private final String credentialAlias;
  private final HttpClient httpClient;
  private final ObjectMapper objectMapper;
  private final WorkerHttpEgressPolicy egressPolicy;
  private final Clock clock;

  public LibertyHttpsReleaseAdapter(
      String baseUrl,
      String credentialAlias,
      HttpClient httpClient,
      ObjectMapper objectMapper,
      WorkerHttpEgressPolicy egressPolicy,
      Clock clock) {
    this.baseUri = normalizedBaseUri(baseUrl);
    this.credentialAlias = requiredText(credentialAlias, "credentialAlias");
    this.httpClient = httpClient;
    this.objectMapper = objectMapper;
    this.egressPolicy = egressPolicy;
    this.clock = clock;
  }

  @Override
  public String managementMode() {
    return "LIBERTY_HTTPS";
  }

  @Override
  public Mono<ReleaseWorkerResult> precheck(ReleaseWorkerRequest request) {
    return call("/precheck", request);
  }

  @Override
  public Mono<ReleaseWorkerResult> deploy(ReleaseWorkerRequest request) {
    return call("/deploy", request);
  }

  @Override
  public Mono<ReleaseWorkerResult> start(ReleaseWorkerRequest request) {
    return call("/start", request);
  }

  @Override
  public Mono<ReleaseWorkerResult> stop(ReleaseWorkerRequest request) {
    return call("/stop", request);
  }

  @Override
  public Mono<ReleaseWorkerResult> rollback(ReleaseWorkerRequest request) {
    return call("/rollback", request);
  }

  @Override
  public Mono<ReleaseWorkerResult> healthcheck(ReleaseWorkerRequest request) {
    return call("/health", request);
  }

  @Override
  public Mono<ReleaseWorkerResult> collectLogs(ReleaseWorkerRequest request) {
    return call("/logs", request);
  }

  private Mono<ReleaseWorkerResult> call(String endpointPath, ReleaseWorkerRequest request) {
    return Mono.fromCallable(() -> {
      URI endpoint = endpoint(endpointPath);
      egressPolicy.validate(endpoint);
      HttpResponse<String> response = httpClient.send(
          request(endpoint, request),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() >= 200 && response.statusCode() < 300) {
        return ReleaseWorkerResult.succeeded(request, clock);
      }
      return ReleaseWorkerResult.failed(
          request,
          "LIBERTY_HTTPS_REQUEST_FAILED",
          "Liberty HTTPS release service returned non-success status",
          clock);
    }).onErrorResume(WorkerHttpEgressException.class, exception -> Mono.just(ReleaseWorkerResult.rejected(
        request,
        exception.errorCode(),
        exception.safeMessage(),
        clock))).onErrorResume(IOException.class, exception -> Mono.just(ReleaseWorkerResult.failed(
            request,
            "LIBERTY_HTTPS_REQUEST_FAILED",
            "Liberty HTTPS release service could not be reached",
            clock))).onErrorResume(InterruptedException.class, exception -> {
              Thread.currentThread().interrupt();
              return Mono.just(ReleaseWorkerResult.failed(
                  request,
                  "LIBERTY_HTTPS_REQUEST_INTERRUPTED",
                  "Liberty HTTPS release request was interrupted",
                  clock));
            });
  }

  private HttpRequest request(URI endpoint, ReleaseWorkerRequest request) throws IOException {
    ObjectNode body = objectMapper.createObjectNode();
    body.put("releaseId", request.command().releaseId());
    body.put("workflowId", request.command().workflowId());
    body.put("operation", request.command().operation());
    body.put("applicationId", request.command().applicationId());
    body.put("artifactId", request.command().artifact().artifactId());
    body.put("nodeId", request.command().nodes().get(0).nodeId());
    body.put("credentialAlias", credentialAlias);
    return HttpRequest.newBuilder(endpoint)
        .header("Accept", "application/json")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body), StandardCharsets.UTF_8))
        .build();
  }

  private URI endpoint(String endpointPath) {
    String base = baseUri.toASCIIString();
    if (base.endsWith("/")) {
      base = base.substring(0, base.length() - 1);
    }
    return URI.create(base + endpointPath);
  }

  private URI normalizedBaseUri(String baseUrl) {
    URI uri = URI.create(requiredText(baseUrl, "baseUrl"));
    if (uri.getRawQuery() != null || uri.getFragment() != null || uri.getUserInfo() != null) {
      throw new IllegalArgumentException("baseUrl must not contain query, fragment or user info");
    }
    String scheme = uri.getScheme();
    if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
      throw new IllegalArgumentException("baseUrl must be http or https");
    }
    if (uri.getHost() == null || uri.getHost().isBlank()) {
      throw new IllegalArgumentException("baseUrl host is required");
    }
    return uri;
  }

  private String requiredText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value.trim();
  }
}
