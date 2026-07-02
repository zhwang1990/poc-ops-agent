package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import java.nio.file.Files;
import org.junit.jupiter.api.Test;

class SqlCredentialKeyStoreWriterTest {

  @Test
  void writesCredentialAliasReadableByExistingPasswordProvider() throws Exception {
    char[] storePassword = "store-password".toCharArray();
    char[] databasePassword = "database-password".toCharArray();
    var keyStorePath = Files.createTempFile("ops-agent-sql-credentials", ".jceks");
    Files.deleteIfExists(keyStorePath);

    new SqlCredentialKeyStoreWriter()
        .put(keyStorePath, storePassword, "as400-dev-readonly", databasePassword);

    var provider = new JavaKeyStorePasswordProvider(keyStorePath, storePassword);
    assertArrayEquals(databasePassword, provider.password("as400-dev-readonly"));
  }

  @Test
  void replacesExistingCredentialAlias() throws Exception {
    char[] storePassword = "store-password".toCharArray();
    var keyStorePath = Files.createTempFile("ops-agent-sql-credentials", ".jceks");
    Files.deleteIfExists(keyStorePath);
    var writer = new SqlCredentialKeyStoreWriter();

    writer.put(keyStorePath, storePassword, "as400-dev-readonly", "old-password".toCharArray());
    writer.put(keyStorePath, storePassword, "as400-dev-readonly", "new-password".toCharArray());

    var provider = new JavaKeyStorePasswordProvider(keyStorePath, storePassword);
    assertArrayEquals("new-password".toCharArray(), provider.password("as400-dev-readonly"));
  }
}
