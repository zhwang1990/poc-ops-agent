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
 */

/**
 * @param {string} source
 * @returns {JsonTransformResult}
 */
export function formatJsonDocument(source) {
  return transformJson(source, (value) => JSON.stringify(value, null, 2));
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
    return { ok: false, error: "JSON 解析失败，请检查对象、数组、逗号和引号。" };
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
 */
export function previewSecretInput(secret) {
  if (!secret) {
    return "未输入临时凭据";
  }
  return `已输入 ${secret.length} 位临时凭据，本页不会在历史或预览中显示明文。`;
}
