package com.company.opsagent.controlplane.modules.sqlworkbench;

import java.time.Duration;

/** 受控 SQL DML 预检回执的服务端签名配置。 */
public final class SqlDmlPreflightReceiptProperties {

  private String keyId;
  private String hmacSecret;
  private Duration ttl = Duration.ofMinutes(5);

  public String getKeyId() {
    return keyId;
  }

  public void setKeyId(String keyId) {
    this.keyId = keyId;
  }

  public String getHmacSecret() {
    return hmacSecret;
  }

  public void setHmacSecret(String hmacSecret) {
    this.hmacSecret = hmacSecret;
  }

  public Duration getTtl() {
    return ttl;
  }

  public void setTtl(Duration ttl) {
    this.ttl = ttl;
  }

  boolean isConfigured() {
    return keyId != null
        && !keyId.isBlank()
        && hmacSecret != null
        && !hmacSecret.isBlank()
        && ttl != null
        && !ttl.isNegative()
        && !ttl.isZero();
  }
}
