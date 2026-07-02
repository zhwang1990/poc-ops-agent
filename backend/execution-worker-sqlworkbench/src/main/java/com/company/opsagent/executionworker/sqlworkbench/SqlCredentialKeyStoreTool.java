package com.company.opsagent.executionworker.sqlworkbench;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Command-line entry point for writing Worker-local SQL credential aliases.
 */
public final class SqlCredentialKeyStoreTool {

  private SqlCredentialKeyStoreTool() {
  }

  public static void main(String[] args) {
    int exitCode = run(args, System.getenv(), System.in, System.out, System.err);
    System.exit(exitCode);
  }

  static int run(
      String[] args,
      Map<String, String> environment,
      InputStream input,
      PrintStream output,
      PrintStream error) {
    try {
      Arguments arguments = Arguments.parse(args);
      String storePassword = environment.get(arguments.storePasswordEnv());
      if (storePassword == null || storePassword.isBlank()) {
        error.println("Missing KeyStore password environment variable: " + arguments.storePasswordEnv());
        return 2;
      }
      char[] secret = readSecret(input);
      char[] storePasswordChars = storePassword.toCharArray();
      try {
        new SqlCredentialKeyStoreWriter()
            .put(arguments.store(), storePasswordChars, arguments.alias(), secret);
      } finally {
        Arrays.fill(secret, '\0');
        Arrays.fill(storePasswordChars, '\0');
      }
      output.println("SQL credential alias written: " + arguments.alias());
      output.println("KeyStore: " + arguments.store().toAbsolutePath());
      return 0;
    } catch (IllegalArgumentException exception) {
      error.println(exception.getMessage());
      printUsage(error);
      return 2;
    } catch (IllegalStateException | IOException exception) {
      error.println(exception.getMessage());
      return 3;
    }
  }

  private static char[] readSecret(InputStream input) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    String line = reader.readLine();
    if (line == null || line.isEmpty()) {
      throw new IllegalArgumentException("SQL credential secret is required on stdin");
    }
    return line.toCharArray();
  }

  private static void printUsage(PrintStream error) {
    error.println("Usage: put --store <path> --alias <credentialAlias> "
        + "--store-password-env <envName> --secret-stdin");
  }

  private record Arguments(
      Path store,
      String alias,
      String storePasswordEnv) {

    private static final Set<String> VALUE_OPTIONS = Set.of(
        "--store",
        "--alias",
        "--store-password-env");

    static Arguments parse(String[] args) {
      if (args.length == 0 || "--help".equals(args[0]) || "-h".equals(args[0])) {
        throw new IllegalArgumentException("SQL credential KeyStore tool");
      }
      if (!"put".equals(args[0])) {
        throw new IllegalArgumentException("Unsupported command: " + args[0]);
      }
      Map<String, String> values = new HashMap<>();
      boolean secretStdin = false;
      for (int index = 1; index < args.length; index++) {
        String token = args[index];
        if ("--secret-stdin".equals(token)) {
          secretStdin = true;
          continue;
        }
        if (!token.startsWith("--")) {
          throw new IllegalArgumentException("Unexpected argument: " + token);
        }
        if (!VALUE_OPTIONS.contains(token)) {
          throw new IllegalArgumentException("Unsupported option: " + token);
        }
        if (index + 1 >= args.length) {
          throw new IllegalArgumentException("Missing value for " + token);
        }
        values.put(token, args[++index]);
      }
      if (!secretStdin) {
        throw new IllegalArgumentException("--secret-stdin is required");
      }
      String store = require(values, "--store");
      String alias = require(values, "--alias");
      String storePasswordEnv = require(values, "--store-password-env");
      return new Arguments(Path.of(store), alias, storePasswordEnv);
    }

    private static String require(Map<String, String> values, String option) {
      String value = values.get(option);
      if (value == null || value.isBlank()) {
        throw new IllegalArgumentException(option + " is required");
      }
      return value.trim();
    }
  }
}
