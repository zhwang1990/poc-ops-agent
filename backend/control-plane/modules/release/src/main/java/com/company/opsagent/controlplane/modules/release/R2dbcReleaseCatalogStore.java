package com.company.opsagent.controlplane.modules.release;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public class R2dbcReleaseCatalogStore implements ReleaseCatalogStore {

  private final DatabaseClient databaseClient;

  public R2dbcReleaseCatalogStore(DatabaseClient databaseClient) {
    this.databaseClient = databaseClient;
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
            Boolean.TRUE.equals(row.get("enabled", Boolean.class))))
        .all();
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
    return spec.fetch().rowsUpdated();
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

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
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
}
