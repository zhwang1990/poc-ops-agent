package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.calcite.sql.SqlCall;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlDynamicParam;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlLiteral;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

/**
 * Worker 本地 DML 功能、写凭据和受控语句子集门禁。
 */
public final class WorkerSqlDmlExecutionPolicy {

  private final Map<String, WorkerSqlConnectionDescriptor> descriptorsByConnectionId;

  public WorkerSqlDmlExecutionPolicy(List<WorkerSqlConnectionDescriptor> descriptors) {
    this.descriptorsByConnectionId = descriptors.stream().collect(Collectors.toMap(
        WorkerSqlConnectionDescriptor::connectionId,
        descriptor -> descriptor,
        (left, right) -> {
          throw new IllegalArgumentException("connectionId must be unique");
        },
        LinkedHashMap::new));
  }

  public void assertEnabled(SqlControlledDmlExecutionRequest request) {
    WorkerSqlConnectionDescriptor descriptor =
        descriptorsByConnectionId.get(request.commitRequest().query().connectionId());
    if (descriptor == null
        || !descriptor.enabled()
        || !descriptor.dmlEnabled()
        || isBlank(descriptor.dmlCredentialAlias())) {
      throw new WorkerSqlEgressException(
          "SQL_DML_WORKER_DISABLED",
          "SQL DML is not enabled with a write credential for this worker connection");
    }
  }

  public boolean isSupportedSubset(String sql) {
    try {
      SqlNodeList statements = SqlParser.create(sql).parseStmtList();
      return statements.size() == 1 && isSupportedStatement(statements.getFirst());
    } catch (SqlParseException exception) {
      return false;
    }
  }

  private boolean isSupportedStatement(SqlNode statement) {
    if (statement instanceof SqlInsert insert) {
      return isTarget(insert.getTargetTable())
          && isSimpleColumns(insert.getTargetColumnList())
          && isSingleValuesSource(insert.getSource());
    }
    if (statement instanceof SqlUpdate update) {
      return update.getSourceSelect() == null
          && update.getAlias() == null
          && isTarget(update.getTargetTable())
          && isSimpleColumns(update.getTargetColumnList())
          && update.getSourceExpressionList().stream().allMatch(this::isStaticValue)
          && isPredicate(update.getCondition());
    }
    if (statement instanceof SqlDelete delete) {
      return delete.getSourceSelect() == null
          && delete.getAlias() == null
          && isTarget(delete.getTargetTable())
          && isPredicate(delete.getCondition());
    }
    return false;
  }

  private boolean isSingleValuesSource(SqlNode source) {
    if (!(source instanceof SqlCall values)
        || values.getKind() != SqlKind.VALUES
        || values.operandCount() != 1) {
      return false;
    }
    SqlNode row = values.operand(0);
    if (!(row instanceof SqlCall rowCall) || rowCall.getKind() != SqlKind.ROW) {
      return false;
    }
    return rowCall.getOperandList().stream().allMatch(this::isStaticValue);
  }

  private boolean isPredicate(SqlNode node) {
    if (node == null) {
      return true;
    }
    if (!(node instanceof SqlCall call)) {
      return false;
    }
    SqlKind kind = call.getKind();
    if (kind == SqlKind.AND || kind == SqlKind.OR) {
      return call.getOperandList().stream().allMatch(this::isPredicate);
    }
    if (kind == SqlKind.IS_NULL || kind == SqlKind.IS_NOT_NULL) {
      return call.operandCount() == 1 && isSimpleColumn(call.operand(0));
    }
    if (kind == SqlKind.EQUALS
        || kind == SqlKind.NOT_EQUALS
        || kind == SqlKind.LESS_THAN
        || kind == SqlKind.LESS_THAN_OR_EQUAL
        || kind == SqlKind.GREATER_THAN
        || kind == SqlKind.GREATER_THAN_OR_EQUAL) {
      return call.operandCount() == 2
          && isComparisonOperand(call.operand(0))
          && isComparisonOperand(call.operand(1))
          && (isSimpleColumn(call.operand(0)) || isSimpleColumn(call.operand(1)));
    }
    return false;
  }

  private boolean isComparisonOperand(SqlNode node) {
    return isSimpleColumn(node) || isStaticValue(node);
  }

  private boolean isStaticValue(SqlNode node) {
    return node instanceof SqlLiteral || node instanceof SqlDynamicParam;
  }

  private boolean isTarget(SqlNode node) {
    if (!(node instanceof SqlIdentifier identifier)
        || identifier.names.isEmpty()
        || identifier.names.size() > 2) {
      return false;
    }
    for (int index = 0; index < identifier.names.size(); index++) {
      if (identifier.isComponentQuoted(index)) {
        return false;
      }
    }
    return true;
  }

  private boolean isSimpleColumns(SqlNodeList columns) {
    return columns != null && !columns.isEmpty() && columns.stream().allMatch(this::isSimpleColumn);
  }

  private boolean isSimpleColumn(SqlNode node) {
    return node instanceof SqlIdentifier identifier
        && identifier.names.size() == 1
        && !identifier.isComponentQuoted(0);
  }

  private boolean isBlank(String value) {
    return value == null || value.isBlank();
  }
}
