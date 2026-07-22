package com.company.opsagent.contracts;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.identity.AdminResetPasswordRequest;
import com.company.opsagent.contracts.identity.IdentityErrorResponse;
import com.company.opsagent.contracts.identity.IdentitySessionStatusResponse;
import com.company.opsagent.contracts.identity.PasswordChangeRequest;
import com.company.opsagent.contracts.identity.PasswordLoginRequest;
import com.company.opsagent.contracts.identity.PasswordLoginResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证身份相关 API 契约在边界上拒绝无效输入，并且对应 Schema 已经落库。
 */
class IdentityContractsTest {

  @Test
  void rejectsBlankLoginUsername() {
    assertThrows(IllegalArgumentException.class, () -> new PasswordLoginRequest(" ", "secret"));
  }

  @Test
  void rejectsPasswordChangeWhenNewPasswordMatchesCurrentPassword() {
    assertThrows(IllegalArgumentException.class, () -> new PasswordChangeRequest("same-password", "same-password"));
  }

  @Test
  void rejectsAuthenticatedSessionWithoutSubject() {
    assertThrows(IllegalArgumentException.class, () -> new IdentitySessionStatusResponse(
        true,
        null,
        "alice",
        List.of("ROLE_ops-reader"),
        "PASSWORD",
        OffsetDateTime.now().plusMinutes(30),
        false));
  }

  @Test
  void rejectsBlankResetPasswordTargetAccount() {
    assertThrows(IllegalArgumentException.class, () -> new AdminResetPasswordRequest(
        " ", "routine reset", java.util.UUID.randomUUID().toString(), true));
  }

  @Test
  void rejectsBlankResetPasswordTemporaryPassword() {
    assertThrows(IllegalArgumentException.class, () -> new AdminResetPasswordRequest("account-1", "routine reset", " ", true));
  }

  @Test
  void rejectsBlankIdentityErrorCode() {
    assertThrows(IllegalArgumentException.class, () -> new IdentityErrorResponse(" ", "login failed", "trace-1"));
  }

  @Test
  void rejectsUnauthenticatedLoginResponseWithPrincipalDetails() {
    assertThrows(IllegalArgumentException.class, () -> new PasswordLoginResponse(
        false,
        "account-1",
        "alice",
        List.of("ROLE_ops-reader"),
        false));
  }

  @Test
  void containsIdentityApiSchemas() {
    assertTrue(Files.exists(Path.of("api", "identity", "password-login-request-v1.schema.json")));
    assertTrue(Files.exists(Path.of("api", "identity", "password-login-response-v1.schema.json")));
    assertTrue(Files.exists(Path.of("api", "identity", "identity-session-status-response-v1.schema.json")));
    assertTrue(Files.exists(Path.of("api", "identity", "password-change-request-v1.schema.json")));
    assertTrue(Files.exists(Path.of("api", "identity", "admin-reset-password-request-v1.schema.json")));
    assertTrue(Files.exists(Path.of("api", "identity", "identity-error-response-v1.schema.json")));
  }
}
