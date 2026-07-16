package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlControlledDmlExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlImpactPreview;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionRequest;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryExecutionResult;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import com.company.opsagent.contracts.sqlworkbench.SqlTargetEnvironments;
import java.sql.SQLException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Locale;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * SQL 查询专用受限 Worker，只接受未过期的开发测试环境 SELECT。
 */
public class RestrictedSqlQueryExecutionWorker {

  private final SqlReadOnlyGuard readOnlyGuard;
  private final SqlDmlGuard dmlGuard;
  private final SqlQueryExecutor executor;
  private final SqlDmlImpactPreviewExecutor previewExecutor;
  private final WorkerSqlDmlExecutionPolicy dmlExecutionPolicy;
  private final SqlDmlWriteCapabilityValidator dmlWriteCapabilityValidator;
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
    this(
        readOnlyGuard,
        dmlGuard,
        executor,
        request -> Mono.error(new UnsupportedOperationException("SQL DML preview is not configured")),
        new WorkerSqlDmlExecutionPolicy(List.of()),
        SqlDmlWriteCapabilityValidator.rejecting(),
        clock);
  }

  public RestrictedSqlQueryExecutionWorker(
      SqlReadOnlyGuard readOnlyGuard,
      SqlDmlGuard dmlGuard,
      SqlQueryExecutor executor,
      SqlDmlImpactPreviewExecutor previewExecutor,
      WorkerSqlDmlExecutionPolicy dmlExecutionPolicy,
      Clock clock) {
    this(
        readOnlyGuard,
        dmlGuard,
        executor,
        previewExecutor,
        dmlExecutionPolicy,
        SqlDmlWriteCapabilityValidator.rejecting(),
        clock);
  }

  public RestrictedSqlQueryExecutionWorker(
      SqlReadOnlyGuard readOnlyGuard,
      SqlDmlGuard dmlGuard,
      SqlQueryExecutor executor,
      SqlDmlImpactPreviewExecutor previewExecutor,
      WorkerSqlDmlExecutionPolicy dmlExecutionPolicy,
      SqlDmlWriteCapabilityValidator dmlWriteCapabilityValidator,
      Clock clock) {
    this.readOnlyGuard = readOnlyGuard;
    this.dmlGuard = dmlGuard;
    this.executor = executor;
    this.previewExecutor = previewExecutor;
    this.dmlExecutionPolicy = dmlExecutionPolicy;
    this.dmlWriteCapabilityValidator = dmlWriteCapabilityValidator;
    this.clock = clock;
  }

  public SqlQueryExecutionResult execute(SqlQueryExecutionRequest request) {
    if (request.query().action() == SqlQueryAction.COMMIT_DML) {
      return rejected(
          request,
          "SQL_DML_LEGACY_ENVELOPE_REJECTED",
          "Controlled SQL DML requires the dedicated execution envelope");
    }
    if (!request.expiresAt().isAfter(OffsetDateTime.now(clock))) {
      return rejected(request, "REQUEST_EXPIRED", "execution request has expired");
    }
    try {
      if (request.query().action() == SqlQueryAction.RUN_READ_ONLY) {
        return executeReadOnly(request);
      }
      return rejected(request, "SQL_ACTION_NOT_EXECUTABLE", "Worker accepts only read-only query actions");
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

  public Mono<SqlDmlImpactPreview> preflightDml(SqlDmlPreflightExecutionRequest request) {
    return Mono.defer(() -> {
      assertNotExpired(request.expiresAt());
      dmlExecutionPolicy.assertEnabled(request);
      assertDmlEnvironment(request.query().targetEnvironment());
      assertControlledDml(request.query().sql());
      dmlWriteCapabilityValidator.assertPreflightAllowed(request);
      return previewExecutor.preview(request);
    });
  }

  public SqlQueryExecutionResult executeControlledDml(SqlControlledDmlExecutionRequest request) {
    if (!request.expiresAt().isAfter(OffsetDateTime.now(clock))) {
      return rejected(request, "REQUEST_EXPIRED", "execution request has expired");
    }
    try {
      dmlExecutionPolicy.assertEnabled(request);
      if (!SqlTargetEnvironments.allowsCrud(request.commitRequest().query().targetEnvironment())) {
        return rejected(
            request,
            "SQL_DML_ENVIRONMENT_NOT_ALLOWED",
            "SQL DML is allowed only in dev, sit, or uat");
      }
      if (!dmlGuard.isControlledDml(request.commitRequest().query().sql())
          || !dmlExecutionPolicy.isSupportedSubset(request.commitRequest().query().sql())) {
        return rejected(
            request,
            "SQL_NOT_CONTROLLED_DML",
            "Worker accepts exactly one controlled INSERT, UPDATE, or DELETE statement");
      }
      dmlWriteCapabilityValidator.assertCommitAllowed(request);
      int affectedRows = executor.executeDml(legacyRequest(request));
      return new SqlQueryExecutionResult(
          "1.0",
          request.executionRequestId(),
          request.workflowId(),
          "SUCCEEDED",
          null,
          null,
          null,
          affectedRows);
    } catch (WorkerSqlEgressException exception) {
      return rejected(request, exception.errorCode(), exception.safeMessage());
    } catch (RuntimeException exception) {
      return failed(request, exception);
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

  private SqlQueryExecutionResult rejected(
      SqlControlledDmlExecutionRequest request,
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

  private SqlQueryExecutionResult failed(
      SqlControlledDmlExecutionRequest request,
      RuntimeException exception) {
    return new SqlQueryExecutionResult(
        "1.0",
        request.executionRequestId(),
        request.workflowId(),
        "FAILED",
        null,
        "SQL_EXECUTION_FAILED",
        safeExecutionFailureMessage(exception));
  }

  private void assertNotExpired(OffsetDateTime expiresAt) {
    if (!expiresAt.isAfter(OffsetDateTime.now(clock))) {
      throw new WorkerSqlEgressException("REQUEST_EXPIRED", "execution request has expired");
    }
  }

  private void assertDmlEnvironment(String targetEnvironment) {
    if (!SqlTargetEnvironments.allowsCrud(targetEnvironment)) {
      throw new WorkerSqlEgressException(
          "SQL_DML_ENVIRONMENT_NOT_ALLOWED",
          "SQL DML is allowed only in dev, sit, or uat");
    }
  }

  private void assertControlledDml(String sql) {
    if (!dmlGuard.isControlledDml(sql) || !dmlExecutionPolicy.isSupportedSubset(sql)) {
      throw new WorkerSqlEgressException(
          "SQL_NOT_CONTROLLED_DML",
          "Worker accepts exactly one controlled INSERT, UPDATE, or DELETE statement");
    }
  }

  private SqlQueryExecutionRequest legacyRequest(SqlControlledDmlExecutionRequest request) {
    return new SqlQueryExecutionRequest(
        "1.0",
        request.executionRequestId(),
        request.workflowId(),
        request.commitRequest().query(),
        request.binding().bindingHash(),
        request.operator(),
        request.policyDecision(),
        request.trace(),
        request.expiresAt());
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
