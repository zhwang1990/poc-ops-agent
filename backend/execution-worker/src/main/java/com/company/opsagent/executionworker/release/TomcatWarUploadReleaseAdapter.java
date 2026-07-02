package com.company.opsagent.executionworker.release;

import com.company.opsagent.executionworker.WorkerHttpEgressException;
import com.company.opsagent.executionworker.WorkerHttpEgressPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class TomcatWarUploadReleaseAdapter implements ReleaseAdapter {

  private static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$");
  private static final Pattern STORAGE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,180}[.]war$");
  private static final Pattern SHA256_PATTERN = Pattern.compile("^sha256:[a-fA-F0-9]{64}$");

  private final Path artifactStoragePath;
  private final Map<String, ReleaseWorkerProperties.Tomcat.Credential> credentials;
  private final HttpClient httpClient;
  private final WorkerHttpEgressPolicy workerHttpEgressPolicy;
  private final Clock clock;

  public TomcatWarUploadReleaseAdapter(
      Path artifactStoragePath,
      Map<String, ReleaseWorkerProperties.Tomcat.Credential> credentials,
      HttpClient httpClient,
      WorkerHttpEgressPolicy workerHttpEgressPolicy,
      Clock clock) {
    this.artifactStoragePath = required(artifactStoragePath, "artifactStoragePath").toAbsolutePath().normalize();
    this.credentials = Map.copyOf(required(credentials, "credentials"));
    this.httpClient = required(httpClient, "httpClient");
    this.workerHttpEgressPolicy = required(workerHttpEgressPolicy, "workerHttpEgressPolicy");
    this.clock = required(clock, "clock");
  }

  @Override
  public String managementMode() {
    return "TOMCAT_WAR_UPLOAD";
  }

  @Override
  public Mono<ReleaseWorkerResult> precheck(ReleaseWorkerRequest request) {
    return Mono.fromCallable(() -> validateRequest(request))
        .subscribeOn(Schedulers.boundedElastic())
        .map(error -> error == null ? ReleaseWorkerResult.succeeded(request, clock) : error);
  }

  @Override
  public Mono<ReleaseWorkerResult> deploy(ReleaseWorkerRequest request) {
    return Mono.fromCallable(() -> deployBlocking(request)).subscribeOn(Schedulers.boundedElastic());
  }

  @Override
  public Mono<ReleaseWorkerResult> start(ReleaseWorkerRequest request) {
    return Mono.just(notConfigured(request));
  }

  @Override
  public Mono<ReleaseWorkerResult> stop(ReleaseWorkerRequest request) {
    return Mono.just(notConfigured(request));
  }

  @Override
  public Mono<ReleaseWorkerResult> rollback(ReleaseWorkerRequest request) {
    return Mono.just(notConfigured(request));
  }

  @Override
  public Mono<ReleaseWorkerResult> healthcheck(ReleaseWorkerRequest request) {
    return precheck(request);
  }

  @Override
  public Mono<ReleaseWorkerResult> collectLogs(ReleaseWorkerRequest request) {
    return Mono.just(notConfigured(request));
  }

  private ReleaseWorkerResult deployBlocking(ReleaseWorkerRequest request) {
    ReleaseWorkerResult validationError = validateRequest(request);
    if (validationError != null) {
      return validationError;
    }
    ReleaseWorkerRequest.ReleaseCommand command = request.command();
    ReleaseWorkerRequest.ReleaseArtifactReference artifact = command.artifact();
    ReleaseWorkerRequest.ReleaseNodeTarget node = command.nodes().getFirst();
    Path artifactPath = artifactPath(artifact.storageKey());
    try {
      verifyChecksum(artifactPath, artifact.checksum());
      URI deployUri = deployUri(node.managementEndpoint(), node.applicationPath());
      workerHttpEgressPolicy.validate(deployUri);
      HttpResponse<String> response = httpClient.send(
          HttpRequest.newBuilder(deployUri)
              .header("Authorization", basicAuthorization(node.credentialAlias()))
              .header("Content-Type", "application/octet-stream")
              .PUT(HttpRequest.BodyPublishers.ofFile(artifactPath))
              .build(),
          HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
      if (response.statusCode() >= 200 && response.statusCode() < 300 && response.body().startsWith("OK")) {
        return ReleaseWorkerResult.succeeded(request, clock);
      }
      return ReleaseWorkerResult.failed(
          request,
          "TOMCAT_MANAGER_DEPLOY_FAILED",
          safeResponse(response.statusCode(), response.body()),
          clock);
    } catch (WorkerHttpEgressException exception) {
      return ReleaseWorkerResult.rejected(request, exception.errorCode(), exception.getMessage(), clock);
    } catch (IOException exception) {
      return ReleaseWorkerResult.failed(
          request,
          "TOMCAT_MANAGER_IO_ERROR",
          "Tomcat manager deploy request failed",
          clock);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return ReleaseWorkerResult.failed(
          request,
          "TOMCAT_MANAGER_INTERRUPTED",
          "Tomcat manager deploy request was interrupted",
          clock);
    }
  }

  private ReleaseWorkerResult validateRequest(ReleaseWorkerRequest request) {
    ReleaseWorkerRequest.ReleaseCommand command = request == null ? null : request.command();
    ReleaseWorkerRequest.ReleaseArtifactReference artifact = command == null ? null : command.artifact();
    ReleaseWorkerRequest.ReleaseNodeTarget node = command == null || command.nodes() == null || command.nodes().isEmpty()
        ? null
        : command.nodes().getFirst();
    if (artifact == null || !"WAR".equals(artifact.type())) {
      return rejected(request, "TOMCAT_ARTIFACT_TYPE_NOT_SUPPORTED", "Tomcat WAR upload only accepts WAR artifacts");
    }
    if (artifact.artifactId() == null || !ARTIFACT_ID_PATTERN.matcher(artifact.artifactId()).matches()) {
      return rejected(request, "TOMCAT_ARTIFACT_ID_INVALID", "Tomcat artifact id must reference a registered artifact");
    }
    if (artifact.checksum() == null || !SHA256_PATTERN.matcher(artifact.checksum()).matches()) {
      return rejected(request, "TOMCAT_ARTIFACT_CHECKSUM_REQUIRED", "Tomcat artifact checksum must be sha256");
    }
    if (artifact.storageKey() == null || !STORAGE_KEY_PATTERN.matcher(artifact.storageKey()).matches()) {
      return rejected(request, "TOMCAT_ARTIFACT_STORAGE_KEY_INVALID", "Tomcat artifact storage key is invalid");
    }
    if (node == null || isBlank(node.managementEndpoint()) || isBlank(node.applicationPath())) {
      return rejected(request, "TOMCAT_TARGET_REQUIRED", "Tomcat management endpoint and application path are required");
    }
    if (isBlank(node.credentialAlias()) || !credentials.containsKey(node.credentialAlias())) {
      return rejected(request, "TOMCAT_CREDENTIAL_NOT_CONFIGURED", "Tomcat credential alias is not configured");
    }
    if (!Files.isRegularFile(artifactPath(artifact.storageKey()))) {
      return rejected(request, "TOMCAT_ARTIFACT_NOT_FOUND", "Tomcat artifact content was not found");
    }
    return null;
  }

  private Path artifactPath(String storageKey) {
    Path path = artifactStoragePath.resolve(storageKey).normalize();
    if (!path.startsWith(artifactStoragePath)) {
      throw new IllegalArgumentException("artifact path escaped storage directory");
    }
    return path;
  }

  private URI deployUri(String managementEndpoint, String applicationPath) {
    String base = requiredText(managementEndpoint, "managementEndpoint");
    String normalizedBase = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
    String path = requiredText(applicationPath, "applicationPath");
    String normalizedPath = path.startsWith("/") ? path : "/" + path;
    return URI.create(normalizedBase + "/deploy?path="
        + URLEncoder.encode(normalizedPath, StandardCharsets.UTF_8)
        + "&update=true");
  }

  private String basicAuthorization(String credentialAlias) {
    ReleaseWorkerProperties.Tomcat.Credential credential = credentials.get(requiredText(credentialAlias, "credentialAlias"));
    String username = requiredText(credential.getUsername(), "tomcat username");
    String password = requiredText(credential.getPassword(), "tomcat password");
    return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
  }

  private void verifyChecksum(Path artifactPath, String expectedChecksum) throws IOException {
    String actual = checksum(artifactPath);
    if (!actual.equalsIgnoreCase(expectedChecksum)) {
      throw new IOException("artifact checksum mismatch");
    }
  }

  private String checksum(Path artifactPath) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(artifactPath)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private ReleaseWorkerResult notConfigured(ReleaseWorkerRequest request) {
    return rejected(
        request,
        "SERVER_MANAGEMENT_MODE_NOT_CONFIGURED",
        "Tomcat WAR upload mode does not provide this server management action");
  }

  private ReleaseWorkerResult rejected(ReleaseWorkerRequest request, String errorCode, String errorMessage) {
    return ReleaseWorkerResult.rejected(request, errorCode, errorMessage, clock);
  }

  private String safeResponse(int statusCode, String body) {
    String safeBody = body == null ? "" : body.replaceAll("[\\r\\n]+", " ").trim();
    if (safeBody.length() > 240) {
      safeBody = safeBody.substring(0, 240);
    }
    return "Tomcat manager returned HTTP " + statusCode + (safeBody.isBlank() ? "" : ": " + safeBody);
  }

  private static <T> T required(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  private String requiredText(String value, String name) {
    if (isBlank(value)) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
