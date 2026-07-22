package com.company.opsagent.executionworker.sqlworkbench;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSqlDmlExecutionReplayGuardTest {

  private static final Clock CLOCK = Clock.fixed(
      Instant.parse("2026-07-17T00:00:00Z"),
      ZoneOffset.UTC);

  @TempDir
  Path temporaryDirectory;

  @Test
  void persistsConsumedRequestAcrossGuardInstances() {
    var firstInstance = new FileSqlDmlExecutionReplayGuard(temporaryDirectory, CLOCK);

    assertTrue(firstInstance.consume("execution-1"));

    var restartedInstance = new FileSqlDmlExecutionReplayGuard(temporaryDirectory, CLOCK);
    assertFalse(restartedInstance.consume("execution-1"));
  }

  @Test
  void permitsOnlyOneConcurrentConsumerForRequestId() throws Exception {
    var firstGuard = new FileSqlDmlExecutionReplayGuard(temporaryDirectory, CLOCK);
    var secondGuard = new FileSqlDmlExecutionReplayGuard(temporaryDirectory, CLOCK);
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first = executor.submit(() -> {
        start.await();
        return firstGuard.consume("execution-1");
      });
      var second = executor.submit(() -> {
        start.await();
        return secondGuard.consume("execution-1");
      });

      start.countDown();

      assertEquals(List.of(false, true), List.of(first.get(), second.get()).stream().sorted().toList());
    }
  }

  @Test
  void failsClosedWhenReplayDirectoryCannotBeEstablished() throws Exception {
    Path regularFile = Files.createFile(temporaryDirectory.resolve("not-a-directory"));

    assertThrows(
        SqlDmlReplayStateException.class,
        () -> new FileSqlDmlExecutionReplayGuard(regularFile, CLOCK));
  }
}
