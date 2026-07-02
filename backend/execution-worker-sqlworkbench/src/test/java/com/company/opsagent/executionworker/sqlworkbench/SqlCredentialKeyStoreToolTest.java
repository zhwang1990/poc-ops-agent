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
        Map.of("OPS_AGENT_SQL_KEYSTORE_PASSWORD", "store-password"),
        new ByteArrayInputStream("database-password\n".getBytes(StandardCharsets.UTF_8)),
        new PrintStream(output, true, StandardCharsets.UTF_8),
        new PrintStream(error, true, StandardCharsets.UTF_8));

    assertEquals(0, exitCode);
    assertFalse(output.toString(StandardCharsets.UTF_8).contains("database-password"));
    assertFalse(error.toString(StandardCharsets.UTF_8).contains("database-password"));
    var provider = new JavaKeyStorePasswordProvider(keyStorePath, "store-password".toCharArray());
    assertArrayEquals("database-password".toCharArray(), provider.password("as400-dev-readonly"));
  }

  @Test
  void rejectsMissingStorePasswordEnvironmentVariable() {
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
        new ByteArrayInputStream("database-password\n".getBytes(StandardCharsets.UTF_8)),
        new PrintStream(output, true, StandardCharsets.UTF_8),
        new PrintStream(error, true, StandardCharsets.UTF_8));

    assertEquals(2, exitCode);
    assertFalse(error.toString(StandardCharsets.UTF_8).contains("database-password"));
  }

  @Test
  void rejectsUnknownOption() throws Exception {
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
        Map.of("OPS_AGENT_SQL_KEYSTORE_PASSWORD", "store-password"),
        new ByteArrayInputStream("database-password\n".getBytes(StandardCharsets.UTF_8)),
        new PrintStream(output, true, StandardCharsets.UTF_8),
        new PrintStream(error, true, StandardCharsets.UTF_8));

    assertEquals(2, exitCode);
    assertFalse(Files.exists(keyStorePath));
  }
}
