package com.company.opsagent.controlplane.modules.release;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;

class R2dbcReleaseCatalogStoreTest {

  @Test
  void storesCatalogRecordsWithoutCredentialPlaintext() {
    ReleaseCatalogStore store = store();

    store.saveApplication(ReleaseApplication.create("orders", "订单服务", ArtifactType.WAR, "/health", true)).block();
    store.saveEnvironmentPolicy(ReleaseEnvironmentPolicy.defaultFor(TargetEnvironment.SIT)
        .requireConfirmation(true)
        .withLogAnalysis(true)).block();
    store.saveServer(ReleaseServer.create(
        "sit-tomcat-1",
        "sit",
        ServerType.TOMCAT,
        ManagementMode.TOMCAT_WAR_UPLOAD,
        "https://tomcat-sit.example",
        "/apps/orders",
        null,
        true)).block();
    store.saveArtifact(ReleaseArtifact.create(
        "artifact-1",
        "orders",
        "sit",
        ArtifactType.WAR,
        "sha256:abc123",
        "orders.war",
        "release-artifacts/orders/artifact-1.war",
        1024,
        "alice",
        "TOMCAT_UPLOAD",
        true)).block();
    OffsetDateTime now = OffsetDateTime.parse("2026-07-01T00:00:00Z");
    store.saveCredential(new ReleaseCredential(
        "sit-tomcat",
        ServerType.TOMCAT,
        "encrypted-value",
        "nonce-value",
        "AES_GCM_V1",
        "fp_example",
        now,
        now)).block();

    List<ReleaseApplication> applications = store.listApplications().collectList().block();
    List<ReleaseServer> servers = store.listServers("sit").collectList().block();
    ReleaseEnvironmentPolicy policy = store.findEnvironmentPolicy(TargetEnvironment.SIT).block();
    ReleaseCredential credential = store.findCredential("sit-tomcat").block();

    assertEquals(1, applications.size());
    assertEquals("orders", applications.get(0).applicationId());
    assertEquals(1, servers.size());
    assertEquals("sit-tomcat-1", servers.get(0).nodeId());
    assertNull(servers.get(0).credentialAlias());
    assertTrue(policy.confirmationRequired());
    assertTrue(policy.logAnalysisEnabled());
    assertEquals("fp_example", credential.fingerprint());
    assertEquals("encrypted-value", credential.ciphertext());
  }

  @Test
  void storesLibertyScriptProfileServerParameters() {
    ReleaseCatalogStore store = store();
    ReleaseServer server = ReleaseServer.create(
        "dev-liberty-1",
        "dev",
        ServerType.LIBERTY,
        ManagementMode.LIBERTY_SCRIPT_PROFILE,
        "https://liberty-dev.example",
        "/orders",
        "liberty-dev",
        new ReleaseScriptProfile(
            "liberty-war-deploy",
            List.of(
                new ReleaseScriptParameter("serverName", "defaultServer"),
                new ReleaseScriptParameter("applicationName", "orders"))),
        true);

    store.saveServer(server).block();

    ReleaseServer loaded = store.findServer("dev-liberty-1").block();
    assertEquals("liberty-war-deploy", loaded.scriptProfile().profileId());
    assertEquals("defaultServer", loaded.scriptProfile().parameters().get(0).value());
    assertEquals("orders", loaded.scriptProfile().parameters().get(1).value());
  }

  @Test
  void storesScriptProfileDefinitionsByEnvironment() {
    ReleaseCatalogStore store = store();
    ReleaseScriptProfileDefinition profile = ReleaseScriptProfileDefinition.create(
        "liberty-war-deploy",
        "dev",
        "Liberty WAR deploy",
        "C:\\ops\\scripts\\liberty-war-deploy.cmd",
        "C:\\ops-agent\\work\\release",
        List.of("{{param.serverName}}", "{{param.applicationName}}", "{{param.artifactPath}}"),
        List.of("serverName", "applicationName", "artifactPath"),
        List.of("serverName", "applicationName", "artifactPath"),
        List.of(0),
        600,
        true,
        true);

    store.saveScriptProfileDefinition(profile).block();

    ReleaseScriptProfileDefinition loaded = store
        .findScriptProfileDefinition("dev", "liberty-war-deploy")
        .block();
    List<ReleaseScriptProfileDefinition> profiles = store
        .listScriptProfileDefinitions("dev")
        .collectList()
        .block();

    assertEquals("liberty-war-deploy", loaded.profileId());
    assertEquals(TargetEnvironment.DEV, loaded.targetEnvironment());
    assertEquals("C:\\ops\\scripts\\liberty-war-deploy.cmd", loaded.executablePath());
    assertEquals("{{param.artifactPath}}", loaded.arguments().get(2));
    assertEquals(List.of("serverName", "applicationName", "artifactPath"), loaded.allowedParameters());
    assertEquals(List.of(0), loaded.successExitCodes());
    assertEquals(600, loaded.timeoutSeconds());
    assertTrue(loaded.approved());
    assertTrue(loaded.enabled());
    assertEquals(1, profiles.size());
  }

  @Test
  void deletesReleaseServerByNodeId() {
    ReleaseCatalogStore store = store();
    store.saveServer(ReleaseServer.create(
        "dev-tomcat-delete",
        "dev",
        ServerType.TOMCAT,
        ManagementMode.TOMCAT_WAR_UPLOAD,
        "https://tomcat-dev.example",
        "/orders",
        "tomcat-dev",
        true)).block();

    store.deleteServer("dev-tomcat-delete").block();

    List<ReleaseServer> servers = store.listServers("dev").collectList().block();
    assertTrue(servers.isEmpty());
  }

  @Test
  void releaseCredentialTableHasNoPlaintextColumn() {
    DatabaseClient databaseClient = databaseClient();

    Number plaintextColumns = databaseClient.sql("""
            select count(*) as column_count
            from information_schema.columns
            where table_name = 'RELEASE_CREDENTIAL'
              and lower(column_name) like '%plaintext%'
            """)
        .map((row, metadata) -> row.get("column_count", Number.class))
        .one()
        .block();

    assertEquals(0L, plaintextColumns.longValue());
  }

  private ReleaseCatalogStore store() {
    return new R2dbcReleaseCatalogStore(databaseClient());
  }

  private DatabaseClient databaseClient() {
    var connectionFactory = connectionFactory("release-catalog");
    initialize(
        connectionFactory,
        new ClassPathResource("sql/migrations/V001__release_center_schema.sql"),
        new ClassPathResource("sql/migrations/V004__release_script_profile_definition.sql"));
    return DatabaseClient.create(connectionFactory);
  }

  private ConnectionFactory connectionFactory(String prefix) {
    return ConnectionFactories.get("r2dbc:h2:mem:///" + prefix + "-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
  }

  private void initialize(ConnectionFactory connectionFactory, ClassPathResource... scripts) {
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(scripts));
    initializer.afterPropertiesSet();
  }
}
