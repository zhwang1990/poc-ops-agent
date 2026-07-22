# Skill 源包工具

本目录提供 P1 Skill 源包到 M03 平台契约包的生成工具。

开发者维护：

```text
backend/skills/<skill>/
|-- SKILL.md
|-- skill.package.yaml
|-- schemas/
|   |-- input.schema.json
|   `-- output.schema.json
`-- examples/
    |-- happy-path.json
    |-- invalid-parameters.json
    `-- policy-denied.json
```

生成产物位于：

```text
backend/contracts/skills/packages/<skill>/
```

常用命令：

```powershell
tools/skills/skill-package-tool.ps1 validate backend/skills/weather-current
tools/skills/skill-package-tool.ps1 generate backend/skills/weather-current
tools/skills/skill-package-tool.ps1 validate-all
tools/skills/skill-package-tool.ps1 generate-all --check
```

`generate` 与 `generate-all` 必须由安全密钥源向当前进程注入
`OPS_AGENT_SKILL_REGISTRY_SIGNING_SECRET`。该变量没有默认值，不得写入源码、脚本、文档、测试数据或环境示例；
`validate` 与 `validate-all` 不读取签名密钥。

P1 源包只能表达只读诊断能力。源包不得包含 endpoint、凭据、allowlist、脚本、命令、生产数据或写执行能力。真实网络出口、目标系统凭据和环境配置归 M07 Worker 配置管理。
