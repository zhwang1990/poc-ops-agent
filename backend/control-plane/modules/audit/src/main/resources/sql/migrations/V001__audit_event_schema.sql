create table if not exists audit_event (
  event_id varchar(64) primary key,
  request_id varchar(128) not null,
  trace_id varchar(128) not null,
  subject varchar(256) not null,
  action varchar(128) not null,
  resource varchar(512) not null,
  policy_version varchar(64) not null,
  result varchar(32) not null,
  reason clob not null,
  occurred_at timestamp with time zone not null
);

create index if not exists idx_audit_event_trace_id on audit_event (trace_id);
create index if not exists idx_audit_event_request_id on audit_event (request_id);
create index if not exists idx_audit_event_subject on audit_event (subject);
create index if not exists idx_audit_event_action on audit_event (action);
create index if not exists idx_audit_event_occurred_at on audit_event (occurred_at);
