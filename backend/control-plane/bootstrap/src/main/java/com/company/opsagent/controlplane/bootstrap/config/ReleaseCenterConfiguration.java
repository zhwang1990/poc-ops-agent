package com.company.opsagent.controlplane.bootstrap.config;

import com.company.opsagent.controlplane.modules.release.R2dbcReleaseCatalogStore;
import com.company.opsagent.controlplane.modules.release.ReleaseCatalogStore;
import io.r2dbc.spi.ConnectionFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.r2dbc.connection.init.ConnectionFactoryInitializer;
import org.springframework.r2dbc.connection.init.ResourceDatabasePopulator;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * 发布中心目录和迁移脚本装配。执行能力仍由后续功能开关控制。
 */
@Configuration
public class ReleaseCenterConfiguration {

  @Bean
  ReleaseCatalogStore releaseCatalogStore(DatabaseClient databaseClient) {
    return new R2dbcReleaseCatalogStore(databaseClient);
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
