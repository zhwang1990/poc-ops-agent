package com.company.opsagent.controlplane.modules.release;

import java.security.SecureRandom;
import java.util.Base64;

final class ReleaseTestSecretMaterial {

  private ReleaseTestSecretMaterial() {}

  static String value() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
