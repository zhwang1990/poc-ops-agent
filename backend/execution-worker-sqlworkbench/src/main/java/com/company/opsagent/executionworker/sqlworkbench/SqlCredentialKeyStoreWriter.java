package com.company.opsagent.executionworker.sqlworkbench;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.spec.SecretKeySpec;

/**
 * Writes SQL credential secrets into the Worker-local JCEKS store.
 */
public final class SqlCredentialKeyStoreWriter {

  private static final String STORE_TYPE = "JCEKS";
  private static final String SECRET_ALGORITHM = "AES";

  public void put(Path keyStorePath, char[] storePassword, String credentialAlias, char[] secret) {
    Path path = requirePath(keyStorePath);
    char[] validatedStorePassword = requireChars(storePassword, "storePassword");
    String alias = requireText(credentialAlias, "credentialAlias");
    char[] validatedSecret = requireChars(secret, "secret");
    byte[] secretBytes = encodeSecret(validatedSecret);
    try {
      KeyStore keyStore = loadKeyStore(path, validatedStorePassword);
      keyStore.setEntry(
          alias,
          new KeyStore.SecretKeyEntry(new SecretKeySpec(secretBytes, SECRET_ALGORITHM)),
          new KeyStore.PasswordProtection(validatedStorePassword));
      Path parent = path.toAbsolutePath().getParent();
      if (parent != null) {
        Files.createDirectories(parent);
      }
      try (var output = Files.newOutputStream(path)) {
        keyStore.store(output, validatedStorePassword);
      }
    } catch (GeneralSecurityException | IOException exception) {
      throw new IllegalStateException("failed to write SQL credential KeyStore", exception);
    } finally {
      Arrays.fill(secretBytes, (byte) 0);
    }
  }

  private KeyStore loadKeyStore(Path path, char[] storePassword)
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

  private static Path requirePath(Path path) {
    if (path == null) {
      throw new IllegalArgumentException("keyStorePath is required");
    }
    return path;
  }

  private static char[] requireChars(char[] value, String fieldName) {
    if (value == null || value.length == 0) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }

  private static String requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value.trim();
  }
}
