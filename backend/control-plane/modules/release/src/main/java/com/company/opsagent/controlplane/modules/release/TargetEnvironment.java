package com.company.opsagent.controlplane.modules.release;

import java.util.Arrays;

public enum TargetEnvironment {
  DEV("dev"),
  SIT("sit"),
  UAT("uat");

  private final String value;

  TargetEnvironment(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static TargetEnvironment from(String value) {
    String normalized = ReleaseValues.requiredText(value, "targetEnvironment").toLowerCase();
    return Arrays.stream(values())
        .filter(environment -> environment.value.equals(normalized))
        .findFirst()
        .orElseThrow(() -> new IllegalArgumentException("targetEnvironment must be dev, sit or uat"));
  }
}
