package com.company.opsagent.controlplane.bootstrap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Comparator;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** 为 Bootstrap 集成测试提供进程内生成且与临时 Skill 制品匹配的签名材料。 */
public abstract class BootstrapSkillRegistryTestSupport {

  private static final String SIGNING_SECRET = runtimeSecret();
  private static final String SECURITY_SHARED_SECRET = runtimeSecret();
  private static final String LOCAL_OIDC_CLIENT_SECRET = runtimeSecret();
  private static final String MODEL_PROVIDER_SECRET_MASTER_KEY = runtimeSecret();
  private static final Path SKILL_ROOT = signedTestSkillRoot();

  @DynamicPropertySource
  static void configureSkillRegistry(DynamicPropertyRegistry registry) {
    registry.add("ops-agent.skill-registry.root-path", () -> SKILL_ROOT.toString());
    registry.add("ops-agent.skill-registry.signature-required", () -> true);
    registry.add("ops-agent.skill-registry.signing-secret", () -> SIGNING_SECRET);
    registry.add("ops-agent.security.shared-secret", () -> SECURITY_SHARED_SECRET);
    registry.add("ops-agent.local-oidc-provider.client-secret", () -> LOCAL_OIDC_CLIENT_SECRET);
    registry.add("OPS_AGENT_LOCAL_OIDC_CLIENT_SECRET", () -> LOCAL_OIDC_CLIENT_SECRET);
    registry.add("ops-agent.agent-runtime.model-provider-secret-master-key",
        () -> MODEL_PROVIDER_SECRET_MASTER_KEY);
  }

  private static Path signedTestSkillRoot() {
    try {
      Path sourceRoot = Path.of(BootstrapSkillRegistryTestSupport.class
          .getClassLoader()
          .getResource("skills")
          .toURI());
      Path targetRoot = Files.createTempDirectory("ops-agent-bootstrap-skills-");
      copyDirectory(sourceRoot, targetRoot);
      resignAllManifests(targetRoot);
      return targetRoot;
    } catch (IOException | URISyntaxException exception) {
      throw new IllegalStateException("test Skill registry fixtures could not be prepared", exception);
    }
  }

  private static void copyDirectory(Path sourceRoot, Path targetRoot) throws IOException {
    try (var paths = Files.walk(sourceRoot)) {
      for (Path source : paths.toList()) {
        Path target = targetRoot.resolve(sourceRoot.relativize(source));
        if (Files.isDirectory(source)) {
          Files.createDirectories(target);
        } else {
          Files.copy(source, target);
        }
      }
    }
  }

  private static void resignAllManifests(Path root) throws IOException {
    ObjectMapper objectMapper = new ObjectMapper();
    try (var paths = Files.walk(root)) {
      for (Path manifest : paths
          .filter(path -> path.getFileName().toString().equals("manifest.json"))
          .sorted(Comparator.naturalOrder())
          .toList()) {
        Path signaturePath = manifest.resolveSibling("manifest.signature.json");
        JsonNode signature = objectMapper.readTree(signaturePath.toFile());
        if (!(signature instanceof ObjectNode publication)) {
          throw new IllegalStateException("test Skill signature fixture must be an object: " + signaturePath);
        }
        String checksum = sha256Hex(Files.readAllBytes(manifest));
        publication.put("checksumSha256", checksum);
        publication.put("signature", hmacSha256Hex(SIGNING_SECRET, checksum));
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(signaturePath.toFile(), publication);
      }
    }
  }

  protected static String securitySharedSecret() {
    return SECURITY_SHARED_SECRET;
  }

  protected static String localOidcClientSecret() {
    return LOCAL_OIDC_CLIENT_SECRET;
  }

  private static String runtimeSecret() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  private static String sha256Hex(byte[] bytes) {
    try {
      return toHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String hmacSha256Hex(String secret, String payload) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
      return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
    } catch (java.security.GeneralSecurityException exception) {
      throw new IllegalStateException("HmacSHA256 is unavailable", exception);
    }
  }

  private static String toHex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      result.append(String.format("%02x", value));
    }
    return result.toString();
  }
}
