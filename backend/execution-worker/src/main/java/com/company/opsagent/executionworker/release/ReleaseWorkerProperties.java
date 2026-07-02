package com.company.opsagent.executionworker.release;

import java.time.Duration;
import java.util.List;
import java.nio.file.Path;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ops-agent.worker.release")
public class ReleaseWorkerProperties {

  private final Tomcat tomcat = new Tomcat();
  private final Liberty liberty = new Liberty();

  public Tomcat getTomcat() {
    return tomcat;
  }

  public Liberty getLiberty() {
    return liberty;
  }

  public static class Tomcat {

    private Path artifactStoragePath = Path.of("build/release-center-artifacts");
    private Map<String, Credential> credentials = Map.of();

    public Path getArtifactStoragePath() {
      return artifactStoragePath;
    }

    public void setArtifactStoragePath(Path artifactStoragePath) {
      this.artifactStoragePath = artifactStoragePath;
    }

    public Map<String, Credential> getCredentials() {
      return credentials;
    }

    public void setCredentials(Map<String, Credential> credentials) {
      this.credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
    }

    public static class Credential {

      private String username = "";
      private String password = "";

      public Credential() {
      }

      public Credential(String username, String password) {
        this.username = username;
        this.password = password;
      }

      public String getUsername() {
        return username;
      }

      public void setUsername(String username) {
        this.username = username;
      }

      public String getPassword() {
        return password;
      }

      public void setPassword(String password) {
        this.password = password;
      }
    }
  }

  public static class Liberty {

    private boolean enabled;
    private String baseUrl = "";
    private String credentialAlias = "";
    private Path artifactStoragePath = Path.of("build/release-center-artifacts");
    private Map<String, ScriptProfile> scriptProfiles = Map.of();

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

    public Path getArtifactStoragePath() {
      return artifactStoragePath;
    }

    public void setArtifactStoragePath(Path artifactStoragePath) {
      this.artifactStoragePath = artifactStoragePath;
    }

    public Map<String, ScriptProfile> getScriptProfiles() {
      return scriptProfiles;
    }

    public void setScriptProfiles(Map<String, ScriptProfile> scriptProfiles) {
      this.scriptProfiles = scriptProfiles == null ? Map.of() : Map.copyOf(scriptProfiles);
    }

    public static class ScriptProfile {

      private Path executablePath;
      private List<String> arguments = List.of();
      private List<String> requiredParameters = List.of();
      private List<String> allowedParameters = List.of();
      private List<Integer> successExitCodes = List.of(0);
      private Duration timeout = Duration.ofMinutes(5);
      private Path workingDirectory;

      public Path getExecutablePath() {
        return executablePath;
      }

      public void setExecutablePath(Path executablePath) {
        this.executablePath = executablePath;
      }

      public List<String> getArguments() {
        return arguments;
      }

      public void setArguments(List<String> arguments) {
        this.arguments = arguments == null ? List.of() : List.copyOf(arguments);
      }

      public List<String> getRequiredParameters() {
        return requiredParameters;
      }

      public void setRequiredParameters(List<String> requiredParameters) {
        this.requiredParameters = requiredParameters == null ? List.of() : List.copyOf(requiredParameters);
      }

      public List<String> getAllowedParameters() {
        return allowedParameters;
      }

      public void setAllowedParameters(List<String> allowedParameters) {
        this.allowedParameters = allowedParameters == null ? List.of() : List.copyOf(allowedParameters);
      }

      public List<Integer> getSuccessExitCodes() {
        return successExitCodes;
      }

      public void setSuccessExitCodes(List<Integer> successExitCodes) {
        this.successExitCodes = successExitCodes == null ? List.of(0) : List.copyOf(successExitCodes);
      }

      public Duration getTimeout() {
        return timeout;
      }

      public void setTimeout(Duration timeout) {
        this.timeout = timeout == null ? Duration.ofMinutes(5) : timeout;
      }

      public Path getWorkingDirectory() {
        return workingDirectory;
      }

      public void setWorkingDirectory(Path workingDirectory) {
        this.workingDirectory = workingDirectory;
      }
    }
  }
}
