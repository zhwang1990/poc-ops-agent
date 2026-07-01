package com.company.opsagent.controlplane.modules.release;

final class ReleaseValues {

  private ReleaseValues() {
  }

  static String requiredText(String value, String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value.trim();
  }

  static <T> T required(T value, String fieldName) {
    if (value == null) {
      throw new IllegalArgumentException(fieldName + " is required");
    }
    return value;
  }

  static String sha256Checksum(String value) {
    String checksum = requiredText(value, "checksum");
    if (!checksum.matches("^sha256:[a-fA-F0-9]{3,}$")) {
      throw new IllegalArgumentException("checksum must be a sha256 digest");
    }
    return checksum;
  }
}
