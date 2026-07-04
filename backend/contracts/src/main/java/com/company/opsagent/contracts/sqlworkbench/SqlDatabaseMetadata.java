package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.requiredText;
import static com.company.opsagent.contracts.ContractValues.requiredTime;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * SQL 工作台对象浏览器使用的授权数据库元数据响应。
 */
public record SqlDatabaseMetadata(
    String contractVersion,
    String connectionId,
    String schema,
    List<SqlMetadataObject> objects,
    boolean truncated,
    OffsetDateTime refreshedAt) {

  public SqlDatabaseMetadata {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    connectionId = requiredText(connectionId, "connectionId");
    schema = requiredText(schema, "schema");
    objects = List.copyOf(objects);
    refreshedAt = requiredTime(refreshedAt, "refreshedAt");
  }
}
