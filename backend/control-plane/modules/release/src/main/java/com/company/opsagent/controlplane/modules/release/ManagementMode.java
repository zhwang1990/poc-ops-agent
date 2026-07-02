package com.company.opsagent.controlplane.modules.release;

public enum ManagementMode {
  LIBERTY_HTTPS,
  LIBERTY_SCRIPT_PROFILE,
  TOMCAT_WAR_UPLOAD,
  TOMCAT_MANAGER_API,
  NODE_AGENT_HTTPS,
  CONTROLLED_SSH_TEMPLATE,
  DISABLED
}
