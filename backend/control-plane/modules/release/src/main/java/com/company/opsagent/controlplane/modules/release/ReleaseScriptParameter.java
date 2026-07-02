package com.company.opsagent.controlplane.modules.release;

import java.util.Locale;
import java.util.regex.Pattern;

public record ReleaseScriptParameter(String name, String value) {

  private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_.-]{0,63}$");
  private static final int MAX_VALUE_LENGTH = 500;

  public ReleaseScriptParameter {
    name = ReleaseValues.requiredText(name, "script parameter name");
    value = ReleaseValues.requiredText(value, "script parameter value");
    if (!NAME_PATTERN.matcher(name).matches()) {
      throw new IllegalArgumentException("script parameter name is invalid");
    }
    String lowerName = name.toLowerCase(Locale.ROOT);
    if (lowerName.contains("password") || lowerName.contains("secret") || lowerName.contains("token")) {
      throw new IllegalArgumentException("script parameter must not carry secret material");
    }
    if (value.length() > MAX_VALUE_LENGTH || value.contains("\r") || value.contains("\n")) {
      throw new IllegalArgumentException("script parameter value is invalid");
    }
  }
}
