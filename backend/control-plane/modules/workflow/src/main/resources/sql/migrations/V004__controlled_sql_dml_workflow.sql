create table if not exists controlled_sql_dml_workflow (
  workflow_id varchar(64) primary key,
  idempotency_key varchar(128) not null,
  operator_id varchar(128) not null,
  target_environment varchar(64) not null,
  binding_hash varchar(128) not null,
  connection_id varchar(128) not null,
  schema_name varchar(128) not null,
  statement_type varchar(16) not null,
  sql_hash varchar(128) not null,
  parameters_hash varchar(128) not null,
  preflight_hash varchar(128) not null,
  confirmation_hash varchar(128) not null,
  policy_decision_id varchar(128) not null,
  policy_version varchar(64) not null,
  trace_id varchar(128) not null,
  request_id varchar(128) not null,
  status varchar(32) not null,
  attempt_count integer not null,
  affected_row_count integer,
  failure_code varchar(128),
  confirmed_at timestamp with time zone,
  created_at timestamp with time zone not null,
  updated_at timestamp with time zone not null,
  completed_at timestamp with time zone
);

create unique index if not exists ux_controlled_sql_dml_idempotency
  on controlled_sql_dml_workflow (idempotency_key, operator_id, target_environment);

alter table if exists controlled_sql_dml_workflow
  add column if not exists confirmed_at timestamp with time zone;
