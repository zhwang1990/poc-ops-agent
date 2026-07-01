package com.company.opsagent.controlplane.bootstrap.api;

import com.company.opsagent.controlplane.bootstrap.security.PolicyEnforcementWebFilter;
import com.company.opsagent.controlplane.modules.audit.ExecutionContext;
import com.company.opsagent.controlplane.modules.release.ArtifactType;
import com.company.opsagent.controlplane.modules.release.ReleaseApplication;
import com.company.opsagent.controlplane.modules.release.ReleaseCatalogStore;
import com.company.opsagent.controlplane.modules.release.ReleaseCredentialService;
import com.company.opsagent.controlplane.modules.release.ReleaseCredentialSummary;
import com.company.opsagent.controlplane.modules.release.ServerType;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
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

  private final ReleaseCatalogStore releaseCatalogStore;
  private final ReleaseCredentialService releaseCredentialService;
  private final ObjectMapper objectMapper;

  public ReleaseCenterController(
      ReleaseCatalogStore releaseCatalogStore,
      ReleaseCredentialService releaseCredentialService,
      ObjectMapper objectMapper) {
    this.releaseCatalogStore = releaseCatalogStore;
    this.releaseCredentialService = releaseCredentialService;
    this.objectMapper = objectMapper;
  }

  @GetMapping("/applications")
  public Mono<List<ReleaseApplication>> applications() {
    return releaseCatalogStore.listApplications().collectList();
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

  private record ReleaseConnectionTestResult(
      String nodeId,
      String status,
      String message,
      OffsetDateTime checkedAt) {
  }
}
