package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.requiredText;

/**
 * 受控 DML 提交必须携带的服务端哈希绑定。
 */
public record SqlDmlExecutionBinding(
    String bindingHash,
    String parametersHash,
    String preflightHash,
    String confirmationHash) {

  public SqlDmlExecutionBinding {
    bindingHash = requireHash(bindingHash, "bindingHash");
    parametersHash = requireHash(parametersHash, "parametersHash");
    preflightHash = requireHash(preflightHash, "preflightHash");
    confirmationHash = requireHash(confirmationHash, "confirmationHash");
  }

  private static String requireHash(String value, String fieldName) {
    return requiredText(value, fieldName);
  }
}
