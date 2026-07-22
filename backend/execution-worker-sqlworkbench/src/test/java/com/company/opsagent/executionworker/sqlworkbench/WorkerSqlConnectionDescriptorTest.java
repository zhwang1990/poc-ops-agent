package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 验证 Worker SQL 出口连接目录项的本地安全约束。
 */
class WorkerSqlConnectionDescriptorTest {

  /**
   * 验证开发环境只读连接目录项可以被正常构造。
   */
  @Test
  void acceptsDevelopmentConnectionDescriptor() {
    var descriptor = new WorkerSqlConnectionDescriptor(
        "as400-dev-readonly",
        "development",
        "as400-dev.internal",
        446,
        "as400-dev-readonly",
        true);

    assertEquals("as400-dev-readonly", descriptor.connectionId());
    assertEquals("dev", descriptor.targetEnvironment());
    assertEquals("as400-dev.internal", descriptor.host());
    assertEquals(446, descriptor.port());
  }

  /**
   * 验证 Worker 本地连接目录允许生产连接元数据，执行层仍只允许只读查询。
   */
  @Test
  void acceptsProductionConnectionDescriptorForReadOnlyQueries() {
    var descriptor = new WorkerSqlConnectionDescriptor(
        "as400-prod-readonly",
        "production",
        "as400-prod.internal",
        446,
        "as400-prod-readonly",
        true);

    assertEquals("production", descriptor.targetEnvironment());
  }

  /**
   * 验证 host/port allowlist 目标和连接目录都拒绝非法端口。
   */
  @Test
  void rejectsInvalidPort() {
    assertThrows(IllegalArgumentException.class, () -> new WorkerSqlEgressTarget("as400-dev.internal", 0));
    assertThrows(IllegalArgumentException.class, () -> new WorkerSqlConnectionDescriptor(
        "as400-dev-readonly",
        "development",
        "as400-dev.internal",
        70000,
        "as400-dev-readonly",
        true));
  }

  @Test
  void rejectsDmlCredentialAliasMatchingReadCredentialAlias() {
    assertThrows(IllegalArgumentException.class, () -> new WorkerSqlConnectionDescriptor(
        "as400-dev",
        "dev",
        "DB2_FOR_I",
        "as400-dev.internal",
        446,
        "as400-read",
        "readonly_user",
        true,
        true,
        " AS400-READ ",
        "writer_user"));
  }

  @Test
  void rejectsDmlUsernameMatchingReadUsername() {
    assertThrows(IllegalArgumentException.class, () -> new WorkerSqlConnectionDescriptor(
        "as400-dev",
        "dev",
        "DB2_FOR_I",
        "as400-dev.internal",
        446,
        "as400-read",
        "readonly_user",
        true,
        true,
        "as400-write",
        " READONLY_USER "));
  }
}
