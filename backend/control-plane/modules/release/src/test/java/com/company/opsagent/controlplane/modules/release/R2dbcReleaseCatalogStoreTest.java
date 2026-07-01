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
    initialize(connectionFactory, new ClassPathResource("sql/migrations/V001__release_center_schema.sql"));
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
