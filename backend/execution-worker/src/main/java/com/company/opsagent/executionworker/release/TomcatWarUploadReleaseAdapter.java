package com.company.opsagent.executionworker.release;

import java.time.Clock;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;

public class TomcatWarUploadReleaseAdapter implements ReleaseAdapter {

  private static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$");
  private static final Pattern SHA256_PATTERN = Pattern.compile("^sha256:[a-fA-F0-9]{3,}$");

  private final Clock clock;

  public TomcatWarUploadReleaseAdapter(Clock clock) {
    this.clock = clock;
  }

  @Override
  public String managementMode() {
    return "TOMCAT_WAR_UPLOAD";
  }

  @Override
  public Mono<ReleaseWorkerResult> precheck(ReleaseWorkerRequest request) {
    return validateWar(request).map(Mono::just).orElseGet(() -> Mono.just(ReleaseWorkerResult.succeeded(request, clock)));
  }

  @Override
  public Mono<ReleaseWorkerResult> deploy(ReleaseWorkerRequest request) {
    return validateWar(request).map(Mono::just).orElseGet(() -> Mono.just(ReleaseWorkerResult.succeeded(request, clock)));
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
    return validateWar(request).map(Mono::just).orElseGet(() -> Mono.just(ReleaseWorkerResult.succeeded(request, clock)));
  }

  @Override
  public Mono<ReleaseWorkerResult> collectLogs(ReleaseWorkerRequest request) {
    return Mono.just(notConfigured(request));
  }

  private java.util.Optional<ReleaseWorkerResult> validateWar(ReleaseWorkerRequest request) {
    ReleaseWorkerRequest.ReleaseArtifactReference artifact = request.command().artifact();
    if (artifact == null || !"WAR".equals(artifact.type())) {
      return java.util.Optional.of(rejected(
          request,
          "TOMCAT_ARTIFACT_TYPE_NOT_SUPPORTED",
          "Tomcat WAR upload only accepts WAR artifacts"));
    }
    if (artifact.artifactId() == null || !ARTIFACT_ID_PATTERN.matcher(artifact.artifactId()).matches()) {
      return java.util.Optional.of(rejected(
          request,
          "TOMCAT_ARTIFACT_ID_INVALID",
          "Tomcat artifact id must reference a registered artifact"));
    }
    if (artifact.checksum() == null || !SHA256_PATTERN.matcher(artifact.checksum()).matches()) {
      return java.util.Optional.of(rejected(
          request,
          "TOMCAT_ARTIFACT_CHECKSUM_REQUIRED",
          "Tomcat artifact checksum must be sha256"));
    }
    return java.util.Optional.empty();
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
}
