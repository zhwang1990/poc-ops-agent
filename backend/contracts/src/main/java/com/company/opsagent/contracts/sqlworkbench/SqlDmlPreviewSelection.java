package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.requiredText;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 服务端策略解析出的 DML 样本列和掩码列选择。
 */
public record SqlDmlPreviewSelection(
    String contractVersion,
    List<String> sampleColumns,
    List<String> maskedSampleColumns) {

  public SqlDmlPreviewSelection {
    if (!"1.0".equals(contractVersion)) {
      throw new IllegalArgumentException("contractVersion must be 1.0");
    }
    sampleColumns = normalizeColumns(sampleColumns, "sampleColumns");
    maskedSampleColumns = normalizeColumns(maskedSampleColumns, "maskedSampleColumns");
    if (!sampleColumns.containsAll(maskedSampleColumns)) {
      throw new IllegalArgumentException("maskedSampleColumns must be included in sampleColumns");
    }
  }

  private static List<String> normalizeColumns(List<String> columns, String fieldName) {
    List<String> normalized = columns == null ? List.of() : List.copyOf(columns);
    for (String column : normalized) {
      requiredText(column, fieldName);
    }
    Set<String> uniqueColumns = new HashSet<>(normalized);
    if (uniqueColumns.size() != normalized.size()) {
      throw new IllegalArgumentException(fieldName + " must not contain duplicates");
    }
    return normalized;
  }
}
