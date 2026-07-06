package com.company.opsagent.controlplane.bootstrap.config;

import com.company.opsagent.controlplane.bootstrap.audit.FileBackedAuditTrail;
import com.company.opsagent.controlplane.bootstrap.api.ApiErrorResponseWriter;
import com.company.opsagent.controlplane.bootstrap.security.AuthenticatedPrincipalOperatorIdentityResolver;
import com.company.opsagent.controlplane.bootstrap.security.ConfigurableJwtOperatorIdentityAuthenticator;
import com.company.opsagent.controlplane.bootstrap.security.OperatorIdentityAuthenticator;
import com.company.opsagent.controlplane.bootstrap.security.PolicyEnforcementWebFilter;
import com.company.opsagent.controlplane.modules.audit.AuditTrail;
import com.company.opsagent.controlplane.modules.audit.R2dbcAuditTrail;
import com.company.opsagent.controlplane.modules.identity.IdentityClaimsMapper;
import com.company.opsagent.controlplane.modules.identity.api.IdentitySessionQueryService;
import com.company.opsagent.controlplane.modules.policy.PolicyDecisionService;
import com.company.opsagent.controlplane.modules.policy.RoleBasedPolicyDecider;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.net.URI;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.core.io.ClassPathResource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpMethod;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.oidc.web.server.logout.OidcClientInitiatedServerLogoutSuccessHandler;
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.logout.RedirectServerLogoutSuccessHandler;
import org.springframework.security.web.server.authentication.logout.ServerLogoutSuccessHandler;
import org.springframework.security.web.server.util.matcher.OrServerWebExchangeMatcher;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;

/**
 * 控制面安全装配配置。
 *
 * <p>该类把身份映射、策略决策、审计持久化、认证器和 WebFlux 过滤链拼接成完整的
 * “认证 -> 授权 -> 审计” 执行路径。
 */
@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties({SecurityProperties.class, AuditProperties.class, PolicyProperties.class})
public class SecurityConfiguration {

  /**
   * 注册身份 Claim 映射器。
   */
  @Bean
  IdentityClaimsMapper identityClaimsMapper() {
    return new IdentityClaimsMapper();
  }

  /**
   * 根据外部配置构建策略决策服务。
   *
   * @param properties 策略配置属性
   * @return 当前使用的 RBAC 决策实现
   */
  @Bean
  PolicyDecisionService policyDecisionService(PolicyProperties properties) {
    return new RoleBasedPolicyDecider(
        properties.getVersion(),
        properties.getRequiredRolesByAction());
  }

  /**
   * 构建审计链实现。
   *
   * <p>如果未显式配置路径，则落到默认 JSONL 文件位置。
   */
  @Bean
  AuditTrail auditTrail(
      AuditProperties properties,
      ObjectMapper objectMapper,
      ObjectProvider<DatabaseClient> databaseClientProvider) {
    String storageMode = properties.storageMode() == null || properties.storageMode().isBlank()
        ? "file"
        : properties.storageMode();
    if ("database".equalsIgnoreCase(storageMode)) {
      DatabaseClient databaseClient = databaseClientProvider.getIfAvailable();
      if (databaseClient == null) {
        throw new IllegalStateException("database audit storage requires a DatabaseClient");
      }
      return new R2dbcAuditTrail(databaseClient);
    }
    if (!"file".equalsIgnoreCase(storageMode)) {
      throw new IllegalArgumentException("unsupported audit storage mode: " + storageMode);
    }
    String storagePath = properties.storagePath() == null || properties.storagePath().isBlank()
        ? "var/audit/control-plane-audit.jsonl"
        : properties.storagePath();
    return new FileBackedAuditTrail(Path.of(storagePath), objectMapper);
  }

  @Bean
  ConnectionFactoryInitializer auditSchemaInitializer(ConnectionFactory connectionFactory) {
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(
        new ClassPathResource("sql/migrations/V001__audit_event_schema.sql")));
    return initializer;
  }

  /**
   * 构建操作人身份认证器。
   */
  @Bean
  OperatorIdentityAuthenticator operatorIdentityAuthenticator(
      SecurityProperties securityProperties,
      IdentityClaimsMapper identityClaimsMapper) {
    return new ConfigurableJwtOperatorIdentityAuthenticator(securityProperties, identityClaimsMapper);
  }

  /**
   * 构建浏览器会话主体到内部身份模型的解析器。
   */
  @Bean
  AuthenticatedPrincipalOperatorIdentityResolver authenticatedPrincipalOperatorIdentityResolver(
      SecurityProperties securityProperties,
      IdentityClaimsMapper identityClaimsMapper) {
    return new AuthenticatedPrincipalOperatorIdentityResolver(securityProperties, identityClaimsMapper);
  }

  /**
   * 构建统一的策略执行过滤器。
   */
  private PolicyEnforcementWebFilter policyEnforcementWebFilter(
      PolicyDecisionService policyDecisionService,
      AuditTrail auditTrail,
      ApiErrorResponseWriter apiErrorResponseWriter,
      OperatorIdentityAuthenticator operatorIdentityAuthenticator,
      AuthenticatedPrincipalOperatorIdentityResolver authenticatedPrincipalOperatorIdentityResolver,
      ObjectProvider<IdentitySessionQueryService> identitySessionQueryServiceProvider,
      ObjectProvider<BuiltInIdentityProperties> builtInIdentityPropertiesProvider) {
    BuiltInIdentityProperties builtInIdentityProperties = builtInIdentityPropertiesProvider.getIfAvailable();
    return new PolicyEnforcementWebFilter(
        policyDecisionService,
        auditTrail,
        apiErrorResponseWriter,
        operatorIdentityAuthenticator,
        authenticatedPrincipalOperatorIdentityResolver,
        identitySessionQueryServiceProvider.getIfAvailable(),
        builtInIdentityProperties == null ? "OPS_AGENT_SESSION" : builtInIdentityProperties.getSessionCookieName());
  }

  /**
   * 构建 WebFlux 安全过滤链。
   *
   * <p>当前仅放行文档与运维基础端点，其余受保护端点交给自定义过滤器处理。
   */
  @Bean
  SecurityWebFilterChain securityWebFilterChain(
      ServerHttpSecurity http,
      PolicyDecisionService policyDecisionService,
      AuditTrail auditTrail,
      ApiErrorResponseWriter apiErrorResponseWriter,
      OperatorIdentityAuthenticator operatorIdentityAuthenticator,
      AuthenticatedPrincipalOperatorIdentityResolver authenticatedPrincipalOperatorIdentityResolver,
      ObjectProvider<IdentitySessionQueryService> identitySessionQueryServiceProvider,
      ObjectProvider<BuiltInIdentityProperties> builtInIdentityPropertiesProvider,
      SecurityProperties securityProperties,
      ObjectProvider<ReactiveClientRegistrationRepository> clientRegistrationRepositoryProvider,
      ObjectProvider<LocalOidcProviderProperties> localOidcProviderPropertiesProvider) {
    PolicyEnforcementWebFilter policyEnforcementWebFilter = policyEnforcementWebFilter(
        policyDecisionService,
        auditTrail,
        apiErrorResponseWriter,
        operatorIdentityAuthenticator,
        authenticatedPrincipalOperatorIdentityResolver,
        identitySessionQueryServiceProvider,
        builtInIdentityPropertiesProvider);
    http
        .csrf(ServerHttpSecurity.CsrfSpec::disable)
        .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
        .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
        .authorizeExchange(spec -> spec
            .pathMatchers(
                "/actuator/**",
                "/v3/api-docs/**",
                "/swagger-ui.html",
                "/swagger-ui/**",
                "/auth/login",
                "/auth/logout",
                "/auth/password",
                "/login/**",
                "/oauth2/**",
                "/mock-oidc/**",
                "/.well-known/**").permitAll()
            .anyExchange().permitAll())
        .addFilterAt(policyEnforcementWebFilter, SecurityWebFiltersOrder.AUTHENTICATION);

    if (securityProperties.browserLoginEnabled() && isBuiltInMode(securityProperties)) {
      http.logout(ServerHttpSecurity.LogoutSpec::disable);
      return http.build();
    }

    if (securityProperties.browserLoginEnabled() && isOidcMode(securityProperties)) {
      ReactiveClientRegistrationRepository clientRegistrationRepository =
          clientRegistrationRepositoryProvider.getIfAvailable();
      if (clientRegistrationRepository == null) {
        throw new IllegalStateException("browser login enabled but no OIDC client registration is configured");
      }
      http.oauth2Login(oauth2 -> oauth2
          .authenticationSuccessHandler(browserLoginSuccessHandler(securityProperties)));
      http.logout(logout -> logout
          .requiresLogout(new OrServerWebExchangeMatcher(
              ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET, "/logout"),
              ServerWebExchangeMatchers.pathMatchers(HttpMethod.POST, "/logout")))
          .logoutSuccessHandler(browserLogoutSuccessHandler(
              clientRegistrationRepository,
              securityProperties,
              localOidcProviderPropertiesProvider.getIfAvailable())));
      return http.build();
    }

    http.logout(ServerHttpSecurity.LogoutSpec::disable);
    return http.build();
  }

  private ServerLogoutSuccessHandler browserLogoutSuccessHandler(
      ReactiveClientRegistrationRepository clientRegistrationRepository,
      SecurityProperties securityProperties,
      LocalOidcProviderProperties localOidcProviderProperties) {
    if (localOidcProviderProperties != null && localOidcProviderProperties.enabled()) {
      RedirectServerLogoutSuccessHandler logoutSuccessHandler = new RedirectServerLogoutSuccessHandler();
      logoutSuccessHandler.setLogoutSuccessUrl(URI.create(resolveLocalLogoutSuccessUri(securityProperties)));
      return logoutSuccessHandler;
    }
    OidcClientInitiatedServerLogoutSuccessHandler logoutSuccessHandler =
        new OidcClientInitiatedServerLogoutSuccessHandler(clientRegistrationRepository);
    String postLogoutRedirectUri = securityProperties.browserLogoutSuccessUri() == null
        || securityProperties.browserLogoutSuccessUri().isBlank()
        ? "{baseUrl}/"
        : securityProperties.browserLogoutSuccessUri();
    logoutSuccessHandler.setPostLogoutRedirectUri(postLogoutRedirectUri);
    return logoutSuccessHandler;
  }

  private String resolveLocalLogoutSuccessUri(SecurityProperties securityProperties) {
    String configured = securityProperties.browserLogoutSuccessUri();
    if (configured == null || configured.isBlank() || configured.contains("{baseUrl}")) {
      return "/";
    }
    return configured;
  }

  private ServerAuthenticationSuccessHandler browserLoginSuccessHandler(
      SecurityProperties securityProperties) {
    String successUri = securityProperties.browserLoginSuccessUri() == null
        || securityProperties.browserLoginSuccessUri().isBlank()
        ? "/auth/session"
        : securityProperties.browserLoginSuccessUri();
    return new RedirectServerAuthenticationSuccessHandler(successUri);
  }

  private boolean isBuiltInMode(SecurityProperties securityProperties) {
    return "built-in".equalsIgnoreCase(securityProperties.authMode());
  }

  private boolean isOidcMode(SecurityProperties securityProperties) {
    return "oidc".equalsIgnoreCase(securityProperties.authMode());
  }
}
