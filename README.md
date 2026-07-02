# 企业级智能运维 Agent 开放底座

本仓库是企业级智能运维 Agent 开放底座的研发工作区。

项目首先建设只读诊断 MVP，随后引入受控的低风险变更能力，最后完成生产加固和上线交付。

## 开始阅读

- 全局工程规范：[AGENTS.md](AGENTS.md)
- 模块地图：[docs/architecture/module-map.md](docs/architecture/module-map.md)
- 项目术语解释：[docs/architecture/terminology-glossary.md](docs/architecture/terminology-glossary.md)
- 项目计划：[docs/planning/project-plan.md](docs/planning/project-plan.md)
- 设计范围追溯：[docs/planning/design-traceability.md](docs/planning/design-traceability.md)
- 研发规范：[docs/standards/development.md](docs/standards/development.md)
- 架构决策记录：[docs/adr/README.md](docs/adr/README.md)

## 交付单元

- `frontend/operator-console`：操作、审批和人工接管界面。
- `backend/control-plane`：身份、策略、Skill、路由、工作流、DAG 编排和控制 API。
- `backend/execution-worker`：受限执行和安全隔离边界。
- `backend/skills`：版本化运维 Skill 和测试。
- `backend/contracts`：共享 API、事件、Skill 和工作流契约。
- `backend/deploy`：部署和运维配置。

## 当前阶段

阶段 P2：受控变更试点。

P1 只读诊断 MVP 已于 2026-07-01 完成里程碑验收。P2 仅推进非生产、低风险、可回滚的受控变更能力；生产写操作、任意脚本执行和绕过策略、审批、幂等、审计或隔离控制的路径仍明确不在范围内。

## 构建结构

仓库当前按三层组织：

- `frontend/`：前端应用
- `backend/`：后端代码、契约、Skill、部署配置和 Maven 根工程
- `docs/`：架构、规范、ADR、计划和运行手册

后端当前采用标准 Maven 多模块结构：

- `backend/pom.xml`：后端聚合父工程
- `backend/control-plane`：控制面聚合父模块
- `backend/control-plane/bootstrap`：控制面入口模块
- `backend/control-plane/modules/*`：控制面业务子模块
- `backend/execution-worker`：独立 Worker 模块

## 本地验证

需要 Java 21，并设置 `JAVA_HOME`。仓库使用 Maven Wrapper，不要求预先安装 Maven：

```powershell
Set-Location .\backend
.\mvnw.cmd -f .\pom.xml -B -ntp verify
Set-Location ..
./tools/ci/check-repository.ps1
./tools/ci/scan-secrets.ps1
./tools/ci/collect-artifacts.ps1
```

分支与评审规则见 `docs/standards/git-workflow.md`。

远程仓库和默认分支保护配置见 `docs/runbooks/repository-bootstrap.md`。
