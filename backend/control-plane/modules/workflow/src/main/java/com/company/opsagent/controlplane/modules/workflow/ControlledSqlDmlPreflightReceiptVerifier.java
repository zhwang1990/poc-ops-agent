package com.company.opsagent.controlplane.modules.workflow;

/**
 * M05 对服务端签发 DML 预检回执的验证端口。
 *
 * <p>签发和密钥保管由上游模块实现；M05 只在创建工作流前要求验证成功，避免反向依赖 M09。
 */
@FunctionalInterface
public interface ControlledSqlDmlPreflightReceiptVerifier {

  void verify(ControlledSqlDmlWorkflowRequest request);
}
