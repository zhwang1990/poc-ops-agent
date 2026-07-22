package com.company.opsagent.controlplane.bootstrap.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Skill 注册中心配置属性。
 *
 * <p>当前阶段支持从仓库目录扫描 Skill Manifest，并在启动阶段完成签名与发布校验。
 * 后续如果切换到制品仓库或数据库，可以在不改变上层接口的前提下替换底层加载源。
 */
@ConfigurationProperties(prefix = "ops-agent.skill-registry")
public class SkillRegistryProperties {

  private String rootPath = "backend/contracts/skills/packages";
  private boolean signatureRequired = true;
  private String signingSecret;

  /**
   * 返回 Skill Manifest 根目录。
   */
  public String getRootPath() {
    return rootPath;
  }

  /**
   * 设置 Skill Manifest 根目录。
   *
   * @param rootPath 仓库中的 Skill 平台契约包根目录，支持相对路径和绝对路径
   */
  public void setRootPath(String rootPath) {
    this.rootPath = rootPath;
  }

  /**
   * 返回是否强制要求 Skill 发布签名。
   */
  public boolean isSignatureRequired() {
    return signatureRequired;
  }

  /**
   * 设置是否强制要求 Skill 发布签名。
   */
  public void setSignatureRequired(boolean signatureRequired) {
    this.signatureRequired = signatureRequired;
  }

  /**
   * 返回 HMAC 签名校验密钥。
   */
  public String getSigningSecret() {
    return signingSecret;
  }

  /**
   * 设置 HMAC 签名校验密钥。
   */
  public void setSigningSecret(String signingSecret) {
    this.signingSecret = signingSecret;
  }
}
