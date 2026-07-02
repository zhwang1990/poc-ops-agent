package com.company.opsagent.executionworker;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 配置型 HTTP/JSON 只读 Skill 适配器配置。
 *
 * <p>该配置仅保存非敏感元数据、端点和字段映射。真实密钥不得写入这里。
 */
@ConfigurationProperties(prefix = "ops-agent.worker.configured-http-skills")
public class ConfiguredHttpReadOnlySkillProperties {

  private List<Skill> skills = List.of();

  public List<Skill> getSkills() {
    return skills;
  }

  public void setSkills(List<Skill> skills) {
    this.skills = skills == null ? List.of() : List.copyOf(skills);
  }

  /**
   * 单个简单 HTTP/JSON 只读 Skill 的执行配置。
   */
  public static class Skill {

    private String skillId;
    private String version;
    private String endpointUrl;
    private String inputParameterName;
    private String queryParameterName;
    private String source = "";
    private Duration timeout = Duration.ofSeconds(5);
    private List<String> allowedResponseFields = List.of();

    public String getSkillId() {
      return skillId;
    }

    public void setSkillId(String skillId) {
      this.skillId = normalize(skillId);
    }

    public String getVersion() {
      return version;
    }

    public void setVersion(String version) {
      this.version = normalize(version);
    }

    public String getEndpointUrl() {
      return endpointUrl;
    }

    public void setEndpointUrl(String endpointUrl) {
      this.endpointUrl = normalize(endpointUrl);
    }

    public String getInputParameterName() {
      return inputParameterName;
    }

    public void setInputParameterName(String inputParameterName) {
      this.inputParameterName = normalize(inputParameterName);
    }

    public String getQueryParameterName() {
      return queryParameterName;
    }

    public void setQueryParameterName(String queryParameterName) {
      this.queryParameterName = normalize(queryParameterName);
    }

    public String getSource() {
      return source;
    }

    public void setSource(String source) {
      this.source = normalize(source);
    }

    public Duration getTimeout() {
      return timeout;
    }

    public void setTimeout(Duration timeout) {
      this.timeout = timeout == null ? Duration.ofSeconds(5) : timeout;
    }

    public List<String> getAllowedResponseFields() {
      return allowedResponseFields;
    }

    public void setAllowedResponseFields(List<String> allowedResponseFields) {
      this.allowedResponseFields = allowedResponseFields == null ? List.of() : List.copyOf(allowedResponseFields);
    }

    private static String normalize(String value) {
      return value == null ? "" : value.trim();
    }
  }
}
