package com.company.opsagent.controlplane.modules.identity;

import java.security.SecureRandom;
import java.util.Base64;

public final class IdentityTestSecretMaterial {

  private IdentityTestSecretMaterial() {}

  public static String value() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
