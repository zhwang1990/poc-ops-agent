package com.company.opsagent.controlplane.modules.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreviewSelection;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ControlledSqlDmlPolicyTest {

  private final CalciteSqlValidationService validation = new CalciteSqlValidationService();

  @Test
  void resolvesConfiguredPreviewSelectionForExactlyMatchingRule() {
    var policy = policyFor(
        "ORDERS",
        Set.of("STATUS"),
        Set.of("ORDER_ID"),
        Set.of("EQUALS"),
        List.of("ORDER_ID", "STATUS"),
        List.of("STATUS"));
    SqlQueryRequest request = updateRequest("update ORDERS set STATUS = 'READY' where ORDER_ID = 42");

    SqlDmlPreviewSelection selection = policy.authorize(request, validation.validate(request));

    assertEquals(new SqlDmlPreviewSelection(
        "1.0",
        List.of("ORDER_ID", "STATUS"),
        List.of("STATUS")), selection);
  }

  @Test
  void rejectsPredicateColumnOutsideAllowlist() {
    var policy = policyFor(
        "ORDERS",
        Set.of("STATUS"),
        Set.of("ORDER_ID"),
        Set.of("EQUALS"),
        List.of(),
        List.of());
    SqlQueryRequest request = updateRequest("update ORDERS set STATUS = 'READY' where OWNER = 'ops'");

    SqlWorkbenchException exception = assertThrows(
        SqlWorkbenchException.class,
        () -> policy.authorize(request, validation.validate(request)));

    assertEquals("SQL_DML_POLICY_DENIED", exception.code());
  }

  @Test
  void defaultsToDenyWhenNoEnvironmentIsEnabled() {
    var policy = new ControlledSqlDmlPolicy(
        new ControlledSqlDmlProperties(),
        new CalciteSqlDmlAnalysis());
    SqlQueryRequest request = updateRequest("update ORDERS set STATUS = 'READY' where ORDER_ID = 42");

    SqlWorkbenchException exception = assertThrows(
        SqlWorkbenchException.class,
        () -> policy.authorize(request, validation.validate(request)));

    assertEquals("SQL_DML_DISABLED", exception.code());
  }

  @Test
  void rejectsProductionEvenWhenItIsMisconfiguredAsEnabled() {
    var properties = new ControlledSqlDmlProperties();
    properties.setEnabledEnvironments(Set.of("dev", "production"));

    assertFalse(properties.isEnabledFor("production"));
  }

  @Test
  void resolvesEmptyPreviewSelectionWhenRuleDoesNotRequestSampleColumns() {
    var policy = policyFor(
        "ORDERS",
        Set.of("STATUS"),
        Set.of("ORDER_ID"),
        Set.of("EQUALS"),
        List.of(),
        List.of());
    SqlQueryRequest request = updateRequest("update ORDERS set STATUS = 'READY' where ORDER_ID = 42");

    SqlDmlPreviewSelection selection = policy.authorize(request, validation.validate(request));

    assertEquals(new SqlDmlPreviewSelection("1.0", List.of(), List.of()), selection);
  }

  @Test
  void rejectsRuleWithMaskedColumnOutsidePreviewColumns() {
    assertThrows(IllegalArgumentException.class, () -> policyFor(
        "ORDERS",
        Set.of("STATUS"),
        Set.of("ORDER_ID"),
        Set.of("EQUALS"),
        List.of("ORDER_ID"),
        List.of("STATUS")));
  }

  @Test
  void rejectsRuleWithMissingPreviewConfiguration() {
    var rule = updateRule();

    assertThrows(IllegalArgumentException.class, () -> policyFor(rule));
  }

  @Test
  void rejectsRuleWithNullPreviewConfiguration() {
    var rule = updateRule();
    rule.setPreviewSampleColumns(null);
    rule.setMaskedPreviewColumns(List.of());

    assertThrows(IllegalArgumentException.class, () -> policyFor(rule));
  }

  private ControlledSqlDmlPolicy policyFor(
      String table,
      Set<String> changedColumns,
      Set<String> predicateColumns,
      Set<String> operators,
      List<String> previewSampleColumns,
      List<String> maskedPreviewColumns) {
    var properties = new ControlledSqlDmlProperties();
    properties.setEnabledEnvironments(Set.of("dev"));
    var rule = updateRule();
    rule.setTable(table);
    rule.setChangedColumns(changedColumns);
    rule.setPredicateColumns(predicateColumns);
    rule.setOperators(operators);
    rule.setPreviewSampleColumns(previewSampleColumns);
    rule.setMaskedPreviewColumns(maskedPreviewColumns);
    properties.setRules(List.of(rule));
    return new ControlledSqlDmlPolicy(properties, new CalciteSqlDmlAnalysis());
  }

  private ControlledSqlDmlPolicy policyFor(ControlledSqlDmlProperties.Rule rule) {
    var properties = new ControlledSqlDmlProperties();
    properties.setEnabledEnvironments(Set.of("dev"));
    properties.setRules(List.of(rule));
    return new ControlledSqlDmlPolicy(properties, new CalciteSqlDmlAnalysis());
  }

  private ControlledSqlDmlProperties.Rule updateRule() {
    var rule = new ControlledSqlDmlProperties.Rule();
    rule.setConnectionId("as400-development");
    rule.setSchema("ORDERS");
    rule.setTable("ORDERS");
    rule.setStatementType(SqlStatementType.UPDATE);
    rule.setChangedColumns(Set.of("STATUS"));
    rule.setPredicateColumns(Set.of("ORDER_ID"));
    rule.setOperators(Set.of("EQUALS"));
    return rule;
  }

  private SqlQueryRequest updateRequest(String sql) {
    return new SqlQueryRequest(
        "1.0",
        "as400-development",
        "dev",
        "ORDERS",
        SqlQueryAction.PREFLIGHT_DML,
        sql,
        List.of(),
        new SqlQueryLimits(500, 5_000_000, 30),
        "test-key");
  }
}
