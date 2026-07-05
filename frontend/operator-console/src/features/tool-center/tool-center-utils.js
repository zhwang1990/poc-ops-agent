import { jsonrepair } from "jsonrepair";

const allowedMethods = new Set(["GET", "POST", "PUT", "PATCH", "DELETE", "HEAD", "OPTIONS"]);

/**
 * @typedef {{ok: true, value: string} | {ok: false, error: string}} JsonTransformResult
 * @typedef {{ok: true, origin: string, host: string} | {ok: false, error: string}} OriginResult
 * @typedef {{
 *   targetName: string,
 *   origin: string,
 *   environmentLabel: string,
 *   methods: string[],
 *   timeoutSeconds: number,
 *   maxRequestBytes: number,
 *   maxResponseBytes: number,
 * }} AllowlistDraft
 * @typedef {"object" | "array" | "string" | "number" | "boolean" | "null"} JsonHeroNodeKind
 * @typedef {{
 *   key: string,
 *   path: string,
 *   kind: JsonHeroNodeKind,
 *   depth: number,
 *   preview: string,
 *   childCount: number,
 *   value: unknown,
 *   children: JsonHeroNode[],
 * }} JsonHeroNode
 * @typedef {{ok: true, root: JsonHeroNode} | {ok: false, error: string}} JsonHeroParseResult
 */
const jsonIdentifierPattern = /^[A-Za-z_$][\w$]*$/u;
const jsonParseErrorMessage = "JSON 解析失败，请检查对象、数组、逗号和引号。";

/**
 * @param {string} source
 * @returns {JsonTransformResult}
 */
export function formatJsonDocument(source) {
  return transformJson(source, (value) => JSON.stringify(value, null, 2));
}

/**
 * @param {string} source
 * @returns {JsonHeroParseResult}
 */
export function parseJsonForHeroView(source) {
  try {
    return { ok: true, root: createJsonHeroNode(JSON.parse(source), "$", "$", 0) };
  } catch {
    return { ok: false, error: jsonParseErrorMessage };
  }
}

/**
 * Repairs JSON syntax locally with jsonrepair without sending content to the backend.
 *
 * @param {string} source
 * @returns {JsonTransformResult}
 */
export function repairJsonDocument(source) {
  try {
    const repaired = jsonrepair(source.trim());
    const parsed = JSON.parse(repaired);
    return { ok: true, value: JSON.stringify(parsed, null, 2) };
  } catch {
    // Keep formatter errors consistent regardless of which local transform failed.
  }
  return { ok: false, error: jsonParseErrorMessage };
}

/**
 * @param {string} parentPath
 * @param {string} key
 * @param {boolean} arrayItem
 */
export function createJsonPath(parentPath, key, arrayItem) {
  if (arrayItem) {
    return `${parentPath}[${key}]`;
  }
  if (jsonIdentifierPattern.test(key)) {
    return parentPath === "$" ? `$.${key}` : `${parentPath}.${key}`;
  }
  return `${parentPath}[${JSON.stringify(key)}]`;
}

/**
 * @param {JsonHeroNode} node
 */
export function formatJsonHeroNodeValue(node) {
  return JSON.stringify(node.value, null, 2);
}

/**
 * @param {JsonHeroNode} root
 * @param {string} query
 * @returns {{ matchingPaths: string[], ancestorPaths: string[] }}
 */
export function findJsonHeroMatches(root, query) {
  const normalizedQuery = query.trim().toLowerCase();
  if (!normalizedQuery) {
    return { matchingPaths: [], ancestorPaths: [] };
  }

  /** @type {string[]} */
  const matchingPaths = [];
  /** @type {Set<string>} */
  const ancestorPaths = new Set();

  visitJsonHeroNodes(root, (node, ancestors) => {
    const haystack = `${node.key} ${node.path} ${node.kind} ${node.preview}`.toLowerCase();
    if (haystack.includes(normalizedQuery)) {
      matchingPaths.push(node.path);
      for (const ancestor of ancestors) {
        ancestorPaths.add(ancestor.path);
      }
    }
  });

  return { matchingPaths, ancestorPaths: Array.from(ancestorPaths) };
}

/**
 * @param {string} source
 * @returns {JsonTransformResult}
 */
export function minifyJsonDocument(source) {
  return transformJson(source, (value) => JSON.stringify(value));
}

/**
 * @param {string} source
 * @param {(value: unknown) => string} formatter
 * @returns {JsonTransformResult}
 */
function transformJson(source, formatter) {
  try {
    return { ok: true, value: formatter(JSON.parse(source)) };
  } catch {
    return { ok: false, error: jsonParseErrorMessage };
  }
}

/**
 * @param {unknown} value
 * @param {string} key
 * @param {string} path
 * @param {number} depth
 * @returns {JsonHeroNode}
 */
function createJsonHeroNode(value, key, path, depth) {
  const kind = getJsonHeroNodeKind(value);
  /** @type {Array<[string, unknown]>} */
  const entries =
    kind === "object"
      ? Object.entries(/** @type {Record<string, unknown>} */ (value))
      : kind === "array"
        ? /** @type {unknown[]} */ (value).map((item, index) => [String(index), item])
        : [];
  const children = entries.map(([childKey, childValue]) =>
    createJsonHeroNode(childValue, childKey, createJsonPath(path, childKey, kind === "array"), depth + 1),
  );

  return {
    key,
    path,
    kind,
    depth,
    preview: createJsonHeroPreview(value, kind),
    childCount: children.length,
    value,
    children,
  };
}

/**
 * @param {unknown} value
 * @returns {JsonHeroNodeKind}
 */
function getJsonHeroNodeKind(value) {
  if (value === null) {
    return "null";
  }
  if (Array.isArray(value)) {
    return "array";
  }
  if (typeof value === "object") {
    return "object";
  }
  if (typeof value === "string") {
    return "string";
  }
  if (typeof value === "number") {
    return "number";
  }
  return "boolean";
}

/**
 * @param {unknown} value
 * @param {JsonHeroNodeKind} kind
 */
function createJsonHeroPreview(value, kind) {
  if (kind === "object") {
    return `${Object.keys(/** @type {Record<string, unknown>} */ (value)).length} fields`;
  }
  if (kind === "array") {
    return `${/** @type {unknown[]} */ (value).length} items`;
  }
  const serialized = JSON.stringify(value);
  if (typeof serialized !== "string") {
    return String(value);
  }
  return serialized.length > 80 ? `${serialized.slice(0, 77)}...` : serialized;
}

/**
 * @param {JsonHeroNode} node
 * @param {(node: JsonHeroNode, ancestors: JsonHeroNode[]) => void} visitor
 * @param {JsonHeroNode[]} ancestors
 */
function visitJsonHeroNodes(node, visitor, ancestors = []) {
  visitor(node, ancestors);
  for (const child of node.children) {
    visitJsonHeroNodes(child, visitor, [...ancestors, node]);
  }
}

/**
 * @param {string} value
 * @returns {OriginResult}
 */
export function deriveRequestOrigin(value) {
  try {
    const parsed = new URL(value);
    if (parsed.protocol !== "http:" && parsed.protocol !== "https:") {
      return { ok: false, error: "请输入包含 http 或 https scheme 的完整 URL。" };
    }
    return { ok: true, origin: parsed.origin, host: parsed.hostname };
  } catch {
    return { ok: false, error: "请输入包含 http 或 https scheme 的完整 URL。" };
  }
}

/**
 * @param {AllowlistDraft} draft
 * @returns {{ok: boolean, errors: string[]}}
 */
export function validateAllowlistDraft(draft) {
  const errors = [];
  const originResult = deriveRequestOrigin(draft.origin);

  if (!draft.targetName.trim()) {
    errors.push("目标系统名称不能为空。");
  }
  if (!draft.environmentLabel.trim()) {
    errors.push("环境标签不能为空。");
  }
  if (!originResult.ok) {
    errors.push(originResult.error);
  } else if (draft.origin.includes("*")) {
    errors.push("首版不允许配置通配域名。");
  }
  if (draft.methods.length === 0 || draft.methods.some((method) => !allowedMethods.has(method))) {
    errors.push("HTTP 方法必须来自允许集合。");
  }
  if (!Number.isInteger(draft.timeoutSeconds) || draft.timeoutSeconds < 1 || draft.timeoutSeconds > 120) {
    errors.push("超时时间必须在 1 到 120 秒之间。");
  }
  if (!Number.isInteger(draft.maxRequestBytes) || draft.maxRequestBytes < 1) {
    errors.push("最大请求体大小必须是正整数。");
  }
  if (!Number.isInteger(draft.maxResponseBytes) || draft.maxResponseBytes < 1) {
    errors.push("最大响应体大小必须是正整数。");
  }

  return { ok: errors.length === 0, errors };
}

/**
 * @param {string} secret
 * @param {string} [label]
 */
export function previewSecretInput(secret, label = "临时凭据") {
  const labelPrefix = /^[A-Za-z0-9]/u.test(label) ? ` ${label}` : label;
  if (!secret) {
    return `未输入${labelPrefix}`;
  }
  return `已输入 ${secret.length} 位${labelPrefix}，本页不会在历史或预览中显示明文。`;
}
