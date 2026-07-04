package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlTargetEnvironments;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;

/**
 * SQL 查询专用受限 Worker，只接受未过期的开发测试环境 SELECT。
 */
public class RestrictedSqlQueryExecutionWorker {

  private final SqlReadOnlyGuard readOnlyGuard;
  private final SqlDmlGuard dmlGuard;
  private final SqlQueryExecutor executor;
  private final Clock clock;

  public RestrictedSqlQueryExecutionWorker(
      SqlReadOnlyGuard readOnlyGuard,
      SqlQueryExecutor executor,
      Clock clock) {
    this(readOnlyGuard, new CalciteSqlDmlGuard(), executor, clock);
  }

  public RestrictedSqlQueryExecutionWorker(
      SqlReadOnlyGuard readOnlyGuard,
      SqlDmlGuard dmlGuard,
      SqlQueryExecutor executor,
      Clock clock) {
    this.readOnlyGuard = readOnlyGuard;
    this.dmlGuard = dmlGuard;
    this.executor = executor;
    this.clock = clock;
  }

  public SqlQueryExecutionResult execute(SqlQueryExecutionRequest request) {
    if (!request.expiresAt().isAfter(OffsetDateTime.now(clock))) {
      return rejected(request, "REQUEST_EXPIRED", "execution request has expired");
    }
    try {
      if (request.query().action() == SqlQueryAction.RUN_READ_ONLY) {
        return executeReadOnly(request);
      }
      if (request.query().action() == SqlQueryAction.COMMIT_DML) {
        return executeControlledDml(request);
      }
      return rejected(request, "SQL_ACTION_NOT_EXECUTABLE", "Worker accepts only query or controlled DML actions");
    } catch (WorkerSqlEgressException exception) {
      return rejected(request, exception.errorCode(), exception.safeMessage());
    } catch (RuntimeException exception) {
      return new SqlQueryExecutionResult(
          "1.0",
          request.executionRequestId(),
          request.workflowId(),
          "FAILED",
          null,
          "SQL_EXECUTION_FAILED",
          safeExecutionFailureMessage(exception));
    }
  }

  private SqlQueryExecutionResult executeReadOnly(SqlQueryExecutionRequest request) {
    if (!readOnlyGuard.isReadOnly(request.query().sql())) {
      return rejected(request, "SQL_NOT_READ_ONLY", "Worker accepts exactly one SELECT statement");
    }
    String resultId = executor.execute(request);
    return new SqlQueryExecutionResult(
        "1.0",
        request.executionRequestId(),
        request.workflowId(),
        "SUCCEEDED",
        resultId,
        null,
        null);
  }

  private SqlQueryExecutionResult executeControlledDml(SqlQueryExecutionRequest request) {
    if (!SqlTargetEnvironments.allowsCrud(request.query().targetEnvironment())) {
      return rejected(request, "SQL_DML_ENVIRONMENT_NOT_ALLOWED", "SQL DML is allowed only in dev, sit, or uat");
    }
    if (!dmlGuard.isControlledDml(request.query().sql())) {
      return rejected(request, "SQL_NOT_CONTROLLED_DML", "Worker accepts exactly one INSERT, UPDATE, or DELETE statement");
    }
    int affectedRows = executor.executeDml(request);
    return new SqlQueryExecutionResult(
        "1.0",
        request.executionRequestId(),
        request.workflowId(),
        "SUCCEEDED",
        null,
        null,
        null,
        affectedRows);
  }

  private SqlQueryExecutionResult rejected(
      SqlQueryExecutionRequest request,
      String code,
      String message) {
    return new SqlQueryExecutionResult(
        "1.0",
        request.executionRequestId(),
        request.workflowId(),
        "REJECTED",
        null,
        code,
        message);
  }

  private String safeExecutionFailureMessage(RuntimeException exception) {
    Throwable rootCause = rootCause(exception);
    StringBuilder message = new StringBuilder("SQL query execution failed");
    message.append(System.lineSeparator()).append("failureType=").append(rootCause.getClass().getSimpleName());
    if (rootCause instanceof SQLException sqlException) {
      if (hasText(sqlException.getSQLState())) {
        message.append(System.lineSeparator()).append("sqlState=").append(sqlException.getSQLState());
      }
      if (sqlException.getErrorCode() != 0) {
        message.append(System.lineSeparator()).append("vendorCode=").append(sqlException.getErrorCode());
      }
    }
    String safeMessage = sanitizeFailureMessage(rootCause.getMessage());
    if (safeMessage != null) {
      message.append(System.lineSeparator()).append("message=").append(safeMessage);
    }
    return message.toString();
  }

  private Throwable rootCause(Throwable exception) {
    Throwable current = exception;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    return current;
  }

  private String sanitizeFailureMessage(String rawMessage) {
    if (!hasText(rawMessage)) {
      return null;
    }
    String message = rawMessage.replaceAll("\\R+", " ").trim();
    int sqlStatementIndex = indexOfIgnoreCase(message, "SQL statement:");
    if (sqlStatementIndex >= 0) {
      message = message.substring(0, sqlStatementIndex).trim();
    }
    message = message.replaceAll("(?i)(password|pwd|secret|token)\\s*=\\s*[^\\s;]+", "$1=<redacted>");
    if (message.endsWith(";")) {
      message = message.substring(0, message.length() - 1).trim();
    }
    if (message.length() > 240) {
      message = message.substring(0, 240).trim();
    }
    return hasText(message) ? message : null;
  }

  private int indexOfIgnoreCase(String value, String marker) {
    return value.toLowerCase(Locale.ROOT).indexOf(marker.toLowerCase(Locale.ROOT));
  }

  private boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
