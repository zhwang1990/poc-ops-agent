package com.company.opsagent.executionworker.sqlworkbench;

import java.security.SecureRandom;
import java.util.Base64;

final class SqlTestSecretMaterial {

  private SqlTestSecretMaterial() {}

  static String value() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }

  static char[] password() {
    return value().toCharArray();
  }
}
