package com.company.opsagent.controlplane.bootstrap.config;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ops-agent.release-center")
public class ReleaseCenterProperties {

  private boolean enabled;
  private String credentialMasterKey;
  private Path artifactStoragePath = Path.of("build/release-center-artifacts");

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(boolean enabled) {
    this.enabled = enabled;
  }

  public String getCredentialMasterKey() {
    return credentialMasterKey;
  }

  public void setCredentialMasterKey(String credentialMasterKey) {
    this.credentialMasterKey = credentialMasterKey;
  }

  public Path getArtifactStoragePath() {
    return artifactStoragePath;
  }

  public void setArtifactStoragePath(Path artifactStoragePath) {
    this.artifactStoragePath = artifactStoragePath;
  }
}
