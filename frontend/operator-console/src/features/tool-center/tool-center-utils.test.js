import { describe, expect, test } from "vitest";

import {
  createJsonPath,
  deriveRequestOrigin,
  findJsonHeroMatches,
  formatJsonDocument,
  formatJsonHeroNodeValue,
  minifyJsonDocument,
  parseJsonForHeroView,
  previewSecretInput,
  repairJsonDocument,
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

  test("repairs common JSON syntax locally without extracting wrapped content", () => {
    expect(repairJsonDocument('{"service":"queFork","enabled":true,}')).toEqual({
      ok: true,
      value: '{\n  "service": "queFork",\n  "enabled": true\n}',
    });
    expect(repairJsonDocument('"{\\"service\\":\\"queFork\\"}"')).toEqual({
      ok: true,
      value: '"{\\"service\\":\\"queFork\\"}"',
    });
  });

  test("builds JSON hero nodes with stable paths and type metadata", () => {
    const result = parseJsonForHeroView(
      '{"service":{"name":"queFork","enabled":true},"ports":[8080,null],"release-window":"night"}',
    );

    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    expect(result.root.path).toBe("$");
    expect(result.root.kind).toBe("object");
    expect(result.root.childCount).toBe(3);
    expect(result.root.children.map((node) => node.path)).toEqual([
      "$.service",
      "$.ports",
      '$["release-window"]',
    ]);
    expect(result.root.children[0].children[0]).toMatchObject({
      key: "name",
      kind: "string",
      path: "$.service.name",
      preview: '"queFork"',
    });
    expect(result.root.children[1].children[1]).toMatchObject({
      key: "1",
      kind: "null",
      path: "$.ports[1]",
      preview: "null",
    });
  });

  test("creates JSONPath segments for identifiers special keys and arrays", () => {
    expect(createJsonPath("$", "service", false)).toBe("$.service");
    expect(createJsonPath("$.service", "display-name", false)).toBe('$.service["display-name"]');
    expect(createJsonPath("$.items", "0", true)).toBe("$.items[0]");
  });

  test("formats selected JSON hero node values as valid JSON", () => {
    const result = parseJsonForHeroView('{"service":{"name":"queFork"},"enabled":true}');

    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }
    const serviceNode = result.root.children[0];
    const enabledNode = result.root.children[1];

    expect(formatJsonHeroNodeValue(serviceNode)).toBe('{\n  "name": "queFork"\n}');
    expect(formatJsonHeroNodeValue(enabledNode)).toBe("true");
  });

  test("searches JSON hero nodes by key path type and scalar preview", () => {
    const result = parseJsonForHeroView('{"service":{"name":"queFork"},"ports":[8080]}');

    expect(result.ok).toBe(true);
    if (!result.ok) {
      return;
    }

    expect(findJsonHeroMatches(result.root, "name").matchingPaths).toEqual(["$.service.name"]);
    expect(findJsonHeroMatches(result.root, "8080").matchingPaths).toEqual(["$.ports[0]"]);
    expect(findJsonHeroMatches(result.root, "array")).toEqual({
      matchingPaths: ["$.ports"],
      ancestorPaths: ["$"],
    });
  });

  test("returns a stable JSON hero parse error", () => {
    expect(parseJsonForHeroView('{"service":')).toEqual({
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
    expect(previewSecretInput("super-secret-token", "Bearer Token")).toBe(
      "已输入 18 位 Bearer Token，本页不会在历史或预览中显示明文。",
    );
    expect(previewSecretInput("")).toBe("未输入临时凭据");
  });
});
