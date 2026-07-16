package com.company.opsagent.executionworker.sqlworkbench;

import java.sql.SQLException;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.sql.DataSource;
import org.h2.jdbcx.JdbcDataSource;

/**
 * Creates Worker-local H2 data sources for development and test diagnostics.
 */
public final class H2SqlDataSourceFactory {

  private final ConcurrentMap<String, DataSource> dataSources = new ConcurrentHashMap<>();
  private final ConcurrentMap<String, DataSource> initializedDatabases = new ConcurrentHashMap<>();

  public DataSource create(WorkerSqlConnectionDescriptor descriptor) {
    return dataSources.computeIfAbsent(
        dataSourceKey(descriptor.connectionId(), "read"),
        ignored -> {
          initializeDatabase(descriptor.connectionId(), null, null);
          return createDataSource(descriptor.connectionId(), null, null, true);
        });
  }

  public DataSource createWrite(WorkerSqlConnectionDescriptor descriptor, char[] password) {
    return dataSources.computeIfAbsent(
        dataSourceKey(descriptor.connectionId(), "write"),
        ignored -> {
          initializeDatabase(descriptor.connectionId(), descriptor.dmlUsername(), password);
          return createDataSource(descriptor.connectionId(), descriptor.dmlUsername(), password, false);
        });
  }

  private void initializeDatabase(String connectionId, String username, char[] password) {
    initializedDatabases.computeIfAbsent(connectionId, ignored -> {
      DataSource dataSource = createDataSource(connectionId, username, password, true);
      initialize(dataSource);
      return dataSource;
    });
  }

  private DataSource createDataSource(
      String connectionId,
      String username,
      char[] password,
      boolean applyDatabaseSettings) {
    JdbcDataSource dataSource = new JdbcDataSource();
    dataSource.setURL(connectionUrl(connectionId, applyDatabaseSettings));
    if (username != null) {
      dataSource.setUser(username);
      dataSource.setPassword(new String(password));
    }
    return dataSource;
  }

  private void initialize(DataSource dataSource) {
    try (var connection = dataSource.getConnection();
        var statement = connection.createStatement()) {
      statement.execute("""
          CREATE TABLE IF NOT EXISTS PUBLIC.CUSTOMERS (
            CUSTOMER_ID VARCHAR(48) PRIMARY KEY,
            CUSTOMER_NAME VARCHAR(120) NOT NULL,
            TIER VARCHAR(24) NOT NULL,
            REGION VARCHAR(48) NOT NULL,
            CUSTOMER_STATUS VARCHAR(24) NOT NULL
          )
          """);
      statement.execute("""
          CREATE TABLE IF NOT EXISTS PUBLIC.ORDERS (
            ORDER_ID INTEGER PRIMARY KEY,
            STATUS VARCHAR(24) NOT NULL,
            AMOUNT DECIMAL(12, 2) NOT NULL,
            CUSTOMER_ID VARCHAR(48) NOT NULL,
            CREATED_AT TIMESTAMP NOT NULL
          )
          """);
      statement.execute("""
          CREATE TABLE IF NOT EXISTS PUBLIC.INCIDENTS (
            INCIDENT_ID VARCHAR(48) PRIMARY KEY,
            SERVICE_NAME VARCHAR(80) NOT NULL,
            SEVERITY VARCHAR(24) NOT NULL,
            INCIDENT_STATUS VARCHAR(24) NOT NULL,
            STARTED_AT TIMESTAMP NOT NULL,
            RESOLVED_AT TIMESTAMP,
            IMPACT_SUMMARY VARCHAR(240) NOT NULL
          )
          """);
      statement.execute("""
          CREATE TABLE IF NOT EXISTS PUBLIC.SERVICE_HEALTH (
            SERVICE_NAME VARCHAR(80) NOT NULL,
            ENVIRONMENT VARCHAR(24) NOT NULL,
            HEALTH_STATUS VARCHAR(24) NOT NULL,
            ERROR_RATE_PERCENT DECIMAL(8, 3) NOT NULL,
            P95_LATENCY_MS INTEGER NOT NULL,
            UPDATED_AT TIMESTAMP NOT NULL,
            PRIMARY KEY (SERVICE_NAME, ENVIRONMENT)
          )
          """);
      statement.execute("""
          MERGE INTO PUBLIC.CUSTOMERS KEY(CUSTOMER_ID)
          VALUES
            ('CUST-001', 'Northwind Retail', 'gold', 'north', 'ACTIVE'),
            ('CUST-002', 'Contoso Finance', 'platinum', 'east', 'ACTIVE'),
            ('CUST-003', 'Fabrikam Logistics', 'silver', 'south', 'ACTIVE'),
            ('CUST-004', 'Adventure Works', 'gold', 'west', 'WATCH')
          """);
      statement.execute("""
          MERGE INTO PUBLIC.ORDERS KEY(ORDER_ID)
          VALUES
            (1, 'READY', 128.50, 'CUST-001', TIMESTAMP '2026-06-27 09:00:00'),
            (2, 'PENDING', 256.75, 'CUST-002', TIMESTAMP '2026-06-27 10:00:00'),
            (3, 'READY', 980.00, 'CUST-002', TIMESTAMP '2026-06-28 11:35:00'),
            (4, 'FAILED', 76.25, 'CUST-003', TIMESTAMP '2026-06-28 12:10:00'),
            (5, 'READY', 512.30, 'CUST-004', TIMESTAMP '2026-06-29 08:20:00'),
            (6, 'REVIEW', 342.10, 'CUST-001', TIMESTAMP '2026-06-29 14:45:00')
          """);
      statement.execute("""
          MERGE INTO PUBLIC.INCIDENTS KEY(INCIDENT_ID)
          VALUES
            ('INC-1001', 'order-api', 'P2', 'RESOLVED', TIMESTAMP '2026-06-29 02:15:00', TIMESTAMP '2026-06-29 02:42:00', 'checkout latency above threshold'),
            ('INC-1002', 'billing-worker', 'P3', 'MONITORING', TIMESTAMP '2026-06-29 09:05:00', null, 'retry backlog is draining'),
            ('INC-1003', 'inventory-sync', 'P1', 'OPEN', TIMESTAMP '2026-06-30 07:30:00', null, 'stock update delay impacts test region')
          """);
      statement.execute("""
          MERGE INTO PUBLIC.SERVICE_HEALTH KEY(SERVICE_NAME, ENVIRONMENT)
          VALUES
            ('order-api', 'test', 'DEGRADED', 1.250, 420, TIMESTAMP '2026-06-30 09:00:00'),
            ('billing-worker', 'test', 'WATCH', 0.750, 310, TIMESTAMP '2026-06-30 09:00:00'),
            ('inventory-sync', 'test', 'UNHEALTHY', 2.400, 860, TIMESTAMP '2026-06-30 09:00:00'),
            ('operator-console', 'test', 'HEALTHY', 0.010, 95, TIMESTAMP '2026-06-30 09:00:00'),
            ('order-api', 'development', 'HEALTHY', 0.050, 180, TIMESTAMP '2026-06-30 09:00:00')
          """);
    } catch (SQLException exception) {
      throw new IllegalStateException("H2 SQL data source initialization failed", exception);
    }
  }

  private String databaseName(String connectionId) {
    String normalized = connectionId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_");
    if (normalized.isBlank()) {
      return "ops_agent_sql";
    }
    return normalized;
  }

  private String dataSourceKey(String connectionId, String role) {
    return connectionId + "|" + role;
  }

  private String connectionUrl(String connectionId, boolean applyDatabaseSettings) {
    String url = "jdbc:h2:mem:" + databaseName(connectionId);
    return applyDatabaseSettings ? url + ";DB_CLOSE_DELAY=-1;DATABASE_TO_UPPER=TRUE" : url;
  }
}
