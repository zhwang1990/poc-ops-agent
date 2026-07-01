package com.company.opsagent.controlplane.modules.release;

public enum ReleaseStatus {
  DRAFT,
  WAIT_CONFIRM,
  READY,
  RUNNING,
  SUCCEEDED,
  SUCCEEDED_WITH_WARNINGS,
  PARTIAL_FAILED,
  FAILED,
  ROLLING_BACK,
  ROLLED_BACK,
  ROLLBACK_FAILED,
  MANUAL_INTERVENTION
}
