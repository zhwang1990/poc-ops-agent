package com.company.opsagent.contracts.sqlworkbench;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;

/**
 * Worker 返回的受控 DML 只读影响预览。
 *
 * <p>样本值只用于受控传输和界面展示，调用方不得将其写入日志、审计或持久化存储。
 */
public record SqlDmlImpactPreview(
    String contractVersion,
    Long affectedRows,
    List<SqlResultColumn> sampleColumns,
    List<List<JsonNode>> sampleRows,
    List<String> unverifiedItems) {

  public SqlDmlImpactPreview {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    if (affectedRows != null && affectedRows < 0) {
      throw new IllegalArgumentException("affectedRows must not be negative");
    }
    sampleColumns = sampleColumns == null ? List.of() : List.copyOf(sampleColumns);
    sampleRows = sampleRows == null ? List.of() : sampleRows.stream().map(List::copyOf).toList();
    if (sampleColumns.isEmpty() && !sampleRows.isEmpty()) {
      throw new IllegalArgumentException("sampleRows require sampleColumns");
    }
    for (List<JsonNode> sampleRow : sampleRows) {
      if (sampleRow.size() != sampleColumns.size()) {
        throw new IllegalArgumentException("sampleRows must match sampleColumns");
      }
    }
    unverifiedItems = unverifiedItems == null ? List.of() : List.copyOf(unverifiedItems);
  }
}
