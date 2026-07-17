package com.company.opsagent.controlplane.modules.identity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.company.opsagent.controlplane.modules.identity.application.AdminResetPasswordCommand;
import com.company.opsagent.controlplane.modules.identity.application.PasswordChangeCommand;
import com.company.opsagent.controlplane.modules.identity.application.PasswordLoginCommand;
import com.company.opsagent.controlplane.modules.identity.domain.Account;
import com.company.opsagent.controlplane.modules.identity.domain.AccountStatus;
import com.company.opsagent.controlplane.modules.identity.domain.MfaRequirement;
import com.company.opsagent.controlplane.modules.identity.domain.PasswordState;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 锁定正式身份模块骨架，避免继续把生产身份逻辑堆积在 bootstrap 层。
 */
class IdentityProductionSkeletonTest {

  @Test
  void exposesFormalIdentityPackages() {
    assertDoesNotThrow(() -> Class.forName("com.company.opsagent.controlplane.modules.identity.api.IdentityAuthenticationService"));
    assertDoesNotThrow(() -> Class.forName("com.company.opsagent.controlplane.modules.identity.api.IdentityAdministrationService"));
    assertDoesNotThrow(() -> Class.forName("com.company.opsagent.controlplane.modules.identity.api.IdentitySessionQueryService"));
    assertDoesNotThrow(() -> Class.forName("com.company.opsagent.controlplane.modules.identity.infrastructure.AccountRepository"));
    assertDoesNotThrow(() -> Class.forName("com.company.opsagent.controlplane.modules.identity.infrastructure.AccountSessionRepository"));
  }

  @Test
  void rejectsBlankAccountIdentifiers() {
    assertThrows(IllegalArgumentException.class, () -> new Account(
        " ",
        "alice",
        AccountStatus.ACTIVE,
        PasswordState.ACTIVE,
        MfaRequirement.NOT_REQUIRED,
        List.of("ROLE_ops-reader"),
        0,
        null));
  }

  @Test
  void rejectsBlankPasswordLoginUsername() {
    assertThrows(IllegalArgumentException.class, () -> new PasswordLoginCommand(
        " ", IdentityTestSecretMaterial.value()));
  }

  @Test
  void rejectsBlankPasswordChangeNewPassword() {
    assertThrows(IllegalArgumentException.class, () -> new PasswordChangeCommand(
        IdentityTestSecretMaterial.value(), " "));
  }

  @Test
  void rejectsBlankAdminResetPasswordReason() {
    assertThrows(IllegalArgumentException.class, () -> new AdminResetPasswordCommand(
        "account-1", " ", IdentityTestSecretMaterial.value(), true));
  }

  @Test
  void rejectsBlankAdminResetTemporaryPassword() {
    assertThrows(IllegalArgumentException.class, () -> new AdminResetPasswordCommand("account-1", "routine reset", " ", true));
  }
}
