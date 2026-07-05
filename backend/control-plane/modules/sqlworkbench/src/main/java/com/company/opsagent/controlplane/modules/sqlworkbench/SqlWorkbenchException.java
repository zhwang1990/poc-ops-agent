package com.company.opsagent.controlplane.modules.sqlworkbench;

public final class SqlWorkbenchException extends RuntimeException {

  private final String code;

  public SqlWorkbenchException(String code, String message) {
    super(message);
    if (code == null || code.isBlank()) {
      throw new IllegalArgumentException("code must not be blank");
    }
    this.code = code;
  }

  public String code() {
    return code;
  }
}
