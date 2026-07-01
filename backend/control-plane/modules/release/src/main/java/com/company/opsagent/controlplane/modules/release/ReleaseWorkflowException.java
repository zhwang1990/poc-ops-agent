package com.company.opsagent.controlplane.modules.release;

public final class ReleaseWorkflowException extends RuntimeException {

  private final String code;

  public ReleaseWorkflowException(String code, String message) {
    super(message);
    this.code = ReleaseValues.requiredText(code, "code");
  }

  public String code() {
    return code;
  }
}
