package com.company.opsagent.controlplane.modules.release;

import java.io.InputStream;
import reactor.core.publisher.Mono;

public interface ReleaseArtifactStore {

  Mono<ReleaseArtifact> storeWar(
      String applicationId,
      String targetEnvironment,
      String originalFilename,
      String uploadedBy,
      InputStream inputStream);
}
