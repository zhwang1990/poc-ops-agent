package com.company.opsagent.controlplane.modules.events;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class EventsModuleTest {

  @Test
  void exposesModuleId() {
    assertEquals("M09", EventsModule.moduleId());
  }

  @Test
  void exposesReleaseEventContractId() {
    assertEquals("release/release-events-v1.schema.json", EventsModule.releaseEventContractId());
  }
}
