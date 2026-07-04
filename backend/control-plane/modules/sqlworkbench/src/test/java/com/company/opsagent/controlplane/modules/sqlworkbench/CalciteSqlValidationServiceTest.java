package com.company.opsagent.controlplane.modules.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryLimits;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationLevel;
import java.util.List;
import org.junit.jupiter.api.Test;

class CalciteSqlValidationServiceTest {

  private final CalciteSqlValidationService service = new CalciteSqlValidationService();

  @Test
  void acceptsSingleSelectForReadOnlyExecution() {
    var report = service.validate(request(SqlQueryAction.RUN_READ_ONLY,
        "select order_id, status from ORDERS.ORDERS where order_id = 42"));

    assertEquals(SqlStatementType.SELECT, report.statementType());
    assertEquals(SqlValidationLevel.VALIDATED, report.validationLevel());
    assertTrue(report.rejectionReasons().isEmpty());
    assertTrue(report.referencedObjects().contains("ORDERS.ORDERS"));
  }

  @Test
  void acceptsSingleSelectWithLineCommentsForReadOnlyExecution() {
    var report = service.validate(request(SqlQueryAction.RUN_READ_ONLY,
        "-- operator note\nselect order_id, status from ORDERS.ORDERS\n-- stable ordering\norder by order_id"));

    assertEquals(SqlStatementType.SELECT, report.statementType());
    assertEquals(SqlValidationLevel.VALIDATED, report.validationLevel());
    assertTrue(report.rejectionReasons().isEmpty());
    assertTrue(report.referencedObjects().contains("ORDERS.ORDERS"));
  }

  @Test
  void rejectsDmlForReadOnlyExecution() {
    var report = service.validate(request(SqlQueryAction.RUN_READ_ONLY,
        "update ORDERS.ORDERS set status = 'READY' where order_id = 42"));

    assertEquals(SqlStatementType.UPDATE, report.statementType());
    assertEquals(SqlValidationLevel.REJECTED, report.validationLevel());
    assertTrue(report.rejectionReasons().contains("DML execution is prohibited for read-only actions"));
  }

  @Test
  void warnsForDeleteWithoutWhereDuringPreflight() {
    var report = service.validate(request(SqlQueryAction.PREFLIGHT_DML,
        "delete from ORDERS.ORDERS"));

    assertEquals(SqlStatementType.DELETE, report.statementType());
    assertEquals(SqlValidationLevel.PARTIAL, report.validationLevel());
    assertTrue(report.risks().contains("DELETE_WITHOUT_WHERE"));
  }

  @Test
  void rejectsMultipleStatements() {
    var report = service.validate(request(SqlQueryAction.VALIDATE,
        "select * from ORDERS.ORDERS; delete from ORDERS.ORDERS"));

    assertEquals(SqlValidationLevel.REJECTED, report.validationLevel());
    assertTrue(report.rejectionReasons().contains("exactly one SQL statement is required"));
  }

  @Test
  void rejectsUnsupportedDdl() {
    var report = service.validate(request(SqlQueryAction.VALIDATE,
        "drop table ORDERS.ORDERS"));

    assertEquals(SqlStatementType.UNSUPPORTED, report.statementType());
    assertEquals(SqlValidationLevel.REJECTED, report.validationLevel());
  }

  private SqlQueryRequest request(SqlQueryAction action, String sql) {
    return new SqlQueryRequest(
        "1.0",
        "as400-development",
        "development",
        "ORDERS",
        action,
        sql,
        List.of(),
        new SqlQueryLimits(500, 5_000_000, 30),
        "test-key");
  }
}
