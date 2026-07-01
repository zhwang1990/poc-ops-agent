package com.company.opsagent.controlplane.modules.release;

import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class InMemoryReleaseCatalogStore implements ReleaseCatalogStore {

  private final Map<String, ReleaseApplication> applications = new ConcurrentHashMap<>();
  private final Map<TargetEnvironment, ReleaseEnvironmentPolicy> policies = new ConcurrentHashMap<>();
  private final Map<String, ReleaseServer> servers = new ConcurrentHashMap<>();
  private final Map<String, ReleaseArtifact> artifacts = new ConcurrentHashMap<>();
  private final Map<String, ReleaseCredential> credentials = new ConcurrentHashMap<>();

  @Override
  public Mono<ReleaseApplication> saveApplication(ReleaseApplication application) {
    applications.put(application.applicationId(), application);
    return Mono.just(application);
  }

  @Override
  public Flux<ReleaseApplication> listApplications() {
    return Flux.fromIterable(applications.values())
        .sort(Comparator.comparing(ReleaseApplication::applicationId));
  }

  @Override
  public Mono<ReleaseEnvironmentPolicy> saveEnvironmentPolicy(ReleaseEnvironmentPolicy policy) {
    policies.put(policy.targetEnvironment(), policy);
    return Mono.just(policy);
  }

  @Override
  public Mono<ReleaseEnvironmentPolicy> findEnvironmentPolicy(TargetEnvironment targetEnvironment) {
    return Mono.justOrEmpty(policies.get(targetEnvironment));
  }

  @Override
  public Mono<ReleaseServer> saveServer(ReleaseServer server) {
    servers.put(server.nodeId(), server);
    return Mono.just(server);
  }

  @Override
  public Flux<ReleaseServer> listServers(String targetEnvironment) {
    TargetEnvironment environment = TargetEnvironment.from(targetEnvironment);
    return Flux.fromIterable(servers.values())
        .filter(server -> server.targetEnvironment() == environment)
        .sort(Comparator.comparing(ReleaseServer::nodeId));
  }

  @Override
  public Mono<ReleaseArtifact> saveArtifact(ReleaseArtifact artifact) {
    artifacts.put(artifact.artifactId(), artifact);
    return Mono.just(artifact);
  }

  @Override
  public Mono<ReleaseCredential> saveCredential(ReleaseCredential credential) {
    credentials.put(credential.credentialAlias(), credential);
    return Mono.just(credential);
  }

  @Override
  public Mono<ReleaseCredential> findCredential(String credentialAlias) {
    return Mono.justOrEmpty(credentials.get(credentialAlias));
  }
}
