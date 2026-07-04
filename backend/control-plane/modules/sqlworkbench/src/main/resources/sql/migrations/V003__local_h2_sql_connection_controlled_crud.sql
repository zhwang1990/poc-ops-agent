update sql_workbench_connection
set
  target_environment = 'sit',
  capabilities = '["VALIDATE","RUN_READ_ONLY","PREFLIGHT_DML","COMMIT_DML"]'
where connection_id = 'h2-local-test';
