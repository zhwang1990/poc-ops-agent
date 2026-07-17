package com.company.opsagent.controlplane.modules.identity.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.controlplane.modules.identity.domain.Account;
import com.company.opsagent.controlplane.modules.identity.domain.AccountSession;
import com.company.opsagent.controlplane.modules.identity.domain.AccountStatus;
import com.company.opsagent.controlplane.modules.identity.domain.MfaRequirement;
import com.company.opsagent.controlplane.modules.identity.domain.PasswordCredential;
import com.company.opsagent.controlplane.modules.identity.domain.PasswordState;
import com.company.opsagent.controlplane.modules.identity.infrastructure.AccountRepository;
import com.company.opsagent.controlplane.modules.identity.infrastructure.AccountSessionRepository;
import com.company.opsagent.controlplane.modules.identity.infrastructure.PasswordCredentialRepository;
import com.company.opsagent.controlplane.modules.identity.infrastructure.PasswordHasher;
import com.company.opsagent.controlplane.modules.identity.IdentityTestSecretMaterial;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.junit.jupiter.api.Test;

/**
 * 验证正式内建身份模块的登录、重置与首次改密闭环。
 */
class IdentityBuiltInLifecycleTest {

  private static final String INITIAL_PASSWORD = IdentityTestSecretMaterial.value();
  private static final String TEMPORARY_PASSWORD = IdentityTestSecretMaterial.value();
  private static final String CHANGED_PASSWORD = IdentityTestSecretMaterial.value();

  @Test
  void resetPasswordRevokesOldSessionAndRequiresPasswordChangeBeforeFullAccess() {
    Clock clock = Clock.fixed(Instant.parse("2026-06-07T12:00:00Z"), ZoneOffset.UTC);
    InMemoryAccountRepository accountRepository = new InMemoryAccountRepository(activeAccount());
    InMemoryPasswordCredentialRepository credentialRepository = new InMemoryPasswordCredentialRepository(
        new PasswordCredential("credential-1", "account-1", "pbkdf2", "i=310000", "placeholder", 1L, false));
    InMemoryAccountSessionRepository sessionRepository = new InMemoryAccountSessionRepository();
    Pbkdf2PasswordService passwordService = new Pbkdf2PasswordService(clock);
    credentialRepository.save(passwordService.hash("account-1", INITIAL_PASSWORD, 1L, false));

    DefaultIdentityAuthenticationService authenticationService = new DefaultIdentityAuthenticationService(
        accountRepository,
        credentialRepository,
        sessionRepository,
        passwordService,
        clock,
        5,
        java.time.Duration.ofMinutes(15),
        java.time.Duration.ofHours(8));
    DefaultIdentityAdministrationService administrationService = new DefaultIdentityAdministrationService(
        accountRepository,
        credentialRepository,
        sessionRepository,
        passwordService,
        clock);
    DefaultIdentityPasswordManagementService passwordManagementService = new DefaultIdentityPasswordManagementService(
        accountRepository,
        credentialRepository,
        sessionRepository,
        passwordService,
        clock,
        java.time.Duration.ofMinutes(15),
        java.time.Duration.ofHours(8));
    DefaultIdentitySessionQueryService sessionQueryService = new DefaultIdentitySessionQueryService(
        accountRepository,
        sessionRepository,
        clock);

    IdentityAuthenticationResult initialLogin = authenticationService.authenticate(
        new PasswordLoginCommand("alice", INITIAL_PASSWORD));
    assertTrue(sessionQueryService.findOperatorIdentityBySessionId(initialLogin.sessionId()).isPresent());

    administrationService.resetPassword(new AdminResetPasswordCommand(
        "account-1",
        "routine reset",
        TEMPORARY_PASSWORD,
        true));

    assertTrue(sessionQueryService.findOperatorIdentityBySessionId(initialLogin.sessionId()).isEmpty());

    IdentityAuthenticationResult resetLogin = authenticationService.authenticate(
        new PasswordLoginCommand("alice", TEMPORARY_PASSWORD));
    assertTrue(resetLogin.passwordChangeRequired());
    assertTrue(sessionQueryService.findOperatorIdentityBySessionId(resetLogin.sessionId()).isEmpty());
    assertTrue(sessionQueryService.findSessionStatusBySessionId(resetLogin.sessionId()).orElseThrow().authenticated());

    IdentityAuthenticationResult changedPassword = passwordManagementService.changePassword(
        resetLogin.sessionId(),
        new PasswordChangeCommand(TEMPORARY_PASSWORD, CHANGED_PASSWORD));

    assertEquals("account-1", changedPassword.identity().subject());
    assertTrue(sessionQueryService.findOperatorIdentityBySessionId(changedPassword.sessionId()).isPresent());
    assertEquals(AccountStatus.ACTIVE, accountRepository.findByAccountId("account-1").orElseThrow().status());
    assertEquals(PasswordState.ACTIVE, accountRepository.findByAccountId("account-1").orElseThrow().passwordState());
  }

  private Account activeAccount() {
    return new Account(
        "account-1",
        "alice",
        AccountStatus.ACTIVE,
        PasswordState.ACTIVE,
        MfaRequirement.NOT_REQUIRED,
        List.of("ROLE_ops-reader"),
        0,
        null);
  }

  private static final class InMemoryAccountRepository implements AccountRepository {

    private final Map<String, Account> accountsById = new ConcurrentHashMap<>();
    private final Map<String, String> accountIdByUsername = new ConcurrentHashMap<>();

    private InMemoryAccountRepository(Account account) {
      save(account);
    }

    @Override
    public Optional<Account> findByUsername(String username) {
      String accountId = accountIdByUsername.get(username);
      return accountId == null ? Optional.empty() : findByAccountId(accountId);
    }

    @Override
    public Optional<Account> findByAccountId(String accountId) {
      return Optional.ofNullable(accountsById.get(accountId));
    }

    @Override
    public void save(Account account) {
      accountsById.put(account.accountId(), account);
      accountIdByUsername.put(account.username(), account.accountId());
    }
  }

  private static final class InMemoryPasswordCredentialRepository implements PasswordCredentialRepository {

    private final Map<String, PasswordCredential> credentialsByAccountId = new ConcurrentHashMap<>();

    private InMemoryPasswordCredentialRepository(PasswordCredential credential) {
      save(credential);
    }

    @Override
    public Optional<PasswordCredential> findActiveByAccountId(String accountId) {
      return Optional.ofNullable(credentialsByAccountId.get(accountId));
    }

    @Override
    public void save(PasswordCredential credential) {
      credentialsByAccountId.put(credential.accountId(), credential);
    }
  }

  private static final class InMemoryAccountSessionRepository implements AccountSessionRepository {

    private final Map<String, AccountSession> sessionsById = new ConcurrentHashMap<>();

    @Override
    public Optional<AccountSession> findBySessionId(String sessionId) {
      return Optional.ofNullable(sessionsById.get(sessionId));
    }

    @Override
    public void save(AccountSession session) {
      sessionsById.put(session.sessionId(), session);
    }

    @Override
    public void revokeBySessionId(String sessionId, OffsetDateTime revokedAt, String reason) {
      findBySessionId(sessionId).ifPresent(session -> sessionsById.put(sessionId, session.revoke(revokedAt)));
    }

    @Override
    public void revokeByAccountId(String accountId, OffsetDateTime revokedAt, String reason) {
      sessionsById.values().stream()
          .filter(session -> session.accountId().equals(accountId))
          .forEach(session -> sessionsById.put(session.sessionId(), session.revoke(revokedAt)));
    }

    @Override
    public void touch(String sessionId, OffsetDateTime refreshedIdleExpiresAt) {
      findBySessionId(sessionId).ifPresent(session -> sessionsById.put(sessionId, session.touch(refreshedIdleExpiresAt)));
    }
  }
}
