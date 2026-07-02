package com.company.opsagent.executionworker.release;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

public class LibertyScriptProfileReleaseAdapter implements ReleaseAdapter {

  private static final Pattern ARTIFACT_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$");
  private static final Pattern STORAGE_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,180}[.]war$");
  private static final Pattern SHA256_PATTERN = Pattern.compile("^sha256:[a-fA-F0-9]{64}$");
  private static final Pattern PROFILE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,119}$");
  private static final Pattern PARAMETER_NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,63}$");
  private static final Pattern TEMPLATE_TOKEN_PATTERN = Pattern.compile("\\{\\{([A-Za-z0-9_.:-]+)}}");
  private static final int MAX_OUTPUT_CHARS = 600;

  private final Path artifactStoragePath;
  private final Map<String, ReleaseWorkerProperties.Liberty.ScriptProfile> scriptProfiles;
  private final Clock clock;

  public LibertyScriptProfileReleaseAdapter(
      Path artifactStoragePath,
      Map<String, ReleaseWorkerProperties.Liberty.ScriptProfile> scriptProfiles,
      Clock clock) {
    this.artifactStoragePath = required(artifactStoragePath, "artifactStoragePath").toAbsolutePath().normalize();
    this.scriptProfiles = Map.copyOf(required(scriptProfiles, "scriptProfiles"));
    this.clock = required(clock, "clock");
  }

  @Override
  public String managementMode() {
    return "LIBERTY_SCRIPT_PROFILE";
  }

  @Override
  public Mono<ReleaseWorkerResult> precheck(ReleaseWorkerRequest request) {
    return Mono.fromCallable(() -> validateRequest(request))
        .subscribeOn(Schedulers.boundedElastic())
        .map(error -> error == null ? ReleaseWorkerResult.succeeded(request, clock) : error);
  }

  @Override
  public Mono<ReleaseWorkerResult> deploy(ReleaseWorkerRequest request) {
    return Mono.fromCallable(() -> deployBlocking(request)).subscribeOn(Schedulers.boundedElastic());
  }

  @Override
  public Mono<ReleaseWorkerResult> start(ReleaseWorkerRequest request) {
    return Mono.just(notConfigured(request));
  }

  @Override
  public Mono<ReleaseWorkerResult> stop(ReleaseWorkerRequest request) {
    return Mono.just(notConfigured(request));
  }

  @Override
  public Mono<ReleaseWorkerResult> rollback(ReleaseWorkerRequest request) {
    return Mono.just(notConfigured(request));
  }

  @Override
  public Mono<ReleaseWorkerResult> healthcheck(ReleaseWorkerRequest request) {
    return precheck(request);
  }

  @Override
  public Mono<ReleaseWorkerResult> collectLogs(ReleaseWorkerRequest request) {
    return Mono.just(notConfigured(request));
  }

  private ReleaseWorkerResult deployBlocking(ReleaseWorkerRequest request) {
    ReleaseWorkerResult validationError = validateRequest(request);
    if (validationError != null) {
      return validationError;
    }

    ReleaseWorkerRequest.ReleaseCommand command = request.command();
    ReleaseWorkerRequest.ReleaseArtifactReference artifact = command.artifact();
    ReleaseWorkerRequest.ReleaseNodeTarget node = command.nodes().getFirst();
    ReleaseWorkerRequest.ReleaseScriptProfile requestedProfile = node.scriptProfile();
    ReleaseWorkerProperties.Liberty.ScriptProfile configuredProfile = scriptProfiles.get(requestedProfile.profileId());
    Path artifactPath = artifact == null ? null : artifactPath(artifact.storageKey());
    Path workingDirectory = workingDirectory(configuredProfile);
    Path outputPath = workingDirectory.resolve(outputFileName(request.executionRequestId())).normalize();
    if (!outputPath.startsWith(workingDirectory)) {
      return rejected(request, "LIBERTY_SCRIPT_PROFILE_INVALID", "script output path is invalid");
    }

    try {
      if (artifact != null) {
        verifyChecksum(artifactPath, artifact.checksum());
      }
      Files.createDirectories(workingDirectory);
      List<String> commandLine = commandLine(request, artifactPath, configuredProfile);
      ProcessBuilder processBuilder = new ProcessBuilder(commandLine)
          .directory(workingDirectory.toFile())
          .redirectErrorStream(true)
          .redirectOutput(outputPath.toFile());
      Process process = processBuilder.start();
      Duration timeout = timeout(configuredProfile);
      boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
      if (!finished) {
        process.destroyForcibly();
        return ReleaseWorkerResult.failed(
            request,
            "LIBERTY_SCRIPT_TIMEOUT",
            "Liberty script profile timed out",
            clock);
      }
      int exitCode = process.exitValue();
      if (successExitCodes(configuredProfile).contains(exitCode)) {
        return ReleaseWorkerResult.succeeded(request, clock);
      }
      return ReleaseWorkerResult.failed(
          request,
          "LIBERTY_SCRIPT_EXIT_CODE_FAILED",
          "Liberty script profile exited with code " + exitCode + outputSummary(outputPath),
          clock);
    } catch (IOException exception) {
      return ReleaseWorkerResult.failed(
          request,
          "LIBERTY_SCRIPT_IO_ERROR",
          "Liberty script profile could not be executed",
          clock);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return ReleaseWorkerResult.failed(
          request,
          "LIBERTY_SCRIPT_INTERRUPTED",
          "Liberty script profile execution was interrupted",
          clock);
    } catch (IllegalArgumentException exception) {
      return rejected(request, "LIBERTY_SCRIPT_PROFILE_INVALID", exception.getMessage());
    }
  }

  private ReleaseWorkerResult validateRequest(ReleaseWorkerRequest request) {
    ReleaseWorkerRequest.ReleaseCommand command = request == null ? null : request.command();
    ReleaseWorkerRequest.ReleaseArtifactReference artifact = command == null ? null : command.artifact();
    ReleaseWorkerRequest.ReleaseNodeTarget node = command == null || command.nodes() == null || command.nodes().isEmpty()
        ? null
        : command.nodes().getFirst();
    if (node == null || !"LIBERTY".equals(node.serverType()) || !"LIBERTY_SCRIPT_PROFILE".equals(node.managementMode())) {
      return rejected(request, "LIBERTY_SCRIPT_TARGET_INVALID", "Liberty script profile requires a Liberty node target");
    }
    if (isBlank(node.managementEndpoint()) || isBlank(node.applicationPath())) {
      return rejected(request, "LIBERTY_SCRIPT_TARGET_REQUIRED", "Liberty management endpoint and application path are required");
    }
    ReleaseWorkerRequest.ReleaseScriptProfile requestedProfile = node.scriptProfile();
    if (requestedProfile == null || isBlank(requestedProfile.profileId())) {
      return rejected(request, "LIBERTY_SCRIPT_PROFILE_REQUIRED", "Liberty script profile is required");
    }
    if (!PROFILE_ID_PATTERN.matcher(requestedProfile.profileId()).matches()) {
      return rejected(request, "LIBERTY_SCRIPT_PROFILE_INVALID", "Liberty script profile id is invalid");
    }
    ReleaseWorkerProperties.Liberty.ScriptProfile configuredProfile = scriptProfiles.get(requestedProfile.profileId());
    if (configuredProfile == null) {
      return rejected(request, "LIBERTY_SCRIPT_PROFILE_NOT_CONFIGURED", "Liberty script profile is not configured on this Worker");
    }
    ReleaseWorkerResult configuredProfileError = validateConfiguredProfile(request, configuredProfile);
    if (configuredProfileError != null) {
      return configuredProfileError;
    }
    ReleaseWorkerResult artifactError = validateArtifact(request, artifact, configuredProfile);
    if (artifactError != null) {
      return artifactError;
    }
    return validateParameters(request, requestedProfile, configuredProfile);
  }

  private ReleaseWorkerResult validateArtifact(
      ReleaseWorkerRequest request,
      ReleaseWorkerRequest.ReleaseArtifactReference artifact,
      ReleaseWorkerProperties.Liberty.ScriptProfile configuredProfile) {
    if (artifact == null) {
      if (usesArtifactTemplate(configuredProfile)) {
        return rejected(request, "LIBERTY_SCRIPT_ARTIFACT_REQUIRED", "Liberty script profile requires artifact context");
      }
      return null;
    }
    if (!"WAR".equals(artifact.type())) {
      return rejected(request, "LIBERTY_SCRIPT_ARTIFACT_TYPE_NOT_SUPPORTED", "Liberty script profiles only accept WAR artifacts");
    }
    if (artifact.artifactId() == null || !ARTIFACT_ID_PATTERN.matcher(artifact.artifactId()).matches()) {
      return rejected(request, "LIBERTY_SCRIPT_ARTIFACT_ID_INVALID", "Liberty artifact id must reference a registered artifact");
    }
    if (artifact.checksum() == null || !SHA256_PATTERN.matcher(artifact.checksum()).matches()) {
      return rejected(request, "LIBERTY_SCRIPT_ARTIFACT_CHECKSUM_REQUIRED", "Liberty artifact checksum must be sha256");
    }
    if (artifact.storageKey() == null || !STORAGE_KEY_PATTERN.matcher(artifact.storageKey()).matches()) {
      return rejected(request, "LIBERTY_SCRIPT_ARTIFACT_STORAGE_KEY_INVALID", "Liberty artifact storage key is invalid");
    }
    if (!Files.isRegularFile(artifactPath(artifact.storageKey()))) {
      return rejected(request, "LIBERTY_SCRIPT_ARTIFACT_NOT_FOUND", "Liberty artifact content was not found");
    }
    return null;
  }

  private ReleaseWorkerResult validateConfiguredProfile(
      ReleaseWorkerRequest request,
      ReleaseWorkerProperties.Liberty.ScriptProfile configuredProfile) {
    Path executablePath = configuredProfile.getExecutablePath();
    if (executablePath == null || !executablePath.isAbsolute() || !Files.isRegularFile(executablePath)) {
      return rejected(request, "LIBERTY_SCRIPT_PROFILE_INVALID", "Liberty script executable path is invalid");
    }
    if (timeout(configuredProfile).isZero() || timeout(configuredProfile).isNegative()) {
      return rejected(request, "LIBERTY_SCRIPT_PROFILE_INVALID", "Liberty script timeout must be positive");
    }
    if (successExitCodes(configuredProfile).isEmpty()) {
      return rejected(request, "LIBERTY_SCRIPT_PROFILE_INVALID", "Liberty script success exit codes are required");
    }
    return null;
  }

  private ReleaseWorkerResult validateParameters(
      ReleaseWorkerRequest request,
      ReleaseWorkerRequest.ReleaseScriptProfile requestedProfile,
      ReleaseWorkerProperties.Liberty.ScriptProfile configuredProfile) {
    Map<String, String> parameters;
    try {
      parameters = parameters(requestedProfile);
    } catch (IllegalArgumentException exception) {
      return rejected(request, "LIBERTY_SCRIPT_PROFILE_INVALID", exception.getMessage());
    }
    Set<String> allowed = allowedParameters(configuredProfile);
    for (String name : parameters.keySet()) {
      if (!allowed.contains(name)) {
        return rejected(request, "LIBERTY_SCRIPT_PARAMETER_NOT_ALLOWED", "Liberty script parameter is not allowed");
      }
    }
    for (String requiredParameter : configuredProfile.getRequiredParameters()) {
      if (!parameters.containsKey(requiredParameter)) {
        return rejected(request, "LIBERTY_SCRIPT_PARAMETER_REQUIRED", "Liberty script required parameter is missing");
      }
    }
    return null;
  }

  private List<String> commandLine(
      ReleaseWorkerRequest request,
      Path artifactPath,
      ReleaseWorkerProperties.Liberty.ScriptProfile configuredProfile) {
    ReleaseWorkerRequest.ReleaseCommand command = request.command();
    ReleaseWorkerRequest.ReleaseArtifactReference artifact = command.artifact();
    ReleaseWorkerRequest.ReleaseNodeTarget node = command.nodes().getFirst();
    Map<String, String> context = new HashMap<>();
    if (artifact != null) {
      context.put("artifactPath", artifactPath.toString());
      context.put("artifactId", artifact.artifactId());
      context.put("artifactStorageKey", artifact.storageKey());
      context.put("artifactChecksum", artifact.checksum());
    }
    context.put("applicationId", command.applicationId());
    context.put("releaseId", command.releaseId());
    context.put("workflowId", command.workflowId());
    context.put("nodeId", node.nodeId());
    context.put("managementEndpoint", node.managementEndpoint());
    context.put("applicationPath", node.applicationPath());
    context.put("credentialAlias", node.credentialAlias());

    Map<String, String> scriptParameters = parameters(node.scriptProfile());
    List<String> commandLine = new ArrayList<>();
    commandLine.add(configuredProfile.getExecutablePath().toString());
    for (String argumentTemplate : configuredProfile.getArguments()) {
      commandLine.add(resolveTemplate(argumentTemplate, context, scriptParameters));
    }
    return commandLine;
  }

  private String resolveTemplate(
      String template,
      Map<String, String> context,
      Map<String, String> scriptParameters) {
    String value = requiredText(template, "script argument template");
    Matcher matcher = TEMPLATE_TOKEN_PATTERN.matcher(value);
    StringBuffer buffer = new StringBuffer();
    while (matcher.find()) {
      String token = matcher.group(1);
      String replacement;
      if (token.startsWith("param.")) {
        replacement = scriptParameters.get(token.substring("param.".length()));
      } else {
        replacement = context.get(token);
      }
      if (replacement == null) {
        throw new IllegalArgumentException("unknown script argument template token: " + token);
      }
      matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
    }
    matcher.appendTail(buffer);
    return buffer.toString();
  }

  private Map<String, String> parameters(ReleaseWorkerRequest.ReleaseScriptProfile scriptProfile) {
    if (scriptProfile == null || scriptProfile.parameters() == null) {
      return Map.of();
    }
    Map<String, String> parameters = new HashMap<>();
    for (ReleaseWorkerRequest.ReleaseScriptParameter parameter : scriptProfile.parameters()) {
      String name = requiredText(parameter.name(), "script parameter name");
      String value = requiredText(parameter.value(), "script parameter value");
      String lowerName = name.toLowerCase(java.util.Locale.ROOT);
      if (!PARAMETER_NAME_PATTERN.matcher(name).matches()
          || lowerName.contains("password")
          || lowerName.contains("secret")
          || lowerName.contains("token")
          || value.length() > 500) {
        throw new IllegalArgumentException("script parameter is invalid");
      }
      if (parameters.put(name, value) != null) {
        throw new IllegalArgumentException("script parameter is duplicated");
      }
    }
    return Map.copyOf(parameters);
  }

  private Set<String> allowedParameters(ReleaseWorkerProperties.Liberty.ScriptProfile configuredProfile) {
    Set<String> allowed = new HashSet<>(configuredProfile.getAllowedParameters());
    if (allowed.isEmpty()) {
      allowed.addAll(configuredProfile.getRequiredParameters());
    }
    return allowed;
  }

  private boolean usesArtifactTemplate(ReleaseWorkerProperties.Liberty.ScriptProfile configuredProfile) {
    return configuredProfile.getArguments().stream()
        .anyMatch(argument -> argument != null && (
            argument.contains("{{artifactPath}}")
                || argument.contains("{{artifactId}}")
                || argument.contains("{{artifactStorageKey}}")
                || argument.contains("{{artifactChecksum}}")));
  }

  private Path artifactPath(String storageKey) {
    Path path = artifactStoragePath.resolve(storageKey).normalize();
    if (!path.startsWith(artifactStoragePath)) {
      throw new IllegalArgumentException("artifact path escaped storage directory");
    }
    return path;
  }

  private Path workingDirectory(ReleaseWorkerProperties.Liberty.ScriptProfile configuredProfile) {
    Path workingDirectory = configuredProfile.getWorkingDirectory() == null
        ? artifactStoragePath
        : configuredProfile.getWorkingDirectory().toAbsolutePath().normalize();
    return workingDirectory;
  }

  private String outputFileName(String executionRequestId) {
    String safeId = requiredText(executionRequestId, "executionRequestId").replaceAll("[^A-Za-z0-9._-]", "-");
    return "release-script-" + safeId + ".log";
  }

  private void verifyChecksum(Path artifactPath, String expectedChecksum) throws IOException {
    String actual = checksum(artifactPath);
    if (!actual.equalsIgnoreCase(expectedChecksum)) {
      throw new IOException("artifact checksum mismatch");
    }
  }

  private String checksum(Path artifactPath) throws IOException {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      try (var input = Files.newInputStream(artifactPath)) {
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
          digest.update(buffer, 0, read);
        }
      }
      return "sha256:" + HexFormat.of().formatHex(digest.digest());
    } catch (GeneralSecurityException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }

  private List<Integer> successExitCodes(ReleaseWorkerProperties.Liberty.ScriptProfile configuredProfile) {
    return configuredProfile.getSuccessExitCodes() == null ? List.of(0) : configuredProfile.getSuccessExitCodes();
  }

  private Duration timeout(ReleaseWorkerProperties.Liberty.ScriptProfile configuredProfile) {
    return configuredProfile.getTimeout() == null ? Duration.ofMinutes(5) : configuredProfile.getTimeout();
  }

  private String outputSummary(Path outputPath) {
    try {
      if (!Files.isRegularFile(outputPath)) {
        return "";
      }
      String output = Files.readString(outputPath, StandardCharsets.UTF_8)
          .replaceAll("[\\r\\n]+", " ")
          .trim();
      if (output.isBlank()) {
        return "";
      }
      if (output.length() > MAX_OUTPUT_CHARS) {
        output = output.substring(0, MAX_OUTPUT_CHARS);
      }
      return ": " + output;
    } catch (IOException exception) {
      return "";
    }
  }

  private ReleaseWorkerResult notConfigured(ReleaseWorkerRequest request) {
    return rejected(
        request,
        "SERVER_MANAGEMENT_MODE_NOT_CONFIGURED",
        "Liberty script profile mode does not provide this server management action");
  }

  private ReleaseWorkerResult rejected(ReleaseWorkerRequest request, String errorCode, String errorMessage) {
    return ReleaseWorkerResult.rejected(request, errorCode, errorMessage, clock);
  }

  private static <T> T required(T value, String name) {
    if (value == null) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value;
  }

  private String requiredText(String value, String name) {
    if (isBlank(value)) {
      throw new IllegalArgumentException(name + " is required");
    }
    return value.trim();
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
