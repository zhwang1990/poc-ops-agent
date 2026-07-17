package com.company.opsagent.controlplane.bootstrap.security;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.company.opsagent.controlplane.bootstrap.config.SecurityProperties;
import com.company.opsagent.controlplane.modules.identity.IdentityClaimsMapper;
import com.company.opsagent.controlplane.modules.identity.OperatorIdentity;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/**
 * {@link ConfigurableJwtOperatorIdentityAuthenticator} 单元测试。
 *
 * <p>分别覆盖开发态共享密钥模式下的成功、受众不匹配、过期令牌，以及 OIDC 配置缺失场景。
 */
class ConfigurableJwtOperatorIdentityAuthenticatorTest {

  private static final String SECRET = runtimeSecret();
  private static final String ISSUER = "ops-agent-dev";
  private static final String AUDIENCE = "ops-agent-internal";

  @Test
  void authenticatesSharedSecretTokenWithCustomClaims() {
    // 验证自定义用户名 claim 与角色 claim 能被正确解析和标准化。
    SecurityProperties properties = new SecurityProperties(
        "dev-hs256",
        ISSUER,
        AUDIENCE,
        SECRET,
        null,
        null,
        "user_name",
        "groups",
        "ROLE_",
        false,
        "ops-agent",
        "/auth/session",
        "{baseUrl}/");
    ConfigurableJwtOperatorIdentityAuthenticator authenticator =
        new ConfigurableJwtOperatorIdentityAuthenticator(properties, new IdentityClaimsMapper());

    StepVerifier.create(authenticator.authenticate(token(
            "alice-id",
            ISSUER,
            AUDIENCE,
            "user_name",
            "alice",
            "groups",
            "ops-reader, ops-admin",
            Instant.now().plusSeconds(600))))
        .assertNext(identity -> {
          assertEquals(new OperatorIdentity("alice-id", "alice", List.of("ROLE_ops-reader", "ROLE_ops-admin")), identity);
        })
        .verifyComplete();
  }

  @Test
  void rejectsSharedSecretTokenWithWrongAudience() {
    // audience 不匹配时应视为未认证，返回空结果。
    SecurityProperties properties = new SecurityProperties(
        "dev-hs256",
        ISSUER,
        AUDIENCE,
        SECRET,
        null,
        null,
        "preferred_username",
        "roles",
        "ROLE_",
        false,
        "ops-agent",
        "/auth/session",
        "{baseUrl}/");
    ConfigurableJwtOperatorIdentityAuthenticator authenticator =
        new ConfigurableJwtOperatorIdentityAuthenticator(properties, new IdentityClaimsMapper());

    StepVerifier.create(authenticator.authenticate(token(
            "alice-id",
            ISSUER,
            "wrong-audience",
            "preferred_username",
            "alice",
            "roles",
            List.of("ops-reader"),
            Instant.now().plusSeconds(600))))
        .verifyComplete();
  }

  @Test
  void rejectsExpiredSharedSecretToken() {
    // 过期令牌不能被系统接受。
    SecurityProperties properties = new SecurityProperties(
        "dev-hs256",
        ISSUER,
        AUDIENCE,
        SECRET,
        null,
        null,
        "preferred_username",
        "roles",
        "ROLE_",
        false,
        "ops-agent",
        "/auth/session",
        "{baseUrl}/");
    ConfigurableJwtOperatorIdentityAuthenticator authenticator =
        new ConfigurableJwtOperatorIdentityAuthenticator(properties, new IdentityClaimsMapper());

    StepVerifier.create(authenticator.authenticate(token(
            "alice-id",
            ISSUER,
            AUDIENCE,
            "preferred_username",
            "alice",
            "roles",
            List.of("ops-reader"),
            Instant.now().minusSeconds(60))))
        .verifyComplete();
  }

  @Test
  void failsFastWhenOidcModeHasNoDiscoveryConfiguration() {
    // 当切到 OIDC 模式却没有 issuer-uri 或 jwk-set-uri 时，应立即报配置错误。
    SecurityProperties properties = new SecurityProperties(
        "oidc",
        ISSUER,
        AUDIENCE,
        SECRET,
        null,
        null,
        "preferred_username",
        "roles",
        "ROLE_",
        false,
        "ops-agent",
        "/auth/session",
        "{baseUrl}/");
    ConfigurableJwtOperatorIdentityAuthenticator authenticator =
        new ConfigurableJwtOperatorIdentityAuthenticator(properties, new IdentityClaimsMapper());

    StepVerifier.create(authenticator.authenticate("ignored-token"))
        .expectErrorMatches(error ->
            error instanceof IllegalStateException
                && error.getMessage().contains("issuer-uri or jwk-set-uri"))
        .verify();
  }

  /**
   * 构造共享密钥模式测试令牌。
   */
  private String token(
      String subject,
      String issuer,
      String audience,
      String usernameClaim,
      String username,
      String roleClaim,
      Object roleValue,
      Instant expiration) {
    try {
      JWTClaimsSet claims = new JWTClaimsSet.Builder()
          .subject(subject)
          .issuer(issuer)
          .audience(audience)
          .issueTime(Date.from(Instant.now()))
          .expirationTime(Date.from(expiration))
          .claim(usernameClaim, username)
          .claim(roleClaim, roleValue)
          .build();
      SignedJWT jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), claims);
      jwt.sign(new MACSigner(SECRET.getBytes(StandardCharsets.UTF_8)));
      return jwt.serialize();
    } catch (JOSEException exception) {
      throw new IllegalStateException("failed to create test token", exception);
    }
  }

  private static String runtimeSecret() {
    byte[] bytes = new byte[32];
    new SecureRandom().nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
