import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import javax.crypto.spec.SecretKeySpec;

public final class SqlCredentialKeyStoreCli {
  private static final String STORE_TYPE = "JCEKS";
  private static final String SECRET_ALGORITHM = "AES";
  private static final Set<String> VALUE_OPTIONS = Set.of("--store", "--alias", "--store-password-env");

  private SqlCredentialKeyStoreCli() {
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
      char[] secret = readSecret(input, arguments.secretStdin(), error);
      char[] storePasswordChars = storePassword.toCharArray();
      try {
        put(arguments.store(), storePasswordChars, arguments.alias(), secret);
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

  private static char[] readSecret(InputStream input, boolean secretStdin, PrintStream error)
      throws IOException {
    if (secretStdin) {
      return readSecretFromStdin(input);
    }
    Console console = System.console();
    if (console == null) {
      throw new IllegalArgumentException(
          "No interactive console is available; use cmd.exe directly or pass --secret-stdin from a secure input source");
    }
    char[] secret = console.readPassword("SQL credential secret: ");
    if (secret == null || secret.length == 0) {
      throw new IllegalArgumentException("SQL credential secret is required");
    }
    error.flush();
    return secret;
  }

  private static char[] readSecretFromStdin(InputStream input) throws IOException {
    BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
    String line = reader.readLine();
    if (line == null || line.isEmpty()) {
      throw new IllegalArgumentException("SQL credential secret is required on stdin");
    }
    return line.toCharArray();
  }

  private static void put(Path keyStorePath, char[] storePassword, String credentialAlias, char[] secret) {
    byte[] secretBytes = encodeSecret(secret);
    try {
      KeyStore keyStore = loadKeyStore(keyStorePath, storePassword);
      keyStore.setEntry(
          credentialAlias,
          new KeyStore.SecretKeyEntry(new SecretKeySpec(secretBytes, SECRET_ALGORITHM)),
          new KeyStore.PasswordProtection(storePassword));
      Path parent = keyStorePath.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try (var output = Files.newOutputStream(keyStorePath)) {
        keyStore.store(output, storePassword);
      }
    } catch (GeneralSecurityException | IOException exception) {
      throw new IllegalStateException("failed to write SQL credential KeyStore", exception);
    } finally {
      Arrays.fill(secretBytes, (byte) 0);
    }
  }

  private static KeyStore loadKeyStore(Path path, char[] storePassword)
      throws GeneralSecurityException, IOException {
    KeyStore keyStore = KeyStore.getInstance(STORE_TYPE);
    if (Files.exists(path)) {
      try (var input = Files.newInputStream(path)) {
        keyStore.load(input, storePassword);
      }
      return keyStore;
    }
    keyStore.load(null, storePassword);
    return keyStore;
  }

  private static byte[] encodeSecret(char[] secret) {
    ByteBuffer encoded = StandardCharsets.UTF_8.encode(CharBuffer.wrap(secret));
    byte[] bytes = new byte[encoded.remaining()];
    encoded.get(bytes);
    return bytes;
  }

  private static void printUsage(PrintStream error) {
    error.println("Usage: put --store <path> --alias <credentialAlias> "
        + "--store-password-env <envName> [--secret-stdin]");
  }

  private record Arguments(
      Path store,
      String alias,
      String storePasswordEnv,
      boolean secretStdin) {

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
      return new Arguments(
          Path.of(require(values, "--store")),
          require(values, "--alias"),
          require(values, "--store-password-env"),
          secretStdin);
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
