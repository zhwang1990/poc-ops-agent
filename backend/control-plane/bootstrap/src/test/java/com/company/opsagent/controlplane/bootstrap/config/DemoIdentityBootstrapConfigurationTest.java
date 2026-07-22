package com.company.opsagent.controlplane.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.controlplane.modules.identity.application.Pbkdf2PasswordService;
import com.company.opsagent.controlplane.modules.identity.domain.Account;
import com.company.opsagent.controlplane.modules.identity.domain.AccountStatus;
import com.company.opsagent.controlplane.modules.identity.domain.MfaRequirement;
import com.company.opsagent.controlplane.modules.identity.domain.PasswordCredential;
import com.company.opsagent.controlplane.modules.identity.domain.PasswordState;
import com.company.opsagent.controlplane.modules.identity.infrastructure.AccountRepository;
import com.company.opsagent.controlplane.modules.identity.infrastructure.PasswordCredentialRepository;
import com.company.opsagent.controlplane.modules.identity.infrastructure.PasswordHasher;
import com.company.opsagent.controlplane.modules.identity.infrastructure.PasswordVerifier;
import com.company.opsagent.controlplane.modules.identity.infrastructure.R2dbcAccountRepository;
import com.company.opsagent.controlplane.modules.identity.infrastructure.R2dbcPasswordCredentialRepository;
import io.r2dbc.spi.ConnectionFactories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.core.DatabaseClient;

class DemoIdentityBootstrapConfigurationTest {

  private final Clock clock = Clock.fixed(Instant.parse("2026-06-30T12:00:00Z"), ZoneOffset.UTC);

  @Test
  void demoProfileKeepsLocalBuiltInIdentityAndLoopbackWorker() throws IOException {
    PropertySource<?> demo = loadDemoYaml();

    assertEquals("built-in", demo.getProperty("ops-agent.security.auth-mode"));
    assertEquals(true, demo.getProperty("ops-agent.security.browser-login-enabled"));
    assertEquals(false, demo.getProperty("ops-agent.local-oidc-provider.enabled"));
    assertEquals("http://127.0.0.1:8091", demo.getProperty("ops-agent.worker.base-url"));
    assertEquals(true, demo.getProperty("ops-agent.demo.identity-seed.enabled"));
    assertEquals("admin", demo.getProperty("ops-agent.demo.identity-seed.username"));
    assertEquals("${OPS_AGENT_DEMO_ADMIN_PASSWORD}",
        demo.getProperty("ops-agent.demo.identity-seed.password"));
  }

  @Test
  void seedsDemoAdminAccountWithReadablePasswordAndRoles() throws Exception {
    TestIdentityContext context = identityContext("demo-identity-seed");
    String seedPassword = runtimePassword();
    var properties = new DemoIdentityBootstrapConfiguration.DemoIdentitySeedProperties(
        true,
        "admin",
        seedPassword,
        List.of("ROLE_ops-admin", "ROLE_ops-reader"));

    new DemoIdentityBootstrapConfiguration()
        .demoIdentitySeedRunner(
            properties,
            context.accountRepository(),
            context.passwordCredentialRepository(),
            context.passwordHasher(),
            context.databaseClient(),
            clock)
        .run(applicationArguments());

    Account account = context.accountRepository().findByUsername("admin").orElseThrow();
    assertEquals("demo-admin", account.accountId());
    assertEquals(AccountStatus.ACTIVE, account.status());
    assertEquals(PasswordState.ACTIVE, account.passwordState());
    assertTrue(account.roleCodes().contains("ROLE_ops-admin"));
    assertTrue(account.roleCodes().contains("ROLE_ops-reader"));

    PasswordCredential credential = context.passwordCredentialRepository()
        .findActiveByAccountId(account.accountId())
        .orElseThrow();
    assertFalse(credential.mustChangeOnNextLogin());
    assertTrue(((PasswordVerifier) context.passwordHasher()).matches(seedPassword, credential));
  }

  @Test
  void doesNotOverwriteExistingAdminAccount() throws Exception {
    TestIdentityContext context = identityContext("demo-identity-existing");
    String existingPassword = runtimePassword();
    context.accountRepository().save(new Account(
        "existing-admin",
        "admin",
        AccountStatus.ACTIVE,
        PasswordState.ACTIVE,
        MfaRequirement.NOT_REQUIRED,
        List.of(),
        0,
        null));
    context.passwordCredentialRepository().save(context.passwordHasher().hash(
        "existing-admin",
        existingPassword,
        7L,
        false));

    String seedPassword = runtimePassword();
    var properties = new DemoIdentityBootstrapConfiguration.DemoIdentitySeedProperties(
        true,
        "admin",
        seedPassword,
        List.of("ROLE_ops-admin", "ROLE_ops-reader"));

    new DemoIdentityBootstrapConfiguration()
        .demoIdentitySeedRunner(
            properties,
            context.accountRepository(),
            context.passwordCredentialRepository(),
            context.passwordHasher(),
            context.databaseClient(),
            clock)
        .run(applicationArguments());

    Account account = context.accountRepository().findByUsername("admin").orElseThrow();
    PasswordCredential credential = context.passwordCredentialRepository()
        .findActiveByAccountId(account.accountId())
        .orElseThrow();

    assertEquals("existing-admin", account.accountId());
    assertEquals(7L, credential.passwordVersion());
    assertTrue(((PasswordVerifier) context.passwordHasher()).matches(existingPassword, credential));
  }

  private static String runtimePassword() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  private PropertySource<?> loadDemoYaml() throws IOException {
    List<PropertySource<?>> sources = new YamlPropertySourceLoader()
        .load("application-demo", new ClassPathResource("application-demo.yaml"));
    return sources.getFirst();
  }

  private TestIdentityContext identityContext(String databaseName) throws IOException {
    var connectionFactory = ConnectionFactories.get(
        "r2dbc:h2:mem:///" + databaseName + "-" + System.nanoTime() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1");
    DatabaseClient databaseClient = DatabaseClient.create(connectionFactory);
    applyIdentitySchema(databaseClient);
    PasswordHasher passwordHasher = new Pbkdf2PasswordService(clock);
    return new TestIdentityContext(
        databaseClient,
        new R2dbcAccountRepository(databaseClient, clock),
        new R2dbcPasswordCredentialRepository(databaseClient, clock),
        passwordHasher);
  }

  private void applyIdentitySchema(DatabaseClient databaseClient) throws IOException {
    String schema = Files.readString(Path.of(
        "..",
        "modules",
        "identity",
        "src",
        "main",
        "resources",
        "sql",
        "migrations",
        "V001__identity_schema.sql"));
    for (String statement : schema.split(";")) {
      String sql = statement.trim();
      if (!sql.isEmpty()) {
        databaseClient.sql(sql).fetch().rowsUpdated().block();
      }
    }
  }

  private ApplicationArguments applicationArguments() {
    return new org.springframework.boot.DefaultApplicationArguments();
  }

  private record TestIdentityContext(
      DatabaseClient databaseClient,
      AccountRepository accountRepository,
      PasswordCredentialRepository passwordCredentialRepository,
      PasswordHasher passwordHasher) {
  }
}
