package com.company.opsagent.controlplane.bootstrap.config;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地模拟 OIDC Provider 的配置。
 *
 * @param enabled 是否启用仅用于本地联调的模拟 Provider
 * @param issuer Provider 的发行者地址
 * @param audience 本地签发令牌默认写入的受众
 * @param clientId 本地联调使用的固定客户端标识
 * @param clientSecret 本地联调使用的固定客户端密钥
 * @param defaultSubject 默认登录主体标识
 * @param defaultUsername 默认登录用户名
 * @param defaultRoles 默认登录角色
 * @param authorizationCodeTtl 授权码有效期
 * @param tokenTtl 令牌有效期
 */
@ConfigurationProperties(prefix = "ops-agent.local-oidc-provider")
public record LocalOidcProviderProperties(
    boolean enabled,
    String issuer,
    String audience,
    String clientId,
    String clientSecret,
    String defaultSubject,
    String defaultUsername,
    List<String> defaultRoles,
    Duration authorizationCodeTtl,
    Duration tokenTtl) {

  public LocalOidcProviderProperties {
    issuer = hasText(issuer) ? issuer : "http://127.0.0.1:8080/mock-oidc";
    audience = hasText(audience) ? audience : "ops-agent-internal";
    clientId = hasText(clientId) ? clientId : "ops-agent-local-client";
    clientSecret = requireText(clientSecret, "ops-agent.local-oidc-provider.client-secret");
    defaultSubject = hasText(defaultSubject) ? defaultSubject : "local-reader-id";
    defaultUsername = hasText(defaultUsername) ? defaultUsername : "local.reader";
    defaultRoles = defaultRoles == null || defaultRoles.isEmpty() ? List.of("ops-reader") : List.copyOf(defaultRoles);
    authorizationCodeTtl = authorizationCodeTtl == null ? Duration.ofMinutes(5) : authorizationCodeTtl;
    tokenTtl = tokenTtl == null ? Duration.ofMinutes(10) : tokenTtl;
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }

  private static String requireText(String value, String propertyName) {
    if (!hasText(value)) {
      throw new IllegalArgumentException(propertyName + " must be injected");
    }
    return value;
  }
}
