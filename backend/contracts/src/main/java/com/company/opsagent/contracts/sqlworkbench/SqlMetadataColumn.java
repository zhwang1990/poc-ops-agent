package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.requiredText;

/**
 * SQL 对象字段元数据，不包含任何行级数据。
 */
public record SqlMetadataColumn(
    String name,
    String type,
    boolean nullable,
    int ordinalPosition,
    boolean masked) {

  public SqlMetadataColumn {
    name = requiredText(name, "name");
    type = requiredText(type, "type");
    if (ordinalPosition < 1) {
      throw new IllegalArgumentException("ordinalPosition must be positive");
    }
  }
}
