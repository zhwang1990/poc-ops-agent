package com.company.opsagent.executionworker.release;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ops-agent.worker.release")
public class ReleaseWorkerProperties {

  private final Liberty liberty = new Liberty();

  public Liberty getLiberty() {
    return liberty;
  }

  public static class Liberty {

    private boolean enabled;
    private String baseUrl = "";
    private String credentialAlias = "";

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getBaseUrl() {
      return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
      this.baseUrl = baseUrl;
    }

    public String getCredentialAlias() {
      return credentialAlias;
    }

    public void setCredentialAlias(String credentialAlias) {
      this.credentialAlias = credentialAlias;
    }
  }
}
