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
  private final Map<String, ReleaseScriptProfileDefinition> scriptProfiles = new ConcurrentHashMap<>();
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
  public Mono<ReleaseServer> findServer(String nodeId) {
    return Mono.justOrEmpty(servers.get(ReleaseValues.requiredText(nodeId, "nodeId")));
  }

  @Override
  public Flux<ReleaseServer> listServers(String targetEnvironment) {
    TargetEnvironment environment = TargetEnvironment.from(targetEnvironment);
    return Flux.fromIterable(servers.values())
        .filter(server -> server.targetEnvironment() == environment)
        .sort(Comparator.comparing(ReleaseServer::nodeId));
  }

  @Override
  public Mono<Void> deleteServer(String nodeId) {
    servers.remove(ReleaseValues.requiredText(nodeId, "nodeId"));
    return Mono.empty();
  }

  @Override
  public Mono<ReleaseScriptProfileDefinition> saveScriptProfileDefinition(ReleaseScriptProfileDefinition profile) {
    scriptProfiles.put(scriptProfileKey(profile.targetEnvironment().value(), profile.profileId()), profile);
    return Mono.just(profile);
  }

  @Override
  public Mono<ReleaseScriptProfileDefinition> findScriptProfileDefinition(String targetEnvironment, String profileId) {
    return Mono.justOrEmpty(scriptProfiles.get(scriptProfileKey(targetEnvironment, profileId)));
  }

  @Override
  public Flux<ReleaseScriptProfileDefinition> listScriptProfileDefinitions(String targetEnvironment) {
    TargetEnvironment environment = TargetEnvironment.from(targetEnvironment);
    return Flux.fromIterable(scriptProfiles.values())
        .filter(profile -> profile.targetEnvironment() == environment)
        .sort(Comparator.comparing(ReleaseScriptProfileDefinition::profileId));
  }

  @Override
  public Mono<Void> deleteScriptProfileDefinition(String targetEnvironment, String profileId) {
    scriptProfiles.remove(scriptProfileKey(targetEnvironment, profileId));
    return Mono.empty();
  }

  @Override
  public Mono<ReleaseArtifact> saveArtifact(ReleaseArtifact artifact) {
    artifacts.put(artifact.artifactId(), artifact);
    return Mono.just(artifact);
  }

  @Override
  public Flux<ReleaseArtifact> listArtifacts(String targetEnvironment) {
    TargetEnvironment environment = TargetEnvironment.from(targetEnvironment);
    return Flux.fromIterable(artifacts.values())
        .filter(artifact -> artifact.targetEnvironment() == environment)
        .sort(Comparator.comparing(ReleaseArtifact::artifactId));
  }

  @Override
  public Mono<ReleaseArtifact> findArtifact(String artifactId) {
    return Mono.justOrEmpty(artifacts.get(ReleaseValues.requiredText(artifactId, "artifactId")));
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

  @Override
  public Mono<ReleasePlan> savePlan(ReleasePlan plan) {
    plans.put(plan.releaseId(), plan);
    return Mono.just(plan);
  }

  @Override
  public Mono<ReleasePlan> findPlan(String releaseId) {
    return Mono.justOrEmpty(plans.get(ReleaseValues.requiredText(releaseId, "releaseId")));
  }

  @Override
  public Flux<ReleasePlan> listPlans() {
    return Flux.fromIterable(plans.values())
        .sort(Comparator.comparing(ReleasePlan::updatedAt).reversed());
  }

  private String scriptProfileKey(String targetEnvironment, String profileId) {
    return TargetEnvironment.from(targetEnvironment).value()
        + "/"
        + ReleaseValues.requiredText(profileId, "profileId");
  }

  private final Map<String, ReleasePlan> plans = new ConcurrentHashMap<>();
}
