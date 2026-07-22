package com.company.opsagent.controlplane.modules.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlStatementType;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationLevel;
import com.company.opsagent.contracts.sqlworkbench.SqlValidationReport;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlBasicCall;
import org.apache.calcite.sql.SqlIdentifier;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlJoin;
import org.apache.calcite.sql.SqlKind;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlOrderBy;
import org.apache.calcite.sql.SqlSelect;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.SqlWith;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

/**
 * 使用 Calcite AST 实施保守 SQL 分类，不使用正则表达式决定是否允许执行。
 */
public class CalciteSqlValidationService implements SqlValidationService {

  private final CalciteSqlDmlAnalysis dmlAnalysis;

  public CalciteSqlValidationService() {
    this(new CalciteSqlDmlAnalysis());
  }

  CalciteSqlValidationService(CalciteSqlDmlAnalysis dmlAnalysis) {
    this.dmlAnalysis = dmlAnalysis;
  }

  @Override
  public SqlValidationReport validate(SqlQueryRequest request) {
    String sqlHash = sha256(request.sql());
    SqlNode statement;
    try {
      SqlNodeList statements = SqlParser.create(request.sql()).parseStmtList();
      if (statements.size() != 1) {
        return rejected(SqlStatementType.UNSUPPORTED, sqlHash, "exactly one SQL statement is required");
      }
      statement = statements.getFirst();
    } catch (SqlParseException exception) {
      return rejected(SqlStatementType.UNSUPPORTED, sqlHash, "SQL syntax is not supported");
    }

    SqlStatementType statementType = classify(statement);
    List<String> risks = new ArrayList<>();
    List<String> rejectionReasons = new ArrayList<>();
    List<String> unverifiedItems = new ArrayList<>();

    if (statementType == SqlStatementType.UNSUPPORTED) {
      rejectionReasons.add("statement type is not supported in P1");
    } else if (statementType == SqlStatementType.SELECT) {
      if (request.action() == SqlQueryAction.PREFLIGHT_DML) {
        rejectionReasons.add("PREFLIGHT_DML requires INSERT, UPDATE, or DELETE");
      }
    } else {
      if (request.action() == SqlQueryAction.RUN_READ_ONLY || request.action() == SqlQueryAction.EXPLAIN) {
        rejectionReasons.add("DML execution is prohibited for read-only actions");
      }
      if (request.action() != SqlQueryAction.PREFLIGHT_DML
          && request.action() != SqlQueryAction.COMMIT_DML
          && request.action() != SqlQueryAction.VALIDATE) {
        rejectionReasons.add("DML is only accepted for validation, preflight, or controlled commit");
      }
      try {
        dmlAnalysis.inspect(statement);
      } catch (SqlWorkbenchException exception) {
        rejectionReasons.add(exception.getMessage());
      }
      inspectDml(statement, statementType, risks, unverifiedItems);
    }

    SqlValidationLevel level;
    if (!rejectionReasons.isEmpty()) {
      level = SqlValidationLevel.REJECTED;
    } else if (statementType != SqlStatementType.SELECT) {
      level = SqlValidationLevel.PARTIAL;
      unverifiedItems.add("target constraints, triggers, cascades, and concurrent changes");
    } else {
      level = SqlValidationLevel.VALIDATED;
    }
    List<String> referencedObjects = new ArrayList<>();
    collectReferencedObjects(statement, referencedObjects);
    return new SqlValidationReport(
        "1.0",
        statementType,
        level,
        sqlHash,
        referencedObjects.stream().distinct().toList(),
        risks,
        rejectionReasons,
        unverifiedItems.stream().distinct().toList());
  }

  private void collectReferencedObjects(SqlNode statement, List<String> referencedObjects) {
    SqlNode unwrapped = unwrap(statement);
    if (unwrapped instanceof SqlSelect select) {
      collectFrom(select.getFrom(), referencedObjects);
    } else if (unwrapped instanceof SqlInsert insert) {
      collectFrom(insert.getTargetTable(), referencedObjects);
    } else if (unwrapped instanceof SqlUpdate update) {
      collectFrom(update.getTargetTable(), referencedObjects);
    } else if (unwrapped instanceof SqlDelete delete) {
      collectFrom(delete.getTargetTable(), referencedObjects);
    }
  }

  private void collectFrom(SqlNode node, List<String> referencedObjects) {
      switch (node) {
          case null -> {
              return;
          }
          case SqlIdentifier identifier -> {
              referencedObjects.add(String.join(".", identifier.names));
              return;
          }
          case SqlJoin join -> {
              collectFrom(join.getLeft(), referencedObjects);
              collectFrom(join.getRight(), referencedObjects);
              return;
          }
          default -> {
          }
      }
      if (node instanceof SqlSelect || node instanceof SqlOrderBy || node instanceof SqlWith) {
      collectReferencedObjects(node, referencedObjects);
      return;
    }
    if (node instanceof SqlBasicCall call && call.getKind() == SqlKind.AS) {
      collectFrom(call.operand(0), referencedObjects);
    }
  }

  private SqlStatementType classify(SqlNode statement) {
    SqlNode unwrapped = unwrap(statement);
    if (unwrapped instanceof SqlSelect) {
      return SqlStatementType.SELECT;
    }
    if (unwrapped instanceof SqlInsert) {
      return SqlStatementType.INSERT;
    }
    if (unwrapped instanceof SqlUpdate) {
      return SqlStatementType.UPDATE;
    }
    if (unwrapped instanceof SqlDelete) {
      return SqlStatementType.DELETE;
    }
    return SqlStatementType.UNSUPPORTED;
  }

  private SqlNode unwrap(SqlNode statement) {
    if (statement instanceof SqlOrderBy orderBy) {
      return unwrap(orderBy.query);
    }
    if (statement instanceof SqlWith with) {
      return unwrap(with.body);
    }
    return statement;
  }

  private void inspectDml(
      SqlNode statement,
      SqlStatementType type,
      List<String> risks,
      List<String> unverifiedItems) {
    SqlNode unwrapped = unwrap(statement);
    if (unwrapped instanceof SqlUpdate update && update.getCondition() == null) {
      risks.add("UPDATE_WITHOUT_WHERE");
    }
    if (unwrapped instanceof SqlDelete delete && delete.getCondition() == null) {
      risks.add("DELETE_WITHOUT_WHERE");
    }
    if (type == SqlStatementType.INSERT) {
      unverifiedItems.add("database constraints and trigger effects");
    } else {
      unverifiedItems.add("impact count and masked sample require live read-only preflight");
    }
  }

  private SqlValidationReport rejected(SqlStatementType type, String sqlHash, String reason) {
    return new SqlValidationReport(
        "1.0",
        type,
        SqlValidationLevel.REJECTED,
        sqlHash,
        List.of(),
        List.of(),
        List.of(reason),
        List.of());
  }

  private String sha256(String value) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
      return "sha256:" + HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }
}
