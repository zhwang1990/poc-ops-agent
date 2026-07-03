-- Ops Agent MySQL 建表脚本
-- 生成依据：
-- 1. backend/control-plane/modules/*/src/main/resources/sql/migrations
-- 2. backend/execution-worker-sqlworkbench/src/main/java/.../H2SqlDataSourceFactory.java
--
-- 适用范围：
-- - 控制面业务库表：身份、工作流、模型供应方、SQL 工作台目录、发布中心。
-- - SQL 工作台演示目标表：最后 4 张表仅用于搭建本地/测试演示数据源。
--
-- 注意：
-- - 执行前请先选择目标 MySQL database，例如 `CREATE DATABASE ...; USE ...;`。
-- - 时间字段按 UTC 约定写入；原 H2/R2DBC 的 `timestamp with time zone` 在 MySQL 中使用 `DATETIME(6)`。
-- - 本文件只建表和索引，不写入 demo 管理员、模型供应方、连接目录或凭据种子数据。
-- - P2 阶段仍禁止生产写执行；生产库不要启用 SQL 工作台写能力或发布中心生产变更。

SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- M01 身份认证
-- ============================================================

CREATE TABLE IF NOT EXISTS identity_account (
  account_id VARCHAR(120) NOT NULL,
  username VARCHAR(120) NOT NULL,
  display_name VARCHAR(200) NULL,
  email VARCHAR(320) NULL,
  account_status VARCHAR(40) NOT NULL,
  password_state VARCHAR(40) NOT NULL,
  mfa_requirement VARCHAR(40) NOT NULL,
  failed_login_count INT NOT NULL DEFAULT 0,
  locked_until DATETIME(6) NULL,
  disabled_reason VARCHAR(500) NULL,
  last_login_at DATETIME(6) NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (account_id),
  UNIQUE KEY uk_identity_account_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS identity_account_role_grant (
  grant_id VARCHAR(120) NOT NULL,
  account_id VARCHAR(120) NOT NULL,
  role_code VARCHAR(120) NOT NULL,
  grant_source VARCHAR(80) NOT NULL,
  effective_from DATETIME(6) NOT NULL,
  effective_to DATETIME(6) NULL,
  created_by VARCHAR(120) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  revoked_by VARCHAR(120) NULL,
  revoked_at DATETIME(6) NULL,
  PRIMARY KEY (grant_id),
  KEY idx_identity_role_grant_account_id (account_id),
  CONSTRAINT fk_identity_role_grant_account
    FOREIGN KEY (account_id) REFERENCES identity_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS identity_password_credential (
  credential_id VARCHAR(120) NOT NULL,
  account_id VARCHAR(120) NOT NULL,
  hash_algorithm VARCHAR(80) NOT NULL,
  hash_parameters VARCHAR(500) NOT NULL,
  password_hash VARCHAR(1000) NOT NULL,
  password_version BIGINT NOT NULL,
  must_change_on_next_login BOOLEAN NOT NULL DEFAULT FALSE,
  created_at DATETIME(6) NOT NULL,
  rotated_at DATETIME(6) NULL,
  PRIMARY KEY (credential_id),
  KEY idx_identity_password_credential_account_id (account_id),
  CONSTRAINT fk_identity_password_credential_account
    FOREIGN KEY (account_id) REFERENCES identity_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS identity_account_session (
  session_id VARCHAR(120) NOT NULL,
  account_id VARCHAR(120) NOT NULL,
  session_state VARCHAR(40) NOT NULL,
  issued_at DATETIME(6) NOT NULL,
  last_seen_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  absolute_expires_at DATETIME(6) NOT NULL,
  password_change_required BOOLEAN NOT NULL DEFAULT FALSE,
  revoked_at DATETIME(6) NULL,
  revoked_reason VARCHAR(500) NULL,
  client_ip_hash VARCHAR(200) NULL,
  user_agent_hash VARCHAR(200) NULL,
  PRIMARY KEY (session_id),
  KEY idx_identity_account_session_account_id (account_id),
  CONSTRAINT fk_identity_account_session_account
    FOREIGN KEY (account_id) REFERENCES identity_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS identity_password_reset_ticket (
  ticket_id VARCHAR(120) NOT NULL,
  account_id VARCHAR(120) NOT NULL,
  ticket_state VARCHAR(40) NOT NULL,
  issued_by VARCHAR(120) NOT NULL,
  issued_at DATETIME(6) NOT NULL,
  expires_at DATETIME(6) NOT NULL,
  consumed_at DATETIME(6) NULL,
  reason VARCHAR(500) NOT NULL,
  PRIMARY KEY (ticket_id),
  KEY idx_identity_password_reset_ticket_account_id (account_id),
  CONSTRAINT fk_identity_password_reset_ticket_account
    FOREIGN KEY (account_id) REFERENCES identity_account (account_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- M05 只读诊断工作流
-- ============================================================

CREATE TABLE IF NOT EXISTS workflow_instance (
  workflow_id VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  operator_id VARCHAR(128) NOT NULL,
  target_environment VARCHAR(64) NOT NULL,
  skill_id VARCHAR(128) NOT NULL,
  skill_version VARCHAR(64) NOT NULL,
  parameters_hash VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  policy_decision_id VARCHAR(128) NOT NULL,
  policy_version VARCHAR(64) NOT NULL,
  trace_id VARCHAR(128) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  command_id VARCHAR(64) NOT NULL,
  command_json LONGTEXT NOT NULL,
  current_attempt_no INT NOT NULL,
  max_replay_count INT NOT NULL,
  replay_count INT NOT NULL,
  result_status VARCHAR(32) NULL,
  result_schema_id VARCHAR(256) NULL,
  result_payload_json LONGTEXT NULL,
  error_code VARCHAR(128) NULL,
  error_message LONGTEXT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  completed_at DATETIME(6) NULL,
  PRIMARY KEY (workflow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_idempotency (
  idempotency_key VARCHAR(128) NOT NULL,
  operator_id VARCHAR(128) NOT NULL,
  target_environment VARCHAR(64) NOT NULL,
  skill_id VARCHAR(128) NOT NULL,
  parameters_hash VARCHAR(128) NOT NULL,
  workflow_id VARCHAR(64) NOT NULL,
  PRIMARY KEY (idempotency_key, operator_id, target_environment, skill_id, parameters_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_attempt (
  workflow_id VARCHAR(64) NOT NULL,
  attempt_no INT NOT NULL,
  execution_request_id VARCHAR(64) NOT NULL,
  attempt_kind VARCHAR(16) NOT NULL,
  status VARCHAR(32) NOT NULL,
  started_at DATETIME(6) NOT NULL,
  completed_at DATETIME(6) NULL,
  expires_at DATETIME(6) NULL,
  worker_error_code VARCHAR(128) NULL,
  worker_error_message LONGTEXT NULL,
  retryable BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (workflow_id, attempt_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_event (
  workflow_id VARCHAR(64) NOT NULL,
  sequence BIGINT NOT NULL,
  event_id VARCHAR(64) NOT NULL,
  event_type VARCHAR(64) NOT NULL,
  event_payload_json LONGTEXT NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (workflow_id, sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- M05 Agent 工作流
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_workflow (
  workflow_id VARCHAR(64) NOT NULL,
  workspace_id VARCHAR(128) NOT NULL,
  operator_id VARCHAR(128) NOT NULL,
  target_environment VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  completed_at DATETIME(6) NULL,
  result_status VARCHAR(64) NULL,
  result_summary LONGTEXT NULL,
  result_tool_call_count INT NULL,
  PRIMARY KEY (workflow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_workflow_idempotency (
  workspace_id VARCHAR(128) NOT NULL,
  operator_id VARCHAR(128) NOT NULL,
  target_environment VARCHAR(64) NOT NULL,
  idempotency_key VARCHAR(128) NOT NULL,
  workflow_id VARCHAR(64) NOT NULL,
  PRIMARY KEY (workspace_id, operator_id, target_environment, idempotency_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS agent_tool_step (
  workflow_id VARCHAR(64) NOT NULL,
  workspace_id VARCHAR(128) NOT NULL,
  step_sequence BIGINT NOT NULL,
  tool_call_id VARCHAR(64) NOT NULL,
  skill_id VARCHAR(128) NOT NULL,
  skill_version VARCHAR(64) NOT NULL,
  parameters_hash VARCHAR(128) NOT NULL,
  policy_decision_id VARCHAR(128) NOT NULL,
  status VARCHAR(32) NOT NULL,
  requested_at DATETIME(6) NOT NULL,
  completed_at DATETIME(6) NULL,
  error_code VARCHAR(128) NULL,
  error_message LONGTEXT NULL,
  PRIMARY KEY (workflow_id, step_sequence),
  KEY idx_agent_tool_step_workspace_workflow_sequence (workspace_id, workflow_id, step_sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- M04 模型供应方
-- ============================================================

CREATE TABLE IF NOT EXISTS agent_model_provider (
  provider_id VARCHAR(64) NOT NULL,
  display_name VARCHAR(160) NOT NULL,
  provider_type VARCHAR(64) NOT NULL,
  base_url VARCHAR(512) NOT NULL,
  model_name VARCHAR(256) NOT NULL,
  enabled BOOLEAN NOT NULL,
  default_provider BOOLEAN NOT NULL,
  timeout_seconds BIGINT NOT NULL,
  max_iterations INT NOT NULL,
  max_tool_calls INT NOT NULL,
  max_tool_call_duration_seconds BIGINT NOT NULL,
  api_key_ciphertext LONGTEXT NOT NULL,
  api_key_nonce VARCHAR(128) NOT NULL,
  api_key_algorithm VARCHAR(64) NOT NULL,
  api_key_fingerprint VARCHAR(128) NOT NULL,
  api_key_last_rotated_at DATETIME(6) NOT NULL,
  config_version BIGINT NOT NULL,
  created_by VARCHAR(128) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_by VARCHAR(128) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (provider_id),
  KEY idx_agent_model_provider_default (default_provider, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- M09 SQL 工作台控制面连接目录
-- ============================================================

CREATE TABLE IF NOT EXISTS sql_workbench_connection (
  connection_id VARCHAR(128) NOT NULL,
  display_name VARCHAR(160) NOT NULL,
  target_environment VARCHAR(32) NOT NULL,
  platform_type VARCHAR(32) NOT NULL,
  host VARCHAR(255) NOT NULL,
  port INT NOT NULL,
  default_schema VARCHAR(128) NOT NULL,
  allowed_schemas LONGTEXT NOT NULL,
  capabilities LONGTEXT NOT NULL,
  credential_alias VARCHAR(160) NOT NULL,
  status VARCHAR(64) NOT NULL,
  max_rows_default INT NOT NULL,
  timeout_seconds_default INT NOT NULL,
  PRIMARY KEY (connection_id),
  KEY idx_sql_workbench_connection_environment (target_environment, platform_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- M09 发布中心 P2 受控变更目录和计划
-- ============================================================

CREATE TABLE IF NOT EXISTS release_application (
  application_id VARCHAR(120) NOT NULL,
  display_name VARCHAR(200) NOT NULL,
  artifact_type VARCHAR(40) NOT NULL,
  health_path VARCHAR(400) NOT NULL,
  enabled BOOLEAN NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (application_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS release_environment_policy (
  target_environment VARCHAR(20) NOT NULL,
  allow_deploy BOOLEAN NOT NULL,
  allow_start BOOLEAN NOT NULL,
  allow_stop BOOLEAN NOT NULL,
  allow_rollback BOOLEAN NOT NULL,
  require_confirmation BOOLEAN NOT NULL,
  timeout_seconds INT NOT NULL,
  stop_on_node_failure BOOLEAN NOT NULL,
  log_analysis_enabled BOOLEAN NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (target_environment)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS release_server (
  node_id VARCHAR(120) NOT NULL,
  target_environment VARCHAR(20) NOT NULL,
  server_type VARCHAR(40) NOT NULL,
  management_mode VARCHAR(80) NOT NULL,
  management_endpoint VARCHAR(500) NOT NULL,
  application_path VARCHAR(500) NULL,
  credential_alias VARCHAR(160) NULL,
  script_profile_id VARCHAR(120) NULL,
  script_parameters_json LONGTEXT NULL,
  enabled BOOLEAN NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (node_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS release_credential (
  credential_alias VARCHAR(160) NOT NULL,
  server_type VARCHAR(40) NOT NULL,
  ciphertext LONGTEXT NOT NULL,
  nonce VARCHAR(120) NOT NULL,
  algorithm VARCHAR(80) NOT NULL,
  fingerprint VARCHAR(120) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (credential_alias)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS release_artifact (
  artifact_id VARCHAR(120) NOT NULL,
  application_id VARCHAR(120) NOT NULL,
  target_environment VARCHAR(20) NOT NULL,
  artifact_type VARCHAR(40) NOT NULL,
  checksum VARCHAR(160) NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  storage_key VARCHAR(255) NOT NULL,
  byte_size BIGINT NOT NULL,
  uploaded_by VARCHAR(160) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (artifact_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS release_plan (
  release_id VARCHAR(120) NOT NULL,
  workflow_id VARCHAR(120) NOT NULL,
  application_id VARCHAR(120) NOT NULL,
  target_environment VARCHAR(20) NOT NULL,
  artifact_id VARCHAR(120) NULL,
  operation VARCHAR(40) NOT NULL,
  status VARCHAR(80) NOT NULL,
  parameters_hash VARCHAR(160) NOT NULL,
  policy_version VARCHAR(120) NOT NULL,
  confirmed_by VARCHAR(160) NULL,
  confirmed_at DATETIME(6) NULL,
  created_by VARCHAR(160) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (release_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS release_node_step (
  release_id VARCHAR(120) NOT NULL,
  step_sequence INT NOT NULL,
  node_id VARCHAR(120) NOT NULL,
  action VARCHAR(40) NOT NULL,
  status VARCHAR(80) NOT NULL,
  error_code VARCHAR(160) NULL,
  error_message VARCHAR(1000) NULL,
  started_at DATETIME(6) NULL,
  completed_at DATETIME(6) NULL,
  PRIMARY KEY (release_id, step_sequence)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS release_script_profile_definition (
  profile_id VARCHAR(120) NOT NULL,
  target_environment VARCHAR(20) NOT NULL,
  display_name VARCHAR(200) NOT NULL,
  executable_path VARCHAR(500) NOT NULL,
  working_directory VARCHAR(500) NOT NULL,
  arguments_json LONGTEXT NOT NULL,
  required_parameters_json LONGTEXT NOT NULL,
  allowed_parameters_json LONGTEXT NOT NULL,
  success_exit_codes_json LONGTEXT NOT NULL,
  timeout_seconds INT NOT NULL,
  approved BOOLEAN NOT NULL,
  enabled BOOLEAN NOT NULL,
  created_at DATETIME(6) NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (target_environment, profile_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ============================================================
-- SQL 工作台本地演示目标库表
-- 这些表来源于 Worker H2SqlDataSourceFactory，仅用于本地/测试演示数据源。
-- H2 中是 PUBLIC.CUSTOMERS / PUBLIC.ORDERS / PUBLIC.INCIDENTS / PUBLIC.SERVICE_HEALTH。
-- MySQL 建议使用小写表名，避免 Linux 环境下大小写敏感导致查询不一致。
-- 如果只搭控制面业务库，可以不执行本段。
-- ============================================================

CREATE TABLE IF NOT EXISTS customers (
  customer_id VARCHAR(48) NOT NULL,
  customer_name VARCHAR(120) NOT NULL,
  tier VARCHAR(24) NOT NULL,
  region VARCHAR(48) NOT NULL,
  customer_status VARCHAR(24) NOT NULL,
  PRIMARY KEY (customer_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS orders (
  order_id INT NOT NULL,
  status VARCHAR(24) NOT NULL,
  amount DECIMAL(12, 2) NOT NULL,
  customer_id VARCHAR(48) NOT NULL,
  created_at DATETIME(6) NOT NULL,
  PRIMARY KEY (order_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS incidents (
  incident_id VARCHAR(48) NOT NULL,
  service_name VARCHAR(80) NOT NULL,
  severity VARCHAR(24) NOT NULL,
  incident_status VARCHAR(24) NOT NULL,
  started_at DATETIME(6) NOT NULL,
  resolved_at DATETIME(6) NULL,
  impact_summary VARCHAR(240) NOT NULL,
  PRIMARY KEY (incident_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS service_health (
  service_name VARCHAR(80) NOT NULL,
  environment VARCHAR(24) NOT NULL,
  health_status VARCHAR(24) NOT NULL,
  error_rate_percent DECIMAL(8, 3) NOT NULL,
  p95_latency_ms INT NOT NULL,
  updated_at DATETIME(6) NOT NULL,
  PRIMARY KEY (service_name, environment)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 1;
