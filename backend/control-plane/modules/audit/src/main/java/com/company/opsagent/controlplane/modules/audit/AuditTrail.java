package com.company.opsagent.controlplane.modules.audit;

import java.util.List;
import java.util.Optional;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 审计链抽象接口。
 *
 * <p>定义审计事件记录、查询和清理的基础能力，便于在内存实现、文件实现和未来正式存储实现之间切换。
 */
public interface AuditTrail {

  /**
   * 记录一条审计事件。
   */
  void record(AuditEvent event);

  /**
   * 在调用方的响应式事务中记录审计事件。
   *
   * <p>R2DBC 实现覆盖该方法以复用事务连接；文件和内存实现通过有界弹性线程执行既有同步写入。
   */
  default Mono<Void> recordReactive(AuditEvent event) {
    return Mono.fromRunnable(() -> record(event))
        .subscribeOn(Schedulers.boundedElastic())
        .then();
  }

  /**
   * 返回当前所有审计事件快照。
   */
  List<AuditEvent> snapshot();

  /**
   * 返回最近一条审计事件。
   */
  Optional<AuditEvent> latest();

  /**
   * 清空当前审计链内容。
   */
  void clear();
}
