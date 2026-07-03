package com.company.opsagent.controlplane.modules.release;

import java.util.List;
import java.util.regex.Pattern;

public record ReleaseScriptProfileDefinition(
    String profileId,
    String displayName,
    String executablePath,
    String workingDirectory,
    List<String> arguments,
    List<Integer> successExitCodes,
    int timeoutSeconds,
    boolean approved,
    boolean enabled) {

  private static final Pattern PROFILE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$");
  private static final int MAX_ARGUMENTS = 40;
  private static final int MAX_TIMEOUT_SECONDS = 7200;

  public ReleaseScriptProfileDefinition {
    profileId = ReleaseValues.requiredText(profileId, "profileId");
    if (!PROFILE_ID_PATTERN.matcher(profileId).matches()) {
      throw new IllegalArgumentException("script profile id is invalid");
    }
    displayName = ReleaseValues.requiredText(displayName, "displayName");
    executablePath = validPath(executablePath, "executablePath");
    workingDirectory = validPath(workingDirectory, "workingDirectory");
    arguments = validArguments(arguments);
    successExitCodes = validExitCodes(successExitCodes);
    if (timeoutSeconds <= 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
      throw new IllegalArgumentException("timeoutSeconds is invalid");
    }
  }

  public static ReleaseScriptProfileDefinition create(
      String profileId,
      String displayName,
      String executablePath,
      String workingDirectory,
      List<String> arguments,
      List<Integer> successExitCodes,
      int timeoutSeconds,
      boolean approved,
      boolean enabled) {
    return new ReleaseScriptProfileDefinition(
        profileId,
        displayName,
        executablePath,
        workingDirectory,
        arguments,
        successExitCodes,
        timeoutSeconds,
        approved,
        enabled);
  }

  public boolean executable() {
    return approved && enabled;
  }

  private static String validPath(String value, String fieldName) {
    String path = ReleaseValues.requiredText(value, fieldName);
    if (path.length() > 500 || path.contains("\r") || path.contains("\n")) {
      throw new IllegalArgumentException(fieldName + " is invalid");
    }
    return path;
  }

  private static List<String> validArguments(List<String> values) {
    List<String> arguments = values == null ? List.of() : List.copyOf(values);
    if (arguments.size() > MAX_ARGUMENTS) {
      throw new IllegalArgumentException("arguments are invalid");
    }
    for (String argument : arguments) {
      String value = ReleaseValues.requiredText(argument, "argument");
      if (value.length() > 500 || value.contains("\r") || value.contains("\n")) {
        throw new IllegalArgumentException("argument is invalid");
      }
    }
    return arguments;
  }

  private static List<Integer> validExitCodes(List<Integer> values) {
    List<Integer> exitCodes = values == null ? List.of() : List.copyOf(values);
    if (exitCodes.isEmpty()) {
      throw new IllegalArgumentException("successExitCodes are required");
    }
    for (Integer exitCode : exitCodes) {
      if (exitCode == null || exitCode < 0 || exitCode > 255) {
        throw new IllegalArgumentException("successExitCodes are invalid");
      }
    }
    return exitCodes;
  }
}
