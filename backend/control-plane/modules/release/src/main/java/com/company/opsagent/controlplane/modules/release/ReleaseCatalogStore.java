package com.company.opsagent.controlplane.modules.release;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ReleaseCatalogStore {

  Mono<ReleaseApplication> saveApplication(ReleaseApplication application);

  Flux<ReleaseApplication> listApplications();

  Mono<ReleaseEnvironmentPolicy> saveEnvironmentPolicy(ReleaseEnvironmentPolicy policy);

  Mono<ReleaseEnvironmentPolicy> findEnvironmentPolicy(TargetEnvironment targetEnvironment);

  Mono<ReleaseServer> saveServer(ReleaseServer server);

  Mono<ReleaseServer> findServer(String nodeId);

  Flux<ReleaseServer> listServers(String targetEnvironment);

  Mono<Void> deleteServer(String nodeId);

  Mono<ReleaseScriptProfileDefinition> saveScriptProfileDefinition(ReleaseScriptProfileDefinition profile);

  Mono<ReleaseScriptProfileDefinition> findScriptProfileDefinition(String profileId);

  Flux<ReleaseScriptProfileDefinition> listScriptProfileDefinitions();

  Mono<Void> deleteScriptProfileDefinition(String profileId);

  Mono<ReleaseArtifact> saveArtifact(ReleaseArtifact artifact);

  Flux<ReleaseArtifact> listArtifacts(String targetEnvironment);

  Mono<ReleaseArtifact> findArtifact(String artifactId);

  Mono<ReleaseCredential> saveCredential(ReleaseCredential credential);

  Mono<ReleaseCredential> findCredential(String credentialAlias);

  Mono<ReleasePlan> savePlan(ReleasePlan plan);

  Mono<ReleasePlan> findPlan(String releaseId);

  Flux<ReleasePlan> listPlans();
}
