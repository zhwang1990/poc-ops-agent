package com.company.opsagent.controlplane.modules.release;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface ReleaseCatalogStore {

  Mono<ReleaseApplication> saveApplication(ReleaseApplication application);

  Flux<ReleaseApplication> listApplications();

  Mono<ReleaseEnvironmentPolicy> saveEnvironmentPolicy(ReleaseEnvironmentPolicy policy);

  Mono<ReleaseEnvironmentPolicy> findEnvironmentPolicy(TargetEnvironment targetEnvironment);

  Mono<ReleaseServer> saveServer(ReleaseServer server);

  Flux<ReleaseServer> listServers(String targetEnvironment);

  Mono<ReleaseArtifact> saveArtifact(ReleaseArtifact artifact);

  Mono<ReleaseCredential> saveCredential(ReleaseCredential credential);

  Mono<ReleaseCredential> findCredential(String credentialAlias);
}
