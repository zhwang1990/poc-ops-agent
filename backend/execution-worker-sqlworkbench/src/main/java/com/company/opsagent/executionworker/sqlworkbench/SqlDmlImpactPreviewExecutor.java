package com.company.opsagent.executionworker.sqlworkbench;

import com.company.opsagent.contracts.sqlworkbench.SqlDmlImpactPreview;
import com.company.opsagent.contracts.sqlworkbench.SqlDmlPreflightExecutionRequest;
import reactor.core.publisher.Mono;

/**
 * 已通过 Worker 门禁的 DML 只读影响预览执行边界。
 */
@FunctionalInterface
public interface SqlDmlImpactPreviewExecutor {

  Mono<SqlDmlImpactPreview> preview(SqlDmlPreflightExecutionRequest request);
}
