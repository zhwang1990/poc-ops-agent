package com.company.opsagent.executionworker.sqlworkbench;

import org.apache.calcite.sql.SqlDelete;
import org.apache.calcite.sql.SqlInsert;
import org.apache.calcite.sql.SqlNode;
import org.apache.calcite.sql.SqlNodeList;
import org.apache.calcite.sql.SqlUpdate;
import org.apache.calcite.sql.parser.SqlParseException;
import org.apache.calcite.sql.parser.SqlParser;

/**
 * Worker-side AST guard for the P2 controlled CRUD subset.
 */
public class CalciteSqlDmlGuard implements SqlDmlGuard {

  @Override
  public boolean isControlledDml(String sql) {
    try {
      SqlNodeList statements = SqlParser.create(sql).parseStmtList();
      if (statements.size() != 1) {
        return false;
      }
      SqlNode statement = statements.getFirst();
      return statement instanceof SqlInsert
          || statement instanceof SqlUpdate
          || statement instanceof SqlDelete;
    } catch (SqlParseException exception) {
      return false;
    }
  }
}
