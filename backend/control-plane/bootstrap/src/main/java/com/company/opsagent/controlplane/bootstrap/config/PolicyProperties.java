package com.company.opsagent.controlplane.bootstrap.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 策略配置属性。
 *
 * <p>当前使用最简单的“动作 -> 角色列表”方式承载 RBAC 规则，后续如果切到独立策略源，
 * 这个对象仍可作为配置装配层的输入模型。
 */
@ConfigurationProperties(prefix = "ops-agent.policy")
public class PolicyProperties {

  private String version = "rbac-v1";
  private Map<String, List<String>> requiredRolesByAction = defaultRequiredRolesByAction();

  /**
   * 返回当前策略版本号。
   */
  public String getVersion() {
    return version;
  }

  /**
   * 设置当前策略版本号。
   *
   * @param version 用于审计和策略决策结果标识的版本号
   */
  public void setVersion(String version) {
    this.version = version;
  }

  /**
   * 返回动作到角色要求的映射表。
   */
  public Map<String, List<String>> getRequiredRolesByAction() {
    return requiredRolesByAction;
  }

  /**
   * 设置动作到角色要求的映射表。
   *
   * @param requiredRolesByAction 外部配置驱动的 RBAC 规则定义
   */
  public void setRequiredRolesByAction(Map<String, List<String>> requiredRolesByAction) {
    LinkedHashMap<String, List<String>> merged = new LinkedHashMap<>(defaultRequiredRolesByAction());
    if (requiredRolesByAction != null) {
      merged.putAll(requiredRolesByAction);
    }
    this.requiredRolesByAction = merged;
  }

  private static Map<String, List<String>> defaultRequiredRolesByAction() {
    LinkedHashMap<String, List<String>> defaults = new LinkedHashMap<>();
    defaults.put("release.catalog.read", List.of("ROLE_ops-reader", "ROLE_ops-admin"));
    defaults.put("release.catalog.write", List.of("ROLE_ops-admin"));
    defaults.put("release.credential.rotate", List.of("ROLE_ops-admin"));
    defaults.put("release.connection.test", List.of("ROLE_ops-admin"));
    defaults.put("release.plan.create", List.of("ROLE_ops-admin"));
    defaults.put("release.plan.confirm", List.of("ROLE_ops-admin"));
    defaults.put("release.plan.execute", List.of("ROLE_ops-admin"));
    defaults.put("release.rollback.execute", List.of("ROLE_ops-admin"));
    return defaults;
  }
}
