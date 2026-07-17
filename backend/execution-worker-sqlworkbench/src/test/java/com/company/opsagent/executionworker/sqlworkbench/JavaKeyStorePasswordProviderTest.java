package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.KeyStore;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class JavaKeyStorePasswordProviderTest {

  @Test
  void readsPasswordByCredentialAlias() throws Exception {
    char[] storePassword = SqlTestSecretMaterial.password();
    String databasePassword = SqlTestSecretMaterial.value();
    var path = Files.createTempFile("ops-agent-sql", ".jceks");
    KeyStore keyStore = KeyStore.getInstance("JCEKS");
    keyStore.load(null, storePassword);
    keyStore.setEntry(
        "as400-development",
        new KeyStore.SecretKeyEntry(new SecretKeySpec(
            databasePassword.getBytes(StandardCharsets.UTF_8),
            "AES")),
        new KeyStore.PasswordProtection(storePassword));
    try (var output = Files.newOutputStream(path)) {
      keyStore.store(output, storePassword);
    }

    var provider = new JavaKeyStorePasswordProvider(path, storePassword);

    assertArrayEquals(databasePassword.toCharArray(), provider.password("as400-development"));
  }
}
