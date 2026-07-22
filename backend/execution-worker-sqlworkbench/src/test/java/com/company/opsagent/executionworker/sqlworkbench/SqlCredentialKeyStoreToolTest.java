package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SqlCredentialKeyStoreToolTest {

  @Test
  void writesCredentialFromStdinWithoutEchoingSecret() throws Exception {
    String storePassword = SqlTestSecretMaterial.value();
    String databasePassword = SqlTestSecretMaterial.value();
    var keyStorePath = Files.createTempFile("ops-agent-sql-credentials", ".jceks");
    Files.deleteIfExists(keyStorePath);
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();

    int exitCode = SqlCredentialKeyStoreTool.run(
        new String[] {
            "put",
            "--store",
            keyStorePath.toString(),
            "--alias",
            "as400-dev-readonly",
            "--store-password-env",
            "OPS_AGENT_SQL_KEYSTORE_PASSWORD",
            "--secret-stdin"
        },
        Map.of("OPS_AGENT_SQL_KEYSTORE_PASSWORD", storePassword),
        new ByteArrayInputStream((databasePassword + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)),
        new PrintStream(output, true, StandardCharsets.UTF_8),
        new PrintStream(error, true, StandardCharsets.UTF_8));

    assertEquals(0, exitCode);
    assertFalse(output.toString(StandardCharsets.UTF_8).contains(databasePassword));
    assertFalse(error.toString(StandardCharsets.UTF_8).contains(databasePassword));
    var provider = new JavaKeyStorePasswordProvider(keyStorePath, storePassword.toCharArray());
    assertArrayEquals(databasePassword.toCharArray(), provider.password("as400-dev-readonly"));
  }

  @Test
  void rejectsMissingStorePasswordEnvironmentVariable() {
    String databasePassword = SqlTestSecretMaterial.value();
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();

    int exitCode = SqlCredentialKeyStoreTool.run(
        new String[] {
            "put",
            "--store",
            "sql-credentials.jceks",
            "--alias",
            "as400-dev-readonly",
            "--store-password-env",
            "OPS_AGENT_SQL_KEYSTORE_PASSWORD",
            "--secret-stdin"
        },
        Map.of(),
        new ByteArrayInputStream((databasePassword + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)),
        new PrintStream(output, true, StandardCharsets.UTF_8),
        new PrintStream(error, true, StandardCharsets.UTF_8));

    assertEquals(2, exitCode);
    assertFalse(error.toString(StandardCharsets.UTF_8).contains(databasePassword));
  }

  @Test
  void rejectsUnknownOption() throws Exception {
    String storePassword = SqlTestSecretMaterial.value();
    String databasePassword = SqlTestSecretMaterial.value();
    var keyStorePath = Files.createTempFile("ops-agent-sql-credentials", ".jceks");
    Files.deleteIfExists(keyStorePath);
    var output = new ByteArrayOutputStream();
    var error = new ByteArrayOutputStream();

    int exitCode = SqlCredentialKeyStoreTool.run(
        new String[] {
            "put",
            "--store",
            keyStorePath.toString(),
            "--alias",
            "as400-dev-readonly",
            "--store-password-env",
            "OPS_AGENT_SQL_KEYSTORE_PASSWORD",
            "--unexpected",
            "value",
            "--secret-stdin"
        },
        Map.of("OPS_AGENT_SQL_KEYSTORE_PASSWORD", storePassword),
        new ByteArrayInputStream((databasePassword + System.lineSeparator()).getBytes(StandardCharsets.UTF_8)),
        new PrintStream(output, true, StandardCharsets.UTF_8),
        new PrintStream(error, true, StandardCharsets.UTF_8));

    assertEquals(2, exitCode);
    assertFalse(Files.exists(keyStorePath));
  }
}
