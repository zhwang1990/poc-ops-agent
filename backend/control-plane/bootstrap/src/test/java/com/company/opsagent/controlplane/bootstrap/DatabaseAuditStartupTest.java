package com.company.opsagent.controlplane.bootstrap;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.company.opsagent.controlplane.modules.audit.AuditTrail;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/** Verifies that a clean relational audit store is available during application startup. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
    "ops-agent.security.auth-mode=dev-hs256",
    "ops-agent.security.browser-login-enabled=false",
    "ops-agent.worker.base-url=http://127.0.0.1:1",
    "ops-agent.audit.storage-mode=database",
    "spring.r2dbc.url=r2dbc:h2:mem:///database-audit-startup;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
    "ops-agent.workflow.startup-recovery-enabled=false"
})
class DatabaseAuditStartupTest extends BootstrapSkillRegistryTestSupport {

  @Autowired
  private AuditTrail auditTrail;

  @Test
  void startsWithAnEmptyDatabaseBackedAuditTrail() {
    assertTrue(auditTrail.snapshot().isEmpty());
  }
}
