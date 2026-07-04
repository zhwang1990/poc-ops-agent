insert into sql_workbench_connection (
  connection_id,
  display_name,
  target_environment,
  platform_type,
  host,
  port,
  default_schema,
  allowed_schemas,
  capabilities,
  credential_alias,
  status,
  max_rows_default,
  timeout_seconds_default
)
select
  'h2-local-test',
  'H2 Local Test',
  'sit',
  'H2',
  'localhost',
  9092,
  'PUBLIC',
  '["PUBLIC"]',
  '["VALIDATE","RUN_READ_ONLY","PREFLIGHT_DML","COMMIT_DML"]',
  'h2-local-readonly',
  'READY',
  500,
  30
where not exists (
  select 1
  from sql_workbench_connection
  where connection_id = 'h2-local-test'
);
