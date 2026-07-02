package com.company.opsagent.controlplane.modules.release;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record ReleaseScriptProfileDefinition(
    String profileId,
    TargetEnvironment targetEnvironment,
    String displayName,
    String executablePath,
    String workingDirectory,
    List<String> arguments,
    List<String> requiredParameters,
    List<String> allowedParameters,
    List<Integer> successExitCodes,
    int timeoutSeconds,
    boolean approved,
    boolean enabled) {

  private static final Pattern PROFILE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$");
  private static final Pattern PARAMETER_NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,63}$");
  private static final Pattern TEMPLATE_TOKEN_PATTERN = Pattern.compile("\\{\\{([A-Za-z0-9_.:-]+)}}");
  private static final int MAX_ARGUMENTS = 40;
  private static final int MAX_PARAMETERS = 40;
  private static final int MAX_TIMEOUT_SECONDS = 7200;

  public ReleaseScriptProfileDefinition {
    profileId = ReleaseValues.requiredText(profileId, "profileId");
    if (!PROFILE_ID_PATTERN.matcher(profileId).matches()) {
      throw new IllegalArgumentException("script profile id is invalid");
    }
    targetEnvironment = ReleaseValues.required(targetEnvironment, "targetEnvironment");
    displayName = ReleaseValues.requiredText(displayName, "displayName");
    executablePath = validPath(executablePath, "executablePath");
    workingDirectory = validPath(workingDirectory, "workingDirectory");
    arguments = validArguments(arguments);
    requiredParameters = validParameterNames(requiredParameters, "requiredParameters");
    allowedParameters = validParameterNames(allowedParameters, "allowedParameters");
    if (allowedParameters.isEmpty()) {
      allowedParameters = requiredParameters;
    }
    if (!new LinkedHashSet<>(allowedParameters).containsAll(requiredParameters)) {
      throw new IllegalArgumentException("allowedParameters must include requiredParameters");
    }
    validateTemplateParameters(arguments, allowedParameters);
    successExitCodes = validExitCodes(successExitCodes);
    if (timeoutSeconds <= 0 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
      throw new IllegalArgumentException("timeoutSeconds is invalid");
    }
  }

  public static ReleaseScriptProfileDefinition create(
      String profileId,
      String targetEnvironment,
      String displayName,
      String executablePath,
      String workingDirectory,
      List<String> arguments,
      List<String> requiredParameters,
      List<String> allowedParameters,
      List<Integer> successExitCodes,
      int timeoutSeconds,
      boolean approved,
      boolean enabled) {
    return new ReleaseScriptProfileDefinition(
        profileId,
        TargetEnvironment.from(targetEnvironment),
        displayName,
        executablePath,
        workingDirectory,
        arguments,
        requiredParameters,
        allowedParameters,
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
    if (arguments.isEmpty() || arguments.size() > MAX_ARGUMENTS) {
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

  private static List<String> validParameterNames(List<String> values, String fieldName) {
    List<String> parameters = values == null ? List.of() : List.copyOf(values);
    if (parameters.size() > MAX_PARAMETERS) {
      throw new IllegalArgumentException(fieldName + " are invalid");
    }
    Set<String> unique = new LinkedHashSet<>();
    for (String parameter : parameters) {
      String name = ReleaseValues.requiredText(parameter, fieldName + " item");
      String lowerName = name.toLowerCase(Locale.ROOT);
      if (!PARAMETER_NAME_PATTERN.matcher(name).matches()
          || lowerName.contains("password")
          || lowerName.contains("secret")
          || lowerName.contains("token")) {
        throw new IllegalArgumentException(fieldName + " item is invalid");
      }
      if (!unique.add(name)) {
        throw new IllegalArgumentException(fieldName + " item is duplicated");
      }
    }
    return List.copyOf(unique);
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

  private static void validateTemplateParameters(List<String> arguments, List<String> allowedParameters) {
    Set<String> allowed = Set.copyOf(allowedParameters);
    for (String argument : arguments) {
      Matcher matcher = TEMPLATE_TOKEN_PATTERN.matcher(argument);
      while (matcher.find()) {
        String token = matcher.group(1);
        if (token.startsWith("param.") && !allowed.contains(token.substring("param.".length()))) {
          throw new IllegalArgumentException("argument references an unallowed script parameter");
        }
      }
    }
  }
}
