# 前后端统一发布打包实现计划

> **面向自动化执行者：** 实现时按任务逐项执行，并使用复选框跟踪进度。所有发布脚本必须使用 Node.js，不得新增 PowerShell 发布脚本。

**目标：** 生成前端内嵌控制面 JAR，并生成包含控制面、执行 Worker、前端静态快照、清单、校验和和启动脚本的发布 ZIP。

**架构：** 使用 Node.js ESM 实现发布脚本和脚本测试；使用 Maven Profile 在构建期把 Vite `dist` 复制到控制面 JAR 的 `static/` classpath；使用 JDK `jar` 工具生成 ZIP 格式发布包。脚本只做本地构建和制品组装，不连接目标环境。

**技术栈：** Node.js ESM、npm、Vite、Java 21、Maven Wrapper、Spring Boot Maven Plugin、JDK `jar`。

---

### 任务 1：编写 Node 打包模块测试

**文件：**
- 新增：`tools/release/test-release-packaging.mjs`
- 后续新增：`tools/release/release-packaging.mjs`

- [x] **步骤 1：写失败测试**

测试使用临时目录构造假前端 `dist`、控制面 JAR 和 Worker JAR，并断言：

- 缺少目录时失败关闭；
- 能定位唯一 JAR；
- 发布清单包含控制面 JAR、Worker JAR、前端 `index.html`、控制面启动脚本和 Worker 启动脚本；
- 校验和为 SHA-256；
- 能生成 ZIP。

- [x] **步骤 2：运行测试确认失败**

命令：

```bash
node tools/release/test-release-packaging.mjs
```

预期：因 `release-packaging.mjs` 尚未存在而失败。

### 任务 2：增加 Maven 前端静态资源内嵌 Profile

**文件：**
- 修改：`backend/control-plane/bootstrap/pom.xml`
- 修改：`backend/execution-worker/pom.xml`

- [x] **步骤 1：给控制面增加 `include-operator-console` Profile**

该 Profile 只在显式传入 `-Dops-agent.include-operator-console=true` 时生效，通过 `maven-resources-plugin` 把 `${ops-agent.operator-console.dist}` 复制到 `${project.build.outputDirectory}/static`。

- [x] **步骤 2：绑定 Spring Boot `repackage`**

控制面和执行 Worker 都绑定 `spring-boot-maven-plugin:repackage`，确保发布包内的后端 JAR 可以通过 `java -jar` 运行。

### 任务 3：实现 Node 打包模块和入口脚本

**文件：**
- 新增：`tools/release/release-packaging.mjs`
- 新增：`tools/release/package-release.mjs`

- [x] **步骤 1：实现可测试模块**

模块导出：

- `assertDirectoryExists`
- `assertFileExists`
- `findSingleJar`
- `getSha256Hex`
- `runCommand`
- `buildReleasePackage`

内部能力包括白名单复制、发布目录初始化、启动脚本生成、清单生成、校验和生成、禁止内容检查和 ZIP 生成。

- [x] **步骤 2：实现 CLI**

入口命令：

```bash
node tools/release/package-release.mjs
```

支持参数：

```text
--version <value>
--artifact-root <path>
--publish-dir <path>
--skip-tests
--skip-frontend-install
```

默认构建序列：

```text
npm ci
npm run build
backend/mvnw(.cmd) -f backend/pom.xml -B -ntp -Dops-agent.include-operator-console=true -Dops-agent.operator-console.dist=<dist> verify
```

`--skip-tests` 用于本地快速打包验证，会让 Maven 使用 `package -DskipTests`。

- [x] **步骤 3：运行模块测试确认通过**

命令：

```bash
node tools/release/test-release-packaging.mjs
```

预期：输出 `Release packaging tests passed.`

### 任务 4：更新工具文档和仓库检查

**文件：**
- 修改：`tools/README.md`
- 修改：`tools/ci/check-repository.ps1`

- [x] **步骤 1：记录发布打包命令**

在 `tools/README.md` 中说明 Node 发布脚本入口、模块测试入口，以及发布脚本不使用 PowerShell。

- [x] **步骤 2：纳入仓库基线检查**

在 `tools/ci/check-repository.ps1` 的必备路径中加入：

- `tools/release/package-release.mjs`
- `tools/release/release-packaging.mjs`
- `tools/release/test-release-packaging.mjs`

### 任务 5：验证

**文件：**
- 无新增文件。

- [x] **步骤 1：运行发布脚本测试**

命令：

```bash
node tools/release/test-release-packaging.mjs
```

预期：退出码为 0。

- [x] **步骤 2：构建发布包**

命令：

```bash
node tools/release/package-release.mjs --skip-tests --skip-frontend-install
```

预期：在 `artifacts/release/` 下生成发布目录和 ZIP。

- [x] **步骤 3：验证发布包内容**

检查项：

- 发布目录内控制面 JAR 包含 `BOOT-INF/classes/static/index.html`；
- 发布目录内 Worker JAR 包含 Spring Boot loader；
- ZIP 包含控制面 JAR、Worker JAR、前端 `index.html`、清单、校验和、控制面启动脚本和 Worker 启动脚本；
- ZIP 不包含 `node_modules`、日志、`.env` 或密钥类文件。

- [x] **步骤 4：运行仓库检查和密钥扫描**

命令：

```powershell
tools/ci/check-repository.ps1
tools/ci/scan-secrets.ps1
```

预期：两者均通过。这里复用已有 CI 检查脚本；新增发布脚本仍全部为 Node.js。

- [x] **步骤 5：运行后端完整验证**

命令：

```powershell
backend/mvnw.cmd -f backend/pom.xml -B -ntp verify
```

预期：后端 Maven 全量验证通过。
