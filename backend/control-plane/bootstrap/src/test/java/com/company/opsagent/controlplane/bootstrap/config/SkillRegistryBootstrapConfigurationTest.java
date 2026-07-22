package com.company.opsagent.controlplane.bootstrap.config;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

class SkillRegistryBootstrapConfigurationTest {

  @Test
  void activeBootstrapConfigurationsRequireInjectedSigningSecret() throws IOException {
    assertMissingRequiredSecret("application.yaml");
    assertMissingRequiredSecret("application-oidc-example.yaml");
  }

  @Test
  void propertiesDoNotProvideASigningSecretDefault() {
    assertNull(new SkillRegistryProperties().getSigningSecret());
  }

  private void assertMissingRequiredSecret(String resourceName) throws IOException {
    StandardEnvironment environment = new StandardEnvironment();
    MutablePropertySources sources = environment.getPropertySources();
    List<org.springframework.core.env.PropertySource<?>> loaded = new YamlPropertySourceLoader().load(
        resourceName, new ClassPathResource(resourceName));
    for (org.springframework.core.env.PropertySource<?> source : loaded) {
      sources.addFirst(source);
    }

    assertThrows(IllegalArgumentException.class, () -> environment.getProperty(
        "ops-agent.skill-registry.signing-secret"));
  }
}
