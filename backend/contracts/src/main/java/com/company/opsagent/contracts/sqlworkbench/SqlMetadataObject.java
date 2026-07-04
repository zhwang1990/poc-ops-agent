package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.requiredText;

import java.util.List;
import java.util.Set;

/**
 * SQL Schema 下的表或视图元数据。
 */
public record SqlMetadataObject(
    String schema,
    String name,
    String type,
    List<SqlMetadataColumn> columns,
    List<SqlMetadataIndex> indexes) {

  private static final Set<String> SUPPORTED_TYPES = Set.of("TABLE", "VIEW", "SYSTEM_TABLE");

  public SqlMetadataObject {
    schema = requiredText(schema, "schema");
    name = requiredText(name, "name");
    type = normalizeType(type);
    columns = List.copyOf(columns);
    indexes = List.copyOf(indexes);
  }

  private static String normalizeType(String value) {
    String normalized = requiredText(value, "type").trim().toUpperCase().replace(' ', '_');
    if (!SUPPORTED_TYPES.contains(normalized)) {
      throw new IllegalArgumentException("type must be TABLE, VIEW, or SYSTEM_TABLE");
    }
    return normalized;
  }
}
