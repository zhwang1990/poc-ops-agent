import { describe, expect, test } from "vitest";

import {
  deriveRequestOrigin,
  formatJsonDocument,
  minifyJsonDocument,
  previewSecretInput,
  validateAllowlistDraft,
} from "./tool-center-utils.js";

describe("tool center utilities", () => {
  test("formats and minifies valid JSON without changing data", () => {
    const source = "{\"service\":\"queFork\",\"enabled\":true,\"ports\":[8080,8443]}";

    expect(formatJsonDocument(source)).toEqual({
      ok: true,
      value: "{\n  \"service\": \"queFork\",\n  \"enabled\": true,\n  \"ports\": [\n    8080,\n    8443\n  ]\n}",
    });
    expect(minifyJsonDocument(source)).toEqual({
      ok: true,
      value: "{\"service\":\"queFork\",\"enabled\":true,\"ports\":[8080,8443]}",
    });
  });

  test("returns a stable message for invalid JSON", () => {
    expect(formatJsonDocument("{\"service\":")).toEqual({
      ok: false,
      error: "JSON 解析失败，请检查对象、数组、逗号和引号。",
    });
  });

  test("derives scheme host and port from a full request URL", () => {
    expect(deriveRequestOrigin("https://api.quefork.internal:8443/orders/42?debug=true")).toEqual({
      ok: true,
      origin: "https://api.quefork.internal:8443",
      host: "api.quefork.internal",
    });
    expect(deriveRequestOrigin("not a url")).toEqual({
      ok: false,
      error: "请输入包含 http 或 https scheme 的完整 URL。",
    });
  });

  test("validates administrator allowlist drafts without broad wildcards", () => {
    expect(
      validateAllowlistDraft({
        targetName: "queFork",
        origin: "https://api.quefork.internal:8443",
        environmentLabel: "test",
        methods: ["GET", "POST"],
        timeoutSeconds: 30,
        maxRequestBytes: 65536,
        maxResponseBytes: 1048576,
      }),
    ).toEqual({ ok: true, errors: [] });

    expect(
      validateAllowlistDraft({
        targetName: "wide",
        origin: "https://*.internal",
        environmentLabel: "test",
        methods: ["GET"],
        timeoutSeconds: 30,
        maxRequestBytes: 65536,
        maxResponseBytes: 1048576,
      }),
    ).toEqual({
      ok: false,
      errors: ["首版不允许配置通配域名。"],
    });
  });

  test("does not expose secret input in preview text", () => {
    expect(previewSecretInput("super-secret-token")).toBe("已输入 18 位临时凭据，本页不会在历史或预览中显示明文。");
    expect(previewSecretInput("")).toBe("未输入临时凭据");
  });
});
