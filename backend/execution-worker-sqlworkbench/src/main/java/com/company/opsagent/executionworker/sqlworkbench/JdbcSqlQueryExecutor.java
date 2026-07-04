package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlResultColumn;
import com.company.opsagent.contracts.sqlworkbench.SqlResultPage;
import com.company.opsagent.contracts.sqlworkbench.SqlTypedParameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.PreparedStatement;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 阻塞 JDBC 只读查询执行器，调用方必须把它调度到非 WebFlux 事件循环线程。
 */
public class JdbcSqlQueryExecutor implements SqlQueryExecutor {

  private final SqlDataSourceRegistry dataSourceRegistry;
  private final SqlResultStore resultStore;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  public JdbcSqlQueryExecutor(
      SqlDataSourceRegistry dataSourceRegistry,
      SqlResultStore resultStore,
      ObjectMapper objectMapper,
      Clock clock) {
    this.dataSourceRegistry = dataSourceRegistry;
    this.resultStore = resultStore;
    this.objectMapper = objectMapper;
    this.clock = clock;
  }

  @Override
  public String execute(SqlQueryExecutionRequest request) {
    String resultId = UUID.randomUUID().toString();
    try (Connection connection = dataSourceRegistry.resolve(request).getConnection()) {
      connection.setReadOnly(true);
      connection.setAutoCommit(false);
      connection.setSchema(request.query().schema());
      try (var statement = connection.prepareStatement(request.query().sql())) {
        statement.setQueryTimeout(request.query().limits().timeoutSeconds());
        statement.setMaxRows(request.query().limits().maxRows() + 1);
        bindParameters(statement, request.query().parameters());
        try (ResultSet resultSet = statement.executeQuery()) {
          SqlResultPage page = readPage(resultId, request, resultSet);
          resultStore.save(page);
        }
      } finally {
        connection.rollback();
      }
      return resultId;
    } catch (SQLException exception) {
      throw new IllegalStateException("read-only JDBC query failed", exception);
    }
  }

  @Override
  public int executeDml(SqlQueryExecutionRequest request) {
    try (Connection connection = dataSourceRegistry.resolve(request).getConnection()) {
      connection.setReadOnly(false);
      connection.setAutoCommit(false);
      connection.setSchema(request.query().schema());
      try (var statement = connection.prepareStatement(request.query().sql())) {
        statement.setQueryTimeout(request.query().limits().timeoutSeconds());
        bindParameters(statement, request.query().parameters());
        int affectedRows = statement.executeUpdate();
        connection.commit();
        return affectedRows;
      } catch (SQLException exception) {
        rollbackQuietly(connection);
        throw exception;
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("controlled JDBC DML failed", exception);
    }
  }

  private void rollbackQuietly(Connection connection) {
    try {
      connection.rollback();
    } catch (SQLException ignored) {
      // Preserve the original DML failure as the stable error cause.
    }
  }

  private void bindParameters(PreparedStatement statement, List<SqlTypedParameter> parameters) throws SQLException {
    for (int index = 0; index < parameters.size(); index++) {
      SqlTypedParameter parameter = parameters.get(index);
      int jdbcIndex = index + 1;
      switch (parameter.type().toUpperCase()) {
        case "STRING" -> statement.setString(jdbcIndex, parameter.value().asText());
        case "INTEGER" -> statement.setInt(jdbcIndex, parameter.value().asInt());
        case "LONG" -> statement.setLong(jdbcIndex, parameter.value().asLong());
        case "DECIMAL" -> statement.setBigDecimal(jdbcIndex, new BigDecimal(parameter.value().asText()));
        case "BOOLEAN" -> statement.setBoolean(jdbcIndex, parameter.value().asBoolean());
        case "DATE" -> statement.setObject(jdbcIndex, LocalDate.parse(parameter.value().asText()));
        case "TIMESTAMP" -> statement.setObject(jdbcIndex, LocalDateTime.parse(parameter.value().asText()));
        default -> throw new IllegalArgumentException("unsupported SQL parameter type: " + parameter.type());
      }
    }
  }

  private SqlResultPage readPage(
      String resultId,
      SqlQueryExecutionRequest request,
      ResultSet resultSet) throws SQLException {
    var metadata = resultSet.getMetaData();
    List<SqlResultColumn> columns = new ArrayList<>();
    for (int index = 1; index <= metadata.getColumnCount(); index++) {
      columns.add(new SqlResultColumn(metadata.getColumnLabel(index), metadata.getColumnTypeName(index), false));
    }

    List<List<JsonNode>> rows = new ArrayList<>();
    long bytes = 0;
    boolean truncated = false;
    while (resultSet.next()) {
      if (rows.size() >= request.query().limits().maxRows()) {
        truncated = true;
        break;
      }
      List<JsonNode> row = new ArrayList<>();
      for (int index = 1; index <= metadata.getColumnCount(); index++) {
        Object value = resultSet.getObject(index);
        JsonNode node = value == null ? NullNode.getInstance() : objectMapper.valueToTree(value);
        bytes += node.toString().getBytes(StandardCharsets.UTF_8).length;
        row.add(node);
      }
      if (bytes > request.query().limits().maxBytes()) {
        truncated = true;
        break;
      }
      rows.add(row);
    }
    return new SqlResultPage(
        "1.0",
        resultId,
        columns,
        rows,
        null,
        truncated,
        OffsetDateTime.now(clock).plusMinutes(15));
  }
}
