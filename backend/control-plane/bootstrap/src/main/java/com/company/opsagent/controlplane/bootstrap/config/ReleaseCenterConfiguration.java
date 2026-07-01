package com.company.opsagent.controlplane.bootstrap.config;

import com.company.opsagent.controlplane.modules.release.AesGcmReleaseCredentialSecretCodec;
import com.company.opsagent.controlplane.modules.release.R2dbcReleaseCatalogStore;
import com.company.opsagent.controlplane.modules.release.ReleaseCatalogStore;
import com.company.opsagent.controlplane.modules.release.ReleaseCredentialSecretCodec;
import com.company.opsagent.controlplane.modules.release.ReleaseCredentialService;
import io.r2dbc.spi.ConnectionFactory;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * 发布中心目录和迁移脚本装配。执行能力仍由后续功能开关控制。
 */
@Configuration
@EnableConfigurationProperties(ReleaseCenterProperties.class)
public class ReleaseCenterConfiguration {

  private static final String LOCAL_RELEASE_CREDENTIAL_MASTER_KEY =
      "OPS_AGENT_RELEASE_CENTER_SECRET_MASTER_KEY_REPLACE_ME";

  @Bean
  ReleaseCatalogStore releaseCatalogStore(DatabaseClient databaseClient) {
    return new R2dbcReleaseCatalogStore(databaseClient);
  }

  @Bean
  ReleaseCredentialSecretCodec releaseCredentialSecretCodec(ReleaseCenterProperties properties) {
    String masterKey = properties.getCredentialMasterKey();
    if (masterKey == null || masterKey.isBlank()) {
      if (properties.isEnabled()) {
        throw new IllegalStateException("credentialMasterKey must be configured when release center is enabled");
      }
      masterKey = LOCAL_RELEASE_CREDENTIAL_MASTER_KEY;
    }
    return new AesGcmReleaseCredentialSecretCodec(masterKey);
  }

  @Bean
  ReleaseCredentialService releaseCredentialService(
      ReleaseCatalogStore releaseCatalogStore,
      ReleaseCredentialSecretCodec releaseCredentialSecretCodec) {
    return new ReleaseCredentialService(
        releaseCatalogStore,
        releaseCredentialSecretCodec,
        Clock.systemUTC());
  }

  @Bean
  ConnectionFactoryInitializer releaseCenterSchemaInitializer(ConnectionFactory connectionFactory) {
    var initializer = new ConnectionFactoryInitializer();
    initializer.setConnectionFactory(connectionFactory);
    initializer.setDatabasePopulator(new ResourceDatabasePopulator(
        new ClassPathResource("sql/migrations/V001__release_center_schema.sql")));
    return initializer;
  }
}
