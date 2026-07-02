# 发布中心最小执行闭环 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让发布中心具备首个可用的非生产发布闭环：上传 WAR、登记服务器、创建发布单、确认和执行发布单。

**Architecture:** 沿用现有 `control-plane-release` 领域对象、`ReleaseWorkflowService` 状态机和 Worker 发布适配器，不引入新服务。控制面新增发布单与制品查询/持久化 API，前端只调用控制面，不直接调用目标系统；执行仍限制在 `dev/sit/uat`，其中 `sit/uat` 保持服务端确认要求。

**Tech Stack:** Java 21、Spring Boot WebFlux、R2DBC、React/JSX、JSDoc、Zod、Vitest。

---

## Task 1: 后端发布单 API

**Files:**
- Modify: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/ReleaseCatalogStore.java`
- Modify: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/InMemoryReleaseCatalogStore.java`
- Modify: `backend/control-plane/modules/release/src/main/java/com/company/opsagent/controlplane/modules/release/R2dbcReleaseCatalogStore.java`
- Modify: `backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/api/ReleaseCenterController.java`
- Test: `backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/api/ReleaseCenterControllerTest.java`

- [ ] 写失败测试：创建 dev 发布单返回 `DRAFT`，执行后变为终态；`sit` 发布单返回 `WAIT_CONFIRM`，哈希不匹配确认被拒绝。
- [ ] 运行 `cd backend; .\mvnw.cmd -pl control-plane/bootstrap -Dtest=ReleaseCenterControllerTest test`，确认新增测试先失败。
- [ ] 为 release catalog 增加 artifact 查询、plan 保存、plan 查询和 plan 列表。
- [ ] 在 Controller 增加 `GET /artifacts`、`POST /servers`、`POST /plans`、`POST /plans/{id}/confirm`、`POST /plans/{id}/execute`。
- [ ] 补齐发布中心策略动作映射和默认 RBAC。
- [ ] 运行同一后端测试，确认通过。

## Task 2: 前端发布操作

**Files:**
- Modify: `frontend/operator-console/src/schemas/release-center-schemas.js`
- Modify: `frontend/operator-console/src/api/release-center-api.js`
- Modify: `frontend/operator-console/src/features/release-center/use-release-center.js`
- Modify: `frontend/operator-console/src/features/release-center/ReleaseCenterPage.jsx`
- Test: `frontend/operator-console/src/features/release-center/ReleaseCenterPage.test.jsx`

- [ ] 写失败测试：页面可上传 WAR、创建 dev 发布单，并对 `DRAFT` 发布单显示可点击执行按钮。
- [ ] 运行 `cd frontend/operator-console; npm test -- ReleaseCenterPage.test.jsx`，确认新增测试先失败。
- [ ] 增加 artifact 查询 schema/API/hook。
- [ ] 接通上传、创建、确认、执行按钮；按钮只在服务端状态允许时启用。
- [ ] 运行同一前端测试，确认通过。

## Task 3: 验证

**Files:**
- No production files unless verification exposes a focused bug.

- [ ] 运行 `cd backend; .\mvnw.cmd -pl control-plane/bootstrap -Dtest=ReleaseCenterControllerTest test`。
- [ ] 运行 `cd frontend/operator-console; npm test -- ReleaseCenterPage.test.jsx`。
- [ ] 如时间允许，运行发布中心相关后端 release 模块测试：`cd backend; .\mvnw.cmd -pl control-plane/modules/release test`。
