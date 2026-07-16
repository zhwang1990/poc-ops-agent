package com.company.opsagent.controlplane.modules.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreviewSelection;
import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
import com.company.opsagent.contracts.sqlworkbench.SqlTargetEnvironments;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 受控 DML 的服务端白名单配置。 */
public final class ControlledSqlDmlProperties {

  private Set<String> enabledEnvironments = Set.of();
  private List<Rule> rules = List.of();

  public Set<String> getEnabledEnvironments() {
    return enabledEnvironments;
  }

  public void setEnabledEnvironments(Set<String> enabledEnvironments) {
    this.enabledEnvironments = enabledEnvironments == null ? Set.of() : Set.copyOf(enabledEnvironments);
  }

  public List<Rule> getRules() {
    return rules;
  }

  public void setRules(List<Rule> rules) {
    this.rules = rules == null ? List.of() : List.copyOf(rules);
  }

  boolean isEnabledFor(String targetEnvironment) {
    if (!SqlTargetEnvironments.allowsCrud(targetEnvironment)) {
      return false;
    }
    String normalizedTarget = SqlTargetEnvironments.normalize(targetEnvironment);
    return enabledEnvironments.stream()
        .map(SqlTargetEnvironments::normalize)
        .anyMatch(normalizedTarget::equals);
  }

  void validate() {
    enabledEnvironments.forEach(SqlTargetEnvironments::normalize);
    rules.forEach(Rule::validate);
  }

  public static final class Rule {

    private String connectionId;
    private String schema;
    private String table;
    private SqlStatementType statementType;
    private Set<String> changedColumns = Set.of();
    private Set<String> predicateColumns = Set.of();
    private Set<String> operators = Set.of();
    private List<String> previewSampleColumns = List.of();
    private List<String> maskedPreviewColumns = List.of();

    public String getConnectionId() {
      return connectionId;
    }

    public void setConnectionId(String connectionId) {
      this.connectionId = connectionId;
    }

    public String getSchema() {
      return schema;
    }

    public void setSchema(String schema) {
      this.schema = schema;
    }

    public String getTable() {
      return table;
    }

    public void setTable(String table) {
      this.table = table;
    }

    public SqlStatementType getStatementType() {
      return statementType;
    }

    public void setStatementType(SqlStatementType statementType) {
      this.statementType = statementType;
    }

    public Set<String> getChangedColumns() {
      return changedColumns;
    }

    public void setChangedColumns(Set<String> changedColumns) {
      this.changedColumns = copyValues(changedColumns);
    }

    public Set<String> getPredicateColumns() {
      return predicateColumns;
    }

    public void setPredicateColumns(Set<String> predicateColumns) {
      this.predicateColumns = copyValues(predicateColumns);
    }

    public Set<String> getOperators() {
      return operators;
    }

    public void setOperators(Set<String> operators) {
      this.operators = copyValues(operators);
    }

    public List<String> getPreviewSampleColumns() {
      return previewSampleColumns;
    }

    public void setPreviewSampleColumns(List<String> previewSampleColumns) {
      this.previewSampleColumns = previewSampleColumns == null ? List.of() : List.copyOf(previewSampleColumns);
    }

    public List<String> getMaskedPreviewColumns() {
      return maskedPreviewColumns;
    }

    public void setMaskedPreviewColumns(List<String> maskedPreviewColumns) {
      this.maskedPreviewColumns = maskedPreviewColumns == null ? List.of() : List.copyOf(maskedPreviewColumns);
    }

    boolean matchesTarget(
        String requestConnectionId,
        String requestSchema,
        CalciteSqlDmlAnalysis.DmlStatement statement) {
      return connectionId.equals(requestConnectionId)
          && canonical(schema).equals(canonical(requestSchema))
          && canonical(table).equals(statement.targetTable())
          && statementType == statement.statementType()
          && (statement.targetSchema() == null || canonical(schema).equals(statement.targetSchema()));
    }

    Set<String> canonicalChangedColumns() {
      return canonicalSet(changedColumns);
    }

    Set<String> canonicalPredicateColumns() {
      return canonicalSet(predicateColumns);
    }

    Set<String> canonicalOperators() {
      return canonicalSet(operators);
    }

    SqlDmlPreviewSelection previewSelection() {
      return new SqlDmlPreviewSelection(
          "1.0",
          previewSampleColumns,
          maskedPreviewColumns);
    }

    private void validate() {
      requireText(connectionId, "connectionId");
      requireText(schema, "schema");
      requireText(table, "table");
      if (statementType != SqlStatementType.INSERT
          && statementType != SqlStatementType.UPDATE
          && statementType != SqlStatementType.DELETE) {
        throw new IllegalArgumentException("statementType must be INSERT, UPDATE, or DELETE");
      }
      canonicalSet(changedColumns);
      canonicalSet(predicateColumns);
      canonicalSet(operators);
      previewSelection();
    }
  }

  private static Set<String> copyValues(Collection<String> values) {
    return values == null ? Set.of() : Set.copyOf(values);
  }

  private static Set<String> canonicalSet(Collection<String> values) {
    LinkedHashSet<String> canonicalValues = new LinkedHashSet<>();
    for (String value : values) {
      canonicalValues.add(canonical(value));
    }
    return Set.copyOf(canonicalValues);
  }

  private static String canonical(String value) {
    requireText(value, "rule value");
    return value.trim().toUpperCase(Locale.ROOT);
  }

  private static void requireText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
  }
}
