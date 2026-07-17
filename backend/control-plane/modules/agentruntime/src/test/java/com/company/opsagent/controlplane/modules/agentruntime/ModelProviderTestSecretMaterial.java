package com.company.opsagent.controlplane.modules.agentruntime;

import java.security.SecureRandom;
import java.util.Base64;

final class ModelProviderTestSecretMaterial {

  private ModelProviderTestSecretMaterial() {}

  static String value() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
