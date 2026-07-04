package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;
import com.company.opsagent.contracts.sqlworkbench.SqlMetadataColumn;
import com.company.opsagent.contracts.sqlworkbench.SqlMetadataIndex;
import com.company.opsagent.contracts.sqlworkbench.SqlMetadataObject;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JDBC DatabaseMetaData 读取器，只返回授权 Schema 下的结构信息。
 */
public final class JdbcSqlMetadataReader implements SqlMetadataReader {

  private static final int MAX_OBJECTS = 200;
  private static final String[] OBJECT_TYPES = {"TABLE", "VIEW", "SYSTEM TABLE"};

  private final SqlDataSourceRegistry dataSourceRegistry;
  private final Clock clock;

  public JdbcSqlMetadataReader(SqlDataSourceRegistry dataSourceRegistry, Clock clock) {
    this.dataSourceRegistry = dataSourceRegistry;
    this.clock = clock;
  }

  @Override
  public SqlDatabaseMetadata read(SqlConnectionSummary connectionSummary, String schema) {
    if (schema == null || schema.isBlank()) {
      throw new IllegalArgumentException("schema must not be blank");
    }
    String requestedSchema = schema.trim();
    boolean schemaAllowed = connectionSummary.allowedSchemas().stream()
        .anyMatch(allowed -> allowed.equalsIgnoreCase(requestedSchema));
    if (!schemaAllowed) {
      throw new WorkerSqlEgressException("SQL_SCHEMA_NOT_ALLOWED", "SQL schema is not allowed for this worker request");
    }
    try (Connection connection = dataSourceRegistry.resolve(connectionSummary).getConnection()) {
      connection.setReadOnly(true);
      trySetSchema(connection, requestedSchema);
      DatabaseMetaData metadata = connection.getMetaData();
      ReadObjectsResult result = readObjects(metadata, requestedSchema);
      return new SqlDatabaseMetadata(
          "1.0",
          connectionSummary.connectionId(),
          requestedSchema,
          result.objects(),
          result.truncated(),
          OffsetDateTime.now(clock));
    } catch (SQLException exception) {
      throw new IllegalStateException("SQL metadata read failed", exception);
    }
  }

  private ReadObjectsResult readObjects(DatabaseMetaData metadata, String schema) throws SQLException {
    List<SqlMetadataObject> objects = new ArrayList<>();
    boolean truncated = false;
    try (ResultSet tables = metadata.getTables(null, schema, "%", OBJECT_TYPES)) {
      while (tables.next()) {
        if (objects.size() >= MAX_OBJECTS) {
          truncated = true;
          break;
        }
        String tableSchema = textOrDefault(tables.getString("TABLE_SCHEM"), schema);
        String tableName = tables.getString("TABLE_NAME");
        String tableType = normalizeObjectType(tables.getString("TABLE_TYPE"));
        objects.add(new SqlMetadataObject(
            tableSchema,
            tableName,
            tableType,
            readColumns(metadata, tableSchema, tableName),
            readIndexes(metadata, tableSchema, tableName)));
      }
    }
    objects.sort(Comparator
        .comparing(SqlMetadataObject::type)
        .thenComparing(SqlMetadataObject::name));
    return new ReadObjectsResult(objects, truncated);
  }

  private List<SqlMetadataColumn> readColumns(
      DatabaseMetaData metadata,
      String schema,
      String tableName) throws SQLException {
    List<SqlMetadataColumn> columns = new ArrayList<>();
    try (ResultSet resultSet = metadata.getColumns(null, schema, tableName, "%")) {
      while (resultSet.next()) {
        int nullable = resultSet.getInt("NULLABLE");
        columns.add(new SqlMetadataColumn(
            resultSet.getString("COLUMN_NAME"),
            resultSet.getString("TYPE_NAME"),
            nullable == DatabaseMetaData.columnNullable,
            resultSet.getInt("ORDINAL_POSITION"),
            false));
      }
    }
    columns.sort(Comparator.comparingInt(SqlMetadataColumn::ordinalPosition));
    return columns;
  }

  private List<SqlMetadataIndex> readIndexes(
      DatabaseMetaData metadata,
      String schema,
      String tableName) throws SQLException {
    Map<String, IndexBuilder> indexes = new LinkedHashMap<>();
    try (ResultSet resultSet = metadata.getIndexInfo(null, schema, tableName, false, false)) {
      while (resultSet.next()) {
        if (resultSet.getShort("TYPE") == DatabaseMetaData.tableIndexStatistic) {
          continue;
        }
        String indexName = resultSet.getString("INDEX_NAME");
        String columnName = resultSet.getString("COLUMN_NAME");
        if (indexName == null || indexName.isBlank() || columnName == null || columnName.isBlank()) {
          continue;
        }
        boolean unique = !resultSet.getBoolean("NON_UNIQUE");
        indexes.computeIfAbsent(indexName, name -> new IndexBuilder(name, unique))
            .addColumn(columnName);
      }
    }
    return indexes.values().stream()
        .map(IndexBuilder::build)
        .sorted(Comparator.comparing(SqlMetadataIndex::name))
        .toList();
  }

  private String normalizeObjectType(String rawType) {
    String normalized = textOrDefault(rawType, "TABLE")
        .toUpperCase(Locale.ROOT)
        .replace(' ', '_');
    if ("BASE_TABLE".equals(normalized)) {
      return "TABLE";
    }
    if ("SYSTEM_TABLE".equals(normalized)) {
      return "SYSTEM_TABLE";
    }
    if ("VIEW".equals(normalized)) {
      return "VIEW";
    }
    return "TABLE";
  }

  private void trySetSchema(Connection connection, String schema) throws SQLException {
    try {
      connection.setSchema(schema);
    } catch (SQLFeatureNotSupportedException | AbstractMethodError ignored) {
      // Some JDBC drivers do not support setSchema; DatabaseMetaData filtering remains authoritative.
    }
  }

  private String textOrDefault(String value, String fallback) {
    return value == null || value.isBlank() ? fallback : value;
  }

  private record ReadObjectsResult(List<SqlMetadataObject> objects, boolean truncated) {
  }

  private static final class IndexBuilder {
    private final String name;
    private final boolean unique;
    private final List<String> columns = new ArrayList<>();

    private IndexBuilder(String name, boolean unique) {
      this.name = name;
      this.unique = unique;
    }

    private void addColumn(String columnName) {
      columns.add(columnName);
    }

    private SqlMetadataIndex build() {
      return new SqlMetadataIndex(name, unique, columns);
    }
  }
}
