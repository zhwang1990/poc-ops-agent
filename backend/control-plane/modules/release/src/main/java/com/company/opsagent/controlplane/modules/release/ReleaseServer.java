package com.company.opsagent.controlplane.modules.release;

import java.net.URI;
import java.net.URISyntaxException;

public record ReleaseServer(
    String nodeId,
    TargetEnvironment targetEnvironment,
    ServerType serverType,
    ManagementMode managementMode,
    String managementEndpoint,
    String applicationPath,
    String credentialAlias,
    ReleaseScriptProfile scriptProfile,
    boolean enabled) {

  public ReleaseServer {
    nodeId = ReleaseValues.requiredText(nodeId, "nodeId");
    targetEnvironment = ReleaseValues.required(targetEnvironment, "targetEnvironment");
    serverType = ReleaseValues.required(serverType, "serverType");
    managementMode = ReleaseValues.required(managementMode, "managementMode");
    managementEndpoint = normalizedEndpoint(managementEndpoint, managementMode);
    applicationPath = ReleaseValues.optionalText(applicationPath);
    credentialAlias = ReleaseValues.optionalText(credentialAlias);
    scriptProfile = normalizedScriptProfile(serverType, managementMode, scriptProfile);
  }

  public static ReleaseServer create(
      String nodeId,
      String targetEnvironment,
      ServerType serverType,
      ManagementMode managementMode,
      String managementEndpoint,
      boolean enabled) {
    return create(nodeId, targetEnvironment, serverType, managementMode, managementEndpoint, null, null, enabled);
  }

  public static ReleaseServer create(
      String nodeId,
      String targetEnvironment,
      ServerType serverType,
      ManagementMode managementMode,
      String managementEndpoint,
      String applicationPath,
      String credentialAlias,
      boolean enabled) {
    return create(
        nodeId,
        targetEnvironment,
        serverType,
        managementMode,
        managementEndpoint,
        applicationPath,
        credentialAlias,
        null,
        enabled);
  }

  public static ReleaseServer create(
      String nodeId,
      String targetEnvironment,
      ServerType serverType,
      ManagementMode managementMode,
      String managementEndpoint,
      String applicationPath,
      String credentialAlias,
      ReleaseScriptProfile scriptProfile,
      boolean enabled) {
    return new ReleaseServer(
        nodeId,
        TargetEnvironment.from(targetEnvironment),
        serverType,
        managementMode,
        managementEndpoint,
        applicationPath,
        credentialAlias,
        scriptProfile,
        enabled);
  }

  private static ReleaseScriptProfile normalizedScriptProfile(
      ServerType serverType,
      ManagementMode managementMode,
      ReleaseScriptProfile scriptProfile) {
    if (managementMode == ManagementMode.LIBERTY_SCRIPT_PROFILE) {
      if (serverType != ServerType.LIBERTY) {
        throw new IllegalArgumentException("LIBERTY_SCRIPT_PROFILE requires LIBERTY server type");
      }
      return ReleaseValues.required(scriptProfile, "scriptProfile");
    }
    if (scriptProfile != null) {
      throw new IllegalArgumentException("scriptProfile is only supported by LIBERTY_SCRIPT_PROFILE");
    }
    return null;
  }

  private static String normalizedEndpoint(String managementEndpoint, ManagementMode managementMode) {
    String endpoint = ReleaseValues.requiredText(managementEndpoint, "managementEndpoint");
    if (managementMode == ManagementMode.DISABLED) {
      return endpoint;
    }
    try {
      URI uri = new URI(endpoint);
      String scheme = uri.getScheme();
      if (!"https".equalsIgnoreCase(scheme) && !"http".equalsIgnoreCase(scheme)) {
        throw new IllegalArgumentException("managementEndpoint must be http or https");
      }
      if (uri.getHost() == null || uri.getHost().isBlank()) {
        throw new IllegalArgumentException("managementEndpoint host is required");
      }
      return uri.toString();
    } catch (URISyntaxException exception) {
      throw new IllegalArgumentException("managementEndpoint must be a valid URI", exception);
    }
  }
}
