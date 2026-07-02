package com.company.opsagent.controlplane.bootstrap.api;

import com.company.opsagent.controlplane.bootstrap.security.PolicyEnforcementWebFilter;
import com.company.opsagent.controlplane.modules.audit.ExecutionContext;
import com.company.opsagent.controlplane.modules.release.ArtifactType;
import com.company.opsagent.controlplane.modules.release.ReleaseConfirmation;
import com.company.opsagent.controlplane.modules.release.ReleaseArtifact;
import com.company.opsagent.controlplane.modules.release.ReleaseArtifactStore;
import com.company.opsagent.controlplane.modules.release.ReleaseApplication;
import com.company.opsagent.controlplane.modules.release.ReleaseCatalogStore;
import com.company.opsagent.controlplane.modules.release.ReleaseCredentialService;
import com.company.opsagent.controlplane.modules.release.ReleaseCredentialSummary;
import com.company.opsagent.controlplane.modules.release.ReleaseEnvironmentPolicy;
import com.company.opsagent.controlplane.modules.release.ReleasePlan;
import com.company.opsagent.controlplane.modules.release.ReleaseScriptProfileDefinition;
import com.company.opsagent.controlplane.modules.release.ReleaseScriptProfile;
import com.company.opsagent.controlplane.modules.release.ReleaseServer;
import com.company.opsagent.controlplane.modules.release.ReleaseWorkflowEvent;
import com.company.opsagent.controlplane.modules.release.ReleaseWorkflowException;
import com.company.opsagent.controlplane.modules.release.ReleaseWorkflowService;
import com.company.opsagent.controlplane.modules.release.ServerType;
import com.company.opsagent.controlplane.modules.release.ManagementMode;
import com.company.opsagent.controlplane.modules.release.TargetEnvironment;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * 发布中心管理配置入口。这里只开放非生产目录和凭据配置，不执行发布动作。
 */
@RestController
@RequestMapping("/internal/release-center")
public class ReleaseCenterController {

  private static final Set<String> APPLICATION_FIELDS = Set.of(
      "applicationId",
      "displayName",
      "artifactType",
      "healthCheckPath",
      "enabled");
  private static final Set<String> CREDENTIAL_FIELDS = Set.of(
      "credentialAlias",
      "serverType",
      "secret");
  private static final Set<String> SERVER_FIELDS = Set.of(
      "nodeId",
      "targetEnvironment",
      "serverType",
      "managementMode",
      "managementEndpoint",
      "applicationPath",
      "credentialAlias",
      "scriptProfile",
      "enabled");
  private static final Set<String> SCRIPT_PROFILE_DEFINITION_FIELDS = Set.of(
      "profileId",
      "displayName",
      "executablePath",
      "workingDirectory",
      "arguments",
      "successExitCodes",
      "timeoutSeconds",
      "approved",
      "enabled");
  private static final Set<String> PLAN_FIELDS = Set.of(
      "applicationId",
      "targetEnvironment",
      "artifactId",
      "nodeIds",
      "parametersHash");
  private static final Set<String> CONFIRMATION_FIELDS = Set.of(
      "confirmationId",
      "parametersHash");

  private final ReleaseCatalogStore releaseCatalogStore;
  private final ReleaseArtifactStore releaseArtifactStore;
  private final ReleaseCredentialService releaseCredentialService;
  private final ReleaseWorkflowService releaseWorkflowService;
  private final ObjectMapper objectMapper;

  public ReleaseCenterController(
      ReleaseCatalogStore releaseCatalogStore,
      ReleaseArtifactStore releaseArtifactStore,
      ReleaseCredentialService releaseCredentialService,
      ReleaseWorkflowService releaseWorkflowService,
      ObjectMapper objectMapper) {
    this.releaseCatalogStore = releaseCatalogStore;
    this.releaseArtifactStore = releaseArtifactStore;
    this.releaseCredentialService = releaseCredentialService;
    this.releaseWorkflowService = releaseWorkflowService;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/applications")
  public Mono<List<ReleaseApplication>> applications() {
    return releaseCatalogStore.listApplications().collectList();
  }

  @GetMapping("/servers")
  public Mono<List<ReleaseServer>> servers(@RequestParam("targetEnvironment") String targetEnvironment) {
    return releaseCatalogStore.listServers(targetEnvironment).collectList();
  }

  @GetMapping("/script-profiles")
  public Mono<List<ReleaseScriptProfileDefinition>> scriptProfiles(
      @RequestParam(value = "targetEnvironment", required = false) String targetEnvironment) {
    validateScriptProfileCompatEnvironment(targetEnvironment);
    return releaseCatalogStore.listScriptProfileDefinitions().collectList();
  }

  @GetMapping("/plans")
  public Mono<List<ReleasePlan>> plans() {
    return releaseCatalogStore.listPlans().collectList();
  }

  @GetMapping("/artifacts")
  public Mono<List<ReleaseArtifact>> artifacts(@RequestParam("targetEnvironment") String targetEnvironment) {
    return releaseCatalogStore.listArtifacts(targetEnvironment).collectList();
  }

  @PostMapping("/applications")
  public Mono<ReleaseApplication> createApplication(@RequestBody JsonNode request) {
    ApplicationRequest parsed = parseApplicationRequest(request);
    return releaseCatalogStore.saveApplication(ReleaseApplication.create(
        parsed.applicationId(),
        parsed.displayName(),
        artifactType(parsed.artifactType()),
        parsed.healthCheckPath(),
        parsed.enabled() == null || parsed.enabled()));
  }

  @PostMapping("/servers")
  public Mono<ReleaseServer> createServer(@RequestBody JsonNode request) {
    ServerRequest parsed = parseServerRequest(request);
    ReleaseServer server = ReleaseServer.create(
        parsed.nodeId(),
        parsed.targetEnvironment(),
        serverType(parsed.serverType()),
        managementMode(parsed.managementMode()),
        parsed.managementEndpoint(),
        parsed.applicationPath(),
        parsed.credentialAlias(),
        parsed.scriptProfile(),
        parsed.enabled() == null || parsed.enabled());
    return validateServerScriptProfile(server)
        .then(releaseCatalogStore.saveServer(server));
  }

  @DeleteMapping("/servers/{nodeId}")
  public Mono<ResponseEntity<Void>> deleteServer(@PathVariable("nodeId") String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nodeId is required");
    }
    return releaseCatalogStore.deleteServer(nodeId)
        .thenReturn(ResponseEntity.noContent().build());
  }

  @PostMapping("/script-profiles")
  public Mono<ReleaseScriptProfileDefinition> createScriptProfile(@RequestBody JsonNode request) {
    ScriptProfileDefinitionRequest parsed = parseScriptProfileDefinitionRequest(request);
    return releaseCatalogStore.saveScriptProfileDefinition(ReleaseScriptProfileDefinition.create(
        parsed.profileId(),
        parsed.displayName(),
        parsed.executablePath(),
        parsed.workingDirectory(),
        parsed.arguments(),
        parsed.successExitCodes() == null ? List.of(0) : parsed.successExitCodes(),
        parsed.timeoutSeconds() == null ? 300 : parsed.timeoutSeconds(),
        Boolean.TRUE.equals(parsed.approved()),
        parsed.enabled() == null || parsed.enabled()));
  }

  @DeleteMapping("/script-profiles/{profileId}")
  public Mono<ResponseEntity<Void>> deleteScriptProfile(@PathVariable("profileId") String profileId) {
    return releaseCatalogStore.deleteScriptProfileDefinition(profileId)
        .thenReturn(ResponseEntity.noContent().build());
  }

  @DeleteMapping("/script-profiles/{targetEnvironment}/{profileId}")
  public Mono<ResponseEntity<Void>> deleteScriptProfile(
      @PathVariable("targetEnvironment") String targetEnvironment,
      @PathVariable("profileId") String profileId) {
    validateScriptProfileCompatEnvironment(targetEnvironment);
    return releaseCatalogStore.deleteScriptProfileDefinition(profileId)
        .thenReturn(ResponseEntity.noContent().build());
  }

  @PostMapping("/plans")
  public Mono<ReleasePlan> createPlan(@RequestBody JsonNode request) {
    PlanRequest parsed = parsePlanRequest(request);
    TargetEnvironment targetEnvironment = TargetEnvironment.from(parsed.targetEnvironment());
    Mono<Void> artifactValidation = parsed.artifactId() == null || parsed.artifactId().isBlank()
        ? Mono.empty()
        : releaseCatalogStore.findArtifact(parsed.artifactId())
            .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "release artifact not found")))
            .flatMap(artifact -> validateArtifact(artifact, parsed.applicationId(), targetEnvironment))
            .then();
    return releaseCatalogStore.listServers(targetEnvironment.value()).collectList()
        .map(servers -> selectNodes(servers, parsed.nodeIds()))
        .zipWith(releaseCatalogStore.findEnvironmentPolicy(targetEnvironment)
            .defaultIfEmpty(ReleaseEnvironmentPolicy.defaultFor(targetEnvironment)))
        .flatMap(tuple -> artifactValidation.then(releaseWorkflowService.createPlan(
                releaseId(parsed.applicationId(), targetEnvironment),
                parsed.applicationId(),
                targetEnvironment.value(),
                parsed.artifactId(),
                tuple.getT1(),
                tuple.getT2(),
                parsed.parametersHash())))
        .flatMap(releaseCatalogStore::savePlan)
        .onErrorMap(ReleaseWorkflowException.class, this::badRequest);
  }

  @PostMapping("/plans/{releaseId}/confirm")
  public Mono<ReleasePlan> confirmPlan(
      @PathVariable("releaseId") String releaseId,
      @RequestBody JsonNode request,
      ServerWebExchange exchange) {
    ConfirmationRequest parsed = parseConfirmationRequest(request);
    ExecutionContext context = executionContext(exchange);
    ReleaseConfirmation confirmation = new ReleaseConfirmation(
        parsed.confirmationId(),
        parsed.parametersHash(),
        context.subject(),
        Instant.now());
    return releaseCatalogStore.findPlan(releaseId)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "release plan not found")))
        .flatMap(plan -> releaseWorkflowService.confirm(plan, confirmation))
        .flatMap(releaseCatalogStore::savePlan)
        .onErrorMap(ReleaseWorkflowException.class, this::badRequest);
  }

  @PostMapping("/plans/{releaseId}/execute")
  public Mono<ReleasePlan> executePlan(@PathVariable("releaseId") String releaseId) {
    return releaseCatalogStore.findPlan(releaseId)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "release plan not found")))
        .flatMap(releaseWorkflowService::execute)
        .flatMap(releaseCatalogStore::savePlan)
        .onErrorMap(ReleaseWorkflowException.class, this::badRequest);
  }

  @GetMapping(value = "/plans/{releaseId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public Flux<ServerSentEvent<ReleaseWorkflowEvent>> releasePlanEvents(
      @PathVariable("releaseId") String releaseId,
      @RequestParam(value = "afterSequence", defaultValue = "0") long afterSequence) {
    if (afterSequence < 0) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "afterSequence must not be negative");
    }
    return releaseCatalogStore.findPlan(releaseId)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "release plan not found")))
        .flatMapMany(plan -> releaseWorkflowService.events(plan.releaseId(), afterSequence))
        .map(event -> ServerSentEvent.builder(event)
            .id(String.valueOf(event.sequence()))
            .event(event.type().name())
            .build());
  }

  @PostMapping("/credentials")
  public Mono<ReleaseCredentialSummary> createOrRotateCredential(
      @RequestBody JsonNode request,
      ServerWebExchange exchange) {
    CredentialRequest parsed = parseCredentialRequest(request);
    ExecutionContext context = executionContext(exchange);
    return releaseCredentialService.createOrRotate(
        parsed.credentialAlias(),
        serverType(parsed.serverType()),
        parsed.secret(),
        context.subject());
  }

  @PostMapping(
      value = "/artifacts/tomcat-war",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public Mono<ReleaseArtifact> uploadTomcatWar(
      @RequestPart("applicationId") String applicationId,
      @RequestPart("targetEnvironment") String targetEnvironment,
      @RequestPart("file") FilePart file,
      ServerWebExchange exchange) {
    ExecutionContext context = executionContext(exchange);
    return DataBufferUtils.join(file.content())
        .flatMap(dataBuffer -> {
          try {
            byte[] bytes = new byte[dataBuffer.readableByteCount()];
            dataBuffer.read(bytes);
            return releaseArtifactStore.storeWar(
                    applicationId,
                    targetEnvironment,
                    file.filename(),
                    context.subject(),
                    new ByteArrayInputStream(bytes))
                .flatMap(releaseCatalogStore::saveArtifact);
          } finally {
            DataBufferUtils.release(dataBuffer);
          }
        });
  }

  @PostMapping("/servers/{nodeId}/test")
  public Mono<ReleaseConnectionTestResult> testServer(@PathVariable("nodeId") String nodeId) {
    if (nodeId == null || nodeId.isBlank()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nodeId is required");
    }
    return Mono.just(new ReleaseConnectionTestResult(
        nodeId.trim(),
        "SKIPPED",
        "Release server connection test adapter is not configured yet.",
        OffsetDateTime.now()));
  }

  private ApplicationRequest parseApplicationRequest(JsonNode request) {
    validateObjectFields(request, APPLICATION_FIELDS, "release application request");
    return parse(request, ApplicationRequest.class, "release application request is invalid");
  }

  private CredentialRequest parseCredentialRequest(JsonNode request) {
    validateObjectFields(request, CREDENTIAL_FIELDS, "release credential request");
    return parse(request, CredentialRequest.class, "release credential request is invalid");
  }

  private ServerRequest parseServerRequest(JsonNode request) {
    validateObjectFields(request, SERVER_FIELDS, "release server request");
    return parse(request, ServerRequest.class, "release server request is invalid");
  }

  private ScriptProfileDefinitionRequest parseScriptProfileDefinitionRequest(JsonNode request) {
    validateObjectFields(request, SCRIPT_PROFILE_DEFINITION_FIELDS, "release script profile request");
    return parse(request, ScriptProfileDefinitionRequest.class, "release script profile request is invalid");
  }

  private PlanRequest parsePlanRequest(JsonNode request) {
    validateObjectFields(request, PLAN_FIELDS, "release plan request");
    PlanRequest parsed = parse(request, PlanRequest.class, "release plan request is invalid");
    if (parsed.nodeIds() == null || parsed.nodeIds().isEmpty()) {
      throw new IllegalArgumentException("nodeIds must not be empty");
    }
    return parsed;
  }

  private ConfirmationRequest parseConfirmationRequest(JsonNode request) {
    validateObjectFields(request, CONFIRMATION_FIELDS, "release confirmation request");
    return parse(request, ConfirmationRequest.class, "release confirmation request is invalid");
  }

  private ArtifactType artifactType(String value) {
    if (value == null || value.isBlank()) {
      return ArtifactType.WAR;
    }
    return ArtifactType.valueOf(value.trim().toUpperCase());
  }

  private ServerType serverType(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("serverType is required");
    }
    return ServerType.valueOf(value.trim().toUpperCase());
  }

  private ManagementMode managementMode(String value) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("managementMode is required");
    }
    return ManagementMode.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }

  private Mono<ReleaseArtifact> validateArtifact(
      ReleaseArtifact artifact,
      String applicationId,
      TargetEnvironment targetEnvironment) {
    if (!artifact.enabled()) {
      return Mono.error(new IllegalArgumentException("release artifact is disabled"));
    }
    if (!artifact.applicationId().equals(applicationId)) {
      return Mono.error(new IllegalArgumentException("release artifact application does not match"));
    }
    if (artifact.targetEnvironment() != targetEnvironment) {
      return Mono.error(new IllegalArgumentException("release artifact environment does not match"));
    }
    return Mono.just(artifact);
  }

  private Mono<Void> validateServerScriptProfile(ReleaseServer server) {
    if (server.managementMode() != ManagementMode.LIBERTY_SCRIPT_PROFILE) {
      return Mono.empty();
    }
    ReleaseScriptProfile scriptProfile = server.scriptProfile();
    return releaseCatalogStore
        .findScriptProfileDefinition(scriptProfile.profileId())
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "script profile is not configured")))
        .flatMap(definition -> {
          if (!definition.executable()) {
            return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "script profile is not approved and enabled"));
          }
          return Mono.empty();
        });
  }

  private List<ReleaseServer> selectNodes(List<ReleaseServer> servers, List<String> nodeIds) {
    Map<String, ReleaseServer> byId = servers.stream()
        .collect(Collectors.toMap(ReleaseServer::nodeId, Function.identity()));
    return nodeIds.stream()
        .map(nodeId -> {
          ReleaseServer server = byId.get(nodeId);
          if (server == null) {
            throw new IllegalArgumentException("release node is not registered: " + nodeId);
          }
          return server;
        })
        .filter(ReleaseServer::enabled)
        .sorted(Comparator.comparingInt(server -> nodeIds.indexOf(server.nodeId())))
        .toList();
  }

  private String releaseId(String applicationId, TargetEnvironment targetEnvironment) {
    String normalizedApplication = applicationId.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9-]+", "-");
    return "rel-" + normalizedApplication + "-" + targetEnvironment.value();
  }

  private void validateScriptProfileCompatEnvironment(String targetEnvironment) {
    if (targetEnvironment != null && !targetEnvironment.isBlank()) {
      TargetEnvironment.from(targetEnvironment);
    }
  }

  private ResponseStatusException badRequest(ReleaseWorkflowException exception) {
    return new ResponseStatusException(HttpStatus.BAD_REQUEST, exception.code(), exception);
  }

  private void validateObjectFields(JsonNode request, Set<String> allowedFields, String requestName) {
    if (request == null || !request.isObject()) {
      throw new IllegalArgumentException(requestName + " must be a JSON object");
    }
    Iterator<String> fieldNames = request.fieldNames();
    while (fieldNames.hasNext()) {
      String fieldName = fieldNames.next();
      if (!allowedFields.contains(fieldName)) {
        throw new IllegalArgumentException("unsupported " + requestName + " field: " + fieldName);
      }
    }
  }

  private <T> T parse(JsonNode request, Class<T> type, String errorMessage) {
    try {
      return objectMapper.treeToValue(request, type);
    } catch (JsonProcessingException exception) {
      throw new IllegalArgumentException(errorMessage, exception);
    }
  }

  private ExecutionContext executionContext(ServerWebExchange exchange) {
    return exchange.getRequiredAttribute(PolicyEnforcementWebFilter.EXECUTION_CONTEXT_ATTRIBUTE);
  }

  private record ApplicationRequest(
      String applicationId,
      String displayName,
      String artifactType,
      String healthCheckPath,
      Boolean enabled) {
  }

  private record CredentialRequest(
      String credentialAlias,
      String serverType,
      String secret) {
  }

  private record ServerRequest(
      String nodeId,
      String targetEnvironment,
      String serverType,
      String managementMode,
      String managementEndpoint,
      String applicationPath,
      String credentialAlias,
      ReleaseScriptProfile scriptProfile,
      Boolean enabled) {
  }

  private record ScriptProfileDefinitionRequest(
      String profileId,
      String displayName,
      String executablePath,
      String workingDirectory,
      List<String> arguments,
      List<Integer> successExitCodes,
      Integer timeoutSeconds,
      Boolean approved,
      Boolean enabled) {
  }

  private record PlanRequest(
      String applicationId,
      String targetEnvironment,
      String artifactId,
      List<String> nodeIds,
      String parametersHash) {
  }

  private record ConfirmationRequest(
      String confirmationId,
      String parametersHash) {
  }

  private record ReleaseConnectionTestResult(
      String nodeId,
      String status,
      String message,
      OffsetDateTime checkedAt) {
  }
}
