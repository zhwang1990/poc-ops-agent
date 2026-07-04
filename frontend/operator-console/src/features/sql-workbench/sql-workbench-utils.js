import { ApiError } from "../../api/client.js";

export const DEFAULT_SQL = "";
export const EMPTY_SESSION_SQL = "";

export const DEFAULT_LIMITS = {
  maxRows: 500,
  maxBytes: 5_000_000,
  timeoutSeconds: 30,
};

export const LEGACY_READ_ONLY_VALIDATION_ERROR =
  "query must pass read-only validation before execution";

/**
 * @typedef {import("../../schemas/sql-schemas.js").SqlConnectionSummary} SqlConnectionSummary
 * @typedef {import("../../schemas/sql-schemas.js").SqlValidationReport} SqlValidationReport
 * @typedef {import("../../schemas/sql-schemas.js").SqlQueryRunResult} SqlQueryRunResult
 * @typedef {import("../../schemas/sql-schemas.js").SqlResultPage} SqlResultPage
 * @typedef {import("../../schemas/sql-schemas.js").SqlAssistantResponse} SqlAssistantResponse
 * @typedef {"sql" | "natural-language" | "compare"} SqlSessionMode
 * @typedef {"auto" | "manual"} SqlTransactionMode
 * @typedef {"EXPLAIN_SQL" | "OPTIMIZE_SQL" | "ANALYZE_ERROR" | "GENERATE_SELECT" | "COMPARE_SUMMARY"} SqlAssistantAction
 * @typedef {{draftSql: string, fields: string, includeCurrentSql: boolean, library: string, prompt: string, statusMessage: string | null, tableName: string}} SqlNaturalLanguageState
 * @typedef {{assistant: SqlAssistantResponse | null, baseLibrary: string, compareLibrary: string, errorMessage: string | null, fields: string, ignoredFields: string, keyFields: string, maxRows: string, report: SqlCompareReport | null, statusMessage: string | null, tableName: string, whereClause: string}} SqlCompareState
 * @typedef {{errorMessage: string | null, execution: SqlQueryRunResult | null, id: string, isPending: boolean, label: string, resultPageIndex: number, resultPage: SqlResultPage | null, resultPageToken: string | null, resultPageTokens: Array<string | null>, sql: string}} SqlResultTab
 * @typedef {{
 *   assistant: SqlAssistantResponse | null,
 *   assistantErrorMessage: string | null,
 *   activeResultTabId: string | null,
 *   compare: SqlCompareState,
 *   connectionId: string,
 *   errorMessage: string | null,
 *   execution: SqlQueryRunResult | null,
 *   id: string,
 *   label: string,
 *   mode: SqlSessionMode,
 *   naturalLanguage: SqlNaturalLanguageState,
 *   resultPageIndex: number,
 *   resultPage: SqlResultPage | null,
 *   resultPageToken: string | null,
 *   resultPageTokens: Array<string | null>,
 *   resultTabs: SqlResultTab[],
 *   schema: string,
 *   sql: string,
 *   transactionMode: SqlTransactionMode,
 *   validation: SqlValidationReport | null,
 * }} SqlWorkbenchSession
 * @typedef {{from: number, sql: string, to: number}} SqlEditorStatement
 * @typedef {{column: string, baseValue: string, compareValue: string}} SqlCompareCellDifference
 * @typedef {{key: string, differences: SqlCompareCellDifference[]}} SqlCompareRowDifference
 * @typedef {{key: string, values: Record<string, string>}} SqlCompareOnlyRow
 * @typedef {{baseLibrary: string, baseRowCount: number, baseSql: string, compareLibrary: string, compareRowCount: number, compareSql: string, comparedFields: string[], ignoredFields: string[], keyFields: string[], matchingRows: number, mismatchedRows: SqlCompareRowDifference[], onlyInBase: SqlCompareOnlyRow[], onlyInCompare: SqlCompareOnlyRow[], tableName: string, truncated: boolean}} SqlCompareReport
 */

/**
 * @returns {SqlNaturalLanguageState}
 */
export function createNaturalLanguageState() {
  return {
    draftSql: "",
    fields: "",
    includeCurrentSql: false,
    library: "",
    prompt: "",
    statusMessage: null,
    tableName: "",
  };
}

/**
 * @returns {SqlCompareState}
 */
export function createCompareState() {
  return {
    assistant: null,
    baseLibrary: "",
    compareLibrary: "",
    errorMessage: null,
    fields: "",
    ignoredFields: "",
    keyFields: "",
    maxRows: "500",
    report: null,
    statusMessage: null,
    tableName: "",
    whereClause: "",
  };
}

/**
 * @param {SqlNaturalLanguageState} state
 * @param {string} activeSchema
 * @param {string} currentSql
 */
export function buildNaturalLanguageDiagnosticContext(state, activeSchema, currentSql) {
  const directives = extractNaturalLanguageDirectives(state.prompt);
  const includeCurrentSql = state.includeCurrentSql || directives.includeCurrentSql;
  return [
    `naturalLanguage=${state.prompt.trim()}`,
    `targetLibrary=${state.library.trim() || directives.library || activeSchema}`,
    `targetTable=${state.tableName.trim() || directives.tableName || "unspecified"}`,
    `requestedFields=${state.fields.trim() || directives.fields || "unspecified"}`,
    `currentEditorSql=${includeCurrentSql ? currentSql.trim() || "empty" : "not-included"}`,
    "generationRule=Only generate one SELECT or WITH statement. Do not generate DML, DDL, procedure calls, credentials, or execution instructions.",
  ].join("\n");
}

/**
 * @param {string} prompt
 * @returns {{fields: string, includeCurrentSql: boolean, library: string, tableName: string}}
 */
export function extractNaturalLanguageDirectives(prompt) {
  const library = readDirective(prompt, "@", ["库名", "library", "schema"]);
  const tableName = readDirective(prompt, "#", ["表名", "table"]);
  const fields = readDirective(prompt, "$", ["字段", "字段1,字段2", "fields"]);
  return {
    fields,
    includeCurrentSql: /(?:^|\s)\/sql(?:\s|$)/u.test(prompt),
    library,
    tableName,
  };
}

/**
 * @param {string} prompt
 * @param {string} marker
 * @param {string[]} placeholders
 */
function readDirective(prompt, marker, placeholders) {
  const escapedMarker = marker.replace(/[.*+?^${}()|[\]\\]/gu, "\\$&");
  const match = prompt.match(new RegExp(`(?:^|\\s)${escapedMarker}([^\\s]+)`, "u"));
  const value = match?.[1]?.trim() ?? "";
  return placeholders.includes(value) ? "" : value;
}

/**
 * @param {SqlCompareState} state
 * @returns {{baseSql: string, compareSql: string, comparedFields: string[], ignoredFields: string[], keyFields: string[], maxRows: number}}
 */
export function buildCompareSql(state) {
  const keyFields = splitCsv(state.keyFields);
  const comparedFields = splitCsv(state.fields);
  const ignoredFields = new Set(splitCsv(state.ignoredFields).map((field) => field.toUpperCase()));
  const selectedFields = uniqueSqlFields([...keyFields, ...comparedFields])
    .filter((field) => !ignoredFields.has(field.toUpperCase()));
  const projection = selectedFields.length > 0 ? selectedFields.join(", ") : "*";
  const whereClause = state.whereClause.trim();
  const predicate = whereClause ? ` where ${whereClause}` : "";
  return {
    baseSql: `select ${projection} from ${state.baseLibrary.trim()}.${state.tableName.trim()}${predicate}`,
    compareSql: `select ${projection} from ${state.compareLibrary.trim()}.${state.tableName.trim()}${predicate}`,
    comparedFields,
    ignoredFields: Array.from(ignoredFields),
    keyFields,
    maxRows: clampNumber(Number(state.maxRows) || DEFAULT_LIMITS.maxRows, 1, 10_000),
  };
}

/**
 * @param {SqlCompareState} state
 */
export function validateCompareInput(state) {
  if (!state.baseLibrary.trim() || !state.compareLibrary.trim()) {
    return "请填写基准库和对比库";
  }
  if (!state.tableName.trim()) {
    return "请填写目标表";
  }
  if (splitCsv(state.keyFields).length === 0) {
    return "请至少填写一个主键字段";
  }
  return null;
}

/**
 * @param {{
 *   baseLibrary: string,
 *   basePage: SqlResultPage,
 *   baseSql: string,
 *   compareLibrary: string,
 *   comparePage: SqlResultPage,
 *   compareSql: string,
 *   comparedFields: string[],
 *   ignoredFields: string[],
 *   keyFields: string[],
 *   tableName: string,
 * }} input
 * @returns {SqlCompareReport}
 */
export function createCompareReport(input) {
  const baseRows = rowsByKey(input.basePage, input.keyFields);
  const compareRows = rowsByKey(input.comparePage, input.keyFields);
  /** @type {SqlCompareOnlyRow[]} */
  const onlyInBase = [];
  /** @type {SqlCompareOnlyRow[]} */
  const onlyInCompare = [];
  /** @type {SqlCompareRowDifference[]} */
  const mismatchedRows = [];
  let matchingRows = 0;
  const comparedFields = resolveComparedFields(input);

  for (const [key, baseRow] of baseRows.entries()) {
    const compareRow = compareRows.get(key);
    if (!compareRow) {
      onlyInBase.push({ key, values: baseRow.values });
      continue;
    }
    const differences = comparedFields
      .map((column) => ({
        column,
        baseValue: baseRow.values[column] ?? "",
        compareValue: compareRow.values[column] ?? "",
      }))
      .filter((difference) => difference.baseValue !== difference.compareValue);
    if (differences.length > 0) {
      mismatchedRows.push({ key, differences });
    } else {
      matchingRows += 1;
    }
  }
  for (const [key, compareRow] of compareRows.entries()) {
    if (!baseRows.has(key)) {
      onlyInCompare.push({ key, values: compareRow.values });
    }
  }

  return {
    baseLibrary: input.baseLibrary,
    baseRowCount: input.basePage.rows.length,
    baseSql: input.baseSql,
    compareLibrary: input.compareLibrary,
    compareRowCount: input.comparePage.rows.length,
    compareSql: input.compareSql,
    comparedFields,
    ignoredFields: input.ignoredFields,
    keyFields: input.keyFields,
    matchingRows,
    mismatchedRows: mismatchedRows.slice(0, 20),
    onlyInBase: onlyInBase.slice(0, 20),
    onlyInCompare: onlyInCompare.slice(0, 20),
    tableName: input.tableName,
    truncated:
      input.basePage.truncated ||
      input.comparePage.truncated ||
      mismatchedRows.length > 20 ||
      onlyInBase.length > 20 ||
      onlyInCompare.length > 20,
  };
}

/**
 * @param {SqlCompareReport} report
 */
export function buildCompareDiagnosticContext(report) {
  return [
    `compareType=data-consistency`,
    `base=${report.baseLibrary}.${report.tableName}`,
    `compare=${report.compareLibrary}.${report.tableName}`,
    `keyFields=${report.keyFields.join(", ")}`,
    `comparedFields=${report.comparedFields.join(", ")}`,
    `baseRows=${report.baseRowCount}`,
    `compareRows=${report.compareRowCount}`,
    `matchingRows=${report.matchingRows}`,
    `mismatchedRows=${report.mismatchedRows.length}`,
    `onlyInBase=${report.onlyInBase.length}`,
    `onlyInCompare=${report.onlyInCompare.length}`,
    `truncated=${report.truncated}`,
    `sampleDifferences=${JSON.stringify(report.mismatchedRows.slice(0, 5))}`,
    `sampleOnlyInBase=${JSON.stringify(report.onlyInBase.slice(0, 5))}`,
    `sampleOnlyInCompare=${JSON.stringify(report.onlyInCompare.slice(0, 5))}`,
    "summaryRule=Summarize only the deterministic diff facts above. Do not invent missing rows, causes, or remediation not supported by the diff.",
  ].join("\n").slice(0, 4_000);
}

/**
 * @param {string[]} fields
 */
function uniqueSqlFields(fields) {
  const seen = new Set();
  return fields.filter((field) => {
    const key = field.toUpperCase();
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    return true;
  });
}

/**
 * @param {{
 *   basePage: SqlResultPage,
 *   comparePage: SqlResultPage,
 *   comparedFields: string[],
 *   ignoredFields: string[],
 *   keyFields: string[],
 * }} input
 */
function resolveComparedFields(input) {
  if (input.comparedFields.length > 0) {
    return input.comparedFields;
  }
  const ignored = new Set([
    ...input.ignoredFields.map((field) => field.toUpperCase()),
    ...input.keyFields.map((field) => field.toUpperCase()),
  ]);
  return input.basePage.columns
    .map((column) => column.name)
    .filter((column) => !ignored.has(column.toUpperCase()));
}

/**
 * @param {SqlResultPage} page
 * @param {string[]} keyFields
 */
function rowsByKey(page, keyFields) {
  const columnIndex = new Map(
    page.columns.map((column, index) => [column.name.toUpperCase(), index]),
  );
  const rows = new Map();
  for (const row of page.rows) {
    const values = rowValues(page, row);
    const key = keyFields
      .map((field) => values[field] ?? values[field.toUpperCase()] ?? "")
      .join(" | ");
    rows.set(key, { values });
    for (const field of keyFields) {
      if (!columnIndex.has(field.toUpperCase())) {
        rows.set(`missing-key:${field}`, { values });
      }
    }
  }
  return rows;
}

/**
 * @param {SqlResultPage} page
 * @param {unknown[]} row
 */
function rowValues(page, row) {
  /** @type {Record<string, string>} */
  const values = {};
  page.columns.forEach((column, index) => {
    const value = row[index];
    values[column.name] = value === null || value === undefined ? "" : String(value);
  });
  return values;
}

/**
 * @param {number} index
 * @param {string} sql
 * @returns {SqlWorkbenchSession}
 */
export function createSession(index, sql) {
  return {
    assistant: null,
    assistantErrorMessage: null,
    activeResultTabId: null,
    compare: createCompareState(),
    connectionId: "",
    errorMessage: null,
    execution: null,
    id: `sql-session-${index}`,
    label: `SQL ${index}`,
    mode: "sql",
    naturalLanguage: createNaturalLanguageState(),
    resultPage: null,
    resultPageIndex: 0,
    resultPageToken: null,
    resultPageTokens: [null],
    resultTabs: [],
    schema: "",
    sql,
    transactionMode: "auto",
    validation: null,
  };
}

/**
 * Lightweight UX hint only. The server still performs the authoritative
 * read-only validation inside the RUN_READ_ONLY execution path.
 *
 * @param {string} sql
 */
export function isLikelyReadOnlySql(sql) {
  return /^(?:\s|--[^\n]*(?:\n|$)|\/\*[\s\S]*?\*\/)*(?:select|with)\b/iu.test(sql);
}

/**
 * Lightweight UX hint only. The server performs the authoritative AST-based
 * controlled DML validation before commit.
 *
 * @param {string} sql
 */
export function isLikelyControlledDmlSql(sql) {
  return /^(?:\s|--[^\n]*(?:\n|$)|\/\*[\s\S]*?\*\/)*(?:insert|update|delete)\b/iu.test(sql);
}

/**
 * Splits editor text into executable SQL statements while ignoring semicolons
 * inside strings and comments. Leading comments are kept as annotations, not
 * executable statement anchors.
 *
 * @param {string} sqlText
 * @returns {SqlEditorStatement[]}
 */
export function findSqlEditorStatements(sqlText) {
  /** @type {SqlEditorStatement[]} */
  const statements = [];
  let statementStart = -1;
  /** @type {"" | "'" | "\"" | "`"} */
  let quotedBy = "";

  for (let index = 0; index < sqlText.length; index += 1) {
    const current = sqlText[index];
    const next = sqlText[index + 1];

    if (quotedBy) {
      if (current === quotedBy) {
        if (next === quotedBy) {
          index += 1;
        } else {
          quotedBy = "";
        }
      }
      continue;
    }

    if (current === "-" && next === "-") {
      index = findLineEnd(sqlText, index + 2);
      continue;
    }

    if (current === "/" && next === "*") {
      const commentEnd = sqlText.indexOf("*/", index + 2);
      index = commentEnd === -1 ? sqlText.length : commentEnd + 1;
      continue;
    }

    if (current === "'" || current === "\"" || current === "`") {
      if (statementStart === -1) {
        statementStart = index;
      }
      quotedBy = current;
      continue;
    }

    if (current === ";") {
      appendSqlEditorStatement(statements, sqlText, statementStart, index);
      statementStart = -1;
      continue;
    }

    if (statementStart === -1 && !/\s/u.test(current)) {
      statementStart = index;
    }
  }

  appendSqlEditorStatement(statements, sqlText, statementStart, sqlText.length);
  return statements;
}

/**
 * @param {SqlEditorStatement[]} statements
 * @param {string} sqlText
 * @param {number} from
 * @param {number} to
 */
function appendSqlEditorStatement(statements, sqlText, from, to) {
  if (from < 0) {
    return;
  }
  let end = to;
  while (end > from && /\s/u.test(sqlText[end - 1] ?? "")) {
    end -= 1;
  }
  const sql = sqlText.slice(from, end);
  if (sql.trim().length === 0) {
    return;
  }
  statements.push({ from, sql, to: end });
}

/**
 * @param {string} value
 * @param {number} start
 */
function findLineEnd(value, start) {
  const newlineIndex = value.indexOf("\n", start);
  return newlineIndex === -1 ? value.length : newlineIndex;
}

/**
 * @param {SqlConnectionSummary} connection
 */
export function buildLimits(connection) {
  return {
    maxRows: connection.maxRowsDefault ?? DEFAULT_LIMITS.maxRows,
    maxBytes: DEFAULT_LIMITS.maxBytes,
    timeoutSeconds: connection.timeoutSecondsDefault ?? DEFAULT_LIMITS.timeoutSeconds,
  };
}

/**
 * @param {SqlConnectionSummary} connection
 * @param {string} schema
 * @param {"VALIDATE" | "PREFLIGHT_DML" | "RUN_READ_ONLY" | "COMMIT_DML"} action
 * @param {string} sql
 * @param {string} idempotencyAction
 */
export function buildSqlQueryRequest(connection, schema, action, sql, idempotencyAction) {
  return {
    contractVersion: "1.0",
    connectionId: connection.connectionId,
    targetEnvironment: connection.targetEnvironment,
    schema,
    action,
    sql,
    parameters: [],
    limits: buildLimits(connection),
    idempotencyKey: createSqlIdempotencyKey(idempotencyAction),
  };
}

/**
 * @param {number} value
 * @param {number} min
 * @param {number} max
 */
export function clampNumber(value, min, max) {
  return Math.min(Math.max(value, min), max);
}

/**
 * @param {string[]} values
 */
export function formatValues(values) {
  return values.length > 0 ? values.join(" / ") : "无";
}

/**
 * @param {string} message
 * @returns {{summary: string, details: string[]}}
 */
export function splitDiagnosticMessage(message) {
  const lines = message
    .split(/\r?\n/u)
    .map((line) => line.trim())
    .filter(Boolean);
  return {
    summary: lines[0] ?? message,
    details: lines.slice(1),
  };
}

/**
 * @param {unknown} error
 */
export function shouldFetchValidationDiagnostics(error) {
  return (
    error instanceof ApiError &&
    error.message.includes(LEGACY_READ_ONLY_VALIDATION_ERROR)
  );
}

/**
 * @param {SqlValidationReport} report
 */
export function buildReadOnlyValidationDiagnosticMessage(report) {
  return [
    "SELECT 执行未通过服务端只读校验。控制面没有向 Worker 提交执行请求。",
    `statementType=${report.statementType}`,
    `validationLevel=${report.validationLevel}`,
    `rejectionReasons=${formatValues(report.rejectionReasons)}`,
    `risks=${formatValues(report.risks)}`,
    `referencedObjects=${formatValues(report.referencedObjects)}`,
    `unverifiedItems=${formatValues(report.unverifiedItems)}`,
    `sqlHash=${report.sqlHash}`,
    "nextStep=先修正 SQL 语法或点击校验查看完整报告；AI SQL 助手只提供参考，不能授权执行。",
  ].join("\n");
}

/**
 * @param {SqlValidationReport} report
 */
export function shouldAutoAnalyzeValidation(report) {
  if (report.validationLevel !== "REJECTED" || report.statementType !== "UNSUPPORTED") {
    return false;
  }
  return report.rejectionReasons.some((reason) => /syntax|parse|解析|语法/iu.test(reason));
}

/**
 * @param {SqlQueryRunResult} execution
 */
export function shouldAutoAnalyzeExecution(execution) {
  return execution.status === "FAILED" && Boolean(execution.errorCode || execution.errorMessage);
}

/**
 * @param {{
 *   errorMessage: string | null,
 *   execution?: SqlQueryRunResult | null,
 *   validation?: SqlValidationReport | null,
 * }} session
 * @returns {string | undefined}
 */
export function buildAssistantDiagnosticContext(session) {
  const parts = [];
  if (session.errorMessage) {
    parts.push(`errorMessage=${session.errorMessage}`);
  }
  if (session.execution?.errorCode || session.execution?.errorMessage) {
    parts.push(`executionErrorCode=${session.execution.errorCode ?? "none"}`);
    parts.push(`executionErrorMessage=${session.execution.errorMessage ?? "none"}`);
  }
  if (session.validation) {
    parts.push(`statementType=${session.validation.statementType}`);
    parts.push(`validationLevel=${session.validation.validationLevel}`);
    parts.push(`sqlHash=${session.validation.sqlHash}`);
  }
  if (session.validation?.rejectionReasons.length) {
    parts.push(`rejectionReasons=${session.validation.rejectionReasons.join(" / ")}`);
  }
  if (session.validation?.risks.length) {
    parts.push(`risks=${session.validation.risks.join(" / ")}`);
  }
  const value = parts.join("\n").trim();
  return value.length > 0 ? value : undefined;
}

/**
 * @param {string} action
 */
export function createSqlIdempotencyKey(action) {
  const randomPart =
    typeof globalThis.crypto?.randomUUID === "function"
      ? globalThis.crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `sql:${action}:${randomPart}`;
}

/**
 * @param {string} value
 */
export function splitCsv(value) {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}
