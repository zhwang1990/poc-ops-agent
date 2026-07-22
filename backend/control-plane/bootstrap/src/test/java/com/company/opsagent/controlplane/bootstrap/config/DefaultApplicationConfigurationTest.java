package com.company.opsagent.controlplane.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

class DefaultApplicationConfigurationTest {

  @Test
  void defaultApplicationConfigurationUsesBuiltInBrowserIdentity() throws IOException {
    PropertySource<?> application = loadApplicationYaml();

    assertEquals(8080, application.getProperty("server.port"));
    assertEquals("built-in", application.getProperty("ops-agent.security.auth-mode"));
    assertEquals(true, application.getProperty("ops-agent.security.browser-login-enabled"));
    assertEquals(false, application.getProperty("ops-agent.local-oidc-provider.enabled"));
    assertEquals("${OPS_AGENT_SECURITY_SHARED_SECRET}",
        application.getProperty("ops-agent.security.shared-secret"));
    assertEquals("${OPS_AGENT_LOCAL_OIDC_CLIENT_SECRET}",
        application.getProperty("ops-agent.local-oidc-provider.client-secret"));
  }

  @Test
  void localOidcProfileRequiresInjectedClientSecret() throws IOException {
    PropertySource<?> localOidc = loadYaml("application-local-oidc.yaml");

    assertEquals("${OPS_AGENT_LOCAL_OIDC_CLIENT_SECRET}",
        localOidc.getProperty("ops-agent.local-oidc-provider.client-secret"));
    assertEquals("${OPS_AGENT_LOCAL_OIDC_CLIENT_SECRET}",
        localOidc.getProperty("spring.security.oauth2.client.registration.ops-agent.client-secret"));
  }

  private PropertySource<?> loadApplicationYaml() throws IOException {
    return loadYaml("application.yaml");
  }

  private PropertySource<?> loadYaml(String resourceName) throws IOException {
    List<PropertySource<?>> sources = new YamlPropertySourceLoader()
        .load(resourceName, new ClassPathResource(resourceName));
    return sources.getFirst();
  }
}
