package com.company.opsagent.controlplane.bootstrap;

import static org.springframework.http.MediaType.APPLICATION_JSON;

import com.company.opsagent.controlplane.modules.identity.domain.PasswordCredential;
import com.company.opsagent.controlplane.modules.identity.infrastructure.PasswordHasher;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseCookie;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * 验证正式内建身份模式下的浏览器登录、锁定、改密与撤销闭环。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
    "ops-agent.security.auth-mode=built-in",
    "ops-agent.security.browser-login-enabled=true",
    "ops-agent.policy.version=rbac-v1",
    "ops-agent.policy.required-roles-by-action.internal.health.read[0]=ROLE_ops-reader",
    "ops-agent.policy.required-roles-by-action.internal.health.read[1]=ROLE_ops-admin",
    "ops-agent.policy.required-roles-by-action.internal.identity.password-reset[0]=ROLE_ops-admin",
    "spring.r2dbc.url=r2dbc:h2:mem:///built-in-browser-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "ops-agent.workflow.startup-recovery-enabled=false",
    "ops-agent.built-in-identity.lockout-threshold=5",
    "ops-agent.built-in-identity.lockout-duration=15m",
    "ops-agent.built-in-identity.session-idle-timeout=15m",
    "ops-agent.built-in-identity.session-absolute-timeout=8h"
})
class BuiltInBrowserAuthenticationIntegrationTest extends BootstrapSkillRegistryTestSupport {

  private static final OffsetDateTime FIXED_TIME = OffsetDateTime.parse("2026-06-07T12:00:00Z");

  @Autowired
  private WebTestClient webTestClient;

  @Autowired
  private DatabaseClient databaseClient;

  @Autowired
  private PasswordHasher passwordHasher;

  @BeforeEach
  void seedIdentityData() {
    deleteAll("identity_account_session");
    deleteAll("identity_password_credential");
    deleteAll("identity_account_role_grant");
    deleteAll("identity_password_reset_ticket");
    deleteAll("identity_account");

    insertAccount("account-1", "alice");
    insertRoleGrant("grant-1", "account-1", "ROLE_ops-reader");
    insertCredential(passwordHasher.hash("account-1", "Start#2026", 1L, false));

    insertAccount("account-admin-1", "admin");
    insertRoleGrant("grant-admin-1", "account-admin-1", "ROLE_ops-admin");
    insertCredential(passwordHasher.hash("account-admin-1", "Admin#2026", 1L, false));
  }

  @Test
  void supportsBuiltInBrowserLoginAndInternalAuthorization() {
    ResponseCookie sessionCookie = login("alice", "Start#2026");

    webTestClient.get()
        .uri("/auth/session")
        .cookie(sessionCookie.getName(), sessionCookie.getValue())
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.authenticated").isEqualTo(true)
        .jsonPath("$.username").isEqualTo("alice");

    webTestClient.get()
        .uri("/internal/healthz")
        .cookie(sessionCookie.getName(), sessionCookie.getValue())
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("UP");
  }

  @Test
  void supportsAdminResetAndForcedPasswordChangeFlow() {
    ResponseCookie adminCookie = login("admin", "Admin#2026");

    webTestClient.post()
        .uri("/internal/identity/password-reset")
        .contentType(APPLICATION_JSON)
        .cookie(adminCookie.getName(), adminCookie.getValue())
        .bodyValue("""
            {
              "accountId": "account-1",
              "reason": "routine reset",
              "temporaryPassword": "Temp#2026",
              "forcePasswordChange": true
            }
            """)
        .exchange()
        .expectStatus().isNoContent();

    ResponseCookie temporaryCookie = login("alice", "Temp#2026");

    webTestClient.get()
        .uri("/internal/healthz")
        .cookie(temporaryCookie.getName(), temporaryCookie.getValue())
        .exchange()
        .expectStatus().isUnauthorized();

    ResponseCookie changedCookie = changePassword(temporaryCookie, "Temp#2026", "Changed#2026");

    webTestClient.get()
        .uri("/internal/healthz")
        .cookie(changedCookie.getName(), changedCookie.getValue())
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("UP");
  }

  @Test
  void revokesServerSideSessionOnLogout() {
    ResponseCookie sessionCookie = login("alice", "Start#2026");

    webTestClient.get()
        .uri("/auth/logout")
        .cookie(sessionCookie.getName(), sessionCookie.getValue())
        .exchange()
        .expectStatus().isFound()
        .expectHeader().valueMatches("Set-Cookie", "OPS_AGENT_SESSION=.*Max-Age=0.*");

    webTestClient.get()
        .uri("/internal/healthz")
        .cookie(sessionCookie.getName(), sessionCookie.getValue())
        .exchange()
        .expectStatus().isUnauthorized();
  }

  @Test
  void returnsStructuredIdentityErrorAndEventuallyLocksAccount() {
    for (int attempt = 0; attempt < 5; attempt++) {
      webTestClient.post()
          .uri("/auth/login")
          .contentType(APPLICATION_JSON)
          .bodyValue("""
              {
                "username": "alice",
                "password": "Wrong#2026"
              }
              """)
          .exchange()
          .expectStatus().isUnauthorized()
          .expectBody()
          .jsonPath("$.errorCode").isEqualTo("INVALID_CREDENTIALS");
    }

    webTestClient.post()
        .uri("/auth/login")
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "username": "alice",
              "password": "Start#2026"
            }
            """)
        .exchange()
        .expectStatus().isEqualTo(423)
        .expectBody()
        .jsonPath("$.errorCode").isEqualTo("ACCOUNT_LOCKED");
  }

  private ResponseCookie login(String username, String password) {
    String setCookieHeader = webTestClient.post()
        .uri("/auth/login")
        .contentType(APPLICATION_JSON)
        .bodyValue("""
            {
              "username": "%s",
              "password": "%s"
            }
            """.formatted(username, password))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.authenticated").isEqualTo(true)
        .jsonPath("$.username").isEqualTo(username)
        .returnResult()
        .getResponseHeaders()
        .getFirst("Set-Cookie");
    return parseSessionCookie(setCookieHeader);
  }

  private ResponseCookie changePassword(ResponseCookie currentCookie, String currentPassword, String newPassword) {
    String setCookieHeader = webTestClient.post()
        .uri("/auth/password")
        .contentType(APPLICATION_JSON)
        .cookie(currentCookie.getName(), currentCookie.getValue())
        .bodyValue("""
            {
              "currentPassword": "%s",
              "newPassword": "%s"
            }
            """.formatted(currentPassword, newPassword))
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.authenticated").isEqualTo(true)
        .jsonPath("$.passwordChangeRequired").isEqualTo(false)
        .returnResult()
        .getResponseHeaders()
        .getFirst("Set-Cookie");
    return parseSessionCookie(setCookieHeader);
  }

  private ResponseCookie parseSessionCookie(String setCookieHeader) {
    return ResponseCookie.fromClientResponse(
            "OPS_AGENT_SESSION",
            setCookieHeader.substring("OPS_AGENT_SESSION=".length(), setCookieHeader.indexOf(';')))
        .build();
  }

  private void insertAccount(String accountId, String username) {
    databaseClient.sql("""
            insert into identity_account (
              account_id,
              username,
              account_status,
              password_state,
              mfa_requirement,
              failed_login_count,
              created_at,
              updated_at
            ) values (
              :accountId,
              :username,
              'ACTIVE',
              'ACTIVE',
              'NOT_REQUIRED',
              0,
              :createdAt,
              :updatedAt
            )
            """)
        .bind("accountId", accountId)
        .bind("username", username)
        .bind("createdAt", FIXED_TIME)
        .bind("updatedAt", FIXED_TIME)
        .fetch()
        .rowsUpdated()
        .block();
  }

  private void insertRoleGrant(String grantId, String accountId, String roleCode) {
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
              'bootstrap-test',
              :createdAt
            )
            """)
        .bind("grantId", grantId)
        .bind("accountId", accountId)
        .bind("roleCode", roleCode)
        .bind("effectiveFrom", FIXED_TIME.minusMinutes(5))
        .bind("createdAt", FIXED_TIME.minusMinutes(5))
        .fetch()
        .rowsUpdated()
        .block();
  }

  private void insertCredential(PasswordCredential credential) {
    databaseClient.sql("""
            insert into identity_password_credential (
              credential_id,
              account_id,
              hash_algorithm,
              hash_parameters,
              password_hash,
              password_version,
              must_change_on_next_login,
              created_at
            ) values (
              :credentialId,
              :accountId,
              :hashAlgorithm,
              :hashParameters,
              :passwordHash,
              :passwordVersion,
              :mustChangeOnNextLogin,
              :createdAt
            )
            """)
        .bind("credentialId", credential.credentialId())
        .bind("accountId", credential.accountId())
        .bind("hashAlgorithm", credential.hashAlgorithm())
        .bind("hashParameters", credential.hashParameters())
        .bind("passwordHash", credential.passwordHash())
        .bind("passwordVersion", credential.passwordVersion())
        .bind("mustChangeOnNextLogin", credential.mustChangeOnNextLogin())
        .bind("createdAt", FIXED_TIME)
        .fetch()
        .rowsUpdated()
        .block();
  }

  private void deleteAll(String tableName) {
    databaseClient.sql("delete from " + tableName)
        .fetch()
        .rowsUpdated()
        .block();
  }
}
