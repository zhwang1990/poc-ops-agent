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
  }

  private PropertySource<?> loadApplicationYaml() throws IOException {
    List<PropertySource<?>> sources = new YamlPropertySourceLoader()
        .load("application", new ClassPathResource("application.yaml"));
    return sources.getFirst();
  }
}
