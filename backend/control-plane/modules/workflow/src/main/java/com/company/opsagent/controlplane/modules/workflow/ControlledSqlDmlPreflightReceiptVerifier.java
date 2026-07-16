package com.company.opsagent.controlplane.modules.workflow;

/**
 * M05 对服务端签发 DML 预检回执的验证端口。
 *
 * <p>签发和密钥保管由上游模块实现；M05 只在创建工作流前要求验证成功，避免反向依赖 M09。
 */
@FunctionalInterface
public interface ControlledSqlDmlPreflightReceiptVerifier {

  /** 验证回执的服务端真实性及其与当前受控 DML 请求的绑定。 */
  void verifyAuthenticityAndBinding(ControlledSqlDmlWorkflowRequest request);

  /**
   * 验证回执仍可用于创建或继续一次新的 Worker 提交。
   *
   * <p>默认实现保留既有实现的 fail-closed 语义；终态复用和 RUNNING 状态协调不会调用此方法。
   */
  default void verifyUsableForDispatch(ControlledSqlDmlWorkflowRequest request) {
    verifyAuthenticityAndBinding(request);
  }

  /**
   * 兼容旧调用方的全量验证入口。
   *
   * @deprecated 新代码必须在所有路径先调用 {@link #verifyAuthenticityAndBinding}，仅在新的 Worker
   *     提交前调用 {@link #verifyUsableForDispatch}。
   */
  @Deprecated(forRemoval = false)
  default void verify(ControlledSqlDmlWorkflowRequest request) {
    verifyUsableForDispatch(request);
  }
}
