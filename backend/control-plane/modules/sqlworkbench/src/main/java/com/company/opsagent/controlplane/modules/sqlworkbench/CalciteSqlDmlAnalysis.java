package com.company.opsagent.controlplane.modules.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
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
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

/** 使用 Calcite AST 提取受控 DML 白名单匹配所需的静态特征。 */
public final class CalciteSqlDmlAnalysis {

  public DmlStatement inspect(String sql) {
    try {
      SqlNodeList statements = SqlParser.create(sql).parseStmtList();
      if (statements.size() != 1) {
        throw rejected("exactly one SQL statement is required");
      }
      return inspect(statements.getFirst());
    } catch (SqlParseException exception) {
      throw rejected("SQL syntax is not supported");
    }
  }

  DmlStatement inspect(SqlNode statement) {
    if (statement instanceof SqlWith) {
      throw rejected("controlled DML does not allow WITH clauses");
    }
    if (statement instanceof SqlInsert insert) {
      return inspectInsert(insert);
    }
    if (statement instanceof SqlUpdate update) {
      return inspectUpdate(update);
    }
    if (statement instanceof SqlDelete delete) {
      return inspectDelete(delete);
    }
    throw rejected("controlled DML requires INSERT, UPDATE, or DELETE");
  }

  private DmlStatement inspectInsert(SqlInsert insert) {
    Target target = target(insert.getTargetTable());
    Set<String> changedColumns = columns(insert.getTargetColumnList(), "changed columns");
    if (changedColumns.isEmpty()) {
      throw rejected("controlled INSERT requires an explicit target column list");
    }
    SqlNode source = insert.getSource();
    if (!(source instanceof SqlCall call) || call.getKind() != SqlKind.VALUES) {
      throw rejected("controlled INSERT requires a VALUES source");
    }
    verifyStaticValues(source);
    return new DmlStatement(SqlStatementType.INSERT, target.schema(), target.table(), changedColumns, Set.of(), Set.of());
  }

  private DmlStatement inspectUpdate(SqlUpdate update) {
    if (update.getSourceSelect() != null) {
      throw rejected("controlled DML does not allow source subqueries");
    }
    Target target = target(update.getTargetTable());
    Set<String> changedColumns = columns(update.getTargetColumnList(), "changed columns");
    if (changedColumns.isEmpty()) {
      throw rejected("controlled UPDATE requires changed columns");
    }
    for (SqlNode sourceExpression : update.getSourceExpressionList()) {
      verifyStaticValue(sourceExpression);
    }
    Predicate predicate = predicate(update.getCondition());
    return new DmlStatement(
        SqlStatementType.UPDATE,
        target.schema(),
        target.table(),
        changedColumns,
        predicate.columns(),
        predicate.operators());
  }

  private DmlStatement inspectDelete(SqlDelete delete) {
    if (delete.getSourceSelect() != null) {
      throw rejected("controlled DML does not allow source subqueries");
    }
    Target target = target(delete.getTargetTable());
    Predicate predicate = predicate(delete.getCondition());
    return new DmlStatement(
        SqlStatementType.DELETE,
        target.schema(),
        target.table(),
        Set.of(),
        predicate.columns(),
        predicate.operators());
  }

  private Target target(SqlNode targetNode) {
    if (!(targetNode instanceof SqlIdentifier identifier)) {
      throw rejected("controlled DML target must be a table identifier");
    }
    List<String> names = identifier.names;
    rejectQuotedIdentifiers(identifier);
    if (names.size() == 1) {
      return new Target(null, canonical(names.getFirst()));
    }
    if (names.size() == 2) {
      return new Target(canonical(names.getFirst()), canonical(names.get(1)));
    }
    throw rejected("controlled DML target must include at most schema and table");
  }

  private Set<String> columns(SqlNodeList columns, String fieldName) {
    if (columns == null || columns.isEmpty()) {
      return Set.of();
    }
    LinkedHashSet<String> result = new LinkedHashSet<>();
    for (SqlNode column : columns) {
      result.add(column(column, fieldName));
    }
    return Set.copyOf(result);
  }

  private Predicate predicate(SqlNode condition) {
    if (condition == null) {
      return new Predicate(Set.of(), Set.of());
    }
    LinkedHashSet<String> columns = new LinkedHashSet<>();
    LinkedHashSet<String> operators = new LinkedHashSet<>();
    collectPredicate(condition, columns, operators);
    return new Predicate(Set.copyOf(columns), Set.copyOf(operators));
  }

  private void collectPredicate(SqlNode node, Set<String> columns, Set<String> operators) {
    if (!(node instanceof SqlCall call)) {
      throw rejected("controlled DML predicate must use supported operators");
    }
    SqlKind kind = call.getKind();
    if (kind == SqlKind.AND || kind == SqlKind.OR) {
      operators.add(kind.name());
      for (SqlNode operand : call.getOperandList()) {
        collectPredicate(operand, columns, operators);
      }
      return;
    }
    if (kind == SqlKind.IS_NULL || kind == SqlKind.IS_NOT_NULL) {
      operators.add(kind.name());
      if (call.operandCount() != 1) {
        throw rejected("controlled DML predicate has an invalid operator arity");
      }
      columns.add(column(call.operand(0), "predicate column"));
      return;
    }
    if (kind == SqlKind.EQUALS
        || kind == SqlKind.NOT_EQUALS
        || kind == SqlKind.LESS_THAN
        || kind == SqlKind.LESS_THAN_OR_EQUAL
        || kind == SqlKind.GREATER_THAN
        || kind == SqlKind.GREATER_THAN_OR_EQUAL) {
      operators.add(kind.name());
      if (call.operandCount() != 2) {
        throw rejected("controlled DML predicate has an invalid operator arity");
      }
      int columnsBefore = columns.size();
      collectComparisonOperand(call.operand(0), columns);
      collectComparisonOperand(call.operand(1), columns);
      if (columns.size() == columnsBefore) {
        throw rejected("controlled DML predicate must reference a column");
      }
      return;
    }
    throw rejected("controlled DML predicate contains an unsupported operator");
  }

  private void collectComparisonOperand(SqlNode operand, Set<String> columns) {
    if (operand instanceof SqlIdentifier) {
      columns.add(column(operand, "predicate column"));
      return;
    }
    if (operand instanceof SqlLiteral || operand instanceof SqlDynamicParam) {
      return;
    }
    throw rejected("controlled DML predicate contains an unsupported column expression");
  }

  private void verifyStaticValues(SqlNode node) {
    if (node instanceof SqlNodeList nodes) {
      for (SqlNode nested : nodes) {
        verifyStaticValues(nested);
      }
      return;
    }
    if (node instanceof SqlCall call
        && (call.getKind() == SqlKind.VALUES || call.getKind() == SqlKind.ROW)) {
      for (SqlNode operand : call.getOperandList()) {
        verifyStaticValues(operand);
      }
      return;
    }
    verifyStaticValue(node);
  }

  private void verifyStaticValue(SqlNode node) {
    if (!(node instanceof SqlLiteral) && !(node instanceof SqlDynamicParam)) {
      throw rejected("controlled DML values must be literals or parameters");
    }
  }

  private String column(SqlNode node, String fieldName) {
    if (!(node instanceof SqlIdentifier identifier) || identifier.names.size() != 1) {
      throw rejected("controlled DML " + fieldName + " must be a simple identifier");
    }
    rejectQuotedIdentifiers(identifier);
    return canonical(identifier.names.getFirst());
  }

  private void rejectQuotedIdentifiers(SqlIdentifier identifier) {
    for (int index = 0; index < identifier.names.size(); index++) {
      if (identifier.isComponentQuoted(index)) {
        throw rejected("controlled DML does not allow quoted identifiers");
      }
    }
  }

  private static String canonical(String value) {
    if (value == null || value.isBlank()) {
      throw rejected("controlled DML identifier must not be blank");
    }
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private static SqlWorkbenchException rejected(String message) {
    return new SqlWorkbenchException("SQL_DML_STATIC_ANALYSIS_REJECTED", message);
  }

  public record DmlStatement(
      SqlStatementType statementType,
      String targetSchema,
      String targetTable,
      Set<String> changedColumns,
      Set<String> predicateColumns,
      Set<String> operators) {

    public DmlStatement {
      changedColumns = Set.copyOf(changedColumns);
      predicateColumns = Set.copyOf(predicateColumns);
      operators = Set.copyOf(operators);
    }

    void verifyAllowedBy(ControlledSqlDmlProperties.Rule rule) {
      if (!changedColumns.equals(rule.canonicalChangedColumns())
          || !predicateColumns.equals(rule.canonicalPredicateColumns())
          || !operators.equals(rule.canonicalOperators())) {
        throw new SqlWorkbenchException("SQL_DML_POLICY_DENIED", "No matching DML policy rule");
      }
    }
  }

  private record Target(String schema, String table) {
  }

  private record Predicate(Set<String> columns, Set<String> operators) {
  }
}
