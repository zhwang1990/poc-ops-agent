# 仓库工具

仓库内的开发和 CI 工具保存在此目录。

工具必须可重复执行、有明确文档，并且默认安全。工具不得静默访问生产系统、凭据或外部网络。

## CI 工具

- `tools/ci/check-repository.ps1`：检查必要文件和禁止提交的敏感文件。
- `tools/ci/scan-secrets.ps1`：扫描高置信度密钥模式。
- `tools/ci/collect-artifacts.ps1`：收集 Maven 测试报告、构建产物和构建元数据。

## 发布打包工具

- `tools/release/package-release.mjs`：使用 Node.js 编排前端 Vite 构建、后端 Maven 构建和统一发布包生成；发布脚本不使用 PowerShell。
- `tools/release/release-packaging.mjs`：可测试的发布包组装模块。
- `tools/release/test-release-packaging.mjs`：发布包组装模块测试，不触发真实 npm 或 Maven 构建。

本地快速验证：

```bash
node tools/release/test-release-packaging.mjs
node tools/release/package-release.mjs --skip-tests
```
