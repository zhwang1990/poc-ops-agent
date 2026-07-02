CREATE TABLE IF NOT EXISTS release_script_profile_definition (
  profile_id VARCHAR(120) NOT NULL,
  target_environment VARCHAR(20) NOT NULL,
  display_name VARCHAR(200) NOT NULL,
  executable_path VARCHAR(500) NOT NULL,
  working_directory VARCHAR(500) NOT NULL,
  arguments_json CLOB NOT NULL,
  required_parameters_json CLOB NOT NULL,
  allowed_parameters_json CLOB NOT NULL,
  success_exit_codes_json CLOB NOT NULL,
  timeout_seconds INTEGER NOT NULL,
  approved BOOLEAN NOT NULL,
  enabled BOOLEAN NOT NULL,
  created_at TIMESTAMP WITH TIME ZONE NOT NULL,
  updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (target_environment, profile_id)
);
