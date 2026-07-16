alter table if exists controlled_sql_dml_workflow
  add column if not exists execution_expires_at timestamp with time zone;
