CREATE TABLE IF NOT EXISTS release_application (
  application_id VARCHAR(120) PRIMARY KEY,
  display_name VARCHAR(200) NOT NULL,
  artifact_type VARCHAR(40) NOT NULL,
  health_path VARCHAR(400) NOT NULL,
  enabled BOOLEAN NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS release_environment_policy (
  target_environment VARCHAR(20) PRIMARY KEY,
  allow_deploy BOOLEAN NOT NULL,
  allow_start BOOLEAN NOT NULL,
  allow_stop BOOLEAN NOT NULL,
  allow_rollback BOOLEAN NOT NULL,
  require_confirmation BOOLEAN NOT NULL,
  timeout_seconds INTEGER NOT NULL,
  stop_on_node_failure BOOLEAN NOT NULL,
  log_analysis_enabled BOOLEAN NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS release_server (
  node_id VARCHAR(120) PRIMARY KEY,
  target_environment VARCHAR(20) NOT NULL,
  server_type VARCHAR(40) NOT NULL,
  management_mode VARCHAR(80) NOT NULL,
  management_endpoint VARCHAR(500) NOT NULL,
  application_path VARCHAR(500),
  credential_alias VARCHAR(160),
  script_profile_id VARCHAR(120),
  script_parameters_json CLOB,
  enabled BOOLEAN NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS release_credential (
  credential_alias VARCHAR(160) PRIMARY KEY,
  server_type VARCHAR(40) NOT NULL,
  ciphertext CLOB NOT NULL,
  nonce VARCHAR(120) NOT NULL,
  algorithm VARCHAR(80) NOT NULL,
  fingerprint VARCHAR(120) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS release_artifact (
  artifact_id VARCHAR(120) PRIMARY KEY,
  application_id VARCHAR(120) NOT NULL,
  target_environment VARCHAR(20) NOT NULL,
  artifact_type VARCHAR(40) NOT NULL,
  checksum VARCHAR(160) NOT NULL,
  original_filename VARCHAR(255) NOT NULL,
  storage_key VARCHAR(255) NOT NULL,
  byte_size BIGINT NOT NULL,
  uploaded_by VARCHAR(160) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS release_plan (
  release_id VARCHAR(120) PRIMARY KEY,
  workflow_id VARCHAR(120) NOT NULL,
  application_id VARCHAR(120) NOT NULL,
  target_environment VARCHAR(20) NOT NULL,
  artifact_id VARCHAR(120),
  operation VARCHAR(40) NOT NULL,
  status VARCHAR(80) NOT NULL,
  parameters_hash VARCHAR(160) NOT NULL,
  policy_version VARCHAR(120) NOT NULL,
  confirmed_by VARCHAR(160),
  confirmed_at TIMESTAMP WITH TIME ZONE,
  created_by VARCHAR(160) NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE TABLE IF NOT EXISTS release_node_step (
  release_id VARCHAR(120) NOT NULL,
  step_sequence INTEGER NOT NULL,
  node_id VARCHAR(120) NOT NULL,
  action VARCHAR(40) NOT NULL,
  status VARCHAR(80) NOT NULL,
  error_code VARCHAR(160),
  error_message VARCHAR(1000),
  started_at TIMESTAMP WITH TIME ZONE,
  completed_at TIMESTAMP WITH TIME ZONE,
  PRIMARY KEY (release_id, step_sequence)
);
