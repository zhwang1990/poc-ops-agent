package com.company.opsagent.controlplane.modules.events;

/**
 * 事件模块元信息定义。
 */
public final class EventsModule {

  private EventsModule() {
  }

  /**
   * 返回事件模块编号。
   */
  public static String moduleId() {
    return "M09";
  }

  public static String releaseEventContractId() {
    return "release/release-events-v1.schema.json";
  }
}
