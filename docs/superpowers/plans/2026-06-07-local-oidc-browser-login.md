# 本地模拟 OIDC 浏览器登录实现计划

> **供代理式执行者使用：** 必须先使用 `superpowers:subagent-driven-development`（推荐）或 `superpowers:executing-plans` 按任务逐项执行本计划。所有步骤使用复选框 `- [ ]` 进行跟踪。

**目标：** 构建仅用于本地联调的模拟 OIDC 授权码提供方，将操作台默认登录方式切换为浏览器会话，并完成本地登录与只读诊断链路的端到端验证。

**架构：** 控制面仍然是单进程本地服务。通过显式本地 profile 在 `control-plane-bootstrap` 内挂载模拟 OIDC Provider，并且仅在本地 OIDC 配置启用时暴露。前端继续只访问 `/auth/**` 与 `/internal/**`，所有登录状态以 `GET /auth/session` 返回值为唯一事实源。

**技术栈：** Java 21、Spring Boot WebFlux、Spring Security OAuth2 Client / Resource Server、React 19、TypeScript 5、Vite 6、Maven Surefire、npm build。

---

## 文件结构

### 后端文件

- 新建：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/LocalOidcProviderProperties.java`
  作用：承载按 profile 启用的本地模拟 OIDC 配置。
- 新建：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/LocalOidcProviderConfiguration.java`
  作用：仅在本地模拟 OIDC 启用时装配 RSA 密钥、授权码存储与相关 Bean。
- 新建：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/security/localoidc/LocalOidcKeyMaterial.java`
  作用：管理运行时生成的 RSA 密钥对与 JWK 序列化。
- 新建：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/security/localoidc/LocalOidcAuthorizationService.java`
  作用：签发短期授权码，并确保授权码只可交换一次令牌。
- 新建：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/api/LocalOidcProviderController.java`
  作用：暴露仅用于本地浏览器登录的发现文档、JWK、授权端点与令牌端点。
- 修改：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/SecurityConfiguration.java`
  作用：放行 `/mock-oidc/**`，并保持非本地 profile 下原有浏览器登录行为不变。
- 修改：`backend/control-plane/bootstrap/src/main/resources/application.yaml`
  作用：增加默认关闭的 `ops-agent.local-oidc-provider` 配置。
- 新建：`backend/control-plane/bootstrap/src/main/resources/application-local-oidc.yaml`
  作用：启用本地 OIDC 认证模式、浏览器登录与本地 client/provider 注册。
- 新建：`backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/LocalOidcProviderControllerTest.java`
  作用：验证发现文档、JWK、授权重定向与令牌交换。
- 新建：`backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/LocalOidcBrowserLoginIntegrationTest.java`
  作用：验证浏览器登录、会话读取、内部接口访问、权限拒绝与退出流程。

### 前端文件

- 修改：`frontend/operator-console/src/types.ts`
  作用：增加浏览器会话响应类型。
- 修改：`frontend/operator-console/src/api.ts`
  作用：增加 `fetchBrowserSession`，移除主诊断路径对手工 Bearer Token 的默认依赖，并保留 `credentials: "include"`。
- 修改：`frontend/operator-console/src/App.tsx`
  作用：切换到会话优先的 UI 与登录/退出流程。
- 修改：`frontend/operator-console/src/styles.css`
  作用：增加登录卡片、会话横幅和相关状态样式。

### 文档文件

- 修改：`docs/runbooks/local-oidc-mock-testing.md`
  作用：从仅 Token 验证更新为可运行的本地浏览器登录联调手册。
- 修改：`frontend/operator-console/README.md`
  作用：记录本地登录链路与前端开发服务器要求。

## 任务 1：实现本地模拟 OIDC Provider 基础能力

**文件：**
- 新建：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/LocalOidcProviderProperties.java`
- 新建：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/LocalOidcProviderConfiguration.java`
- 新建：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/security/localoidc/LocalOidcKeyMaterial.java`
- 新建：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/security/localoidc/LocalOidcAuthorizationService.java`
- 新建：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/api/LocalOidcProviderController.java`
- 新建：`backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/LocalOidcProviderControllerTest.java`

- [ ] **步骤 1：先写失败的 Provider 测试**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureWebTestClient
@TestPropertySource(properties = {
    "server.port=18080",
    "ops-agent.local-oidc-provider.enabled=true",
    "ops-agent.local-oidc-provider.issuer=http://127.0.0.1:18080/mock-oidc",
    "ops-agent.local-oidc-provider.client-id=ops-agent-local-client",
    "ops-agent.local-oidc-provider.client-secret=${OPS_AGENT_LOCAL_OIDC_CLIENT_SECRET}"
})
class LocalOidcProviderControllerTest {

  @Autowired
  private WebTestClient webTestClient;

  @Test
  void exposesDiscoveryDocument() {
    webTestClient.get()
        .uri("/mock-oidc/.well-known/openid-configuration")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.issuer").isEqualTo("http://127.0.0.1:18080/mock-oidc")
        .jsonPath("$.authorization_endpoint").isEqualTo("http://127.0.0.1:18080/mock-oidc/oauth2/authorize");
  }
}
```

- [ ] **步骤 2：运行测试，确认当前一定失败**

运行：

```powershell
cd backend
.\mvnw.cmd -f .\pom.xml -pl control-plane/bootstrap -am -Dtest=LocalOidcProviderControllerTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD FAILURE`，因为测试类与本地 OIDC Provider 代码尚不存在。

- [ ] **步骤 3：增加 Provider 属性对象与装配类**

```java
@ConfigurationProperties(prefix = "ops-agent.local-oidc-provider")
public record LocalOidcProviderProperties(
    boolean enabled,
    String issuer,
    String clientId,
    String clientSecret,
    String defaultSubject,
    String defaultUsername,
    List<String> defaultRoles,
    Duration authorizationCodeTtl,
    Duration tokenTtl) {
}
```

```java
@Configuration
@EnableConfigurationProperties(LocalOidcProviderProperties.class)
class LocalOidcProviderConfiguration {

  @Bean
  @ConditionalOnProperty(prefix = "ops-agent.local-oidc-provider", name = "enabled", havingValue = "true")
  LocalOidcKeyMaterial localOidcKeyMaterial() {
    return LocalOidcKeyMaterial.generate();
  }

  @Bean
  @ConditionalOnProperty(prefix = "ops-agent.local-oidc-provider", name = "enabled", havingValue = "true")
  LocalOidcAuthorizationService localOidcAuthorizationService(LocalOidcProviderProperties properties) {
    return new LocalOidcAuthorizationService(properties.authorizationCodeTtl(), properties.tokenTtl());
  }
}
```

- [ ] **步骤 4：实现 Provider 核心逻辑与控制器**

```java
public record LocalOidcAuthorization(
    String code,
    String clientId,
    String redirectUri,
    String nonce,
    String state,
    Instant expiresAt,
    String subject,
    String username,
    List<String> roles) {
}
```

```java
@RestController
@RequestMapping("/mock-oidc")
class LocalOidcProviderController {

  @GetMapping("/.well-known/openid-configuration")
  Map<String, Object> configuration(@Value("${ops-agent.local-oidc-provider.issuer}") String issuer) {
    return Map.of(
        "issuer", issuer,
        "authorization_endpoint", issuer + "/oauth2/authorize",
        "token_endpoint", issuer + "/oauth2/token",
        "jwks_uri", issuer + "/oauth2/jwks",
        "response_types_supported", List.of("code"),
        "subject_types_supported", List.of("public"),
        "id_token_signing_alg_values_supported", List.of("RS256"));
  }

  @GetMapping("/oauth2/authorize")
  Mono<ResponseEntity<Void>> authorize(
      @RequestParam("client_id") String clientId,
      @RequestParam("redirect_uri") String redirectUri,
      @RequestParam("state") String state,
      @RequestParam(name = "nonce", required = false) String nonce) {
    String code = authorizationService.issueCode(
        clientId,
        redirectUri,
        state,
        nonce,
        properties.defaultSubject(),
        properties.defaultUsername(),
        properties.defaultRoles());
    return Mono.just(ResponseEntity.status(HttpStatus.FOUND)
        .location(URI.create(redirectUri + "?code=" + code + "&state=" + UriUtils.encode(state, StandardCharsets.UTF_8)))
        .build());
  }
}
```

- [ ] **步骤 5：补充 JWK 与令牌交换测试**

```java
@Test
void exchangesAuthorizationCodeForTokens() {
  EntityExchangeResult<byte[]> authorizeResult = webTestClient.get()
      .uri(uriBuilder -> uriBuilder
          .path("/mock-oidc/oauth2/authorize")
          .queryParam("response_type", "code")
          .queryParam("client_id", "ops-agent-local-client")
          .queryParam("redirect_uri", "http://127.0.0.1/login/oauth2/code/ops-agent")
          .queryParam("scope", "openid profile")
          .queryParam("state", "state-001")
          .queryParam("nonce", "nonce-001")
          .build())
      .exchange()
      .expectStatus().isFound()
      .returnResult(byte[].class);

  String callback = authorizeResult.getResponseHeaders().getLocation().toString();
  String code = UriComponentsBuilder.fromUriString(callback).build().getQueryParams().getFirst("code");

  webTestClient.post()
      .uri("/mock-oidc/oauth2/token")
      .contentType(MediaType.APPLICATION_FORM_URLENCODED)
      // client_secret 仅通过 OPS_AGENT_LOCAL_OIDC_CLIENT_SECRET 在运行时注入。
      .exchange()
      .expectStatus().isOk()
      .expectBody()
      .jsonPath("$.id_token").exists()
      .jsonPath("$.access_token").exists()
      .jsonPath("$.token_type").isEqualTo("Bearer");
}
```

- [ ] **步骤 6：运行聚焦后的后端测试**

运行：

```powershell
cd backend
.\mvnw.cmd -f .\pom.xml -pl control-plane/bootstrap -am -Dtest=LocalOidcProviderControllerTest test
```

预期：`BUILD SUCCESS`，本地 Provider 的发现、JWK 与令牌交换测试通过。

- [ ] **步骤 7：提交这一组改动**

```powershell
git add backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/LocalOidcProviderProperties.java `
  backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/LocalOidcProviderConfiguration.java `
  backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/security/localoidc/LocalOidcKeyMaterial.java `
  backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/security/localoidc/LocalOidcAuthorizationService.java `
  backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/api/LocalOidcProviderController.java `
  backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/LocalOidcProviderControllerTest.java
git commit -m "Add local mock OIDC provider"
```

## 任务 2：接入本地 OIDC profile，并验证完整浏览器会话链路

**文件：**
- 修改：`backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/SecurityConfiguration.java`
- 修改：`backend/control-plane/bootstrap/src/main/resources/application.yaml`
- 新建：`backend/control-plane/bootstrap/src/main/resources/application-local-oidc.yaml`
- 新建：`backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/LocalOidcBrowserLoginIntegrationTest.java`

- [ ] **步骤 1：先写失败的浏览器登录集成测试**

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("local-oidc")
@TestPropertySource(properties = {
    "server.port=18080",
    "ops-agent.security.issuer=http://127.0.0.1:18080/mock-oidc",
    "ops-agent.security.issuer-uri=http://127.0.0.1:18080/mock-oidc",
    "ops-agent.local-oidc-provider.enabled=true",
    "ops-agent.local-oidc-provider.issuer=http://127.0.0.1:18080/mock-oidc",
    "ops-agent.local-oidc-provider.client-id=ops-agent-local-client",
    "ops-agent.local-oidc-provider.client-secret=${OPS_AGENT_LOCAL_OIDC_CLIENT_SECRET}"
})
class LocalOidcBrowserLoginIntegrationTest {

  @Autowired
  private WebTestClient webTestClient;

  @Test
  void establishesBrowserSessionViaLocalOidcProvider() {
    webTestClient.get()
        .uri("/auth/login")
        .exchange()
        .expectStatus().isFound()
        .expectHeader().valueMatches("Location", "http://127.0.0.1:18080/mock-oidc/oauth2/authorize.*");
  }
}
```

- [ ] **步骤 2：运行测试，确认当前在浏览器登录路径上失败**

运行：

```powershell
cd backend
.\mvnw.cmd -f .\pom.xml -pl control-plane/bootstrap -am -Dtest=LocalOidcBrowserLoginIntegrationTest -Dsurefire.failIfNoSpecifiedTests=false test
```

预期：`BUILD FAILURE`，因为 `/mock-oidc/**` 放行、本地 profile 或授权码回调链路尚未接通。

- [ ] **步骤 3：暴露本地 profile 并放行 Provider 端点**

```yaml
ops-agent:
  security:
    auth-mode: oidc
    issuer: http://127.0.0.1:8080/mock-oidc
    issuer-uri: http://127.0.0.1:8080/mock-oidc
    username-claim: preferred_username
    role-claim: roles
    browser-login-enabled: true
    browser-registration-id: ops-agent
  local-oidc-provider:
    enabled: true
    issuer: http://127.0.0.1:8080/mock-oidc
    client-id: ops-agent-local-client
    client-secret: ${OPS_AGENT_LOCAL_OIDC_CLIENT_SECRET}
    default-subject: local-reader-id
    default-username: local.reader
    default-roles:
      - ops-reader
```

```java
.authorizeExchange(spec -> spec
    .pathMatchers(
        "/actuator/**",
        "/v3/api-docs/**",
        "/swagger-ui.html",
        "/swagger-ui/**",
        "/auth/login",
        "/auth/logout",
        "/login/**",
        "/oauth2/**",
        "/mock-oidc/**").permitAll()
    .anyExchange().permitAll())
```

- [ ] **步骤 4：把测试扩展为完整浏览器登录流程**

```java
@Test
void establishesBrowserSessionViaLocalOidcProvider() {
  EntityExchangeResult<byte[]> loginResult = webTestClient.get()
      .uri("/auth/login")
      .exchange()
      .expectStatus().isFound()
      .returnResult(byte[].class);

  String authorizeUri = loginResult.getResponseHeaders().getLocation().toString();

  EntityExchangeResult<byte[]> authorizeResult = webTestClient.get()
      .uri(authorizeUri)
      .cookie("SESSION", loginResult.getResponseCookies().getFirst("SESSION").getValue())
      .exchange()
      .expectStatus().isFound()
      .returnResult(byte[].class);

  String callbackUri = authorizeResult.getResponseHeaders().getLocation().toString();

  EntityExchangeResult<byte[]> callbackResult = webTestClient.get()
      .uri(callbackUri)
      .cookie("SESSION", authorizeResult.getResponseCookies().getFirst("SESSION").getValue())
      .exchange()
      .expectStatus().isFound()
      .returnResult(byte[].class);

  webTestClient.get()
      .uri("/auth/session")
      .cookie("SESSION", callbackResult.getResponseCookies().getFirst("SESSION").getValue())
      .exchange()
      .expectStatus().isOk()
      .expectBody()
      .jsonPath("$.authenticated").isEqualTo(true)
      .jsonPath("$.username").isEqualTo("local.reader")
      .jsonPath("$.roles[0]").isEqualTo("ROLE_ops-reader");
}
```

- [ ] **步骤 5：补充内部访问、权限拒绝与退出场景**

```java
@Test
void allowsReadOnlyInternalEndpointWithBrowserSession() {
  String sessionCookie = loginAndReturnSessionCookie();

  webTestClient.get()
      .uri("/internal/healthz")
      .cookie("SESSION", sessionCookie)
      .exchange()
      .expectStatus().isOk()
      .expectBody()
      .jsonPath("$.status").isEqualTo("UP");
}

@Test
void rejectsAdminEndpointForReaderSession() {
  String sessionCookie = loginAndReturnSessionCookie();

  webTestClient.get()
      .uri("/internal/failures/illegal-argument")
      .cookie("SESSION", sessionCookie)
      .exchange()
      .expectStatus().isForbidden()
      .expectBody()
      .jsonPath("$.code").isEqualTo("POLICY_DENIED");
}

@Test
void clearsBrowserSessionOnLogout() {
  String sessionCookie = loginAndReturnSessionCookie();

  webTestClient.get()
      .uri("/auth/logout")
      .cookie("SESSION", sessionCookie)
      .exchange()
      .expectStatus().isFound();
}

private String loginAndReturnSessionCookie() {
  EntityExchangeResult<byte[]> loginResult = webTestClient.get()
      .uri("/auth/login")
      .exchange()
      .expectStatus().isFound()
      .returnResult(byte[].class);

  EntityExchangeResult<byte[]> authorizeResult = webTestClient.get()
      .uri(loginResult.getResponseHeaders().getLocation().toString())
      .cookie("SESSION", loginResult.getResponseCookies().getFirst("SESSION").getValue())
      .exchange()
      .expectStatus().isFound()
      .returnResult(byte[].class);

  EntityExchangeResult<byte[]> callbackResult = webTestClient.get()
      .uri(authorizeResult.getResponseHeaders().getLocation().toString())
      .cookie("SESSION", authorizeResult.getResponseCookies().getFirst("SESSION").getValue())
      .exchange()
      .expectStatus().isFound()
      .returnResult(byte[].class);

  return callbackResult.getResponseCookies().getFirst("SESSION").getValue();
}
```

- [ ] **步骤 6：运行浏览器登录相关后端测试集**

运行：

```powershell
cd backend
.\mvnw.cmd -f .\pom.xml -pl control-plane/bootstrap -am -Dtest=LocalOidcProviderControllerTest,LocalOidcBrowserLoginIntegrationTest test
```

预期：`BUILD SUCCESS`，发现文档、授权码交换、会话、`401`、`403` 与退出主路径全部通过。

- [ ] **步骤 7：提交这一组改动**

```powershell
git add backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/SecurityConfiguration.java `
  backend/control-plane/bootstrap/src/main/resources/application.yaml `
  backend/control-plane/bootstrap/src/main/resources/application-local-oidc.yaml `
  backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/LocalOidcBrowserLoginIntegrationTest.java
git commit -m "Wire local OIDC browser login profile"
```

## 任务 3：把前端 API 层切换到浏览器会话登录

**文件：**
- 修改：`frontend/operator-console/src/types.ts`
- 修改：`frontend/operator-console/src/api.ts`

- [ ] **步骤 1：先补会话类型与 API 签名，让编译在 UI 未改之前失败**

```ts
export interface BrowserSession {
  authenticated: boolean;
  subject: string | null;
  username: string | null;
  roles: string[];
  authenticationType: string;
}

export async function fetchBrowserSession(): Promise<BrowserSession> {
  const response = await fetch("/auth/session", { credentials: "include" });
  if (response.status === 401) {
    return {
      authenticated: false,
      subject: null,
      username: null,
      roles: [],
      authenticationType: "anonymous",
    };
  }
  return parseBrowserSession(await response.json());
}
```

- [ ] **步骤 2：运行前端构建，确认在 `App.tsx` 未改时失败**

运行：

```powershell
cd frontend\operator-console
npm run build
```

预期：`npm run build` 失败，因为 `App.tsx` 仍依赖旧的 Token 驱动 API。

- [ ] **步骤 3：实现会话解析和基于会话的诊断请求**

```ts
function parseBrowserSession(value: unknown): BrowserSession {
  if (!isRecord(value) || typeof value.authenticated !== "boolean" || !Array.isArray(value.roles)) {
    throw new Error("控制面返回了不符合会话契约的数据");
  }
  return {
    authenticated: value.authenticated,
    subject: typeof value.subject === "string" ? value.subject : null,
    username: typeof value.username === "string" ? value.username : null,
    roles: value.roles.filter((role): role is string => typeof role === "string"),
    authenticationType: typeof value.authenticationType === "string" ? value.authenticationType : "unknown",
  };
}

export async function streamDiagnosticEvents(
  request: DiagnosticRequest,
  onEvent: (event: SemanticEvent) => void,
): Promise<void> {
  const response = await fetch("/internal/diagnostics/read-only/events", {
    method: "POST",
    headers: new Headers({ "Content-Type": "application/json", Accept: "text/event-stream" }),
    credentials: "include",
    body: JSON.stringify(request),
  });
  if (!response.ok || !response.body) {
    throw new Error(`诊断请求失败：HTTP ${response.status}`);
  }
  // 保留现有 SSE 帧解析逻辑
}
```

- [ ] **步骤 4：再次运行前端构建**

运行：

```powershell
cd frontend\operator-console
npm run build
```

预期：构建仍失败，直到 `App.tsx` 停止传入 token 参数，并改为消费 `BrowserSession`。

- [ ] **步骤 5：提交这一组改动**

```powershell
git add frontend/operator-console/src/types.ts frontend/operator-console/src/api.ts
git commit -m "Add browser session client helpers"
```

## 任务 4：把操作台 UI 切换为会话优先，并同步更新中文文档

**文件：**
- 修改：`frontend/operator-console/src/App.tsx`
- 修改：`frontend/operator-console/src/styles.css`
- 修改：`docs/runbooks/local-oidc-mock-testing.md`
- 修改：`frontend/operator-console/README.md`

- [ ] **步骤 1：重写应用状态，启动时先查询会话**

```tsx
const [session, setSession] = useState<BrowserSession | null>(null);
const [sessionLoading, setSessionLoading] = useState(true);
const [error, setError] = useState("");
const [events, setEvents] = useState<SemanticEvent[]>([]);
const [running, setRunning] = useState(false);

useEffect(() => {
  let cancelled = false;
  fetchBrowserSession()
    .then((currentSession) => {
      if (!cancelled) {
        setSession(currentSession);
      }
    })
    .catch((caught) => {
      if (!cancelled) {
        setError(caught instanceof Error ? caught.message : "会话查询失败");
      }
    })
    .finally(() => {
      if (!cancelled) {
        setSessionLoading(false);
      }
    });
  return () => {
    cancelled = true;
  };
}, []);
```

- [ ] **步骤 2：用登录卡片和会话横幅替换 Token 输入界面**

```tsx
if (sessionLoading) {
  return <main><section className="panel">正在加载登录会话...</section></main>;
}

if (!session?.authenticated) {
  return (
    <main>
      <header>
        <p className="eyebrow">P1 Read-only Diagnostics</p>
        <h1>智能运维只读操作台</h1>
        <p>当前本地联调默认使用浏览器会话登录，不再要求手工输入 Bearer Token。</p>
      </header>
      <section className="panel login-panel">
        <p>你尚未登录本地模拟 OIDC 会话。</p>
        <a className="button-link" href="/auth/login">使用本地 OIDC 登录</a>
      </section>
    </main>
  );
}
```

- [ ] **步骤 3：把诊断提交流程改为直接使用会话版 API**

```tsx
await streamDiagnosticEvents(
  {
    skillId: "node-health-read",
    targetEnvironment: "development",
    idempotencyKey: `node-health:${nodeName}:${Date.now()}`,
    parameters: { nodeName },
  },
  (semanticEvent) =>
    setEvents((current) =>
      current.some((item) => item.eventId === semanticEvent.eventId)
        ? current
        : [...current, semanticEvent],
    ),
);
```

- [ ] **步骤 4：补充最小样式，支持登录态与会话态**

```css
.session-bar {
  display: flex;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 24px;
  padding: 16px 20px;
}

.login-panel {
  display: grid;
  gap: 16px;
  justify-items: start;
}

.button-link {
  display: inline-flex;
  align-items: center;
  border-radius: 10px;
  padding: 12px 20px;
  color: #06111f;
  background: #7dd3fc;
  font-weight: 800;
  text-decoration: none;
}
```

- [ ] **步骤 5：更新中文运行手册与前端 README**

```md
1. 在 `backend` 目录启动控制面本地 OIDC profile：
   `.\mvnw.cmd -f .\pom.xml -pl control-plane/bootstrap -am spring-boot:run -Dspring-boot.run.profiles=local-oidc`
2. 在 `frontend/operator-console` 目录启动前端：
   `npm run dev`
3. 打开 `http://127.0.0.1:5173`。
4. 点击“使用本地 OIDC 登录”。
5. 登录成功后确认 `/auth/session` 返回已登录主体，再触发一次只读诊断。
6. 点击退出后确认页面回到未登录状态。
```

- [ ] **步骤 6：运行前端构建与一个后端烟雾测试**

运行：

```powershell
cd frontend\operator-console
npm run build

cd backend
.\mvnw.cmd -f .\pom.xml -pl control-plane/bootstrap -am -Dtest=LocalOidcBrowserLoginIntegrationTest test
```

预期：前端构建通过，且浏览器登录集成测试仍通过。

- [ ] **步骤 7：提交这一组改动**

```powershell
git add frontend/operator-console/src/App.tsx `
  frontend/operator-console/src/styles.css `
  docs/runbooks/local-oidc-mock-testing.md `
  frontend/operator-console/README.md
git commit -m "Switch operator console to local browser login"
```

## 任务 5：执行最终验证

**文件：**
- 不新增文件
- 验证目录：`backend/control-plane/bootstrap`
- 验证目录：`frontend/operator-console`

- [ ] **步骤 1：运行后端聚焦验证**

```powershell
cd backend
.\mvnw.cmd -f .\pom.xml -pl control-plane/bootstrap -am test
```

预期：`BUILD SUCCESS`，现有浏览器认证测试与新增本地 OIDC 测试全部通过。

- [ ] **步骤 2：运行前端验证**

```powershell
cd frontend\operator-console
npm run build
```

预期：TypeScript 检查与 Vite 构建通过，默认路径不再要求手工 Token。

- [ ] **步骤 3：执行本地人工烟雾联调**

```powershell
cd backend
.\mvnw.cmd -f .\pom.xml -pl control-plane/bootstrap -am spring-boot:run -Dspring-boot.run.profiles=local-oidc
```

```powershell
cd frontend\operator-console
npm run dev
```

预期：

- `http://127.0.0.1:5173` 首屏显示登录卡片；
- `/auth/login` 能完成本地模拟 OIDC 登录；
- 会话横幅显示 `local.reader`；
- 只读诊断时间线可以正常渲染事件；
- `ops-reader` 身份访问管理员端点仍返回 `403`；
- 退出后页面回到匿名状态。

- [ ] **步骤 4：提交最终可验证状态**

```powershell
git add backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/LocalOidcProviderProperties.java `
  backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/LocalOidcProviderConfiguration.java `
  backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/security/localoidc/LocalOidcKeyMaterial.java `
  backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/security/localoidc/LocalOidcAuthorizationService.java `
  backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/api/LocalOidcProviderController.java `
  backend/control-plane/bootstrap/src/main/java/com/company/opsagent/controlplane/bootstrap/config/SecurityConfiguration.java `
  backend/control-plane/bootstrap/src/main/resources/application.yaml `
  backend/control-plane/bootstrap/src/main/resources/application-local-oidc.yaml `
  backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/LocalOidcProviderControllerTest.java `
  backend/control-plane/bootstrap/src/test/java/com/company/opsagent/controlplane/bootstrap/LocalOidcBrowserLoginIntegrationTest.java `
  frontend/operator-console/src/types.ts `
  frontend/operator-console/src/api.ts `
  frontend/operator-console/src/App.tsx `
  frontend/operator-console/src/styles.css `
  docs/runbooks/local-oidc-mock-testing.md `
  frontend/operator-console/README.md
git commit -m "Finish local OIDC browser login integration"
```
