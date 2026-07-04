package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlConnectionSummary;
import com.company.opsagent.contracts.sqlworkbench.SqlDatabaseMetadata;

@FunctionalInterface
public interface SqlMetadataReader {

  SqlDatabaseMetadata read(SqlConnectionSummary connection, String schema);
}
