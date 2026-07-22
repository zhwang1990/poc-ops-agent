package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlDmlImpactPreview;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreviewSelection;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlResultColumn;
import com.company.opsagent.contracts.sqlworkbench.SqlTypedParameter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.dialect.AnsiSqlDialect;
import org.apache.calcite.sql.fun.SqlStdOperatorTable;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;
import org.apache.calcite.sql.parser.SqlParserPos;
import reactor.core.publisher.Mono;

/**
 * 使用 Calcite AST 生成只读影响查询的 JDBC 预览执行器。
 */
public final class JdbcSqlDmlImpactPreviewExecutor implements SqlDmlImpactPreviewExecutor {

  private static final int MAX_SAMPLE_ROWS = 20;
  private static final SqlParserPos POSITION = SqlParserPos.ZERO;

  private final SqlDataSourceRegistry dataSourceRegistry;
  private final ObjectMapper objectMapper;

  public JdbcSqlDmlImpactPreviewExecutor(
      SqlDataSourceRegistry dataSourceRegistry,
      ObjectMapper objectMapper) {
    this.dataSourceRegistry = dataSourceRegistry;
    this.objectMapper = objectMapper;
  }

  @Override
  public Mono<SqlDmlImpactPreview> preview(SqlDmlPreflightExecutionRequest request) {
    return Mono.fromCallable(() -> executePreview(request));
  }

  private SqlDmlImpactPreview executePreview(SqlDmlPreflightExecutionRequest request) {
    SqlNode statement = parseControlledStatement(request.query().sql());
    if (statement instanceof SqlInsert) {
      return new SqlDmlImpactPreview(
          "1.0",
          1L,
          List.of(),
          List.of(),
          List.of("INSERT impact is estimated from one controlled VALUES row"));
    }

    PreviewPlan plan = previewPlan(statement, request.previewSelection());
    try (Connection connection = dataSourceRegistry.resolve(legacyRequest(request)).getConnection()) {
      try {
        connection.setReadOnly(true);
        connection.setAutoCommit(false);
        connection.setSchema(request.query().schema());
        long affectedRows = countAffectedRows(connection, request, plan);
        SampleResult samples = readSamples(connection, request, plan);
        return new SqlDmlImpactPreview(
            "1.0",
            affectedRows,
            samples.columns(),
            samples.rows(),
            List.of());
      } finally {
        connection.rollback();
      }
    } catch (SQLException exception) {
      throw new IllegalStateException("read-only JDBC DML preview failed");
    }
  }

  private SqlNode parseControlledStatement(String sql) {
    if (!new WorkerSqlDmlExecutionPolicy(List.of()).isSupportedSubset(sql)) {
      throw new IllegalArgumentException("SQL is outside the controlled DML subset");
    }
    try {
      return SqlParser.create(sql).parseStmtList().getFirst();
    } catch (SqlParseException exception) {
      throw new IllegalArgumentException("SQL is outside the controlled DML subset", exception);
    }
  }

  private PreviewPlan previewPlan(SqlNode statement, SqlDmlPreviewSelection selection) {
    SqlNode target;
    SqlNode condition;
    if (statement instanceof SqlUpdate update) {
      target = update.getTargetTable();
      condition = update.getCondition();
    } else if (statement instanceof SqlDelete delete) {
      target = delete.getTargetTable();
      condition = delete.getCondition();
    } else {
      throw new IllegalArgumentException("DML preview supports INSERT, UPDATE, or DELETE");
    }

    List<Integer> predicateParameterIndexes = new ArrayList<>();
    collectParameterIndexes(condition, predicateParameterIndexes);
    SqlSelect countQuery = select(
        SqlNodeList.of(SqlStdOperatorTable.COUNT.createCall(POSITION, SqlIdentifier.star(POSITION))),
        target,
        condition);
    SqlSelect sampleQuery = selection.sampleColumns().isEmpty()
        ? null
        : select(sampleColumns(selection.sampleColumns()), target, condition);
    return new PreviewPlan(
        countQuery.toSqlString(AnsiSqlDialect.DEFAULT).getSql(),
        sampleQuery == null ? null : sampleQuery.toSqlString(AnsiSqlDialect.DEFAULT).getSql(),
        List.copyOf(predicateParameterIndexes),
        selection);
  }

  private SqlSelect select(SqlNodeList selectList, SqlNode target, SqlNode condition) {
    return new SqlSelect(
        POSITION,
        SqlNodeList.EMPTY,
        selectList,
        target,
        condition,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        SqlNodeList.EMPTY);
  }

  private SqlNodeList sampleColumns(List<String> columns) {
    List<SqlNode> identifiers = columns.stream().map(this::sampleColumn).map(SqlNode.class::cast).toList();
    return new SqlNodeList(identifiers, POSITION);
  }

  private SqlIdentifier sampleColumn(String column) {
    if (!column.matches("[A-Za-z_][A-Za-z0-9_$#@]*")) {
      throw new IllegalArgumentException("DML preview sample columns must be simple identifiers");
    }
    return new SqlIdentifier(column, POSITION);
  }

  private long countAffectedRows(
      Connection connection,
      SqlDmlPreflightExecutionRequest request,
      PreviewPlan plan) throws SQLException {
    try (PreparedStatement statement = connection.prepareStatement(plan.countSql())) {
      statement.setQueryTimeout(request.query().limits().timeoutSeconds());
      bindPredicateParameters(statement, request.query().parameters(), plan.predicateParameterIndexes());
      try (ResultSet resultSet = statement.executeQuery()) {
        if (!resultSet.next()) {
          throw new SQLException("DML preview count did not return a row");
        }
        return resultSet.getLong(1);
      }
    }
  }

  private SampleResult readSamples(
      Connection connection,
      SqlDmlPreflightExecutionRequest request,
      PreviewPlan plan) throws SQLException {
    if (plan.sampleSql() == null) {
      return new SampleResult(List.of(), List.of());
    }
    try (PreparedStatement statement = connection.prepareStatement(plan.sampleSql())) {
      statement.setQueryTimeout(request.query().limits().timeoutSeconds());
      statement.setMaxRows(MAX_SAMPLE_ROWS);
      bindPredicateParameters(statement, request.query().parameters(), plan.predicateParameterIndexes());
      try (ResultSet resultSet = statement.executeQuery()) {
        return serializeSamples(resultSet, plan.selection());
      }
    }
  }

  private SampleResult serializeSamples(ResultSet resultSet, SqlDmlPreviewSelection selection)
      throws SQLException {
    Set<String> maskedColumns = new HashSet<>();
    selection.maskedSampleColumns().forEach(column -> maskedColumns.add(canonical(column)));
    var metadata = resultSet.getMetaData();
    List<SqlResultColumn> columns = new ArrayList<>();
    for (int index = 1; index <= metadata.getColumnCount(); index++) {
      String columnName = selection.sampleColumns().get(index - 1);
      columns.add(new SqlResultColumn(
          columnName,
          metadata.getColumnTypeName(index),
          maskedColumns.contains(canonical(columnName))));
    }

    List<List<JsonNode>> rows = new ArrayList<>();
    while (rows.size() < MAX_SAMPLE_ROWS && resultSet.next()) {
      List<JsonNode> row = new ArrayList<>();
      for (int index = 1; index <= metadata.getColumnCount(); index++) {
        String columnName = selection.sampleColumns().get(index - 1);
        if (maskedColumns.contains(canonical(columnName))) {
          row.add(TextNode.valueOf("***"));
        } else {
          Object value = resultSet.getObject(index);
          row.add(value == null ? NullNode.getInstance() : objectMapper.valueToTree(value));
        }
      }
      rows.add(List.copyOf(row));
    }
    return new SampleResult(List.copyOf(columns), List.copyOf(rows));
  }

  private void collectParameterIndexes(SqlNode node, List<Integer> indexes) {
    if (node == null) {
      return;
    }
    if (node instanceof SqlDynamicParam parameter) {
      indexes.add(parameter.getIndex());
      return;
    }
    if (node instanceof SqlCall call) {
      call.getOperandList().forEach(operand -> collectParameterIndexes(operand, indexes));
      return;
    }
    if (node instanceof SqlNodeList nodes) {
      nodes.forEach(nested -> collectParameterIndexes(nested, indexes));
    }
  }

  private void bindPredicateParameters(
      PreparedStatement statement,
      List<SqlTypedParameter> parameters,
      List<Integer> parameterIndexes) throws SQLException {
    for (int index = 0; index < parameterIndexes.size(); index++) {
      int sourceIndex = parameterIndexes.get(index);
      if (sourceIndex < 0 || sourceIndex >= parameters.size()) {
        throw new IllegalArgumentException("SQL predicate parameter is not present in the request envelope");
      }
      bindParameter(statement, index + 1, parameters.get(sourceIndex));
    }
  }

  private void bindParameter(PreparedStatement statement, int jdbcIndex, SqlTypedParameter parameter)
      throws SQLException {
    switch (parameter.type().toUpperCase(Locale.ROOT)) {
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

  private SqlQueryExecutionRequest legacyRequest(SqlDmlPreflightExecutionRequest request) {
    SqlQueryRequest routingQuery = new SqlQueryRequest(
        "1.0",
        request.query().connectionId(),
        request.query().targetEnvironment(),
        request.query().schema(),
        SqlQueryAction.RUN_READ_ONLY,
        "SELECT 1",
        List.of(),
        request.query().limits(),
        request.query().idempotencyKey());
    return new SqlQueryExecutionRequest(
        "1.0",
        request.executionRequestId(),
        request.workflowId(),
        routingQuery,
        request.validationHash(),
        request.operator(),
        request.policyDecision(),
        request.trace(),
        request.expiresAt());
  }

  private String canonical(String value) {
    return value.toUpperCase(Locale.ROOT);
  }

  private record PreviewPlan(
      String countSql,
      String sampleSql,
      List<Integer> predicateParameterIndexes,
      SqlDmlPreviewSelection selection) {
  }

  private record SampleResult(List<SqlResultColumn> columns, List<List<JsonNode>> rows) {
  }
}
