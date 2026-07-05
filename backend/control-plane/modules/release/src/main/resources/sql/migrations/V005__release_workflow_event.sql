CREATE TABLE IF NOT EXISTS release_workflow_event (
  release_id VARCHAR(120) NOT NULL,
  event_sequence BIGINT NOT NULL,
  event_id VARCHAR(120) NOT NULL,
  workflow_id VARCHAR(120) NOT NULL,
  contract_version VARCHAR(20) NOT NULL,
  event_type VARCHAR(80) NOT NULL,
  payload_json CLOB NOT NULL,
  audit_json CLOB NOT NULL,
  occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
  PRIMARY KEY (release_id, event_sequence),
  UNIQUE (event_id)
);
