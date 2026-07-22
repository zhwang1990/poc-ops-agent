package com.company.opsagent.controlplane.modules.identity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.controlplane.modules.identity.application.Pbkdf2PasswordService;
import com.company.opsagent.controlplane.modules.identity.domain.Account;
import com.company.opsagent.controlplane.modules.identity.domain.AccountSession;
import com.company.opsagent.controlplane.modules.identity.domain.AccountSessionState;
import com.company.opsagent.controlplane.modules.identity.domain.AccountStatus;
import com.company.opsagent.controlplane.modules.identity.domain.MfaRequirement;
import com.company.opsagent.controlplane.modules.identity.domain.PasswordCredential;
import com.company.opsagent.controlplane.modules.identity.domain.PasswordState;
import com.company.opsagent.controlplane.modules.identity.infrastructure.AccountRepository;
import com.company.opsagent.controlplane.modules.identity.infrastructure.AccountSessionRepository;
import com.company.opsagent.controlplane.modules.identity.infrastructure.PasswordCredentialRepository;
import com.company.opsagent.controlplane.modules.identity.infrastructure.PasswordHasher;
import com.company.opsagent.controlplane.modules.identity.infrastructure.R2dbcAccountRepository;
import com.company.opsagent.controlplane.modules.identity.infrastructure.R2dbcAccountSessionRepository;
import com.company.opsagent.controlplane.modules.identity.infrastructure.R2dbcPasswordCredentialRepository;
import io.r2dbc.spi.ConnectionFactories;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * 验证正式身份仓储的 R2DBC 读写与状态迁移。
 */
class R2dbcIdentityRepositoriesIntegrationTest {

  @Test
  void persistsAccountCredentialAndSessionLifecycle() throws IOException {
    Clock clock = Clock.fixed(Instant.parse("2026-06-07T12:00:00Z"), ZoneOffset.UTC);
    DatabaseClient databaseClient = DatabaseClient.create(
        ConnectionFactories.get("r2dbc:h2:mem:///identity-repo-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1"));
    applySchema(databaseClient);

    AccountRepository accountRepository = new R2dbcAccountRepository(databaseClient, clock);
    PasswordCredentialRepository credentialRepository = new R2dbcPasswordCredentialRepository(databaseClient, clock);
    AccountSessionRepository sessionRepository = new R2dbcAccountSessionRepository(databaseClient, clock);
    PasswordHasher passwordHasher = new Pbkdf2PasswordService(clock);
    OffsetDateTime now = OffsetDateTime.now(clock);

    accountRepository.save(new Account(
        "account-1",
        "alice",
        AccountStatus.ACTIVE,
        PasswordState.ACTIVE,
        MfaRequirement.NOT_REQUIRED,
        List.of(),
        0,
        null));
    insertRoleGrant(databaseClient, "grant-1", "account-1", "ROLE_ops-reader", now.minusMinutes(5));

    Account storedAccount = accountRepository.findByUsername("alice").orElseThrow();
    assertEquals(List.of("ROLE_ops-reader"), storedAccount.roleCodes());

    accountRepository.save(new Account(
        "account-1",
        "alice",
        AccountStatus.LOCKED,
        PasswordState.ACTIVE,
        MfaRequirement.NOT_REQUIRED,
        storedAccount.roleCodes(),
        5,
        now.plusMinutes(15)));
    Account lockedAccount = accountRepository.findByAccountId("account-1").orElseThrow();
    assertEquals(AccountStatus.LOCKED, lockedAccount.status());
    assertEquals(5, lockedAccount.failedLoginCount());
    assertNotNull(lockedAccount.lockedUntil());

    PasswordCredential firstCredential = passwordHasher.hash(
        "account-1", IdentityTestSecretMaterial.value(), 1L, false);
    credentialRepository.save(firstCredential);
    PasswordCredential secondCredential = passwordHasher.hash(
        "account-1", IdentityTestSecretMaterial.value(), 2L, true);
    credentialRepository.save(secondCredential);

    PasswordCredential activeCredential = credentialRepository.findActiveByAccountId("account-1").orElseThrow();
    assertEquals(secondCredential.credentialId(), activeCredential.credentialId());
    assertEquals(2L, activeCredential.passwordVersion());
    assertTrue(Boolean.TRUE.equals(databaseClient.sql("""
            select count(*) as total
            from identity_password_credential
            where account_id = :accountId
              and rotated_at is not null
            """)
        .bind("accountId", "account-1")
        .map((row, metadata) -> row.get("total", Long.class) > 0)
        .one()
        .block()));

    AccountSession session = new AccountSession(
        "session-1",
        "account-1",
        AccountSessionState.ACTIVE,
        now.plusMinutes(15),
        now.plusHours(8),
        null,
        true);
    sessionRepository.save(session);
    AccountSession storedSession = sessionRepository.findBySessionId("session-1").orElseThrow();
    assertTrue(storedSession.passwordChangeRequired());

    sessionRepository.touch("session-1", now.plusMinutes(20));
    AccountSession touchedSession = sessionRepository.findBySessionId("session-1").orElseThrow();
    assertEquals(now.plusMinutes(20), touchedSession.idleExpiresAt());

    sessionRepository.revokeBySessionId("session-1", now.plusMinutes(1), "LOGOUT");
    AccountSession revokedSession = sessionRepository.findBySessionId("session-1").orElseThrow();
    assertEquals(AccountSessionState.REVOKED, revokedSession.state());
    assertEquals(now.plusMinutes(1), revokedSession.revokedAt());
  }

  private void applySchema(DatabaseClient databaseClient) throws IOException {
    String schema = Files.readString(Path.of("src", "main", "resources", "sql", "migrations", "V001__identity_schema.sql"));
    for (String statement : schema.split(";")) {
      String sql = statement.trim();
      if (sql.isEmpty()) {
        continue;
      }
      databaseClient.sql(sql).fetch().rowsUpdated().block();
    }
  }

  private void insertRoleGrant(
      DatabaseClient databaseClient,
      String grantId,
      String accountId,
      String roleCode,
      OffsetDateTime effectiveFrom) {
    databaseClient.sql("""
            insert into identity_account_role_grant (
              grant_id,
              account_id,
              role_code,
              grant_source,
              effective_from,
              created_by,
              created_at
            ) values (
              :grantId,
              :accountId,
              :roleCode,
              'MANUAL',
              :effectiveFrom,
              'identity-test',
              :createdAt
            )
            """)
        .bind("grantId", grantId)
        .bind("accountId", accountId)
        .bind("roleCode", roleCode)
        .bind("effectiveFrom", effectiveFrom)
        .bind("createdAt", effectiveFrom)
        .fetch()
        .rowsUpdated()
        .block();
  }
}
