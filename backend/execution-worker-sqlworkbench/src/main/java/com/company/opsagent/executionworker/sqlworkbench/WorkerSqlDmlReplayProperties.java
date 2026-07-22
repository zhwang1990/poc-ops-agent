package com.company.opsagent.executionworker.sqlworkbench;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Worker 受控 DML 防重放持久化配置。 */
@ConfigurationProperties(prefix = "ops-agent.worker.sql-dml-replay")
public class WorkerSqlDmlReplayProperties {

  private String directory;

  public String getDirectory() {
    return directory;
  }

  public void setDirectory(String directory) {
    this.directory = directory;
  }

  public boolean isConfigured() {
    return directory != null && !directory.isBlank();
  }

  public Path directoryPath() {
    if (!isConfigured()) {
      throw new IllegalStateException("SQL DML replay directory is not configured");
    }
    return Path.of(directory.trim());
  }
}
