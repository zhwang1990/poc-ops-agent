ALTER TABLE release_server
  ADD COLUMN IF NOT EXISTS script_profile_id VARCHAR(120);

ALTER TABLE release_server
  ADD COLUMN IF NOT EXISTS script_parameters_json CLOB;
