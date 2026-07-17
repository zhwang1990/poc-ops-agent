package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionProbeResult;
import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlQueryAction;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 验证 Worker 侧连接探测只使用本地连接目录、出口策略和凭据别名。
 */
class SqlConnectionProbeWorkerTest {

  private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-06-27T10:15:30Z"), ZoneOffset.UTC);

  @Test
  void reportsReadyWhenConnectionMetadataEgressAndCredentialAliasAreValid() {
    var worker = new SqlConnectionProbeWorker(policy(true), alias -> SqlTestSecretMaterial.password(), CLOCK);

    SqlConnectionProbeResult result = worker.probe(connection());

    assertEquals("READY", result.status());
    assertEquals("as400-development", result.connectionId());
  }

  @Test
  void reportsCredentialAliasNotFoundWhenPasswordProviderCannotFindAlias() {
    var worker = new SqlConnectionProbeWorker(policy(true), alias -> {
      throw new IllegalArgumentException("alias is not available");
    }, CLOCK);

    SqlConnectionProbeResult result = worker.probe(connection());

    assertEquals("CREDENTIAL_ALIAS_NOT_FOUND", result.status());
  }

  @Test
  void reportsCredentialLockedWhenPasswordProviderCannotUnlockCredentialStore() {
    var worker = new SqlConnectionProbeWorker(policy(true), alias -> {
      throw new IllegalStateException("credential store is locked");
    }, CLOCK);

    SqlConnectionProbeResult result = worker.probe(connection());

    assertEquals("CREDENTIAL_LOCKED", result.status());
  }

  @Test
  void reportsEgressNotAllowedWhenWorkerPolicyRejectsTarget() {
    var worker = new SqlConnectionProbeWorker(policy(false), alias -> SqlTestSecretMaterial.password(), CLOCK);

    SqlConnectionProbeResult result = worker.probe(connection());

    assertEquals("EGRESS_NOT_ALLOWED", result.status());
  }

  private WorkerSqlEgressPolicy policy(boolean includeAllowedTarget) {
    return new WorkerSqlEgressPolicy(
        List.of(new WorkerSqlConnectionDescriptor(
            "as400-development",
            "development",
            "as400-dev.internal",
            446,
            "as400-dev-readonly",
            true)),
        includeAllowedTarget ? List.of(new WorkerSqlEgressTarget("as400-dev.internal", 446)) : List.of());
  }

  private SqlConnectionSummary connection() {
    return new SqlConnectionSummary(
        "1.0",
        "as400-development",
        "AS/400 Development",
        "development",
        "DB2_FOR_I",
        "as400-dev.internal",
        446,
        "ORDERS",
        List.of("ORDERS"),
        List.of(SqlQueryAction.VALIDATE, SqlQueryAction.RUN_READ_ONLY, SqlQueryAction.PREFLIGHT_DML),
        "as400-dev-readonly",
        "READY",
        500,
        30);
  }
}
