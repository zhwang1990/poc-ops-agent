package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.requiredList;
import static com.company.opsagent.contracts.ContractValues.requiredText;

import java.util.List;

/**
 * SQL 对象索引元数据，仅暴露索引名、唯一性和字段列表。
 */
public record SqlMetadataIndex(
    String name,
    boolean unique,
    List<String> columns) {

  public SqlMetadataIndex {
    name = requiredText(name, "name");
    columns = requiredList(columns, "columns");
  }
}
