import { readFileSync } from "node:fs";

import { http, HttpResponse } from "msw";
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { beforeEach, expect, test } from "vitest";
import { MemoryRouter } from "react-router-dom";

import { AppProviders } from "../../app/providers.jsx";
import { server } from "../../test/server.js";
import { ModelSettingsPage } from "./ModelSettingsPage.jsx";

const modelSettingsCss = readFileSync(
  "src/features/model-settings/ModelSettingsPage.module.css",
  "utf8",
);

function runtimeTestInput() {
  return String(Date.now()) + String(Math.random());
}

/** @typedef {import("../../schemas/model-provider-schemas.js").ModelProviderSummary} ModelProviderSummary */
/** @typedef {import("../../schemas/model-provider-schemas.js").ModelProviderCreateRequest} ModelProviderCreateRequest */
/** @typedef {import("../../schemas/model-provider-schemas.js").ModelProviderUpdateRequest} ModelProviderUpdateRequest */

/** @type {ModelProviderSummary[]} */
let providers;
/** @type {ModelProviderCreateRequest | null} */
let createBody;
/** @type {string | null} */
let defaultProviderId;

beforeEach(() => {
  providers = [modelProvider({ providerId: "provider-openai", defaultProvider: true })];
  createBody = null;
  defaultProviderId = null;
  server.use(
    http.get("/auth/session", () =>
      HttpResponse.json({
        authenticated: true,
        subject: "operator-1",
        username: "ops.admin",
        roles: ["ROLE_ops-admin"],
        authenticationType: "built-in",
      }),
    ),
    http.get("/internal/model-providers", () => HttpResponse.json(providers)),
    http.post("/internal/model-providers", async ({ request }) => {
      createBody = /** @type {ModelProviderCreateRequest} */ (
        await request.json()
      );
      const created = modelProvider({
        providerId: "provider-new",
        displayName: createBody.displayName,
        baseUrl: createBody.baseUrl,
        modelName: createBody.modelName,
        defaultProvider: false,
      });
      providers = [...providers, created];
      return HttpResponse.json(created);
    }),
    http.patch("/internal/model-providers/:providerId", async ({ params, request }) => {
      const providerId = String(params.providerId);
      const body = /** @type {ModelProviderUpdateRequest} */ (
        await request.json()
      );
      providers = providers.map((provider) =>
        provider.providerId === providerId
          ? { ...provider, ...body, timeout: `PT${body.timeoutSeconds}S` }
          : provider,
      );
      return HttpResponse.json(
        providers.find((provider) => provider.providerId === providerId),
      );
    }),
    http.post("/internal/model-providers/:providerId/default", ({ params }) => {
      defaultProviderId = String(params.providerId);
      providers = providers.map((provider) => ({
        ...provider,
        defaultProvider: provider.providerId === defaultProviderId,
      }));
      return HttpResponse.json(
        providers.find((provider) => provider.providerId === defaultProviderId),
      );
    }),
    http.post("/internal/model-providers/:providerId/test", () =>
      HttpResponse.json({ status: "SUCCEEDED", message: "ok" }),
    ),
  );
});

test("renders configured model providers without secret material", async () => {
  const secretMaterial = runtimeTestInput();
  renderPage();

  expect(await screen.findByRole("heading", { name: "模型设置" })).toBeInTheDocument();
  expect(await screen.findByRole("button", { name: /OpenAI/u })).toBeInTheDocument();
  expect(screen.getByText("fp_openai")).toBeInTheDocument();
  expect(screen.queryByText(secretMaterial)).not.toBeInTheDocument();
});

test("uses Agent workspace visual language for provider settings", () => {
  expect(modelSettingsCss).toContain("--agent-bg-base: #f6f7f9");
  expect(modelSettingsCss).toContain("--agent-red: #d31145");
  expect(modelSettingsCss).toContain("--agent-blue: #227ea6");
  expect(modelSettingsCss).toContain("backdrop-filter: blur(18px)");
  expect(modelSettingsCss).toContain("rgba(37, 132, 169, 0.08)");
  expect(modelSettingsCss).toContain("rgba(211, 17, 69, 0.08)");
  expect(modelSettingsCss).toContain(".securityFacts div");
  expect(modelSettingsCss).not.toContain("background: var(--color-surface);");
});

test("keeps model settings controls visually compact", () => {
  expect(modelSettingsCss).toContain(":global([class*=\"capsuleHeading\"])");
  expect(modelSettingsCss).toContain("font-size: 16px;");
  expect(modelSettingsCss).toContain("min-height: 36px;");
  expect(modelSettingsCss).toContain("min-height: 34px;");
  expect(modelSettingsCss).toContain("padding: 7px 12px;");
});

test("avoids false alert markers around settings controls", () => {
  expect(modelSettingsCss).not.toContain(".providerRail::before");
  expect(modelSettingsCss).not.toContain(".editorPanel::before");
  expect(modelSettingsCss).not.toContain("inset 4px 0 0");
});

test("keeps action buttons the same width and height", () => {
  expect(modelSettingsCss).toContain("--settings-action-width: 108px;");
  expect(modelSettingsCss).toContain("width: var(--settings-action-width);");
  expect(modelSettingsCss).toContain("height: 34px;");
  expect(modelSettingsCss).toContain("justify-content: center;");
  expect(modelSettingsCss).toContain("white-space: nowrap;");
});

test("renders an icon in each action button", async () => {
  renderPage();

  await screen.findByRole("button", { name: /OpenAI/u });

  const actionButtons = Array.from(document.querySelectorAll("form button"));
  expect(actionButtons).toHaveLength(4);
  for (const button of actionButtons) {
    expect(button.querySelector("svg")).not.toBeNull();
  }
});

test("keeps provider status inline with the provider title", () => {
  expect(modelSettingsCss).toContain(".providerTitleLine");
  expect(modelSettingsCss).toContain(".providerModelName");
  expect(modelSettingsCss).toContain("flex-wrap: nowrap;");
  expect(modelSettingsCss).not.toContain("grid-column: 2;");
});

test("creates a model provider with directly entered API Key", async () => {
  const user = userEvent.setup();
  const apiKey = runtimeTestInput();
  renderPage();

  await screen.findByRole("button", { name: /OpenAI/u });
  await user.click(screen.getByRole("button", { name: "新增模型供应方" }));
  await user.type(screen.getByLabelText("显示名称"), "DeepSeek");
  await user.clear(screen.getByLabelText("Base URL"));
  await user.type(screen.getByLabelText("Base URL"), "https://api.deepseek.com/v1");
  await user.type(screen.getByLabelText("模型名称"), "deepseek-chat");
  await user.type(screen.getByLabelText("API Key"), apiKey);
  await user.click(screen.getByRole("button", { name: /保存/u }));

  expect(await screen.findByText("模型供应方已新增")).toBeInTheDocument();
  expect(createBody).toMatchObject({
    displayName: "DeepSeek",
    baseUrl: "https://api.deepseek.com/v1",
    modelName: "deepseek-chat",
    apiKey,
  });
});

test("switches the selected provider as default", async () => {
  const user = userEvent.setup();
  providers = [
    modelProvider({ providerId: "provider-openai", defaultProvider: true }),
    modelProvider({
      providerId: "provider-deepseek",
      displayName: "DeepSeek",
      modelName: "deepseek-chat",
      defaultProvider: false,
    }),
  ];
  renderPage();

  await user.click(await screen.findByRole("button", { name: /DeepSeek/u }));
  await user.click(screen.getByRole("button", { name: /设为默认/u }));

  expect(await screen.findByText("默认模型已切换")).toBeInTheDocument();
  expect(defaultProviderId).toBe("provider-deepseek");
});

function renderPage() {
  return render(
    <AppProviders Router={MemoryRouter} routerProps={{ initialEntries: ["/model-settings"] }}>
      <ModelSettingsPage />
    </AppProviders>,
  );
}

/**
 * @param {Partial<ModelProviderSummary>} overrides
 * @returns {ModelProviderSummary}
 */
function modelProvider(overrides = {}) {
  return /** @type {ModelProviderSummary} */ ({
    providerId: "provider-openai",
    displayName: "OpenAI",
    providerType: "OPENAI_COMPATIBLE",
    baseUrl: "https://api.openai.com/v1",
    modelName: "gpt-4.1-mini",
    enabled: true,
    defaultProvider: false,
    timeout: "PT30S",
    maxIterations: 5,
    maxToolCalls: 5,
    maxToolCallDuration: "PT30S",
    apiKeyConfigured: true,
    apiKeyFingerprint: "fp_openai",
    apiKeyLastRotatedAt: "2026-06-28T00:00:00Z",
    configVersion: 1,
    updatedAt: "2026-06-28T00:00:00Z",
    ...overrides,
  });
}
