/**
 * @typedef {"frontend" | "backend"} ThirdPartyDeliveryUnit
 */

/**
 * @typedef {Readonly<{
 *   id: string,
 *   name: string,
 *   packageName: string,
 *   version: string,
 *   deliveryUnit: ThirdPartyDeliveryUnit,
 *   deliveryUnitLabel: string,
 *   moduleArea: string,
 *   license: string,
 *   licenseUrl: string,
 *   copyright: string,
 *   notice: string,
 *   usage: string,
 *   homepage: string,
 * }>} ThirdPartyLicenseDeclaration
 */

/**
 * @typedef {Readonly<{
 *   count: number,
 *   id: ThirdPartyDeliveryUnit,
 *   label: string,
 * }>} ThirdPartyDeliveryUnitSummary
 */

/**
 * @typedef {Readonly<{
 *   componentCount: number,
 *   deliveryUnits: readonly ThirdPartyDeliveryUnitSummary[],
 *   licenseTypes: readonly string[],
 * }>} ThirdPartyLicensesSummary
 */

const MIT_LICENSE = "MIT License";
const ISC_LICENSE = "ISC License";
const APACHE_LICENSE = "Apache License 2.0";
const H2_DUAL_LICENSE = "MPL 2.0 / EPL 1.0";
const IBM_PUBLIC_LICENSE = "IBM Public License 1.0";

const MIT_LICENSE_URL = "https://opensource.org/license/mit";
const ISC_LICENSE_URL = "https://opensource.org/license/isc-license-txt";
const APACHE_LICENSE_URL = "https://www.apache.org/licenses/LICENSE-2.0";
const H2_LICENSE_URL = "https://h2database.com/html/license.html";
const IBM_PUBLIC_LICENSE_URL = "https://github.com/IBM/JTOpen/raw/main/license.html";

/** @type {Readonly<Record<ThirdPartyDeliveryUnit, string>>} */
const DELIVERY_UNIT_LABELS = Object.freeze({
  frontend: "前端操作台",
  backend: "后端服务",
});

/** @type {readonly ThirdPartyDeliveryUnit[]} */
const DELIVERY_UNIT_ORDER = Object.freeze(["frontend", "backend"]);

/**
 * @param {string} copyright
 * @returns {string}
 */
function buildMitNotice(copyright) {
  return `${MIT_LICENSE}

${copyright}

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.`;
}

/**
 * @param {string} copyright
 * @returns {string}
 */
function buildIscNotice(copyright) {
  return `${ISC_LICENSE}

${copyright}

Permission to use, copy, modify, and/or distribute this software for any
purpose with or without fee is hereby granted, provided that the above
copyright notice and this permission notice appear in all copies.

THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL WARRANTIES
WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED WARRANTIES OF
MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL THE AUTHOR BE LIABLE FOR
ANY SPECIAL, DIRECT, INDIRECT, OR CONSEQUENTIAL DAMAGES OR ANY DAMAGES
WHATSOEVER RESULTING FROM LOSS OF USE, DATA OR PROFITS, WHETHER IN AN
ACTION OF CONTRACT, NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF
OR IN CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.`;
}

/**
 * @param {string} license
 * @param {string} copyright
 * @param {string} licenseUrl
 * @returns {string}
 */
function buildReferencedNotice(license, copyright, licenseUrl) {
  return `${license}

${copyright}

许可证全文：
${licenseUrl}

本组件随平台分发时保留上述许可证名称、版权归属和原始许可证链接。`;
}

/**
 * @param {Omit<ThirdPartyLicenseDeclaration, "deliveryUnitLabel">} declaration
 * @returns {ThirdPartyLicenseDeclaration}
 */
function declareComponent(declaration) {
  return Object.freeze({
    ...declaration,
    deliveryUnitLabel: DELIVERY_UNIT_LABELS[declaration.deliveryUnit],
  });
}

const codeMirrorCopyright =
  "Copyright (C) 2018-2021 by Marijn Haverbeke <marijn@haverbeke.berlin> and others";
const springCopyright = "Copyright (c) Spring contributors.";
const apacheSoftwareFoundationCopyright = "Copyright (c) The Apache Software Foundation.";

/** @type {readonly ThirdPartyLicenseDeclaration[]} */
export const thirdPartyLicenses = Object.freeze([
  declareComponent({
    id: "codemirror-lang-sql",
    name: "CodeMirror SQL Language",
    packageName: "@codemirror/lang-sql",
    version: "6.10.0",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: MIT_LICENSE,
    licenseUrl: MIT_LICENSE_URL,
    copyright: codeMirrorCopyright,
    notice: buildMitNotice(codeMirrorCopyright),
    usage: "用于 SQL 工作台编辑器的 SQL 语法解析和高亮。",
    homepage: "https://codemirror.net/",
  }),
  declareComponent({
    id: "codemirror-language",
    name: "CodeMirror Language",
    packageName: "@codemirror/language",
    version: "6.12.4",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: MIT_LICENSE,
    licenseUrl: MIT_LICENSE_URL,
    copyright: codeMirrorCopyright,
    notice: buildMitNotice(codeMirrorCopyright),
    usage: "用于 SQL 编辑器语言状态、缩进和语法服务。",
    homepage: "https://codemirror.net/",
  }),
  declareComponent({
    id: "codemirror-state",
    name: "CodeMirror State",
    packageName: "@codemirror/state",
    version: "6.7.0",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: MIT_LICENSE,
    licenseUrl: MIT_LICENSE_URL,
    copyright: codeMirrorCopyright,
    notice: buildMitNotice(codeMirrorCopyright),
    usage: "用于 SQL 编辑器文档状态和扩展状态管理。",
    homepage: "https://codemirror.net/",
  }),
  declareComponent({
    id: "codemirror-view",
    name: "CodeMirror View",
    packageName: "@codemirror/view",
    version: "6.43.4",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: MIT_LICENSE,
    licenseUrl: MIT_LICENSE_URL,
    copyright: codeMirrorCopyright,
    notice: buildMitNotice(codeMirrorCopyright),
    usage: "用于 SQL 工作台编辑器视图渲染、选择和交互。",
    homepage: "https://codemirror.net/",
  }),
  declareComponent({
    id: "lezer-highlight",
    name: "Lezer Highlight",
    packageName: "@lezer/highlight",
    version: "1.2.3",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: MIT_LICENSE,
    licenseUrl: MIT_LICENSE_URL,
    copyright: "Copyright (C) 2018 by Marijn Haverbeke <marijn@haverbeke.berlin> and others",
    notice: buildMitNotice(
      "Copyright (C) 2018 by Marijn Haverbeke <marijn@haverbeke.berlin> and others",
    ),
    usage: "用于 CodeMirror 语法树高亮样式映射。",
    homepage: "https://lezer.codemirror.net/",
  }),
  declareComponent({
    id: "tanstack-react-query",
    name: "TanStack React Query",
    packageName: "@tanstack/react-query",
    version: "5.101.0",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: MIT_LICENSE,
    licenseUrl: MIT_LICENSE_URL,
    copyright: "Copyright (c) 2021-present Tanner Linsley",
    notice: buildMitNotice("Copyright (c) 2021-present Tanner Linsley"),
    usage: "用于操作台服务端状态缓存、请求生命周期和数据同步。",
    homepage: "https://tanstack.com/query",
  }),
  declareComponent({
    id: "codemirror",
    name: "CodeMirror",
    packageName: "codemirror",
    version: "6.0.2",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: MIT_LICENSE,
    licenseUrl: MIT_LICENSE_URL,
    copyright: codeMirrorCopyright,
    notice: buildMitNotice(codeMirrorCopyright),
    usage: "用于组合 SQL 工作台编辑器基础能力。",
    homepage: "https://codemirror.net/",
  }),
  declareComponent({
    id: "jsonrepair",
    name: "jsonrepair",
    packageName: "jsonrepair",
    version: "3.15.0",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: ISC_LICENSE,
    licenseUrl: ISC_LICENSE_URL,
    copyright: "Copyright (c) Jos de Jong",
    notice: buildIscNotice("Copyright (c) Jos de Jong"),
    usage: "用于 JSON Formatter 在浏览器本地修补常见 JSON 语法问题，不发送输入内容。",
    homepage: "https://github.com/josdejong/jsonrepair",
  }),
  declareComponent({
    id: "lucide-react",
    name: "Lucide React",
    packageName: "lucide-react",
    version: "1.18.0",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: ISC_LICENSE,
    licenseUrl: ISC_LICENSE_URL,
    copyright: "Copyright (c) 2026 Lucide Icons and Contributors",
    notice: `${buildIscNotice("Copyright (c) 2026 Lucide Icons and Contributors")}

Lucide 包含来源于 Feather 项目的图标；相关图标按 MIT License 保留：
${buildMitNotice("Copyright (c) 2013-present Cole Bemis")}`,
    usage: "用于操作台按钮、状态、导航和声明页图标。",
    homepage: "https://lucide.dev/",
  }),
  declareComponent({
    id: "react",
    name: "React",
    packageName: "react",
    version: "19.2.7",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: MIT_LICENSE,
    licenseUrl: MIT_LICENSE_URL,
    copyright: "Copyright (c) Meta Platforms, Inc. and affiliates.",
    notice: buildMitNotice("Copyright (c) Meta Platforms, Inc. and affiliates."),
    usage: "用于操作台单页应用的界面渲染、组件组合和状态驱动更新。",
    homepage: "https://react.dev/",
  }),
  declareComponent({
    id: "react-dom",
    name: "React DOM",
    packageName: "react-dom",
    version: "19.2.7",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: MIT_LICENSE,
    licenseUrl: MIT_LICENSE_URL,
    copyright: "Copyright (c) Meta Platforms, Inc. and affiliates.",
    notice: buildMitNotice("Copyright (c) Meta Platforms, Inc. and affiliates."),
    usage: "用于把 React 操作台界面挂载到浏览器 DOM。",
    homepage: "https://react.dev/",
  }),
  declareComponent({
    id: "react-markdown",
    name: "React Markdown",
    packageName: "react-markdown",
    version: "10.1.0",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: MIT_LICENSE,
    licenseUrl: MIT_LICENSE_URL,
    copyright: "Copyright (c) Espen Hovlandsdal",
    notice: buildMitNotice("Copyright (c) Espen Hovlandsdal"),
    usage: "用于操作台中受控 Markdown 内容渲染。",
    homepage: "https://github.com/remarkjs/react-markdown",
  }),
  declareComponent({
    id: "react-router-dom",
    name: "React Router DOM",
    packageName: "react-router-dom",
    version: "7.17.0",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: MIT_LICENSE,
    licenseUrl: MIT_LICENSE_URL,
    copyright:
      "Copyright (c) React Training LLC 2015-2019; Copyright (c) Remix Software Inc. 2020-2021; Copyright (c) Shopify Inc. 2022-2023.",
    notice: buildMitNotice(
      "Copyright (c) React Training LLC 2015-2019; Copyright (c) Remix Software Inc. 2020-2021; Copyright (c) Shopify Inc. 2022-2023.",
    ),
    usage: "用于操作台浏览器路由、受保护页面导航和页面状态切换。",
    homepage: "https://reactrouter.com/",
  }),
  declareComponent({
    id: "remark-gfm",
    name: "Remark GFM",
    packageName: "remark-gfm",
    version: "4.0.1",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: MIT_LICENSE,
    licenseUrl: MIT_LICENSE_URL,
    copyright: "Copyright (c) Titus Wormer <tituswormer@gmail.com>",
    notice: buildMitNotice("Copyright (c) Titus Wormer <tituswormer@gmail.com>"),
    usage: "用于 Markdown 表格、任务列表等 GitHub Flavored Markdown 支持。",
    homepage: "https://github.com/remarkjs/remark-gfm",
  }),
  declareComponent({
    id: "zod",
    name: "Zod",
    packageName: "zod",
    version: "4.4.3",
    deliveryUnit: "frontend",
    moduleArea: "前端操作台",
    license: MIT_LICENSE,
    licenseUrl: MIT_LICENSE_URL,
    copyright: "Copyright (c) 2025 Colin McDonnell",
    notice: buildMitNotice("Copyright (c) 2025 Colin McDonnell"),
    usage: "用于 API、SSE 和前端外部边界数据的运行时 Schema 校验。",
    homepage: "https://zod.dev/",
  }),
  declareComponent({
    id: "spring-boot-starter-actuator",
    name: "Spring Boot Actuator Starter",
    packageName: "org.springframework.boot:spring-boot-starter-actuator",
    version: "3.4.13",
    deliveryUnit: "backend",
    moduleArea: "控制面",
    license: APACHE_LICENSE,
    licenseUrl: APACHE_LICENSE_URL,
    copyright: springCopyright,
    notice: buildReferencedNotice(APACHE_LICENSE, springCopyright, APACHE_LICENSE_URL),
    usage: "用于控制面健康检查、运行状态和可观测性端点基础能力。",
    homepage: "https://spring.io/projects/spring-boot",
  }),
  declareComponent({
    id: "spring-boot-starter-data-r2dbc",
    name: "Spring Boot Data R2DBC Starter",
    packageName: "org.springframework.boot:spring-boot-starter-data-r2dbc",
    version: "3.4.13",
    deliveryUnit: "backend",
    moduleArea: "控制面",
    license: APACHE_LICENSE,
    licenseUrl: APACHE_LICENSE_URL,
    copyright: springCopyright,
    notice: buildReferencedNotice(APACHE_LICENSE, springCopyright, APACHE_LICENSE_URL),
    usage: "用于控制面 R2DBC 数据访问和响应式持久化集成。",
    homepage: "https://spring.io/projects/spring-boot",
  }),
  declareComponent({
    id: "spring-boot-starter-oauth2-client",
    name: "Spring Boot OAuth2 Client Starter",
    packageName: "org.springframework.boot:spring-boot-starter-oauth2-client",
    version: "3.4.13",
    deliveryUnit: "backend",
    moduleArea: "控制面",
    license: APACHE_LICENSE,
    licenseUrl: APACHE_LICENSE_URL,
    copyright: springCopyright,
    notice: buildReferencedNotice(APACHE_LICENSE, springCopyright, APACHE_LICENSE_URL),
    usage: "用于浏览器登录场景下的 OIDC / OAuth2 Client 集成。",
    homepage: "https://spring.io/projects/spring-boot",
  }),
  declareComponent({
    id: "spring-boot-starter-oauth2-resource-server",
    name: "Spring Boot OAuth2 Resource Server Starter",
    packageName: "org.springframework.boot:spring-boot-starter-oauth2-resource-server",
    version: "3.4.13",
    deliveryUnit: "backend",
    moduleArea: "控制面",
    license: APACHE_LICENSE,
    licenseUrl: APACHE_LICENSE_URL,
    copyright: springCopyright,
    notice: buildReferencedNotice(APACHE_LICENSE, springCopyright, APACHE_LICENSE_URL),
    usage: "用于控制面 JWT 和资源服务器鉴权集成。",
    homepage: "https://spring.io/projects/spring-boot",
  }),
  declareComponent({
    id: "spring-boot-starter-security",
    name: "Spring Boot Security Starter",
    packageName: "org.springframework.boot:spring-boot-starter-security",
    version: "3.4.13",
    deliveryUnit: "backend",
    moduleArea: "控制面",
    license: APACHE_LICENSE,
    licenseUrl: APACHE_LICENSE_URL,
    copyright: springCopyright,
    notice: buildReferencedNotice(APACHE_LICENSE, springCopyright, APACHE_LICENSE_URL),
    usage: "用于控制面认证、会话和安全过滤链基础能力。",
    homepage: "https://spring.io/projects/spring-boot",
  }),
  declareComponent({
    id: "spring-boot-starter-validation",
    name: "Spring Boot Validation Starter",
    packageName: "org.springframework.boot:spring-boot-starter-validation",
    version: "3.4.13",
    deliveryUnit: "backend",
    moduleArea: "控制面 / 执行 Worker",
    license: APACHE_LICENSE,
    licenseUrl: APACHE_LICENSE_URL,
    copyright: springCopyright,
    notice: buildReferencedNotice(APACHE_LICENSE, springCopyright, APACHE_LICENSE_URL),
    usage: "用于后端 API、命令和配置对象的 Bean Validation 集成。",
    homepage: "https://spring.io/projects/spring-boot",
  }),
  declareComponent({
    id: "spring-boot-starter-webflux",
    name: "Spring Boot WebFlux Starter",
    packageName: "org.springframework.boot:spring-boot-starter-webflux",
    version: "3.4.13",
    deliveryUnit: "backend",
    moduleArea: "控制面 / 执行 Worker",
    license: APACHE_LICENSE,
    licenseUrl: APACHE_LICENSE_URL,
    copyright: springCopyright,
    notice: buildReferencedNotice(APACHE_LICENSE, springCopyright, APACHE_LICENSE_URL),
    usage: "用于控制面和执行 Worker 的响应式 HTTP 服务能力。",
    homepage: "https://spring.io/projects/spring-boot",
  }),
  declareComponent({
    id: "springdoc-openapi-webflux-ui",
    name: "SpringDoc OpenAPI WebFlux UI",
    packageName: "org.springdoc:springdoc-openapi-starter-webflux-ui",
    version: "2.8.13",
    deliveryUnit: "backend",
    moduleArea: "控制面",
    license: APACHE_LICENSE,
    licenseUrl: APACHE_LICENSE_URL,
    copyright: "Copyright (c) springdoc-openapi contributors.",
    notice: buildReferencedNotice(
      APACHE_LICENSE,
      "Copyright (c) springdoc-openapi contributors.",
      APACHE_LICENSE_URL,
    ),
    usage: "用于控制面 OpenAPI 文档和 WebFlux API 文档界面。",
    homepage: "https://springdoc.org/",
  }),
  declareComponent({
    id: "r2dbc-h2",
    name: "R2DBC H2",
    packageName: "io.r2dbc:r2dbc-h2",
    version: "1.0.1.RELEASE",
    deliveryUnit: "backend",
    moduleArea: "控制面",
    license: APACHE_LICENSE,
    licenseUrl: APACHE_LICENSE_URL,
    copyright: "Copyright (c) R2DBC H2 contributors.",
    notice: buildReferencedNotice(APACHE_LICENSE, "Copyright (c) R2DBC H2 contributors.", APACHE_LICENSE_URL),
    usage: "用于控制面本地和测试环境的 H2 响应式数据库连接。",
    homepage: "https://github.com/r2dbc/r2dbc-h2",
  }),
  declareComponent({
    id: "agentscope-java",
    name: "AgentScope Java",
    packageName: "io.agentscope:agentscope",
    version: "2.0.0-RC4",
    deliveryUnit: "backend",
    moduleArea: "控制面 Agent Runtime",
    license: APACHE_LICENSE,
    licenseUrl: APACHE_LICENSE_URL,
    copyright: "Copyright (c) Alibaba and AgentScope contributors.",
    notice: buildReferencedNotice(
      APACHE_LICENSE,
      "Copyright (c) Alibaba and AgentScope contributors.",
      APACHE_LICENSE_URL,
    ),
    usage: "用于 M04 主 Agent Runtime 的 ReAct 循环和工具回调集成。",
    homepage: "https://github.com/agentscope-ai/agentscope-java",
  }),
  declareComponent({
    id: "reactor-core",
    name: "Project Reactor Core",
    packageName: "io.projectreactor:reactor-core",
    version: "3.7.14",
    deliveryUnit: "backend",
    moduleArea: "控制面",
    license: APACHE_LICENSE,
    licenseUrl: APACHE_LICENSE_URL,
    copyright: "Copyright (c) Project Reactor contributors.",
    notice: buildReferencedNotice(APACHE_LICENSE, "Copyright (c) Project Reactor contributors.", APACHE_LICENSE_URL),
    usage: "用于控制面响应式流、异步编排和后端运行时组合。",
    homepage: "https://projectreactor.io/",
  }),
  declareComponent({
    id: "spring-r2dbc",
    name: "Spring R2DBC",
    packageName: "org.springframework:spring-r2dbc",
    version: "6.2.15",
    deliveryUnit: "backend",
    moduleArea: "控制面",
    license: APACHE_LICENSE,
    licenseUrl: APACHE_LICENSE_URL,
    copyright: springCopyright,
    notice: buildReferencedNotice(APACHE_LICENSE, springCopyright, APACHE_LICENSE_URL),
    usage: "用于控制面工作流、身份和配置数据的响应式数据库访问。",
    homepage: "https://spring.io/projects/spring-framework",
  }),
  declareComponent({
    id: "jackson-databind",
    name: "Jackson Databind",
    packageName: "com.fasterxml.jackson.core:jackson-databind",
    version: "2.18.5",
    deliveryUnit: "backend",
    moduleArea: "控制面 / 执行 Worker",
    license: APACHE_LICENSE,
    licenseUrl: APACHE_LICENSE_URL,
    copyright: "Copyright (c) FasterXML, LLC.",
    notice: buildReferencedNotice(APACHE_LICENSE, "Copyright (c) FasterXML, LLC.", APACHE_LICENSE_URL),
    usage: "用于后端 JSON 数据绑定、命令信封和事件载荷序列化。",
    homepage: "https://github.com/FasterXML/jackson-databind",
  }),
  declareComponent({
    id: "apache-calcite",
    name: "Apache Calcite",
    packageName: "org.apache.calcite:calcite-core",
    version: "1.42.0",
    deliveryUnit: "backend",
    moduleArea: "SQL 工作台",
    license: APACHE_LICENSE,
    licenseUrl: APACHE_LICENSE_URL,
    copyright: apacheSoftwareFoundationCopyright,
    notice: buildReferencedNotice(APACHE_LICENSE, apacheSoftwareFoundationCopyright, APACHE_LICENSE_URL),
    usage: "用于 SQL 工作台 AST 解析、语句分类和只读边界校验。",
    homepage: "https://calcite.apache.org/",
  }),
  declareComponent({
    id: "jt400",
    name: "JT400",
    packageName: "net.sf.jt400:jt400",
    version: "21.0.6",
    deliveryUnit: "backend",
    moduleArea: "执行 Worker",
    license: IBM_PUBLIC_LICENSE,
    licenseUrl: IBM_PUBLIC_LICENSE_URL,
    copyright: "Copyright (c) IBM and JTOpen contributors.",
    notice: buildReferencedNotice(IBM_PUBLIC_LICENSE, "Copyright (c) IBM and JTOpen contributors.", IBM_PUBLIC_LICENSE_URL),
    usage: "用于执行 Worker SQL 工作台适配 AS/400 连接能力。",
    homepage: "https://github.com/IBM/JTOpen",
  }),
  declareComponent({
    id: "h2-database",
    name: "H2 Database",
    packageName: "com.h2database:h2",
    version: "2.3.232",
    deliveryUnit: "backend",
    moduleArea: "执行 Worker",
    license: H2_DUAL_LICENSE,
    licenseUrl: H2_LICENSE_URL,
    copyright: "Copyright (c) H2 Database contributors.",
    notice: buildReferencedNotice(H2_DUAL_LICENSE, "Copyright (c) H2 Database contributors.", H2_LICENSE_URL),
    usage: "用于本地和测试环境的嵌入式数据库能力。",
    homepage: "https://h2database.com/",
  }),
]);

/** @type {readonly string[]} */
const licenseTypes = Object.freeze([...new Set(thirdPartyLicenses.map(({ license }) => license))].sort());

/** @type {readonly ThirdPartyDeliveryUnitSummary[]} */
const deliveryUnits = Object.freeze(
  DELIVERY_UNIT_ORDER.map((id) =>
    Object.freeze({
      count: thirdPartyLicenses.filter((declaration) => declaration.deliveryUnit === id).length,
      id,
      label: DELIVERY_UNIT_LABELS[id],
    }),
  ),
);

/** @type {ThirdPartyLicensesSummary} */
export const thirdPartyLicensesSummary = Object.freeze({
  componentCount: thirdPartyLicenses.length,
  deliveryUnits,
  licenseTypes,
});

/**
 * @param {string} id
 * @returns {ThirdPartyLicenseDeclaration | undefined}
 */
export function findThirdPartyLicenseById(id) {
  return thirdPartyLicenses.find((declaration) => declaration.id === id);
}
