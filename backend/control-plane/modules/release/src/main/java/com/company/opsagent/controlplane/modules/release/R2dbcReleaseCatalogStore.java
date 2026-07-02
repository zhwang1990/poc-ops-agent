package com.company.opsagent.controlplane.modules.release;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class R2dbcReleaseCatalogStore implements ReleaseCatalogStore {

  private static final TypeReference<List<ReleaseScriptParameter>> SCRIPT_PARAMETERS_TYPE = new TypeReference<>() {
  };

  private final DatabaseClient databaseClient;
  private final ObjectMapper objectMapper;

  public R2dbcReleaseCatalogStore(DatabaseClient databaseClient) {
    this(databaseClient, new ObjectMapper());
  }

  public R2dbcReleaseCatalogStore(DatabaseClient databaseClient, ObjectMapper objectMapper) {
    this.databaseClient = databaseClient;
    this.objectMapper = objectMapper;
  }

  @Override
  public Mono<ReleaseApplication> saveApplication(ReleaseApplication application) {
    return databaseClient.sql("delete from release_application where application_id = :applicationId")
        .bind("applicationId", application.applicationId())
        .fetch()
        .rowsUpdated()
        .then(insertApplication(application))
        .thenReturn(application);
  }

  @Override
  public Flux<ReleaseApplication> listApplications() {
    return databaseClient.sql("""
            select *
            from release_application
            order by application_id asc
            """)
        .map((row, metadata) -> new ReleaseApplication(
            row.get("application_id", String.class),
            row.get("display_name", String.class),
            ArtifactType.valueOf(row.get("artifact_type", String.class)),
            row.get("health_path", String.class),
            Boolean.TRUE.equals(row.get("enabled", Boolean.class))))
        .all();
  }

  @Override
  public Mono<ReleaseEnvironmentPolicy> saveEnvironmentPolicy(ReleaseEnvironmentPolicy policy) {
    return databaseClient.sql("delete from release_environment_policy where target_environment = :targetEnvironment")
        .bind("targetEnvironment", policy.targetEnvironment().value())
        .fetch()
        .rowsUpdated()
        .then(insertEnvironmentPolicy(policy))
        .thenReturn(policy);
  }

  @Override
  public Mono<ReleaseEnvironmentPolicy> findEnvironmentPolicy(TargetEnvironment targetEnvironment) {
    return databaseClient.sql("""
            select *
            from release_environment_policy
            where target_environment = :targetEnvironment
            """)
        .bind("targetEnvironment", targetEnvironment.value())
        .map((row, metadata) -> new ReleaseEnvironmentPolicy(
            TargetEnvironment.from(row.get("target_environment", String.class)),
            Boolean.TRUE.equals(row.get("allow_deploy", Boolean.class)),
            Boolean.TRUE.equals(row.get("allow_start", Boolean.class)),
            Boolean.TRUE.equals(row.get("allow_stop", Boolean.class)),
            Boolean.TRUE.equals(row.get("allow_rollback", Boolean.class)),
            Boolean.TRUE.equals(row.get("require_confirmation", Boolean.class)),
            number(row.get("timeout_seconds")).intValue(),
            Boolean.TRUE.equals(row.get("stop_on_node_failure", Boolean.class)),
            Boolean.TRUE.equals(row.get("log_analysis_enabled", Boolean.class)),
            true))
        .one();
  }

  @Override
  public Mono<ReleaseServer> saveServer(ReleaseServer server) {
    return databaseClient.sql("delete from release_server where node_id = :nodeId")
        .bind("nodeId", server.nodeId())
        .fetch()
        .rowsUpdated()
        .then(insertServer(server))
        .thenReturn(server);
  }

  @Override
  public Mono<ReleaseServer> findServer(String nodeId) {
    return databaseClient.sql("""
            select *
            from release_server
            where node_id = :nodeId
            """)
        .bind("nodeId", ReleaseValues.requiredText(nodeId, "nodeId"))
        .map((row, metadata) -> new ReleaseServer(
            row.get("node_id", String.class),
            TargetEnvironment.from(row.get("target_environment", String.class)),
            ServerType.valueOf(row.get("server_type", String.class)),
            ManagementMode.valueOf(row.get("management_mode", String.class)),
            row.get("management_endpoint", String.class),
            row.get("application_path", String.class),
            row.get("credential_alias", String.class),
            scriptProfile(
                row.get("script_profile_id", String.class),
                row.get("script_parameters_json", String.class)),
            Boolean.TRUE.equals(row.get("enabled", Boolean.class))))
        .one();
  }

  @Override
  public Flux<ReleaseServer> listServers(String targetEnvironment) {
    TargetEnvironment environment = TargetEnvironment.from(targetEnvironment);
    return databaseClient.sql("""
            select *
            from release_server
            where target_environment = :targetEnvironment
            order by node_id asc
            """)
        .bind("targetEnvironment", environment.value())
        .map((row, metadata) -> new ReleaseServer(
            row.get("node_id", String.class),
            TargetEnvironment.from(row.get("target_environment", String.class)),
            ServerType.valueOf(row.get("server_type", String.class)),
            ManagementMode.valueOf(row.get("management_mode", String.class)),
            row.get("management_endpoint", String.class),
            row.get("application_path", String.class),
            row.get("credential_alias", String.class),
            scriptProfile(
                row.get("script_profile_id", String.class),
                row.get("script_parameters_json", String.class)),
            Boolean.TRUE.equals(row.get("enabled", Boolean.class))))
        .all();
  }

  @Override
  public Mono<Void> deleteServer(String nodeId) {
    return databaseClient.sql("delete from release_server where node_id = :nodeId")
        .bind("nodeId", ReleaseValues.requiredText(nodeId, "nodeId"))
        .fetch()
        .rowsUpdated()
        .then();
  }

  @Override
  public Mono<ReleaseArtifact> saveArtifact(ReleaseArtifact artifact) {
    OffsetDateTime now = now();
    return databaseClient.sql("delete from release_artifact where artifact_id = :artifactId")
        .bind("artifactId", artifact.artifactId())
        .fetch()
        .rowsUpdated()
        .then(databaseClient.sql("""
                insert into release_artifact (
                  artifact_id,
                  application_id,
                  target_environment,
                  artifact_type,
                  checksum,
                  original_filename,
                  storage_key,
                  byte_size,
                  uploaded_by,
                  created_at,
                  updated_at
                ) values (
                  :artifactId,
                  :applicationId,
                  :targetEnvironment,
                  :artifactType,
                  :checksum,
                  :originalFilename,
                  :storageKey,
                  :byteSize,
                  :uploadedBy,
                  :createdAt,
                  :updatedAt
                )
                """)
            .bind("artifactId", artifact.artifactId())
            .bind("applicationId", artifact.applicationId())
            .bind("targetEnvironment", artifact.targetEnvironment().value())
            .bind("artifactType", artifact.artifactType().name())
            .bind("checksum", artifact.checksum())
            .bind("originalFilename", artifact.originalFilename())
            .bind("storageKey", artifact.storageKey())
            .bind("byteSize", artifact.byteSize())
            .bind("uploadedBy", artifact.uploadedBy())
            .bind("createdAt", now)
            .bind("updatedAt", now)
            .fetch()
            .rowsUpdated())
        .thenReturn(artifact);
  }

  @Override
  public Flux<ReleaseArtifact> listArtifacts(String targetEnvironment) {
    TargetEnvironment environment = TargetEnvironment.from(targetEnvironment);
    return databaseClient.sql("""
            select *
            from release_artifact
            where target_environment = :targetEnvironment
            order by updated_at desc, artifact_id asc
            """)
        .bind("targetEnvironment", environment.value())
        .map((row, metadata) -> artifact(row))
        .all();
  }

  @Override
  public Mono<ReleaseArtifact> findArtifact(String artifactId) {
    String id = ReleaseValues.requiredText(artifactId, "artifactId");
    return databaseClient.sql("""
            select *
            from release_artifact
            where artifact_id = :artifactId
            """)
        .bind("artifactId", id)
        .map((row, metadata) -> artifact(row))
        .one();
  }

  @Override
  public Mono<ReleaseCredential> saveCredential(ReleaseCredential credential) {
    return databaseClient.sql("delete from release_credential where credential_alias = :credentialAlias")
        .bind("credentialAlias", credential.credentialAlias())
        .fetch()
        .rowsUpdated()
        .then(databaseClient.sql("""
                insert into release_credential (
                  credential_alias,
                  server_type,
                  ciphertext,
                  nonce,
                  algorithm,
                  fingerprint,
                  created_at,
                  updated_at
                ) values (
                  :credentialAlias,
                  :serverType,
                  :ciphertext,
                  :nonce,
                  :algorithm,
                  :fingerprint,
                  :createdAt,
                  :updatedAt
                )
                """)
            .bind("credentialAlias", credential.credentialAlias())
            .bind("serverType", credential.serverType().name())
            .bind("ciphertext", credential.ciphertext())
            .bind("nonce", credential.nonce())
            .bind("algorithm", credential.algorithm())
            .bind("fingerprint", credential.fingerprint())
            .bind("createdAt", credential.createdAt())
            .bind("updatedAt", credential.updatedAt())
            .fetch()
            .rowsUpdated())
        .thenReturn(credential);
  }

  @Override
  public Mono<ReleaseCredential> findCredential(String credentialAlias) {
    String alias = ReleaseValues.requiredText(credentialAlias, "credentialAlias");
    return databaseClient.sql("""
            select *
            from release_credential
            where credential_alias = :credentialAlias
            """)
        .bind("credentialAlias", alias)
        .map((row, metadata) -> new ReleaseCredential(
            row.get("credential_alias", String.class),
            ServerType.valueOf(row.get("server_type", String.class)),
            row.get("ciphertext", String.class),
            row.get("nonce", String.class),
            row.get("algorithm", String.class),
            row.get("fingerprint", String.class),
            row.get("created_at", OffsetDateTime.class),
            row.get("updated_at", OffsetDateTime.class)))
        .one();
  }

  @Override
  public Mono<ReleasePlan> savePlan(ReleasePlan plan) {
    OffsetDateTime createdAt = at(plan.createdAt());
    OffsetDateTime updatedAt = at(plan.updatedAt());
    DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
            insert into release_plan (
              release_id,
              workflow_id,
              application_id,
              target_environment,
              artifact_id,
              operation,
              status,
              parameters_hash,
              policy_version,
              confirmed_by,
              confirmed_at,
              created_by,
              created_at,
              updated_at
            ) values (
              :releaseId,
              :workflowId,
              :applicationId,
              :targetEnvironment,
              :artifactId,
              :operation,
              :status,
              :parametersHash,
              :policyVersion,
              :confirmedBy,
              :confirmedAt,
              :createdBy,
              :createdAt,
              :updatedAt
            )
            """)
        .bind("releaseId", plan.releaseId())
        .bind("workflowId", workflowId(plan.releaseId()))
        .bind("applicationId", plan.applicationId())
        .bind("targetEnvironment", plan.targetEnvironment().value())
        .bind("operation", "DEPLOY")
        .bind("status", plan.status().name())
        .bind("parametersHash", plan.parametersHash())
        .bind("policyVersion", "release-center-policy-v1")
        .bind("createdBy", "system")
        .bind("createdAt", createdAt)
        .bind("updatedAt", updatedAt);
    spec = bindNullable(spec, "artifactId", plan.artifactId());
    spec = bindNullable(spec, "confirmedBy", plan.confirmation() == null ? null : plan.confirmation().confirmedBy());
    spec = bindNullableTime(spec, "confirmedAt", plan.confirmation() == null ? null : at(plan.confirmation().confirmedAt()));
    return databaseClient.sql("delete from release_node_step where release_id = :releaseId")
        .bind("releaseId", plan.releaseId())
        .fetch()
        .rowsUpdated()
        .then(databaseClient.sql("delete from release_plan where release_id = :releaseId")
            .bind("releaseId", plan.releaseId())
            .fetch()
            .rowsUpdated())
        .then(spec.fetch().rowsUpdated())
        .thenMany(Flux.fromIterable(plan.nodes())
            .concatMap(node -> insertNode(plan.releaseId(), node)))
        .then(Mono.just(plan));
  }

  @Override
  public Mono<ReleasePlan> findPlan(String releaseId) {
    String id = ReleaseValues.requiredText(releaseId, "releaseId");
    return databaseClient.sql("""
            select *
            from release_plan
            where release_id = :releaseId
            """)
        .bind("releaseId", id)
        .map((row, metadata) -> planRow(row))
        .one()
        .flatMap(this::withNodes);
  }

  @Override
  public Flux<ReleasePlan> listPlans() {
    return databaseClient.sql("""
            select *
            from release_plan
            order by updated_at desc, release_id asc
            """)
        .map((row, metadata) -> planRow(row))
        .all()
        .concatMap(this::withNodes);
  }

  private Mono<Long> insertApplication(ReleaseApplication application) {
    OffsetDateTime now = now();
    return databaseClient.sql("""
            insert into release_application (
              application_id,
              display_name,
              artifact_type,
              health_path,
              enabled,
              created_at,
              updated_at
            ) values (
              :applicationId,
              :displayName,
              :artifactType,
              :healthPath,
              :enabled,
              :createdAt,
              :updatedAt
            )
            """)
        .bind("applicationId", application.applicationId())
        .bind("displayName", application.displayName())
        .bind("artifactType", application.artifactType().name())
        .bind("healthPath", application.healthCheckPath())
        .bind("enabled", application.enabled())
        .bind("createdAt", now)
        .bind("updatedAt", now)
        .fetch()
        .rowsUpdated();
  }

  private Mono<Long> insertEnvironmentPolicy(ReleaseEnvironmentPolicy policy) {
    OffsetDateTime now = now();
    return databaseClient.sql("""
            insert into release_environment_policy (
              target_environment,
              allow_deploy,
              allow_start,
              allow_stop,
              allow_rollback,
              require_confirmation,
              timeout_seconds,
              stop_on_node_failure,
              log_analysis_enabled,
              created_at,
              updated_at
            ) values (
              :targetEnvironment,
              :allowDeploy,
              :allowStart,
              :allowStop,
              :allowRollback,
              :requireConfirmation,
              :timeoutSeconds,
              :stopOnNodeFailure,
              :logAnalysisEnabled,
              :createdAt,
              :updatedAt
            )
            """)
        .bind("targetEnvironment", policy.targetEnvironment().value())
        .bind("allowDeploy", policy.allowDeploy())
        .bind("allowStart", policy.allowStart())
        .bind("allowStop", policy.allowStop())
        .bind("allowRollback", policy.allowRollback())
        .bind("requireConfirmation", policy.confirmationRequired())
        .bind("timeoutSeconds", policy.timeoutSeconds())
        .bind("stopOnNodeFailure", policy.stopOnNodeFailure())
        .bind("logAnalysisEnabled", policy.logAnalysisEnabled())
        .bind("createdAt", now)
        .bind("updatedAt", now)
        .fetch()
        .rowsUpdated();
  }

  private Mono<Long> insertServer(ReleaseServer server) {
    OffsetDateTime now = now();
    DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
            insert into release_server (
              node_id,
              target_environment,
              server_type,
              management_mode,
              management_endpoint,
              application_path,
              credential_alias,
              script_profile_id,
              script_parameters_json,
              enabled,
              created_at,
              updated_at
            ) values (
              :nodeId,
              :targetEnvironment,
              :serverType,
              :managementMode,
              :managementEndpoint,
              :applicationPath,
              :credentialAlias,
              :scriptProfileId,
              :scriptParametersJson,
              :enabled,
              :createdAt,
              :updatedAt
            )
            """)
        .bind("nodeId", server.nodeId())
        .bind("targetEnvironment", server.targetEnvironment().value())
        .bind("serverType", server.serverType().name())
        .bind("managementMode", server.managementMode().name())
        .bind("managementEndpoint", server.managementEndpoint())
        .bind("enabled", server.enabled())
        .bind("createdAt", now)
        .bind("updatedAt", now);
    spec = bindNullable(spec, "applicationPath", server.applicationPath());
    spec = bindNullable(spec, "credentialAlias", server.credentialAlias());
    spec = bindNullable(spec, "scriptProfileId", server.scriptProfile() == null ? null : server.scriptProfile().profileId());
    spec = bindNullable(spec, "scriptParametersJson", scriptParametersJson(server.scriptProfile()));
    return spec.fetch().rowsUpdated();
  }

  private Mono<Long> insertNode(String releaseId, ReleaseNodeStep node) {
    DatabaseClient.GenericExecuteSpec spec = databaseClient.sql("""
            insert into release_node_step (
              release_id,
              step_sequence,
              node_id,
              action,
              status,
              error_code,
              error_message,
              started_at,
              completed_at
            ) values (
              :releaseId,
              :stepSequence,
              :nodeId,
              :action,
              :status,
              :errorCode,
              :errorMessage,
              :startedAt,
              :completedAt
            )
            """)
        .bind("releaseId", releaseId)
        .bind("stepSequence", node.sequence())
        .bind("nodeId", node.nodeId())
        .bind("action", "DEPLOY")
        .bind("status", node.status().name());
    spec = bindNullable(spec, "errorCode", node.statusReason());
    spec = bindNullable(spec, "errorMessage", node.statusReason());
    spec = bindNullableTime(spec, "startedAt", node.startedAt() == null ? null : at(node.startedAt()));
    spec = bindNullableTime(spec, "completedAt", node.completedAt() == null ? null : at(node.completedAt()));
    return spec.fetch().rowsUpdated();
  }

  private Mono<ReleasePlan> withNodes(PlanRow plan) {
    return databaseClient.sql("""
            select
              p.*,
              n.step_sequence,
              n.status,
              n.error_code,
              n.started_at,
              n.completed_at
            from release_node_step n
            join release_server p on p.node_id = n.node_id
            where n.release_id = :releaseId
            order by n.step_sequence asc
            """)
        .bind("releaseId", plan.releaseId())
        .map((row, metadata) -> new ReleaseNodeStep(
            row.get("node_id", String.class),
            ServerType.valueOf(row.get("server_type", String.class)),
            ManagementMode.valueOf(row.get("management_mode", String.class)),
            number(row.get("step_sequence")).intValue(),
            ReleaseNodeStatus.valueOf(row.get("status", String.class)),
            row.get("error_code", String.class),
            instant(row.get("started_at", OffsetDateTime.class)),
            instant(row.get("completed_at", OffsetDateTime.class))))
        .all()
        .collectList()
        .map(nodes -> plan.toReleasePlan(nodes));
  }

  private ReleaseArtifact artifact(io.r2dbc.spi.Row row) {
    return new ReleaseArtifact(
        row.get("artifact_id", String.class),
        row.get("application_id", String.class),
        TargetEnvironment.from(row.get("target_environment", String.class)),
        ArtifactType.valueOf(row.get("artifact_type", String.class)),
        row.get("checksum", String.class),
        row.get("original_filename", String.class),
        row.get("storage_key", String.class),
        number(row.get("byte_size")).longValue(),
        row.get("uploaded_by", String.class),
        "OPERATOR_UPLOAD",
        true);
  }

  private PlanRow planRow(io.r2dbc.spi.Row row) {
    return new PlanRow(
        row.get("release_id", String.class),
        row.get("application_id", String.class),
        TargetEnvironment.from(row.get("target_environment", String.class)),
        row.get("artifact_id", String.class),
        ReleaseStatus.valueOf(row.get("status", String.class)),
        row.get("parameters_hash", String.class),
        row.get("confirmed_by", String.class),
        instant(row.get("confirmed_at", OffsetDateTime.class)),
        instant(row.get("created_at", OffsetDateTime.class)),
        instant(row.get("updated_at", OffsetDateTime.class)));
  }

  private DatabaseClient.GenericExecuteSpec bindNullable(
      DatabaseClient.GenericExecuteSpec spec,
      String name,
      String value) {
    if (value == null) {
      return spec.bindNull(name, String.class);
    }
    return spec.bind(name, value);
  }

  private DatabaseClient.GenericExecuteSpec bindNullableTime(
      DatabaseClient.GenericExecuteSpec spec,
      String name,
      OffsetDateTime value) {
    if (value == null) {
      return spec.bindNull(name, OffsetDateTime.class);
    }
    return spec.bind(name, value);
  }

  private ReleaseScriptProfile scriptProfile(String profileId, String parametersJson) {
    String normalizedProfileId = ReleaseValues.optionalText(profileId);
    if (normalizedProfileId == null) {
      return null;
    }
    try {
      List<ReleaseScriptParameter> parameters = parametersJson == null || parametersJson.isBlank()
          ? List.of()
          : objectMapper.readValue(parametersJson, SCRIPT_PARAMETERS_TYPE);
      return new ReleaseScriptProfile(normalizedProfileId, parameters);
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("release server script parameters are invalid", exception);
    }
  }

  private String scriptParametersJson(ReleaseScriptProfile scriptProfile) {
    if (scriptProfile == null) {
      return null;
    }
    try {
      return objectMapper.writeValueAsString(scriptProfile.parameters());
    } catch (JsonProcessingException exception) {
      throw new IllegalStateException("release server script parameters cannot be serialized", exception);
    }
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private OffsetDateTime at(Instant instant) {
    return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
  }

  private Instant instant(OffsetDateTime value) {
    return value == null ? null : value.toInstant();
  }

  private String workflowId(String releaseId) {
    return UUID.nameUUIDFromBytes(releaseId.getBytes(StandardCharsets.UTF_8)).toString();
  }

  private Number number(Object value) {
    if (value instanceof Number number) {
      return number;
    }
    return Mono.justOrEmpty(value)
        .map(Object::toString)
        .map(Long::parseLong)
        .blockOptional()
        .orElseThrow(() -> new IllegalArgumentException("numeric value is missing"));
  }

  private record PlanRow(
      String releaseId,
      String applicationId,
      TargetEnvironment targetEnvironment,
      String artifactId,
      ReleaseStatus status,
      String parametersHash,
      String confirmedBy,
      Instant confirmedAt,
      Instant createdAt,
      Instant updatedAt) {

    ReleasePlan toReleasePlan(List<ReleaseNodeStep> nodes) {
      ReleaseConfirmation confirmation = confirmedBy == null
          ? null
          : new ReleaseConfirmation("stored-confirmation", parametersHash, confirmedBy, confirmedAt);
      return new ReleasePlan(
          releaseId,
          applicationId,
          targetEnvironment,
          artifactId,
          status,
          nodes,
          parametersHash,
          confirmation,
          true,
          createdAt,
          updatedAt);
    }
  }
}
