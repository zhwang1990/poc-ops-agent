# 运行手册

运行手册应与其支撑的能力同步新增。

V1.0 至少需要以下运行手册：

- 部署与回滚；
- Worker 隔离故障处理；
- 工作流恢复与人工接管；
- Skill 发布回滚；
- 身份或策略服务故障；
- 审计管道故障；
- 备份恢复与灾难恢复。

当前可用手册：

- `local-read-only-vertical-slice.md`：本地只读诊断垂直切片启动、验证与回滚；
- `local-oidc-mock-testing.md`：本地 `local-oidc` 联调与验证；
- `built-in-identity-production-mode.md`：正式内建身份模式启用、验证与回滚；
- `audit-retention-and-recovery.md`：P1 文件审计保留、归档、恢复和访问控制；
- `m07-worker-transport-auth.md`：控制面到 Worker 的应用层传输认证启用、排障和回滚；
- `backend-only-packaging.md`：使用系统 Maven 单独打包后端控制面和执行 Worker，并用 demo profile 启动验证；
- `p1-sql-workbench.md`：P1 SQL 工作台只读查询、Worker 出口、凭据别名和回滚边界；
- `p1-sql-workbench-h2-smoke.md`：本地 H2 SQL 工作台 smoke 流程；
- `release-center.md`：P2 发布中心非生产受控变更的启用、验证、回滚和人工接管边界。
