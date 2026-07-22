package com.company.opsagent.controlplane.modules.skillregistry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * 文件系统 Manifest 加载器测试。
 *
 * <p>覆盖正常加载、签名校验和 P1 只读约束，确保注册中心不会把不合规 Skill
 * 带入后续路由流程。
 */
class FileSystemSkillManifestLoaderTest {

  private static final String SIGNING_SECRET = runtimeSigningSecret();

  private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

  @Test
  void loadsAllRegisteredSkillsFromManifestFiles(@TempDir Path tempDir) throws Exception {
    Path firstSkillDir = Files.createDirectories(tempDir.resolve("node-health"));
    Path secondSkillDir = Files.createDirectories(tempDir.resolve("log-summary"));
    Path firstManifest = firstSkillDir.resolve("manifest.json");
    Path secondManifest = secondSkillDir.resolve("manifest.json");

    Files.writeString(firstManifest, """
        {
          "skillId": "node-health-read",
          "version": "1.0.0",
          "displayName": "节点健康检查",
          "description": "读取节点 CPU、内存和磁盘使用率。",
          "category": "INFRASTRUCTURE_DIAGNOSTICS",
          "riskLevel": "READ_ONLY",
          "executor": "SHELL",
          "outputType": "JSON",
          "readOnly": true,
          "timeoutSeconds": 20,
          "owner": "platform-observability",
          "requiredRoles": ["ROLE_ops-reader"],
          "tags": ["node", "health"],
          "interceptors": ["AUTHORIZATION", "AUDIT"],
          "parameters": [
            {
              "name": "nodeName",
              "displayName": "节点名称",
              "description": "需要检查的节点标识。",
              "type": "STRING",
              "required": true,
              "allowedValues": [],
              "defaultValue": null
            }
          ]
        }
        """);
    Files.writeString(secondManifest, """
        {
          "skillId": "application-log-summary-read",
          "version": "1.1.0",
          "displayName": "应用日志摘要",
          "description": "读取指定应用最近一段时间的错误摘要。",
          "category": "APPLICATION_DIAGNOSTICS",
          "riskLevel": "READ_ONLY",
          "executor": "HTTP",
          "outputType": "MARKDOWN",
          "readOnly": true,
          "timeoutSeconds": 30,
          "owner": "application-ops",
          "requiredRoles": ["ROLE_ops-reader", "ROLE_ops-admin"],
          "tags": ["log", "summary"],
          "interceptors": ["AUTHORIZATION", "AUDIT", "SENSITIVE_DATA_MASKING"],
          "parameters": []
        }
        """);

    writeSignature(firstManifest, "platform-observability", "2026-06-06T21:30:00+08:00");
    writeSignature(secondManifest, "application-ops", "2026-06-06T21:31:00+08:00");

    FileSystemSkillManifestLoader loader = new FileSystemSkillManifestLoader(
        tempDir,
        objectMapper,
        true,
        SIGNING_SECRET);

    assertEquals(2, loader.loadAll().size());
    assertEquals("application-ops", loader.loadAll().get(0).publication().publishedBy());
  }

  @Test
  void rejectsNonReadOnlySkillDuringP1(@TempDir Path tempDir) throws Exception {
    Path skillDir = Files.createDirectories(tempDir.resolve("change-config"));
    Path manifestPath = skillDir.resolve("manifest.json");
    Files.writeString(manifestPath, """
        {
          "skillId": "application-config-update",
          "version": "1.0.0",
          "displayName": "应用配置修改",
          "description": "修改应用运行参数。",
          "category": "APPLICATION_DIAGNOSTICS",
          "riskLevel": "HIGH",
          "executor": "WORKFLOW",
          "outputType": "JSON",
          "readOnly": false,
          "timeoutSeconds": 30,
          "owner": "application-ops",
          "requiredRoles": ["ROLE_ops-admin"],
          "tags": [],
          "interceptors": ["AUTHORIZATION", "AUDIT"],
          "parameters": []
        }
        """);
    writeSignature(manifestPath, "application-ops", "2026-06-06T21:40:00+08:00");

    FileSystemSkillManifestLoader loader = new FileSystemSkillManifestLoader(
        tempDir,
        objectMapper,
        true,
        SIGNING_SECRET);

    assertThrows(IllegalStateException.class, loader::loadAll);
  }

  @Test
  void rejectsManifestWhenSignatureDoesNotMatch(@TempDir Path tempDir) throws Exception {
    Path skillDir = Files.createDirectories(tempDir.resolve("node-health"));
    Path manifestPath = skillDir.resolve("manifest.json");
    Files.writeString(manifestPath, """
        {
          "skillId": "node-health-read",
          "version": "1.0.0",
          "displayName": "节点健康检查",
          "description": "读取节点状态。",
          "category": "INFRASTRUCTURE_DIAGNOSTICS",
          "riskLevel": "READ_ONLY",
          "executor": "SHELL",
          "outputType": "JSON",
          "readOnly": true,
          "timeoutSeconds": 20,
          "owner": "platform-observability",
          "requiredRoles": ["ROLE_ops-reader"],
          "tags": [],
          "interceptors": ["AUTHORIZATION", "AUDIT"],
          "parameters": []
        }
        """);
    Files.writeString(skillDir.resolve("manifest.signature.json"), """
        {
          "publishedBy": "platform-observability",
          "publishedAt": "2026-06-06T21:45:00+08:00",
          "checksumSha256": "deadbeef",
          "signatureAlgorithm": "HmacSHA256",
          "signature": "deadbeef"
        }
        """);

    FileSystemSkillManifestLoader loader = new FileSystemSkillManifestLoader(
        tempDir,
        objectMapper,
        true,
        SIGNING_SECRET);

    assertThrows(IllegalStateException.class, loader::loadAll);
  }

  private void writeSignature(Path manifestPath, String publishedBy, String publishedAt) throws Exception {
    byte[] bytes = Files.readAllBytes(manifestPath);
    String checksum = sha256Hex(bytes);
    String signature = hmacSha256Hex(checksum);
    Files.writeString(manifestPath.resolveSibling("manifest.signature.json"), """
        {
          "publishedBy": "%s",
          "publishedAt": "%s",
          "checksumSha256": "%s",
          "signatureAlgorithm": "HmacSHA256",
          "signature": "%s"
        }
        """.formatted(publishedBy, publishedAt, checksum, signature));
  }

  private String sha256Hex(byte[] bytes) throws Exception {
    MessageDigest messageDigest = MessageDigest.getInstance("SHA-256");
    return toHex(messageDigest.digest(bytes));
  }

  private String hmacSha256Hex(String payload) throws Exception {
    Mac mac = Mac.getInstance("HmacSHA256");
    mac.init(new SecretKeySpec(SIGNING_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
    return toHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
  }

  private String toHex(byte[] bytes) {
    StringBuilder builder = new StringBuilder(bytes.length * 2);
    for (byte value : bytes) {
      builder.append(String.format("%02x", value));
    }
    return builder.toString();
  }

  private static String runtimeSigningSecret() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getEncoder().encodeToString(bytes);
  }
}
