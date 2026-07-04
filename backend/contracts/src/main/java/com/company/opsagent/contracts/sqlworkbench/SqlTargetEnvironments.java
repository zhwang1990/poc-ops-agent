package com.company.opsagent.contracts.sqlworkbench;

import static com.company.opsagent.contracts.ContractValues.requiredText;

import java.util.Set;

/**
 * SQL workbench target environment normalization and safety boundaries.
 */
public final class SqlTargetEnvironments {

  public static final String DEV = "dev";
  public static final String SIT = "sit";
  public static final String UAT = "uat";
  public static final String PRODUCTION = "production";

  private static final Set<String> CRUD_ENVIRONMENTS = Set.of(DEV, SIT, UAT);

  private SqlTargetEnvironments() {
  }

  public static String normalize(String targetEnvironment) {
    String normalized = requiredText(targetEnvironment, "targetEnvironment").toLowerCase();
    return switch (normalized) {
      case "dev", "development" -> DEV;
      case "sit", "test" -> SIT;
      case "uat" -> UAT;
      case "prod", "production" -> PRODUCTION;
      default -> throw new IllegalArgumentException("targetEnvironment must be dev, sit, uat, or production");
    };
  }

  public static boolean same(String left, String right) {
    return normalize(left).equals(normalize(right));
  }

  public static boolean allowsCrud(String targetEnvironment) {
    return CRUD_ENVIRONMENTS.contains(normalize(targetEnvironment));
  }

  public static boolean isProduction(String targetEnvironment) {
    return PRODUCTION.equals(normalize(targetEnvironment));
  }
}
