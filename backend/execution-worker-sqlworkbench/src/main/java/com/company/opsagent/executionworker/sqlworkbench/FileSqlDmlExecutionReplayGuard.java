package com.company.opsagent.executionworker.sqlworkbench;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.Objects;
import java.util.UUID;

/** 使用原子文件创建持久化 executionRequestId 消费记录。 */
public final class FileSqlDmlExecutionReplayGuard implements SqlDmlExecutionReplayGuard {

  private final Path directory;
  private final Clock clock;

  public FileSqlDmlExecutionReplayGuard(Path directory, Clock clock) {
    this.directory = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
    this.clock = Objects.requireNonNull(clock, "clock");
    establishDirectory();
  }

  @Override
  public boolean consume(String executionRequestId) {
    if (executionRequestId == null || executionRequestId.isBlank()) {
      throw new SqlDmlReplayStateException("SQL DML executionRequestId is required");
    }
    Path marker = directory.resolve(markerName(executionRequestId));
    byte[] payload = (executionRequestId.trim()
        + System.lineSeparator()
        + OffsetDateTime.now(clock)
        + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
    try (FileChannel channel = FileChannel.open(
        marker,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE)) {
      ByteBuffer buffer = ByteBuffer.wrap(payload);
      while (buffer.hasRemaining()) {
        channel.write(buffer);
      }
      channel.force(true);
      return true;
    } catch (FileAlreadyExistsException exception) {
      return false;
    } catch (IOException exception) {
      throw new SqlDmlReplayStateException("SQL DML replay state could not be persisted", exception);
    }
  }

  private void establishDirectory() {
    try {
      Files.createDirectories(directory);
      if (!Files.isDirectory(directory)) {
        throw new SqlDmlReplayStateException("SQL DML replay path is not a directory");
      }
      Path probe = directory.resolve(".write-probe-" + UUID.randomUUID());
      try (FileChannel channel = FileChannel.open(
          probe,
          StandardOpenOption.CREATE_NEW,
          StandardOpenOption.WRITE)) {
        channel.force(true);
      } finally {
        Files.deleteIfExists(probe);
      }
    } catch (SqlDmlReplayStateException exception) {
      throw exception;
    } catch (IOException exception) {
      throw new SqlDmlReplayStateException("SQL DML replay directory could not be established", exception);
    }
  }

  private String markerName(String executionRequestId) {
    try {
      byte[] digest = MessageDigest.getInstance("SHA-256")
          .digest(executionRequestId.trim().getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest) + ".consumed";
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is not available", exception);
    }
  }
}
