import { useEffect, useMemo, useRef, useState } from "react";
import {
  AlertTriangle,
  ChevronLeft,
  ChevronRight,
  Database,
  FileSearch,
  LoaderCircle,
  Plus,
  Settings2,
  ShieldCheck,
  Sparkles,
  Table2,
  X,
} from "lucide-react";

import { ApiError } from "../../api/client.js";
import {
  commitControlledSqlDml,
  preflightControlledSqlDml,
  runReadOnlySqlQuery,
} from "../../api/sql-api.js";
import { DataTable } from "../../components/data-display/DataTable.jsx";
import { StatusPill } from "../../components/data-display/StatusPill.jsx";
import toolbarStyles from "../../components/layout/PageToolbar.module.css";
import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { useWorkspaceLayout } from "../../components/layout/WorkspaceLayoutContext.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import { Dialog } from "../../components/primitives/Dialog.jsx";
import {
  useCreateSqlConnection,
  useDeleteSqlConnection,
  useRunSqlCompare,
  useSqlAssistant,
  useSqlConnections,
  useSqlMetadata,
  useSqlResultPage,
  useUpdateSqlConnection,
  useValidateSqlQuery,
} from "./use-sql-workbench.js";
import { SqlEditorPanel } from "./SqlEditorPanel.jsx";
import {
  buildCompareDiagnosticContext,
  buildCompareSql,
  buildNaturalLanguageDiagnosticContext,
  createCompareReport,
  createCompareState,
  createNaturalLanguageState,
  isLikelyReadOnlySql,
  isLikelyControlledDmlSql,
  validateCompareInput,
} from "./sql-workbench-utils.js";
import styles from "./SqlWorkbenchPage.module.css";

const DEFAULT_SQL = "";

const EMPTY_SESSION_SQL = "";
const METADATA_OBJECT_PAGE_SIZE = 30;
const METADATA_SCROLL_LOAD_OFFSET = 120;

let sqlResultTabCounter = 0;

/**
 * @typedef {import("../../schemas/sql-schemas.js").SqlConnectionSummary} SqlConnectionSummary
 * @typedef {import("../../schemas/sql-schemas.js").SqlValidationReport} SqlValidationReport
 * @typedef {import("../../schemas/sql-schemas.js").SqlDmlPreflightResult} SqlDmlPreflightResult
 * @typedef {import("../../schemas/sql-schemas.js").SqlDmlCommitRequest["query"]} SqlDmlCommitQuery
 * @typedef {import("../../schemas/sql-schemas.js").SqlQueryRunResult} SqlQueryRunResult
 * @typedef {import("../../schemas/sql-schemas.js").SqlResultPage} SqlResultPage
 * @typedef {import("../../schemas/sql-schemas.js").SqlMetadataResponse} SqlMetadataResponse
 * @typedef {import("../../schemas/sql-schemas.js").SqlAssistantResponse} SqlAssistantResponse
 * @typedef {import("./sql-workbench-utils.js").SqlCompareState} SqlCompareState
 * @typedef {import("./sql-workbench-utils.js").SqlNaturalLanguageState} SqlNaturalLanguageState
 * @typedef {import("./sql-workbench-utils.js").SqlSessionMode} SqlSessionMode
 * @typedef {import("./sql-workbench-utils.js").SqlTransactionMode} SqlTransactionMode
 * @typedef {"EXPLAIN_SQL" | "OPTIMIZE_SQL" | "ANALYZE_ERROR" | "GENERATE_SELECT" | "COMPARE_SUMMARY"} SqlAssistantAction
 * @typedef {{
 *   errorMessage: string | null,
 *   execution: SqlQueryRunResult | null,
 *   id: string,
 *   isPending: boolean,
 *   label: string,
 *   resultPageIndex: number,
 *   resultPage: SqlResultPage | null,
 *   resultPageToken: string | null,
 *   resultPageTokens: Array<string | null>,
 *   sql: string,
 * }} SqlResultTab
 * @typedef {{
 *   preflight: SqlDmlPreflightResult,
 *   request: SqlDmlCommitQuery,
 *   resultTabId: string,
 *   sessionId: string,
 * }} PendingDmlRiskConfirmation
 * @typedef {{
 *   context: string,
 *   idempotencyKey: string,
 * }} DmlSubmissionIdentity
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
 */

const DEFAULT_LIMITS = {
  maxRows: 500,
  maxBytes: 5_000_000,
  timeoutSeconds: 30,
};

const DEFAULT_EDITOR_RESULT_SPLIT = 72;
const EDITOR_RESULT_SPLIT_MIN = 42;
const EDITOR_RESULT_SPLIT_MAX = 84;
const LEGACY_READ_ONLY_VALIDATION_ERROR =
  "query must pass read-only validation before execution";

const PLATFORM_FORM_DEFAULTS = {
  DB2_FOR_I: {
    host: "",
    port: "446",
    defaultSchema: "",
    allowedSchemas: "",
  },
  H2: {
    host: "localhost",
    port: "9092",
    defaultSchema: "",
    allowedSchemas: "",
  },
  MYSQL: {
    host: "",
    port: "3306",
    defaultSchema: "",
    allowedSchemas: "",
  },
};

const DEFAULT_CONNECTION_FORM = {
  displayName: "",
  targetEnvironment: "dev",
  platformType: "DB2_FOR_I",
  host: "",
  port: "446",
  defaultSchema: "",
  allowedSchemas: "",
  credentialAlias: "",
  maxRowsDefault: "500",
  timeoutSecondsDefault: "30",
};

export function SqlWorkbenchPage() {
  const connectionsQuery = useSqlConnections();
  const createConnectionMutation = useCreateSqlConnection();
  const updateConnectionMutation = useUpdateSqlConnection();
  const deleteConnectionMutation = useDeleteSqlConnection();
  const validateMutation = useValidateSqlQuery();
  const compareMutation = useRunSqlCompare();
  const assistantMutation = useSqlAssistant();
  const [connectionOverrides, setConnectionOverrides] = useState(
    /** @type {SqlConnectionSummary[]} */ ([]),
  );
  const [deletedConnectionIds, setDeletedConnectionIds] = useState(/** @type {string[]} */ ([]));
  const connections = useMemo(
    () =>
      mergeConnections(
        connectionsQuery.data ?? [],
        connectionOverrides,
        deletedConnectionIds,
      ),
    [connectionsQuery.data, connectionOverrides, deletedConnectionIds],
  );
  const [sessions, setSessions] = useState(
    /** @type {SqlWorkbenchSession[]} */ ([createSession(1, DEFAULT_SQL)]),
  );
  const [activeSessionId, setActiveSessionId] = useState("sql-session-1");
  const [isObjectDrawerOpen, setIsObjectDrawerOpen] = useState(false);
  const [isConnectionDialogOpen, setIsConnectionDialogOpen] = useState(false);
  const [pendingDmlRiskConfirmation, setPendingDmlRiskConfirmation] = useState(
    /** @type {PendingDmlRiskConfirmation | null} */ (null),
  );
  const [isDmlRiskCommitPending, setIsDmlRiskCommitPending] = useState(false);
  const dmlSubmissionIdentities = useRef(
    /** @type {Map<string, DmlSubmissionIdentity>} */ (new Map()),
  );
  const { isWorkspaceExpanded } = useWorkspaceLayout();
  const [editorResultSplit, setEditorResultSplit] = useState(DEFAULT_EDITOR_RESULT_SPLIT);

  const activeSession =
    sessions.find((session) => session.id === activeSessionId) ?? sessions[0];
  const activeResultTab = resolveActiveResultTab(activeSession);
  const activeResultTabId = activeResultTab?.id ?? null;
  const activeConnection = resolveActiveConnection(connections, activeSession);
  const hasConnections = connections.length > 0;
  const activeSchema =
    activeConnection
      ? activeSession.schema ||
        activeConnection.defaultSchema ||
        activeConnection.allowedSchemas[0] ||
        ""
      : "";
  const activeLimits = activeConnection ? buildLimits(activeConnection) : DEFAULT_LIMITS;
  const metadataQuery = useSqlMetadata(
    activeConnection?.connectionId,
    activeSchema,
    isObjectDrawerOpen && !isWorkspaceExpanded,
  );

  const resultPageQuery = useSqlResultPage(
    activeResultTab?.execution?.resultId,
    activeResultTab?.resultPageToken,
  );
  const currentExecution = activeResultTab?.execution ?? activeSession.execution;
  const currentResultPage =
    resultPageQuery.data ?? activeResultTab?.resultPage ?? activeSession.resultPage;
  const currentResultError = activeResultTab?.errorMessage ?? activeSession.errorMessage;
  const isReadyConnection = activeConnection?.status === "READY";
  const hasSqlText = activeSession.sql.trim().length > 0;
  const canValidate =
    isReadyConnection &&
    activeConnection?.capabilities.includes("VALIDATE") === true;
  const canRunSqlStatement =
    isReadyConnection &&
    activeConnection?.capabilities.includes("RUN_READ_ONLY") === true;
  const canCommitDmlStatement =
    activeConnection?.capabilities.includes("PREFLIGHT_DML") === true &&
    activeConnection?.capabilities.includes("COMMIT_DML") === true &&
    activeSession.transactionMode === "manual" &&
    currentExecution?.status !== "UNKNOWN_REQUIRES_HANDOFF" &&
    isLikelyControlledDmlSql(activeSession.sql.trim());
  const canUseAssistant =
    canValidate &&
    hasSqlText &&
    !assistantMutation.isPending;
  const editorPanelStyle =
    /** @type {import("react").CSSProperties} */ ({
      gridTemplateRows: `auto minmax(170px, ${editorResultSplit}fr) 8px minmax(96px, ${
        100 - editorResultSplit
      }fr)`,
    });

  useEffect(() => {
    if (!resultPageQuery.data) {
      return;
    }
    if (activeResultTabId) {
      updateResultTab(activeSession.id, activeResultTabId, {
        resultPage: resultPageQuery.data,
      });
      return;
    }
    updateSession(activeSession.id, { resultPage: resultPageQuery.data });
  }, [activeResultTabId, activeSession.id, resultPageQuery.data]);

  /**
   * @param {string} sessionId
   * @param {Partial<SqlWorkbenchSession>} patch
   */
  function updateSession(sessionId, patch) {
    setSessions((currentSessions) =>
      currentSessions.map((session) =>
        session.id === sessionId ? { ...session, ...patch } : session,
      ),
    );
  }

  /**
   * @param {string} sessionId
   * @param {string} tabId
   * @param {Partial<SqlResultTab>} patch
   */
  function updateResultTab(sessionId, tabId, patch) {
    setSessions((currentSessions) =>
      currentSessions.map((session) => {
        if (session.id !== sessionId) {
          return session;
        }
        return {
          ...session,
          resultTabs: session.resultTabs.map((tab) =>
            tab.id === tabId ? { ...tab, ...patch } : tab,
          ),
        };
      }),
    );
  }

  /**
   * @param {string} sessionId
   * @param {SqlResultTab} tab
   */
  function appendResultTab(sessionId, tab) {
    setSessions((currentSessions) =>
      currentSessions.map((session) =>
        session.id === sessionId
          ? {
              ...session,
              activeResultTabId: tab.id,
              errorMessage: null,
              execution: null,
              resultPage: null,
              resultPageIndex: 0,
              resultPageToken: null,
              resultPageTokens: [null],
              resultTabs: [...session.resultTabs, tab],
            }
          : session,
      ),
    );
  }

  /**
   * @param {string} sql
   */
  function updateSql(sql) {
    updateSession(activeSession.id, {
      activeResultTabId: null,
      sql,
      validation: null,
      execution: null,
      resultPage: null,
      resultPageIndex: 0,
      resultPageToken: null,
      resultPageTokens: [null],
      resultTabs: [],
      errorMessage: null,
      assistant: null,
      assistantErrorMessage: null,
    });
  }

  /**
   * @param {SqlSessionMode} mode
   */
  function updateSessionMode(mode) {
    updateSession(activeSession.id, { mode });
  }

  /**
   * @param {File} file
   */
  async function importSqlFile(file) {
    if (!file.name.toLowerCase().endsWith(".sql")) {
      updateSession(activeSession.id, {
        errorMessage: "仅支持导入 .sql 文件",
      });
      return;
    }
    if (
      activeSession.sql.trim().length > 0 &&
      !window.confirm("导入会覆盖当前 SQL，是否继续？")
    ) {
      return;
    }
    updateSql(await file.text());
  }

  function exportSqlFile() {
    const defaultFileName = `${activeSession.label}.sql`;
    const requestedName = window.prompt("请输入导出文件名", defaultFileName);
    if (requestedName === null) {
      return;
    }
    const trimmedName = requestedName.trim();
    if (!trimmedName) {
      return;
    }
    const fileName = trimmedName.toLowerCase().endsWith(".sql")
      ? trimmedName
      : `${trimmedName}.sql`;
    const blob = new Blob([activeSession.sql], {
      type: "application/sql;charset=utf-8",
    });
    const objectUrl = URL.createObjectURL(blob);
    const link = document.createElement("a");
    link.href = objectUrl;
    link.download = fileName;
    link.click();
    URL.revokeObjectURL(objectUrl);
  }

  /**
   * @param {Partial<SqlNaturalLanguageState>} patch
   */
  function updateNaturalLanguageState(patch) {
    updateSession(activeSession.id, {
      naturalLanguage: {
        ...activeSession.naturalLanguage,
        ...patch,
      },
    });
  }

  function generateNaturalLanguageSql() {
    if (!activeConnection || !canValidate) {
      return;
    }
    const sessionId = activeSession.id;
    const naturalLanguage = activeSession.naturalLanguage;
    const sqlContext = activeSession.sql.trim() || "SELECT 1";
    assistantMutation.mutate(
      {
        contractVersion: "1.0",
        connectionId: activeConnection.connectionId,
        targetEnvironment: activeConnection.targetEnvironment,
        schema: activeSchema,
        assistantAction: "GENERATE_SELECT",
        sql: sqlContext,
        limits: buildLimits(activeConnection),
        diagnosticContext: buildNaturalLanguageDiagnosticContext(
          naturalLanguage,
          activeSchema,
          activeSession.sql,
        ),
        idempotencyKey: createSqlIdempotencyKey("ASSISTANT_GENERATE_SELECT"),
      },
      {
        onSuccess: (assistant) => {
          const draftSql =
            assistant.suggestions.find((suggestion) => suggestion.suggestedSql)
              ?.suggestedSql ?? "";
          updateSession(sessionId, {
            assistantErrorMessage: null,
            naturalLanguage: {
              ...naturalLanguage,
              draftSql,
              statusMessage: assistant.summary,
            },
          });
        },
        onError: (error) => {
          updateSession(sessionId, {
            assistantErrorMessage:
              error instanceof Error ? error.message : "自然语言 SQL 生成请求失败",
            naturalLanguage: {
              ...naturalLanguage,
              statusMessage: error instanceof Error ? error.message : "自然语言 SQL 生成请求失败",
            },
          });
        },
      },
    );
  }

  function applyNaturalLanguageDraft() {
    if (!activeConnection) {
      return;
    }
    const draftSql = activeSession.naturalLanguage.draftSql.trim();
    if (!draftSql) {
      return;
    }
    const sessionId = activeSession.id;
    updateSession(sessionId, {
      sql: draftSql,
      mode: "sql",
      validation: null,
      execution: null,
      resultPage: null,
      resultPageIndex: 0,
      resultPageToken: null,
      resultPageTokens: [null],
      errorMessage: null,
      naturalLanguage: {
        ...activeSession.naturalLanguage,
        statusMessage: "已应用到编辑器并提交服务端校验",
      },
    });
    submitValidationForSql({
      action: "VALIDATE",
      connection: activeConnection,
      schema: activeSchema,
      sessionId,
      sql: draftSql,
    });
  }

  /**
   * @param {Partial<SqlCompareState>} patch
   */
  function updateCompareState(patch) {
    updateSession(activeSession.id, {
      compare: {
        ...activeSession.compare,
        ...patch,
      },
    });
  }

  function runCompare() {
    if (!activeConnection) {
      return;
    }
    const sessionId = activeSession.id;
    const compareState = {
      ...activeSession.compare,
      baseLibrary: activeSession.compare.baseLibrary.trim() || activeSchema,
    };
    const inputError = validateCompareInput(compareState);
    if (inputError) {
      updateSession(sessionId, {
        compare: {
          ...compareState,
          errorMessage: inputError,
          statusMessage: null,
        },
      });
      return;
    }
    const compareSql = buildCompareSql(compareState);
    const limits = {
      ...buildLimits(activeConnection),
      maxRows: compareSql.maxRows,
    };
    const baseRequest =
      /** @type {import("../../schemas/sql-schemas.js").SqlQueryRunRequest} */ ({
        ...buildSqlQueryRequest(
          activeConnection,
          compareState.baseLibrary.trim(),
          "RUN_READ_ONLY",
          compareSql.baseSql,
          "COMPARE_BASE",
        ),
        limits,
      });
    const compareRequest =
      /** @type {import("../../schemas/sql-schemas.js").SqlQueryRunRequest} */ ({
        ...buildSqlQueryRequest(
          activeConnection,
          compareState.compareLibrary.trim(),
          "RUN_READ_ONLY",
          compareSql.compareSql,
          "COMPARE_TARGET",
        ),
        limits,
      });
    updateSession(sessionId, {
      compare: {
        ...compareState,
        assistant: null,
        errorMessage: null,
        report: null,
        statusMessage: "正在执行两侧只读查询",
      },
    });
    compareMutation.mutate(
      { baseRequest, compareRequest },
      {
        onSuccess: (result) => {
          if (!result.basePage || !result.comparePage) {
            updateSession(sessionId, {
              compare: {
                ...compareState,
                errorMessage: "对比查询没有返回可读取结果集",
                statusMessage: null,
              },
            });
            return;
          }
          const report = createCompareReport({
            baseLibrary: compareState.baseLibrary.trim(),
            basePage: result.basePage,
            baseSql: compareSql.baseSql,
            compareLibrary: compareState.compareLibrary.trim(),
            comparePage: result.comparePage,
            compareSql: compareSql.compareSql,
            comparedFields: compareSql.comparedFields,
            ignoredFields: compareSql.ignoredFields,
            keyFields: compareSql.keyFields,
            tableName: compareState.tableName.trim(),
          });
          updateSession(sessionId, {
            compare: {
              ...compareState,
              errorMessage: null,
              report,
              statusMessage: "对比完成，正在生成 AI 摘要",
            },
          });
          requestCompareSummary(sessionId, report);
        },
        onError: (error) => {
          updateSession(sessionId, {
            compare: {
              ...compareState,
              errorMessage: error instanceof Error ? error.message : "SQL 对比执行失败",
              statusMessage: null,
            },
          });
        },
      },
    );
  }

  /**
   * @param {string} sessionId
   * @param {import("./sql-workbench-utils.js").SqlCompareReport} report
   */
  function requestCompareSummary(sessionId, report) {
    if (!activeConnection) {
      return;
    }
    assistantMutation.mutate(
      {
        contractVersion: "1.0",
        connectionId: activeConnection.connectionId,
        targetEnvironment: activeConnection.targetEnvironment,
        schema: report.baseLibrary,
        assistantAction: "COMPARE_SUMMARY",
        sql: report.baseSql,
        limits: buildLimits(activeConnection),
        diagnosticContext: buildCompareDiagnosticContext(report),
        idempotencyKey: createSqlIdempotencyKey("ASSISTANT_COMPARE_SUMMARY"),
      },
      {
        onSuccess: (assistant) => {
          updateSession(sessionId, {
            compare: {
              ...activeSession.compare,
              assistant,
              errorMessage: null,
              report,
              statusMessage: "AI 摘要已生成",
            },
          });
        },
        onError: (error) => {
          updateSession(sessionId, {
            compare: {
              ...activeSession.compare,
              assistant: null,
              errorMessage: error instanceof Error ? error.message : "AI 对比摘要生成失败",
              report,
              statusMessage: null,
            },
          });
        },
      },
    );
  }

  /**
   * @param {string} connectionId
   */
  function selectConnection(connectionId) {
    const connection = connections.find((item) => item.connectionId === connectionId);
    updateSession(
      activeSession.id,
      buildConnectionSessionPatch(connection ?? null, activeSchema, connectionId),
    );
  }

  /**
   * @param {SqlConnectionSummary} connection
   */
  function handleConnectionCreated(connection) {
    setDeletedConnectionIds((current) => current.filter((item) => item !== connection.connectionId));
    setConnectionOverrides((current) => upsertConnection(current, connection));
    updateSession(
      activeSession.id,
      buildConnectionSessionPatch(connection, activeSchema, connection.connectionId),
    );
  }

  /**
   * @param {SqlConnectionSummary} connection
   */
  function handleConnectionUpdated(connection) {
    setDeletedConnectionIds((current) => current.filter((item) => item !== connection.connectionId));
    setConnectionOverrides((current) => upsertConnection(current, connection));
    if (activeConnection?.connectionId === connection.connectionId) {
      updateSession(
        activeSession.id,
        buildConnectionSessionPatch(connection, activeSchema, connection.connectionId),
      );
    }
  }

  /**
   * @param {string} connectionId
   */
  function handleConnectionDeleted(connectionId) {
    const nextConnections = connections.filter(
      (connection) => connection.connectionId !== connectionId,
    );
    setDeletedConnectionIds((current) =>
      current.includes(connectionId) ? current : [...current, connectionId],
    );
    setConnectionOverrides((current) =>
      current.filter((connection) => connection.connectionId !== connectionId),
    );
    if (activeConnection?.connectionId === connectionId) {
      updateSession(
        activeSession.id,
        buildConnectionSessionPatch(nextConnections[0] ?? null, "", nextConnections[0]?.connectionId ?? ""),
      );
    }
  }

  /**
   * @param {string} schema
   */
  function selectSchema(schema) {
    updateSession(activeSession.id, {
      schema,
      validation: null,
      execution: null,
      resultPage: null,
      resultPageIndex: 0,
      resultPageToken: null,
      resultPageTokens: [null],
      errorMessage: null,
      assistant: null,
      assistantErrorMessage: null,
    });
  }

  function addSession() {
    const nextIndex = sessions.length + 1;
    const nextSession = createSession(nextIndex, EMPTY_SESSION_SQL);
    setSessions((currentSessions) => [...currentSessions, nextSession]);
    setActiveSessionId(nextSession.id);
  }

  /**
   * @param {{
   *   action: "VALIDATE" | "PREFLIGHT_DML",
   *   connection: SqlConnectionSummary,
   *   schema: string,
   *   sessionId: string,
   *   sql: string,
   * }} input
   */
  function submitValidationForSql(input) {
    const request = buildSqlQueryRequest(
      input.connection,
      input.schema,
      input.action,
      input.sql,
      input.action,
    );

    validateMutation.mutate(
      request,
      {
        onSuccess: (report) => {
          updateSession(input.sessionId, {
            validation: report,
            execution: null,
            resultPage: null,
            resultPageIndex: 0,
            resultPageToken: null,
            resultPageTokens: [null],
            errorMessage: null,
            assistant: null,
            assistantErrorMessage: null,
          });
          maybeRequestSyntaxErrorAnalysis({
            connection: input.connection,
            errorMessage: null,
            limits: request.limits,
            report,
            schema: input.schema,
            sessionId: input.sessionId,
            sql: input.sql,
          });
        },
        onError: (error) => {
          updateSession(input.sessionId, {
            validation: null,
            execution: null,
            resultPage: null,
            resultPageIndex: 0,
            resultPageToken: null,
            resultPageTokens: [null],
            errorMessage: error instanceof Error ? error.message : "SQL 校验请求失败",
            assistant: null,
            assistantErrorMessage: null,
          });
        },
      },
    );
  }

  /**
   * @param {string} sqlText
   */
  function runReadOnlySql(sqlText) {
    if (!activeConnection || !canRunSqlStatement) {
      return;
    }
    const sql = sqlText.trim();
    if (!isLikelyReadOnlySql(sql)) {
      return;
    }
    const sessionId = activeSession.id;
    const schema = activeSchema;
    const connection = activeConnection;
    const request = buildSqlQueryRequest(
      connection,
      schema,
      "RUN_READ_ONLY",
      sql,
      "RUN_READ_ONLY",
    );
    const resultTab = createResultTab(activeSession.resultTabs.length + 1, sql);
    appendResultTab(sessionId, resultTab);

    void runReadOnlySqlQuery(request)
      .then((execution) => {
        updateResultTab(sessionId, resultTab.id, {
          execution,
          errorMessage: execution.errorMessage ?? null,
          isPending: false,
          resultPage: null,
          resultPageIndex: 0,
          resultPageToken: null,
          resultPageTokens: [null],
        });
        maybeRequestExecutionErrorAnalysis({
          connection,
          execution,
          limits: request.limits,
          schema,
          sessionId,
          sql,
        });
      })
      .catch((error) => {
        const errorMessage = error instanceof Error ? error.message : "SELECT 执行请求失败";
        updateResultTab(sessionId, resultTab.id, {
          errorMessage,
          execution: null,
          isPending: false,
          resultPage: null,
          resultPageIndex: 0,
          resultPageToken: null,
          resultPageTokens: [null],
        });
        updateSession(sessionId, {
          errorMessage,
          assistant: null,
          assistantErrorMessage: null,
        });
        if (shouldFetchValidationDiagnostics(error)) {
          validateMutation.mutate(
            {
              ...request,
              idempotencyKey: createSqlIdempotencyKey("RUN_READ_ONLY_DIAGNOSTIC"),
            },
            {
              onSuccess: (report) => {
                const diagnosticMessage = buildReadOnlyValidationDiagnosticMessage(report);
                updateResultTab(sessionId, resultTab.id, {
                  errorMessage: diagnosticMessage,
                  execution: null,
                  isPending: false,
                  resultPage: null,
                  resultPageIndex: 0,
                  resultPageToken: null,
                  resultPageTokens: [null],
                });
                updateSession(sessionId, {
                  validation: report,
                  errorMessage: diagnosticMessage,
                  assistant: null,
                  assistantErrorMessage: null,
                });
                maybeRequestSyntaxErrorAnalysis({
                  connection,
                  errorMessage: diagnosticMessage,
                  limits: request.limits,
                  report,
                  schema,
                  sessionId,
                  sql,
                });
              },
              onError: (validationError) => {
                const validationErrorMessage =
                  validationError instanceof Error
                    ? validationError.message
                    : "服务端校验报告补充失败";
                updateResultTab(sessionId, resultTab.id, {
                  errorMessage: `${errorMessage}\nvalidationDiagnosticError=${validationErrorMessage}`,
                  isPending: false,
                });
                updateSession(sessionId, {
                  errorMessage: `${errorMessage}\nvalidationDiagnosticError=${validationErrorMessage}`,
                });
              },
            },
          );
        }
      });
  }

  async function commitControlledDml() {
    if (!activeConnection || !canCommitDmlStatement) {
      return;
    }
    const sql = activeSession.sql.trim();
    const sessionId = activeSession.id;
    const schema = activeSchema;
    const connection = activeConnection;
    const idempotencyKey = dmlIdempotencyKeyFor(
      sessionId,
      connection,
      schema,
      sql,
    );
    const preflightRequest = buildSqlQueryRequest(
      connection,
      schema,
      "PREFLIGHT_DML",
      sql,
      "PREFLIGHT_DML",
      idempotencyKey,
    );
    const request = /** @type {SqlDmlCommitQuery} */ ({
      ...preflightRequest,
      action: "COMMIT_DML",
    });
    const resultTab = createResultTab(activeSession.resultTabs.length + 1, sql);
    appendResultTab(sessionId, resultTab);

    try {
      const preflight = await preflightControlledSqlDml(preflightRequest);
      const report = preflight.validation;
      updateSession(sessionId, {
        validation: report,
        errorMessage: null,
        assistant: null,
        assistantErrorMessage: null,
      });
      if (report.validationLevel === "REJECTED") {
        clearDmlSubmissionIdentity(sessionId, idempotencyKey);
        throw new Error(buildDmlValidationDiagnosticMessage(report));
      }
      if (!preflight.impactPreview) {
        clearDmlSubmissionIdentity(sessionId, idempotencyKey);
        throw new Error("服务端 DML 预检未返回影响预览，未提交执行请求。");
      }
      if (report.risks.length > 0) {
        setPendingDmlRiskConfirmation({
          preflight,
          request,
          resultTabId: resultTab.id,
          sessionId,
        });
        return;
      }
      const execution = await commitControlledSqlDml({
        contractVersion: "1.1",
        query: request,
        confirmation: buildDmlRiskConfirmation(report),
        receipt: preflight.receipt,
      });
      completeDmlSubmissionIdentity(sessionId, idempotencyKey, execution);
      updateResultTab(sessionId, resultTab.id, {
        execution,
        errorMessage: execution.errorMessage ?? null,
        isPending: false,
        resultPage: null,
        resultPageIndex: 0,
        resultPageToken: null,
        resultPageTokens: [null],
      });
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : "DML 提交请求失败";
      updateResultTab(sessionId, resultTab.id, {
        errorMessage,
        execution: null,
        isPending: false,
        resultPage: null,
        resultPageIndex: 0,
        resultPageToken: null,
        resultPageTokens: [null],
      });
      updateSession(sessionId, {
        errorMessage,
        assistant: null,
        assistantErrorMessage: null,
      });
    }
  }

  async function confirmDmlRiskCommit() {
    if (!pendingDmlRiskConfirmation || isDmlRiskCommitPending) {
      return;
    }
    const { preflight, request, resultTabId, sessionId } = pendingDmlRiskConfirmation;
    setIsDmlRiskCommitPending(true);
    try {
      const execution = await commitControlledSqlDml({
        contractVersion: "1.1",
        query: request,
        confirmation: buildDmlRiskConfirmation(preflight.validation),
        receipt: preflight.receipt,
      });
      completeDmlSubmissionIdentity(sessionId, request.idempotencyKey, execution);
      updateResultTab(sessionId, resultTabId, {
        execution,
        errorMessage: execution.errorMessage ?? null,
        isPending: false,
        resultPage: null,
        resultPageIndex: 0,
        resultPageToken: null,
        resultPageTokens: [null],
      });
      setPendingDmlRiskConfirmation(null);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : "DML 提交请求失败";
      updateResultTab(sessionId, resultTabId, {
        errorMessage,
        execution: null,
        isPending: false,
        resultPage: null,
        resultPageIndex: 0,
        resultPageToken: null,
        resultPageTokens: [null],
      });
      updateSession(sessionId, {
        errorMessage,
        assistant: null,
        assistantErrorMessage: null,
      });
      setPendingDmlRiskConfirmation(null);
    } finally {
      setIsDmlRiskCommitPending(false);
    }
  }

  function cancelDmlRiskConfirmation() {
    if (!pendingDmlRiskConfirmation || isDmlRiskCommitPending) {
      return;
    }
    updateResultTab(
      pendingDmlRiskConfirmation.sessionId,
      pendingDmlRiskConfirmation.resultTabId,
      {
        errorMessage: "DML 提交已取消，未向服务端提交执行请求。",
        execution: null,
        isPending: false,
      },
    );
    clearDmlSubmissionIdentity(
      pendingDmlRiskConfirmation.sessionId,
      pendingDmlRiskConfirmation.request.idempotencyKey,
    );
    setPendingDmlRiskConfirmation(null);
  }

  /**
   * @param {string} sessionId
   * @param {SqlConnectionSummary} connection
   * @param {string} schema
   * @param {string} sql
   */
  function dmlIdempotencyKeyFor(sessionId, connection, schema, sql) {
    const context = dmlSubmissionContext(connection, schema, sql);
    const existing = dmlSubmissionIdentities.current.get(sessionId);
    if (existing?.context === context) {
      return existing.idempotencyKey;
    }
    const idempotencyKey = createSqlIdempotencyKey("COMMIT_DML");
    dmlSubmissionIdentities.current.set(sessionId, { context, idempotencyKey });
    return idempotencyKey;
  }

  /**
   * @param {string} sessionId
   * @param {string} idempotencyKey
   * @param {SqlQueryRunResult} execution
   */
  function completeDmlSubmissionIdentity(sessionId, idempotencyKey, execution) {
    if (execution.status !== "UNKNOWN_REQUIRES_HANDOFF") {
      clearDmlSubmissionIdentity(sessionId, idempotencyKey);
    }
  }

  /**
   * @param {string} sessionId
   * @param {string} idempotencyKey
   */
  function clearDmlSubmissionIdentity(sessionId, idempotencyKey) {
    const existing = dmlSubmissionIdentities.current.get(sessionId);
    if (existing?.idempotencyKey === idempotencyKey) {
      dmlSubmissionIdentities.current.delete(sessionId);
    }
  }

  /**
   * @param {{
   *   assistantAction: SqlAssistantAction,
   *   connection: SqlConnectionSummary,
   *   diagnosticContext?: string,
   *   limits: {maxRows: number, maxBytes: number, timeoutSeconds: number},
   *   schema: string,
   *   sessionId: string,
   *   sql: string,
   * }} input
   */
  function submitAssistantRequest(input) {
    assistantMutation.mutate(
      {
        contractVersion: "1.0",
        connectionId: input.connection.connectionId,
        targetEnvironment: input.connection.targetEnvironment,
        schema: input.schema,
        assistantAction: input.assistantAction,
        sql: input.sql,
        limits: input.limits,
        diagnosticContext: input.diagnosticContext,
        idempotencyKey: createSqlIdempotencyKey(`ASSISTANT_${input.assistantAction}`),
      },
      {
        onSuccess: (assistant) => {
          updateSession(input.sessionId, {
            assistant,
            assistantErrorMessage: null,
          });
        },
        onError: (error) => {
          updateSession(input.sessionId, {
            assistant: null,
            assistantErrorMessage:
              error instanceof Error ? error.message : "AI SQL 助手请求失败",
          });
        },
      },
    );
  }

  /**
   * @param {{
   *   connection: SqlConnectionSummary,
   *   errorMessage: string | null,
   *   limits: {maxRows: number, maxBytes: number, timeoutSeconds: number},
   *   report: SqlValidationReport,
   *   schema: string,
   *   sessionId: string,
   *   sql: string,
   * }} input
   */
  function maybeRequestSyntaxErrorAnalysis(input) {
    if (!shouldAutoAnalyzeValidation(input.report)) {
      return;
    }
    submitAssistantRequest({
      assistantAction: "ANALYZE_ERROR",
      connection: input.connection,
      diagnosticContext: buildAssistantDiagnosticContext({
        errorMessage: input.errorMessage,
        execution: null,
        validation: input.report,
      }),
      limits: input.limits,
      schema: input.schema,
      sessionId: input.sessionId,
      sql: input.sql,
    });
  }

  /**
   * @param {{
   *   connection: SqlConnectionSummary,
   *   execution: SqlQueryRunResult,
   *   limits: {maxRows: number, maxBytes: number, timeoutSeconds: number},
   *   schema: string,
   *   sessionId: string,
   *   sql: string,
   * }} input
   */
  function maybeRequestExecutionErrorAnalysis(input) {
    if (!shouldAutoAnalyzeExecution(input.execution)) {
      return;
    }
    submitAssistantRequest({
      assistantAction: "ANALYZE_ERROR",
      connection: input.connection,
      diagnosticContext: buildAssistantDiagnosticContext({
        errorMessage: input.execution.errorMessage ?? input.execution.status,
        execution: input.execution,
        validation: null,
      }),
      limits: input.limits,
      schema: input.schema,
      sessionId: input.sessionId,
      sql: input.sql,
    });
  }

  /**
   * @param {SqlAssistantAction} assistantAction
   */
  function requestAssistant(assistantAction) {
    if (!activeConnection || !canUseAssistant) {
      return;
    }

    submitAssistantRequest({
      assistantAction,
      connection: activeConnection,
      diagnosticContext: buildAssistantDiagnosticContext(activeSession),
      limits: buildLimits(activeConnection),
      schema: activeSchema,
      sessionId: activeSession.id,
      sql: activeSession.sql,
    });
  }

  function readNextResultPage() {
    if (!activeResultTab || !currentResultPage?.nextCursor) {
      return;
    }
    const nextIndex = activeResultTab.resultPageIndex + 1;
    const nextTokens = [...activeResultTab.resultPageTokens];
    nextTokens[nextIndex] = currentResultPage.nextCursor;
    updateResultTab(activeSession.id, activeResultTab.id, {
      resultPageIndex: nextIndex,
      resultPageToken: currentResultPage.nextCursor,
      resultPageTokens: nextTokens,
    });
  }

  function readPreviousResultPage() {
    if (!activeResultTab || activeResultTab.resultPageIndex <= 0) {
      return;
    }
    const previousIndex = activeResultTab.resultPageIndex - 1;
    updateResultTab(activeSession.id, activeResultTab.id, {
      resultPageIndex: previousIndex,
      resultPageToken: activeResultTab.resultPageTokens[previousIndex] ?? null,
    });
  }

  /**
   * @param {string} tabId
   */
  function selectResultTab(tabId) {
    updateSession(activeSession.id, { activeResultTabId: tabId });
  }

  /**
   * @param {SqlTransactionMode} transactionMode
   */
  function updateTransactionMode(transactionMode) {
    updateSession(activeSession.id, { transactionMode });
  }

  /**
   * @param {number} nextSplit
   */
  function updateEditorResultSplit(nextSplit) {
    setEditorResultSplit(clampNumber(
      nextSplit,
      EDITOR_RESULT_SPLIT_MIN,
      EDITOR_RESULT_SPLIT_MAX,
    ));
  }

  /**
   * @param {number} clientY
   * @param {HTMLElement} panel
   */
  function updateEditorResultSplitFromPointer(clientY, panel) {
    const panelRect = panel.getBoundingClientRect();
    const tabs = panel.querySelector('[role="tablist"]');
    const tabsHeight = tabs instanceof HTMLElement ? tabs.getBoundingClientRect().height : 0;
    const splitTop = panelRect.top + tabsHeight + 10;
    const availableHeight = Math.max(1, panelRect.height - tabsHeight - 28);
    updateEditorResultSplit(((clientY - splitTop) / availableHeight) * 100);
  }

  /**
   * @param {import("react").PointerEvent<HTMLDivElement>} event
   */
  function startEditorResultResize(event) {
    const panel = event.currentTarget.parentElement;
    if (!(panel instanceof HTMLElement)) {
      return;
    }
    const editorPanel = panel;
    event.preventDefault();
    updateEditorResultSplitFromPointer(event.clientY, editorPanel);

    /**
     * @param {PointerEvent} moveEvent
     */
    function handlePointerMove(moveEvent) {
      updateEditorResultSplitFromPointer(moveEvent.clientY, editorPanel);
    }

    function stopResize() {
      document.removeEventListener("pointermove", handlePointerMove);
      document.removeEventListener("pointerup", stopResize);
      document.removeEventListener("pointercancel", stopResize);
    }

    document.addEventListener("pointermove", handlePointerMove);
    document.addEventListener("pointerup", stopResize);
    document.addEventListener("pointercancel", stopResize);
  }

  /**
   * @param {import("react").KeyboardEvent<HTMLDivElement>} event
   */
  function handleEditorResultResizeKeyDown(event) {
    if (event.key === "ArrowUp") {
      event.preventDefault();
      updateEditorResultSplit(editorResultSplit - 4);
    }
    if (event.key === "ArrowDown") {
      event.preventDefault();
      updateEditorResultSplit(editorResultSplit + 4);
    }
    if (event.key === "Home") {
      event.preventDefault();
      updateEditorResultSplit(EDITOR_RESULT_SPLIT_MIN);
    }
    if (event.key === "End") {
      event.preventDefault();
      updateEditorResultSplit(EDITOR_RESULT_SPLIT_MAX);
    }
  }

  if (connectionsQuery.error) {
    const title =
      connectionsQuery.error instanceof ApiError &&
      connectionsQuery.error.kind === "contract"
        ? "SQL 连接契约不兼容"
        : "SQL 连接加载失败";

    return (
      <SqlWorkbenchFrame>
        <Notice
          detail="页面已阻止异常连接数据进入工作台，请检查控制面返回契约。"
          title={title}
        />
      </SqlWorkbenchFrame>
    );
  }

  return (
    <SqlWorkbenchFrame>
      <section
        aria-label="SQL 工作区连接上下文"
        className={`${styles.connectionBar} ${toolbarStyles.surface}`}
      >
        <div className={styles.connectionMeta}>
          <StatusPill tone={isReadyConnection ? "success" : "warning"}>
            {connectionsQuery.isLoading
              ? "正在加载连接目录"
              : activeConnection
                ? `${isReadyConnection ? "已连接" : activeConnection.status} · ${activeConnection.targetEnvironment}`
                : "未配置连接"}
          </StatusPill>
          <select
            aria-label="选择 SQL 连接"
            className={styles.connectionSelect}
            disabled={connectionsQuery.isLoading || !hasConnections}
            onChange={(event) => selectConnection(event.target.value)}
            value={activeConnection?.connectionId ?? ""}
          >
            {!activeConnection ? <option value="">无可用连接</option> : null}
            {connections.map((connection) => (
              <option key={connection.connectionId} value={connection.connectionId}>
                {connection.connectionId}
              </option>
            ))}
          </select>
          <span>{activeSchema || "未选择 Schema"}</span>
          <span>maxRows {activeLimits.maxRows}</span>
        </div>
        <div className={`${styles.connectionActions} ${toolbarStyles.actionGroup}`}>
          <button
            className={`${toolbarStyles.button} ${toolbarStyles.neutral}`}
            disabled={!hasConnections}
            onClick={() => setIsObjectDrawerOpen((current) => !current)}
            type="button"
          >
            <Database aria-hidden="true" size={15} />
            对象浏览器
          </button>
          <button
            className={`${toolbarStyles.button} ${toolbarStyles.neutral}`}
            onClick={() => setIsConnectionDialogOpen(true)}
            type="button"
          >
            <Settings2 aria-hidden="true" size={15} />
            管理连接
          </button>
        </div>
      </section>

      <section
        className={`${styles.workbenchGrid} ${isWorkspaceExpanded ? styles.expandedGrid : ""} ${
          isObjectDrawerOpen ? styles.withObjectDrawer : ""
        }`}
      >
        {isObjectDrawerOpen && !isWorkspaceExpanded && activeConnection ? (
          <ObjectBrowser
            activeConnection={activeConnection}
            activeSchema={activeSchema}
            connections={connections}
            metadata={metadataQuery.data ?? null}
            metadataError={metadataQuery.error instanceof Error ? metadataQuery.error.message : null}
            metadataPending={metadataQuery.isLoading || metadataQuery.isFetching}
            onClose={() => setIsObjectDrawerOpen(false)}
            onSelectConnection={selectConnection}
            onSelectSchema={selectSchema}
          />
        ) : null}

        <main className={styles.editorPanel} style={editorPanelStyle}>
          <SessionTabs
            activeSessionId={activeSession.id}
            onAddSession={addSession}
            onSelectSession={setActiveSessionId}
            sessions={sessions}
          />

          {!connectionsQuery.isLoading && !hasConnections ? (
            <Notice
              detail="连接目录为空。请先新建开发或测试环境连接，并完成 Worker 侧凭据绑定后再校验或执行 SQL。"
              title="尚未配置 SQL 连接"
            />
          ) : null}

          <SqlEditorPanel
            activeSchema={activeSchema}
            canCommitDmlStatement={canCommitDmlStatement}
            canRunSqlStatement={canRunSqlStatement}
            comparePending={compareMutation.isPending}
            naturalLanguagePending={assistantMutation.isPending}
            onExportSql={exportSqlFile}
            onGenerateNaturalLanguageSql={generateNaturalLanguageSql}
            onImportSqlFile={importSqlFile}
            onNaturalLanguageChange={updateNaturalLanguageState}
            onCompareChange={updateCompareState}
            onModeChange={updateSessionMode}
            onCommitDml={commitControlledDml}
            onRunCompare={runCompare}
            onRunStatement={runReadOnlySql}
            onSqlChange={updateSql}
            onTransactionModeChange={updateTransactionMode}
            session={activeSession}
          />

          <div
            aria-label="调整 SQL 编辑区和查询结果高度"
            aria-orientation="horizontal"
            aria-valuemax={EDITOR_RESULT_SPLIT_MAX}
            aria-valuemin={EDITOR_RESULT_SPLIT_MIN}
            aria-valuenow={Math.round(editorResultSplit)}
            aria-valuetext={`SQL 编辑区 ${Math.round(editorResultSplit)}%`}
            className={styles.resultResizeHandle}
            onKeyDown={handleEditorResultResizeKeyDown}
            onPointerDown={startEditorResultResize}
            role="separator"
            tabIndex={0}
          >
            <span aria-hidden="true" />
          </div>

          <ResultPanel
            assistant={activeSession.assistant}
            assistantErrorMessage={activeSession.assistantErrorMessage}
            assistantPending={assistantMutation.isPending}
            canUseAssistant={canUseAssistant}
            errorMessage={currentResultError}
            execution={currentExecution}
            isLoading={Boolean(activeResultTab?.isPending) || resultPageQuery.isFetching}
            naturalLanguage={activeSession.naturalLanguage}
            naturalLanguagePending={assistantMutation.isPending}
            onApplyAssistantSuggestion={updateSql}
            onApplyNaturalLanguageDraft={applyNaturalLanguageDraft}
            onNextPage={() => readNextResultPage()}
            onPreviousPage={() => readPreviousResultPage()}
            onRequestAssistant={requestAssistant}
            onSelectResultTab={selectResultTab}
            pageIndex={activeResultTab?.resultPageIndex ?? activeSession.resultPageIndex}
            resultPage={currentResultPage}
            resultTabs={activeSession.resultTabs}
            selectedResultTabId={activeResultTabId}
            sessionMode={activeSession.mode}
            showInlineAssistant={isWorkspaceExpanded}
          />
        </main>

        {!isWorkspaceExpanded ? (
          <InfoPanel
            assistant={activeSession.assistant}
            assistantErrorMessage={activeSession.assistantErrorMessage}
            assistantPending={assistantMutation.isPending}
            canUseAssistant={canUseAssistant}
            execution={currentExecution}
            onApplyAssistantSuggestion={updateSql}
            onRequestAssistant={requestAssistant}
          />
        ) : null}
      </section>

      <ConnectionManagerDialog
        activeConnectionId={activeConnection?.connectionId ?? ""}
        connections={connections}
        createPending={createConnectionMutation.isPending}
        deletePending={deleteConnectionMutation.isPending}
        key={isConnectionDialogOpen ? "connection-dialog-open" : "connection-dialog-closed"}
        onClose={() => setIsConnectionDialogOpen(false)}
        onCreate={(request) => {
          createConnectionMutation.mutate(request, {
            onSuccess: (connection) => {
              handleConnectionCreated(connection);
              setIsConnectionDialogOpen(false);
            },
          });
        }}
        onDelete={(connectionId) => {
          deleteConnectionMutation.mutate(connectionId, {
            onSuccess: handleConnectionDeleted,
          });
        }}
        onUpdate={(connectionId, request) => {
          updateConnectionMutation.mutate(
            { connectionId, request },
            {
              onSuccess: handleConnectionUpdated,
            },
          );
        }}
        open={isConnectionDialogOpen}
        updatePending={updateConnectionMutation.isPending}
      />
      <DmlRiskConfirmationDialog
        isPending={isDmlRiskCommitPending}
        onCancel={cancelDmlRiskConfirmation}
        onConfirm={confirmDmlRiskCommit}
        open={Boolean(pendingDmlRiskConfirmation)}
        preflight={pendingDmlRiskConfirmation?.preflight ?? null}
      />
    </SqlWorkbenchFrame>
  );
}

/**
 * @param {{children: import("react").ReactNode}} props
 */
function SqlWorkbenchFrame({ children }) {
  return (
    <WorkspacePageFrame className={styles.sqlCanvas}>
      <WorkspaceStatusBar title="SQL 工作台" />
      {children}
    </WorkspacePageFrame>
  );
}

/**
 * @param {{
 *   activeConnection: SqlConnectionSummary,
 *   activeSchema: string,
 *   connections: SqlConnectionSummary[],
 *   metadata: SqlMetadataResponse | null,
 *   metadataError: string | null,
 *   metadataPending: boolean,
 *   onClose: () => void,
 *   onSelectConnection: (connectionId: string) => void,
 *   onSelectSchema: (schema: string) => void,
 * }} props
 */
function ObjectBrowser({
  activeConnection,
  activeSchema,
  connections,
  metadata,
  metadataError,
  metadataPending,
  onClose,
  onSelectConnection,
  onSelectSchema,
}) {
  return (
    <aside aria-label="数据库对象浏览器" className={styles.objectDrawer}>
      <div className={styles.drawerHeader}>
        <PanelHeading
          detail="连接、Schema、表与字段"
          icon={Database}
          title="连接与对象"
        />
        <button aria-label="关闭对象浏览器" onClick={onClose} type="button">
          <X aria-hidden="true" size={16} />
        </button>
      </div>
      <div className={styles.connectionList}>
        {connections.map((connection) => (
          <button
            className={`${styles.connectionButton} ${
              connection.connectionId === activeConnection.connectionId ? styles.active : ""
            }`}
            key={connection.connectionId}
            onClick={() => onSelectConnection(connection.connectionId)}
            type="button"
          >
            <strong>{connection.connectionId}</strong>
            <span>{connection.displayName}</span>
            <small>{connection.targetEnvironment}</small>
          </button>
        ))}
      </div>
      <div className={styles.schemaTree}>
        <div className={styles.schemaSection}>
          <span>Schema</span>
          <div className={styles.schemaOptions}>
            {activeConnection.allowedSchemas.map((schema) => (
              <button
                className={schema === activeSchema ? styles.activeSchema : ""}
                key={schema}
                onClick={() => onSelectSchema(schema)}
                type="button"
              >
                {schema}
              </button>
            ))}
          </div>
        </div>
        <span>Objects</span>
        <MetadataObjects
          activeSchema={activeSchema}
          error={metadataError}
          key={`${activeConnection.connectionId}:${activeSchema}:${metadata?.refreshedAt ?? ""}`}
          metadata={metadata}
          pending={metadataPending}
        />
      </div>
    </aside>
  );
}

/**
 * @param {{
 *   activeSchema: string,
 *   error: string | null,
 *   metadata: SqlMetadataResponse | null,
 *   pending: boolean,
 * }} props
 */
function MetadataObjects({ activeSchema, error, metadata, pending }) {
  const objects = metadata?.objects ?? [];
  const [visibleObjectCount, setVisibleObjectCount] = useState(METADATA_OBJECT_PAGE_SIZE);
  const [expandedObjectKeys, setExpandedObjectKeys] = useState(() => new Set());
  const visibleObjects = objects.slice(0, Math.min(visibleObjectCount, objects.length));

  /**
   * @param {import("react").UIEvent<HTMLDivElement>} event
   */
  function handleMetadataScroll(event) {
    if (visibleObjectCount >= objects.length) {
      return;
    }
    const element = event.currentTarget;
    const distanceToBottom = element.scrollHeight - element.scrollTop - element.clientHeight;
    if (distanceToBottom <= METADATA_SCROLL_LOAD_OFFSET) {
      setVisibleObjectCount((current) => Math.min(objects.length, current + METADATA_OBJECT_PAGE_SIZE));
    }
  }

  /**
   * @param {string} objectKey
   */
  function toggleObject(objectKey) {
    setExpandedObjectKeys((current) => {
      const next = new Set(current);
      if (next.has(objectKey)) {
        next.delete(objectKey);
      } else {
        next.add(objectKey);
      }
      return next;
    });
  }
  if (pending) {
    return <div className={styles.metadataState}>正在读取元数据</div>;
  }
  if (error) {
    return <div className={styles.metadataState}>元数据读取失败</div>;
  }
  if (!metadata || objects.length === 0) {
    return <div className={styles.metadataState}>当前 Schema 暂无可展示对象</div>;
  }

  return (
    <div
      aria-label="Database metadata objects"
      className={styles.metadataList}
      onScroll={handleMetadataScroll}
      role="list"
    >
      {visibleObjects.map((object) => {
        const objectKey = `${object.schema}.${object.name}`;
        const isExpanded = expandedObjectKeys.has(objectKey);
        return (
          <section className={styles.metadataObject} key={objectKey} role="listitem">
            <button
              aria-expanded={isExpanded}
              aria-label={`${object.name} ${object.type}`}
              className={styles.metadataObjectHeader}
              onClick={() => toggleObject(objectKey)}
              type="button"
            >
              <ChevronRight
                aria-hidden="true"
                className={`${styles.metadataChevron} ${isExpanded ? styles.metadataChevronOpen : ""}`}
                size={14}
              />
              <strong>{object.name}</strong>
              <span
                aria-hidden="true"
                className={styles.metadataObjectTypeIcon}
                title={object.type}
              >
                <MetadataObjectTypeIcon objectType={object.type} />
              </span>
            </button>
            {isExpanded ? (
              <>
          <div aria-label={`${object.name} 字段`} className={styles.metadataColumns}>
            {object.columns.map((column) => (
              <div className={styles.metadataColumn} key={`${object.name}.${column.name}`}>
                <strong>{column.name}</strong>
                <span>
                  {column.type}
                  {column.nullable ? " · 可空" : " · 必填"}
                </span>
              </div>
            ))}
          </div>
          {object.indexes.length > 0 ? (
            <div className={styles.metadataIndexes}>
              <span>Indexes</span>
              {object.indexes.map((index) => (
                <strong key={`${object.name}.${index.name}`}>
                  {index.name}
                  {index.unique ? " · unique" : ""}
                </strong>
              ))}
            </div>
          ) : null}
              </>
            ) : null}
          </section>
        );
      })}
      {visibleObjects.length < objects.length ? (
        <div className={styles.metadataState}>
          已显示 {visibleObjects.length} / {objects.length}，继续滚动加载
        </div>
      ) : null}
      {metadata.truncated ? (
        <div className={styles.metadataState}>{activeSchema} 对象较多，已截断显示</div>
      ) : null}
    </div>
  );
}

/**
 * @param {{objectType: string}} props
 */
function MetadataObjectTypeIcon({ objectType }) {
  if (objectType === "TABLE") {
    return <Table2 size={14} strokeWidth={2.4} />;
  }
  return <Database size={14} strokeWidth={2.4} />;
}

/**
 * @param {{
 *   activeSessionId: string,
 *   onAddSession: () => void,
 *   onSelectSession: (sessionId: string) => void,
 *   sessions: SqlWorkbenchSession[],
 * }} props
 */
function SessionTabs({ activeSessionId, onAddSession, onSelectSession, sessions }) {
  return (
    <section aria-label="SQL 会话标签" className={styles.sessionTabs} role="tablist">
      {sessions.map((session) => (
        <button
          aria-selected={session.id === activeSessionId}
          className={session.id === activeSessionId ? styles.activeTab : ""}
          key={session.id}
          onClick={() => onSelectSession(session.id)}
          role="tab"
          type="button"
        >
          {session.label}
        </button>
      ))}
      <button className={styles.newSessionButton} onClick={onAddSession} type="button">
        + 新建会话
      </button>
    </section>
  );
}

/**
 * @param {{
 *   assistant: SqlAssistantResponse | null,
 *   assistantErrorMessage: string | null,
 *   assistantPending: boolean,
 *   canUseAssistant: boolean,
 *   execution: SqlQueryRunResult | null,
 *   onApplyAssistantSuggestion: (sql: string) => void,
 *   onRequestAssistant: (action: SqlAssistantAction) => void,
 * }} props
 */
function InfoPanel({
  assistant,
  assistantErrorMessage,
  assistantPending,
  canUseAssistant,
  execution,
  onApplyAssistantSuggestion,
  onRequestAssistant,
}) {
  const requiresHandoff = execution?.status === "UNKNOWN_REQUIRES_HANDOFF";
  return (
    <aside aria-label="SQL 信息面板" className={styles.infoPanel}>
      {requiresHandoff ? null : <ExecutionFacts execution={execution} />}
      <AiSqlAssistantPanel
        assistant={assistant}
        errorMessage={assistantErrorMessage}
        isPending={assistantPending}
        canUseAssistant={canUseAssistant}
        onApplySuggestion={onApplyAssistantSuggestion}
        onRequest={onRequestAssistant}
      />
    </aside>
  );
}

/**
 * @param {{
 *   assistant: SqlAssistantResponse | null,
 *   canUseAssistant: boolean,
 *   errorMessage: string | null,
 *   isPending: boolean,
 *   onApplySuggestion: (sql: string) => void,
 *   onRequest: (action: SqlAssistantAction) => void,
 * }} props
 */
function AiSqlAssistantPanel({
  assistant,
  canUseAssistant,
  errorMessage,
  isPending,
  onApplySuggestion,
  onRequest,
}) {
  return (
    <section className={styles.aiPanel}>
      <PanelHeading detail="服务端模型建议，不参与自动执行" icon={Sparkles} title="AI SQL 助手" />
      <div className={styles.aiActions}>
        <button
          disabled={!canUseAssistant || isPending}
          onClick={() => onRequest("EXPLAIN_SQL")}
          type="button"
        >
          解释 SQL
        </button>
        <button
          disabled={!canUseAssistant || isPending}
          onClick={() => onRequest("OPTIMIZE_SQL")}
          type="button"
        >
          优化建议
        </button>
        <button
          disabled={!canUseAssistant || isPending}
          onClick={() => onRequest("ANALYZE_ERROR")}
          type="button"
        >
          分析错误
        </button>
      </div>
      {isPending ? <AiAssistantLoadingStatus /> : null}
      {errorMessage ? (
        <div className={styles.errorSummary} role="alert">
          <AlertTriangle aria-hidden="true" size={16} />
          <span>{errorMessage}</span>
        </div>
      ) : null}
      {assistant ? (
        <div className={styles.aiResponse}>
          <strong>{assistant.status}</strong>
          <p>{assistant.summary}</p>
          {assistant.suggestions.map((suggestion, index) => (
            <article className={styles.aiSuggestion} key={`${suggestion.title}-${index}`}>
              <h3>{suggestion.title}</h3>
              <p>{suggestion.rationale}</p>
              {suggestion.suggestedSql ? (
                <>
                  <pre>{suggestion.suggestedSql}</pre>
                  <button
                    onClick={() => onApplySuggestion(String(suggestion.suggestedSql))}
                    type="button"
                  >
                    应用建议到编辑器
                  </button>
                </>
              ) : null}
            </article>
          ))}
          {assistant.safetyNotes.length > 0 ? (
            <ul className={styles.aiSafetyNotes}>
              {assistant.safetyNotes.map((note) => (
                <li key={note}>{note}</li>
              ))}
            </ul>
          ) : null}
        </div>
      ) : null}
    </section>
  );
}

function AiAssistantLoadingStatus() {
  return (
    <div aria-live="polite" className={styles.aiAssistantLoadingStatus} role="status">
      <span aria-hidden="true" className={styles.queryLoadingIcon}>
        <LoaderCircle size={15} strokeWidth={2.4} />
      </span>
      <span className={styles.queryLoadingCopy}>
        <strong>正在请求 AI SQL 助手</strong>
        <span>服务端模型正在生成参考建议</span>
      </span>
      <span aria-hidden="true" className={styles.queryLoadingDots}>
        <span />
        <span />
        <span />
      </span>
      <span aria-hidden="true" className={styles.queryLoadingTrack}>
        <span />
      </span>
    </div>
  );
}

/**
 * @param {{execution: SqlQueryRunResult | null}} props
 */
function ExecutionFacts({ execution }) {
  return (
    <section className={styles.executionFacts}>
      <PanelHeading detail="控制面执行请求与结果引用" icon={ShieldCheck} title="执行事实" />
      <div className={styles.reportGrid}>
        <ReportItem label="执行状态" value={execution?.status ?? "未提交"} />
        <ReportItem label="执行请求" value={execution?.executionRequestId ?? "pending"} />
        <ReportItem label="workflow" value={execution?.workflowId ?? "pending"} />
        <ReportItem label="resultId" value={execution?.resultId ?? "无"} />
      </div>
    </section>
  );
}

/**
 * @param {{
 *   assistant: SqlAssistantResponse | null,
 *   assistantErrorMessage: string | null,
 *   assistantPending: boolean,
 *   canUseAssistant: boolean,
 *   errorMessage: string | null,
 *   execution: SqlQueryRunResult | null,
 *   isLoading: boolean,
 *   naturalLanguage: SqlNaturalLanguageState,
 *   naturalLanguagePending: boolean,
 *   onApplyAssistantSuggestion: (sql: string) => void,
 *   onApplyNaturalLanguageDraft: () => void,
 *   onNextPage: () => void,
 *   onPreviousPage: () => void,
 *   onRequestAssistant: (action: SqlAssistantAction) => void,
 *   onSelectResultTab: (tabId: string) => void,
 *   pageIndex: number,
 *   resultPage: SqlResultPage | null | undefined,
 *   resultTabs: SqlResultTab[],
 *   selectedResultTabId: string | null,
 *   sessionMode: SqlSessionMode,
 *   showInlineAssistant: boolean,
 * }} props
 */
function ResultPanel({
  assistant,
  assistantErrorMessage,
  assistantPending,
  canUseAssistant,
  errorMessage,
  execution,
  isLoading,
  naturalLanguage,
  naturalLanguagePending,
  onApplyAssistantSuggestion,
  onApplyNaturalLanguageDraft,
  onNextPage,
  onPreviousPage,
  onRequestAssistant,
  onSelectResultTab,
  pageIndex,
  resultPage,
  resultTabs,
  selectedResultTabId,
  sessionMode,
  showInlineAssistant,
}) {
  const columns = useMemo(
    () =>
      (resultPage?.columns ?? []).map((column, index) => ({
        key: column.name,
        header: column.name,
        render: (/** @type {unknown} */ row) => readResultCell(row, index),
      })),
    [resultPage?.columns],
  );
  const executionErrorMessage = execution &&
    execution.status !== "SUCCEEDED" &&
    execution.status !== "UNKNOWN_REQUIRES_HANDOFF"
    ? `${execution.errorCode ? `${execution.errorCode}: ` : ""}${
        execution.errorMessage ?? execution.status
      }`
    : null;
  const visibleErrorMessage = executionErrorMessage ?? errorMessage;

  if (execution?.status === "UNKNOWN_REQUIRES_HANDOFF") {
    return <DmlHandoffOutcome workflowId={execution.workflowId} />;
  }

  if (sessionMode === "natural-language") {
    return (
      <NaturalLanguageGenerationResult
        errorMessage={assistantErrorMessage}
        isPending={naturalLanguagePending}
        onApplyDraft={onApplyNaturalLanguageDraft}
        state={naturalLanguage}
      />
    );
  }

  return (
    <section className={styles.resultPanel}>
      <PanelHeading
        compact
        detail={execution?.resultId ?? "等待 SELECT 执行"}
        icon={FileSearch}
        title="查询结果"
      />
      {resultTabs.length > 0 ? (
        <div aria-label="SQL 执行结果" className={styles.resultTabs} role="tablist">
          {resultTabs.map((tab) => (
            <button
              aria-selected={tab.id === selectedResultTabId}
              className={tab.id === selectedResultTabId ? styles.activeResultTab : ""}
              key={tab.id}
              onClick={() => onSelectResultTab(tab.id)}
              role="tab"
              type="button"
            >
              {tab.label} · {formatResultTabStatus(tab)}
            </button>
          ))}
        </div>
      ) : null}
      {isLoading ? <QueryLoadingStatus execution={execution} /> : null}
      {visibleErrorMessage ? <ErrorSummary message={visibleErrorMessage} /> : null}
      {resultPage && columns.length > 0 ? (
        <>
          <DataTable
            ariaLabel="SQL SELECT 查询结果"
            className={styles.resultTable}
            columns={columns}
            minWidth="620px"
            rows={resultPage.rows}
          />
          <div className={styles.resultFooter}>
            <span>第 {pageIndex + 1} 页</span>
            <span>本页 {resultPage.rows.length} 行</span>
            <span>{resultPage.truncated ? "已截断" : "未截断"}</span>
            <span>expiresAt {resultPage.expiresAt}</span>
            <div className={styles.resultPager}>
              <button
                disabled={pageIndex <= 0 || isLoading}
                onClick={onPreviousPage}
                type="button"
              >
                <ChevronLeft aria-hidden="true" size={14} />
                上一页
              </button>
              <button
                disabled={!resultPage.nextCursor || isLoading}
                onClick={onNextPage}
                type="button"
              >
                下一页
                <ChevronRight aria-hidden="true" size={14} />
              </button>
            </div>
          </div>
        </>
      ) : null}
      {execution?.status === "SUCCEEDED" &&
      !resultPage &&
      !isLoading &&
      execution.affectedRows !== undefined &&
      execution.affectedRows !== null ? (
        <p>{`DML 提交完成，影响 ${execution.affectedRows} 行。`}</p>
      ) : null}
      {execution?.status === "SUCCEEDED" &&
      !resultPage &&
      !isLoading &&
      (execution.affectedRows === undefined || execution.affectedRows === null) ? (
        <p>执行完成，等待结果页返回。</p>
      ) : null}
      {showInlineAssistant ? (
        <AiSqlAssistantPanel
          assistant={assistant}
          errorMessage={assistantErrorMessage}
          isPending={assistantPending}
          canUseAssistant={canUseAssistant}
          onApplySuggestion={onApplyAssistantSuggestion}
          onRequest={onRequestAssistant}
        />
      ) : null}
    </section>
  );
}

/**
 * @param {{workflowId: string}} props
 */
function DmlHandoffOutcome({ workflowId }) {
  return (
    <section aria-label="DML 人工接管结果" className={styles.resultPanel}>
      <PanelHeading compact detail="请按工作流完成人工确认" icon={AlertTriangle} title="需要人工接管" />
      <p>需要人工接管以确认 DML 执行结果。</p>
      <p>{workflowId}</p>
    </section>
  );
}

/**
 * @param {{
 *   isPending: boolean,
 *   onCancel: () => void,
 *   onConfirm: () => void,
 *   open: boolean,
 *   preflight: SqlDmlPreflightResult | null,
 * }} props
 */
function DmlRiskConfirmationDialog({
  isPending,
  onCancel,
  onConfirm,
  open,
  preflight,
}) {
  const report = preflight?.validation ?? null;
  return (
    <Dialog
      closeLabel="关闭 DML 风险确认"
      description="无 WHERE 条件的 UPDATE 或 DELETE 会影响更大范围，必须二次确认。"
      icon={<AlertTriangle aria-hidden="true" size={18} strokeWidth={2.35} />}
      onClose={isPending ? () => {} : onCancel}
      open={open && Boolean(preflight)}
      size="compact"
      title="确认 DML 风险"
    >
      {report ? (
        <div className={styles.dmlRiskDialog}>
          <p className={styles.dmlRiskLead}>
            当前 DML 已通过服务端受控校验，但包含高风险项。确认后控制面会携带确认码提交给受限 Worker 执行短事务。
          </p>
          <dl className={styles.dmlRiskFacts}>
            <div>
              <dt>风险</dt>
              <dd className={styles.dmlRiskTags}>
                {report.risks.map((risk) => (
                  <strong key={risk}>{risk}</strong>
                ))}
              </dd>
            </div>
            <div>
              <dt>对象</dt>
              <dd>{formatValues(report.referencedObjects)}</dd>
            </div>
            <div>
              <dt>SQL Hash</dt>
              <dd>{report.sqlHash}</dd>
            </div>
          </dl>
          <DmlImpactPreview preview={preflight?.impactPreview ?? null} />
          {report.unverifiedItems.length > 0 ? (
            <p className={styles.dmlRiskNote}>
              未验证项：{formatValues(report.unverifiedItems)}
            </p>
          ) : null}
          <div className={styles.dmlRiskActions}>
            <button
              className={styles.secondaryButton}
              disabled={isPending}
              onClick={onCancel}
              type="button"
            >
              取消
            </button>
            <button
              className={styles.dangerButton}
              disabled={isPending}
              onClick={onConfirm}
              type="button"
            >
              {isPending ? "提交中" : "确认提交"}
            </button>
          </div>
        </div>
      ) : null}
    </Dialog>
  );
}

/**
 * @param {{preview: SqlDmlPreflightResult["impactPreview"]}} props
 */
function DmlImpactPreview({ preview }) {
  if (!preview) {
    return null;
  }

  const columns = preview.sampleColumns.map((column, index) => ({
    key: column.name,
    header: column.name,
    render: (/** @type {unknown} */ row) =>
      Array.isArray(row) ? readResultCell(row, index) : "",
  }));

  return (
    <section aria-label="DML 预检影响预览">
      <dl className={styles.dmlRiskFacts}>
        <div>
          <dt>预计影响</dt>
          <dd>
            {preview.affectedRows === null ? "服务端未返回计数" : `预计影响 ${preview.affectedRows} 行`}
          </dd>
        </div>
      </dl>
      {columns.length > 0 && preview.sampleRows.length > 0 ? (
        <DataTable
          ariaLabel="DML 预检样本"
          className={styles.resultTable}
          columns={columns}
          minWidth="420px"
          rows={preview.sampleRows}
        />
      ) : null}
      {preview.unverifiedItems.length > 0 ? (
        <p className={styles.dmlRiskNote}>
          预检未验证项：{formatValues(preview.unverifiedItems)}
        </p>
      ) : null}
    </section>
  );
}

/**
 * @param {{
 *   activeConnectionId: string,
 *   connections: SqlConnectionSummary[],
 *   createPending: boolean,
 *   deletePending: boolean,
 *   onClose: () => void,
 *   onCreate: (request: import("../../schemas/sql-schemas.js").SqlConnectionCreateRequest) => void,
 *   onDelete: (connectionId: string) => void,
 *   onUpdate: (connectionId: string, request: import("../../schemas/sql-schemas.js").SqlConnectionUpdateRequest) => void,
 *   open: boolean,
 *   updatePending: boolean,
 * }} props
 */
function ConnectionManagerDialog({
  activeConnectionId,
  connections,
  createPending,
  deletePending,
  onClose,
  onCreate,
  onDelete,
  onUpdate,
  open,
  updatePending,
}) {
  const initialConnection =
    connections.find((connection) => connection.connectionId === activeConnectionId) ??
    connections[0] ??
    null;
  const [mode, setMode] = useState(
    /** @type {"create" | "edit"} */ (initialConnection ? "edit" : "create"),
  );
  const [selectedConnectionId, setSelectedConnectionId] = useState(
    initialConnection?.connectionId ?? "",
  );
  const [form, setForm] = useState(
    initialConnection ? connectionToForm(initialConnection) : DEFAULT_CONNECTION_FORM,
  );
  const [deleteCandidateId, setDeleteCandidateId] = useState("");

  const selectedConnection =
    connections.find((connection) => connection.connectionId === selectedConnectionId) ??
    null;
  const isCreateMode = mode === "create" || !selectedConnection;
  const isPending = isCreateMode ? createPending : updatePending;

  /**
   * @param {keyof typeof DEFAULT_CONNECTION_FORM} key
   * @param {string} value
   */
  function updateField(key, value) {
    setForm((current) => ({ ...current, [key]: value }));
  }

  /**
   * @param {string} platformType
   */
  function updatePlatformType(platformType) {
    const nextDefaults =
      PLATFORM_FORM_DEFAULTS[
        /** @type {keyof typeof PLATFORM_FORM_DEFAULTS} */ (platformType)
      ];
    if (!nextDefaults) {
      updateField("platformType", platformType);
      return;
    }

    setForm((current) => {
      const currentDefaults =
        PLATFORM_FORM_DEFAULTS[
          /** @type {keyof typeof PLATFORM_FORM_DEFAULTS} */ (current.platformType)
        ];
      return {
        ...current,
        platformType,
        host:
          current.host === "" || current.host === currentDefaults?.host
            ? nextDefaults.host
            : current.host,
        port:
          current.port === "" || current.port === currentDefaults?.port
            ? nextDefaults.port
            : current.port,
        defaultSchema:
          current.defaultSchema === "" ||
          current.defaultSchema === currentDefaults?.defaultSchema
            ? nextDefaults.defaultSchema
            : current.defaultSchema,
        allowedSchemas:
          current.allowedSchemas === "" ||
          current.allowedSchemas === currentDefaults?.allowedSchemas
            ? nextDefaults.allowedSchemas
            : current.allowedSchemas,
      };
    });
  }

  function startCreate() {
    setMode("create");
    setSelectedConnectionId("");
    setForm(DEFAULT_CONNECTION_FORM);
    setDeleteCandidateId("");
  }

  /**
   * @param {SqlConnectionSummary} connection
   */
  function selectManagedConnection(connection) {
    setMode("edit");
    setSelectedConnectionId(connection.connectionId);
    setForm(connectionToForm(connection));
    setDeleteCandidateId("");
  }

  /**
   * @param {import("react").FormEvent<HTMLFormElement>} event
   */
  function submitForm(event) {
    event.preventDefault();
    const request = buildConnectionRequest(form);
    if (isCreateMode) {
      onCreate(request);
      return;
    }
    if (selectedConnection) {
      onUpdate(selectedConnection.connectionId, request);
    }
  }

  function confirmDelete() {
    if (!selectedConnection) {
      return;
    }
    const deletedConnectionId = selectedConnection.connectionId;
    const nextConnection =
      connections.find((connection) => connection.connectionId !== deletedConnectionId) ??
      null;
    onDelete(deletedConnectionId);
    if (nextConnection) {
      selectManagedConnection(nextConnection);
    } else {
      startCreate();
    }
    setDeleteCandidateId("");
  }

  return (
    <Dialog
      className={styles.connectionDialog}
      closeLabel="关闭连接管理"
      description="维护开发和测试环境的连接目录元数据，只提交 Worker 侧 credentialAlias。"
      icon={<Database size={18} strokeWidth={2.4} />}
      onClose={onClose}
      open={open}
      size="wide"
      title="管理连接"
    >
      <div className={styles.connectionManagerLayout}>
        <aside aria-label="SQL 连接目录" className={styles.connectionManagerSidebar}>
          <button
            className={styles.connectionManagerCreateButton}
            onClick={startCreate}
            type="button"
          >
            <Plus aria-hidden="true" size={15} />
            新建连接
          </button>
          <div className={styles.connectionManagerList}>
            {connections.length === 0 ? (
              <p className={styles.connectionManagerEmpty}>暂无连接</p>
            ) : null}
            {connections.map((connection) => (
              <button
                aria-label={connection.connectionId}
                className={`${styles.connectionManagerButton} ${
                  !isCreateMode && selectedConnectionId === connection.connectionId
                    ? styles.activeManagerConnection
                    : ""
                }`}
                key={connection.connectionId}
                onClick={() => selectManagedConnection(connection)}
                type="button"
              >
                <strong>{connection.connectionId}</strong>
                <span>{connection.displayName}</span>
                <small>
                  {connection.targetEnvironment} · {connection.status}
                </small>
              </button>
            ))}
          </div>
        </aside>

        <form className={styles.connectionForm} onSubmit={submitForm}>
          <div className={styles.connectionFormHeader}>
            <div>
              <h3>{isCreateMode ? "新建连接" : "连接详情"}</h3>
              <p>{isCreateMode ? "创建新的只读目录元数据" : selectedConnection?.connectionId}</p>
            </div>
            {!isCreateMode && selectedConnection ? (
              <StatusPill tone={selectedConnection.status === "READY" ? "success" : "warning"}>
                {selectedConnection.status}
              </StatusPill>
            ) : null}
          </div>

          <section className={styles.connectionFormSection}>
            <h3>连接身份</h3>
            <div className={styles.connectionFormGrid}>
              <label>
                <span>连接名称</span>
                <input
                  onChange={(event) => updateField("displayName", event.target.value)}
                  required
                  value={form.displayName}
                />
              </label>
              <label>
                <span>目标环境</span>
                <select
                  onChange={(event) => updateField("targetEnvironment", event.target.value)}
                  value={form.targetEnvironment}
                >
                  <option value="dev">dev</option>
                  <option value="sit">sit</option>
                  <option value="uat">uat</option>
                  <option value="production">production</option>
                </select>
              </label>
              <label>
                <span>平台类型</span>
                <select
                  onChange={(event) => updatePlatformType(event.target.value)}
                  value={form.platformType}
                >
                  <option value="DB2_FOR_I">DB2 for i</option>
                  <option value="H2">H2</option>
                  <option value="MYSQL">MySQL</option>
                </select>
              </label>
            </div>
          </section>

          <section className={styles.connectionFormSection}>
            <h3>目标端点</h3>
            <div className={styles.connectionFormGrid}>
              <label className={styles.wideField}>
                <span>主机</span>
                <input
                  onChange={(event) => updateField("host", event.target.value)}
                  required
                  value={form.host}
                />
              </label>
              <label>
                <span>端口</span>
                <input
                  inputMode="numeric"
                  onChange={(event) => updateField("port", event.target.value)}
                  required
                  value={form.port}
                />
              </label>
              <label>
                <span>凭据别名 credentialAlias</span>
                <input
                  onChange={(event) => updateField("credentialAlias", event.target.value)}
                  required
                  value={form.credentialAlias}
                />
              </label>
            </div>
          </section>

          <section className={styles.connectionFormSection}>
            <h3>Schema 与限制</h3>
            <div className={styles.connectionFormGrid}>
              <label>
                <span>默认 Schema</span>
                <input
                  onChange={(event) => updateField("defaultSchema", event.target.value)}
                  required
                  value={form.defaultSchema}
                />
              </label>
              <label>
                <span>允许 Schema</span>
                <input
                  onChange={(event) => updateField("allowedSchemas", event.target.value)}
                  required
                  value={form.allowedSchemas}
                />
              </label>
              <label>
                <span>maxRows</span>
                <input
                  inputMode="numeric"
                  onChange={(event) => updateField("maxRowsDefault", event.target.value)}
                  required
                  value={form.maxRowsDefault}
                />
              </label>
              <label>
                <span>timeoutSeconds</span>
                <input
                  inputMode="numeric"
                  onChange={(event) => updateField("timeoutSecondsDefault", event.target.value)}
                  required
                  value={form.timeoutSecondsDefault}
                />
              </label>
              <div className={styles.formCapabilities}>
                <span>capabilities</span>
                <strong>VALIDATE</strong>
                <strong>RUN_READ_ONLY</strong>
                {form.targetEnvironment === "production" ? null : (
                  <>
                    <strong>PREFLIGHT_DML</strong>
                    <strong>COMMIT_DML</strong>
                  </>
                )}
              </div>
            </div>
          </section>

          {!isCreateMode && selectedConnection ? (
            <section className={styles.deleteSection}>
              <div>
                <strong>删除连接</strong>
                <p>只删除目录元数据，不触碰凭据库或目标系统。</p>
              </div>
              {deleteCandidateId === selectedConnection.connectionId ? (
                <div className={styles.deleteActions}>
                  <button
                    className={styles.secondaryButton}
                    disabled={deletePending}
                    onClick={confirmDelete}
                    type="button"
                  >
                    确认删除
                  </button>
                  <button
                    className={styles.secondaryButton}
                    disabled={deletePending}
                    onClick={() => setDeleteCandidateId("")}
                    type="button"
                  >
                    取消删除
                  </button>
                </div>
              ) : (
                <button
                  className={styles.dangerButton}
                  disabled={deletePending}
                  onClick={() => setDeleteCandidateId(selectedConnection.connectionId)}
                  type="button"
                >
                  删除连接
                </button>
              )}
            </section>
          ) : null}

          <div className={styles.formActions}>
            <button className={styles.secondaryButton} onClick={onClose} type="button">
              关闭
            </button>
            <button className={styles.primaryButton} disabled={isPending} type="submit">
              {isCreateMode ? "创建连接" : "保存修改"}
            </button>
          </div>
        </form>
      </div>
    </Dialog>
  );
}

/**
 * @param {{
 *   errorMessage: string | null,
 *   isPending: boolean,
 *   onApplyDraft: () => void,
 *   state: SqlNaturalLanguageState,
 * }} props
 */
function NaturalLanguageGenerationResult({
  errorMessage,
  isPending,
  onApplyDraft,
  state,
}) {
  const hasDraft = state.draftSql.trim().length > 0;
  return (
    <section className={styles.resultPanel}>
      <PanelHeading
        compact
        detail={hasDraft ? "AI 生成的只读 SELECT 草稿" : "等待自然语言生成"}
        icon={Sparkles}
        title="生成结果"
      />
      {isPending ? <GenerationLoadingStatus /> : null}
      {errorMessage ? <ErrorSummary message={errorMessage} /> : null}
      {state.statusMessage ? (
        <p className={styles.modeStatus}>{state.statusMessage}</p>
      ) : null}
      {hasDraft ? (
        <section className={styles.generatedResult} aria-label="生成结果 SQL">
          <pre className={styles.sqlDraftPreview}>{state.draftSql}</pre>
          <div className={styles.generatedResultActions}>
            <button
              className={styles.primaryButton}
              disabled={!hasDraft || isPending}
              onClick={onApplyDraft}
              type="button"
            >
              <ShieldCheck aria-hidden="true" size={15} />
              应用到编辑器并校验
            </button>
          </div>
        </section>
      ) : (
        <p>生成后的 SELECT 会显示在这里。</p>
      )}
    </section>
  );
}

function GenerationLoadingStatus() {
  return (
    <div aria-live="polite" className={styles.queryLoadingStatus} role="status">
      <span aria-hidden="true" className={styles.queryLoadingIcon}>
        <LoaderCircle size={15} strokeWidth={2.4} />
      </span>
      <span className={styles.queryLoadingCopy}>
        <strong>正在生成 SELECT</strong>
        <span>AI SQL 助手正在返回只读 SQL 草稿</span>
      </span>
      <span aria-hidden="true" className={styles.queryLoadingDots}>
        <span />
        <span />
        <span />
      </span>
      <span aria-hidden="true" className={styles.queryLoadingTrack}>
        <span />
      </span>
    </div>
  );
}

/**
 * @param {{execution: SqlQueryRunResult | null}} props
 */
function QueryLoadingStatus({ execution }) {
  const phase = execution?.resultId ? "正在读取查询结果" : "正在执行 SELECT 查询";
  const detail = execution?.resultId
    ? `resultId ${execution.resultId}`
    : "控制面正在提交只读执行请求";

  return (
    <div aria-live="polite" className={styles.queryLoadingStatus} role="status">
      <span aria-hidden="true" className={styles.queryLoadingIcon}>
        <LoaderCircle size={15} strokeWidth={2.4} />
      </span>
      <span className={styles.queryLoadingCopy}>
        <strong>{phase}</strong>
        <span>{detail}</span>
      </span>
      <span aria-hidden="true" className={styles.queryLoadingDots}>
        <span />
        <span />
        <span />
      </span>
      <span aria-hidden="true" className={styles.queryLoadingTrack}>
        <span />
      </span>
    </div>
  );
}

/**
 * @param {{
 *   compact?: boolean,
 *   detail: string,
 *   icon: import("lucide-react").LucideIcon,
 *   title: string,
 * }} props
 */
function PanelHeading({ compact = false, detail, icon: Icon, title }) {
  return (
    <header className={`${styles.panelHeading} ${compact ? styles.compactPanelHeading : ""}`}>
      <span aria-hidden="true">
        <Icon size={compact ? 13 : 16} strokeWidth={2.4} />
      </span>
      <div>
        <h2>{title}</h2>
        <p>{detail}</p>
      </div>
    </header>
  );
}

/**
 * @param {{label: string, value: string}} props
 */
function ReportItem({ label, value }) {
  return (
    <div className={styles.reportItem}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

/**
 * @param {{detail: string, title: string}} props
 */
function Notice({ detail, title }) {
  return (
    <section className={styles.noticePanel}>
      <AlertTriangle aria-hidden="true" size={18} />
      <strong>{title}</strong>
      <p>{detail}</p>
    </section>
  );
}

/**
 * @param {{message: string}} props
 */
function ErrorSummary({ message }) {
  return (
    <div className={styles.errorSummary} role="alert">
      <AlertTriangle aria-hidden="true" size={16} />
      <ErrorMessageContent message={message} />
    </div>
  );
}

/**
 * @param {{message: string}} props
 */
function ErrorMessageContent({ message }) {
  const { summary, details } = splitDiagnosticMessage(message);
  return (
    <div className={styles.errorText}>
      <strong>{summary}</strong>
      {details.length > 0 ? (
        <div className={styles.errorDetails}>
          <span>排查线索</span>
          <ul>
            {details.map((detail) => (
              <li key={detail}>{detail}</li>
            ))}
          </ul>
        </div>
      ) : null}
    </div>
  );
}

/**
 * @param {SqlConnectionSummary} connection
 * @returns {typeof DEFAULT_CONNECTION_FORM}
 */
function connectionToForm(connection) {
  const platformDefaults =
    PLATFORM_FORM_DEFAULTS[
      /** @type {keyof typeof PLATFORM_FORM_DEFAULTS} */ (connection.platformType)
    ];
  return {
    displayName: connection.displayName,
    targetEnvironment: normalizeSqlTargetEnvironment(connection.targetEnvironment),
    platformType: connection.platformType,
    host: connection.host ?? platformDefaults?.host ?? "",
    port: String(connection.port ?? platformDefaults?.port ?? ""),
    defaultSchema: connection.defaultSchema ?? connection.allowedSchemas[0] ?? "",
    allowedSchemas: connection.allowedSchemas.join(", "),
    credentialAlias: connection.credentialAlias ?? "",
    maxRowsDefault: String(connection.maxRowsDefault ?? DEFAULT_LIMITS.maxRows),
    timeoutSecondsDefault: String(
      connection.timeoutSecondsDefault ?? DEFAULT_LIMITS.timeoutSeconds,
    ),
  };
}

/**
 * @param {typeof DEFAULT_CONNECTION_FORM} form
 * @returns {import("../../schemas/sql-schemas.js").SqlConnectionCreateRequest}
 */
function buildConnectionRequest(form) {
  /** @type {import("../../schemas/sql-schemas.js").SqlConnectionCreateRequest["capabilities"]} */
  const capabilities =
    form.targetEnvironment === "production"
      ? ["VALIDATE", "RUN_READ_ONLY"]
      : ["VALIDATE", "RUN_READ_ONLY", "PREFLIGHT_DML", "COMMIT_DML"];
  return {
    contractVersion: "1.0",
    displayName: form.displayName.trim(),
    targetEnvironment:
      /** @type {"dev" | "sit" | "uat" | "production" | "development" | "test"} */ (
        form.targetEnvironment
      ),
    platformType: /** @type {"DB2_FOR_I" | "H2" | "MYSQL"} */ (form.platformType),
    host: form.host.trim(),
    port: Number(form.port),
    defaultSchema: form.defaultSchema.trim(),
    allowedSchemas: splitCsv(form.allowedSchemas),
    capabilities,
    credentialAlias: form.credentialAlias.trim(),
    maxRowsDefault: Number(form.maxRowsDefault),
    timeoutSecondsDefault: Number(form.timeoutSecondsDefault),
  };
}

/**
 * @param {SqlConnectionSummary[]} connections
 * @param {SqlConnectionSummary} connection
 */
function upsertConnection(connections, connection) {
  const hasConnection = connections.some(
    (item) => item.connectionId === connection.connectionId,
  );
  if (!hasConnection) {
    return [...connections, connection];
  }
  return connections.map((item) =>
    item.connectionId === connection.connectionId ? connection : item,
  );
}

/**
 * @param {SqlConnectionSummary[]} baseConnections
 * @param {SqlConnectionSummary[]} overrides
 * @param {string[]} deletedConnectionIds
 */
function mergeConnections(baseConnections, overrides, deletedConnectionIds) {
  const deleted = new Set(deletedConnectionIds);
  const byId = new Map();
  baseConnections.forEach((connection) => {
    if (!deleted.has(connection.connectionId)) {
      byId.set(connection.connectionId, connection);
    }
  });
  overrides.forEach((connection) => {
    if (!deleted.has(connection.connectionId)) {
      byId.set(connection.connectionId, connection);
    }
  });
  return Array.from(byId.values());
}

/**
 * @param {SqlConnectionSummary | null} connection
 * @param {string} fallbackSchema
 * @param {string} fallbackConnectionId
 * @returns {Partial<SqlWorkbenchSession>}
 */
function buildConnectionSessionPatch(connection, fallbackSchema, fallbackConnectionId) {
  return {
    activeResultTabId: null,
    connectionId: connection?.connectionId ?? fallbackConnectionId,
    schema:
      connection?.defaultSchema ??
      connection?.allowedSchemas[0] ??
      fallbackSchema,
    validation: null,
    execution: null,
    resultPage: null,
    resultPageIndex: 0,
    resultPageToken: null,
    resultPageTokens: [null],
    resultTabs: [],
    transactionMode: "auto",
    errorMessage: null,
    assistant: null,
    assistantErrorMessage: null,
  };
}

/**
 * @param {number} index
 * @param {string} sql
 * @returns {SqlWorkbenchSession}
 */
function createSession(index, sql) {
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
 * @param {number} index
 * @param {string} sql
 * @returns {SqlResultTab}
 */
function createResultTab(index, sql) {
  sqlResultTabCounter += 1;
  return {
    errorMessage: null,
    execution: null,
    id: `sql-result-tab-${Date.now()}-${sqlResultTabCounter}`,
    isPending: true,
    label: `结果 ${index}`,
    resultPage: null,
    resultPageIndex: 0,
    resultPageToken: null,
    resultPageTokens: [null],
    sql,
  };
}

/**
 * @param {SqlWorkbenchSession} session
 * @returns {SqlResultTab | null}
 */
function resolveActiveResultTab(session) {
  return (
    session.resultTabs.find((tab) => tab.id === session.activeResultTabId) ??
    session.resultTabs.at(-1) ??
    null
  );
}

/**
 * @param {SqlResultTab} tab
 */
function formatResultTabStatus(tab) {
  if (tab.isPending) {
    return "执行中";
  }
  if (tab.execution?.status) {
    return tab.execution.status;
  }
  return tab.errorMessage ? "失败" : "等待";
}

/**
 * @param {SqlConnectionSummary[]} connections
 * @param {SqlWorkbenchSession} session
 * @returns {SqlConnectionSummary | null}
 */
function resolveActiveConnection(connections, session) {
  return (
    connections.find((connection) => connection.connectionId === session.connectionId) ??
    connections[0] ??
    null
  );
}

/**
 * @param {SqlConnectionSummary} connection
 */
function buildLimits(connection) {
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
 * @param {string} [idempotencyKey]
 */
function buildSqlQueryRequest(
  connection,
  schema,
  action,
  sql,
  idempotencyAction,
  idempotencyKey = createSqlIdempotencyKey(idempotencyAction),
) {
  return {
    contractVersion: "1.0",
    connectionId: connection.connectionId,
    targetEnvironment: connection.targetEnvironment,
    schema,
    action,
    sql,
    parameters: [],
    limits: buildLimits(connection),
    idempotencyKey,
  };
}

/**
 * @param {SqlConnectionSummary} connection
 * @param {string} schema
 * @param {string} sql
 */
function dmlSubmissionContext(connection, schema, sql) {
  const limits = buildLimits(connection);
  return JSON.stringify([
    connection.connectionId,
    connection.targetEnvironment,
    schema,
    sql,
    [],
    limits.maxRows,
    limits.maxBytes,
    limits.timeoutSeconds,
  ]);
}

/**
 * @param {number} value
 * @param {number} min
 * @param {number} max
 */
function clampNumber(value, min, max) {
  return Math.min(Math.max(value, min), max);
}

/**
 * @param {string[]} values
 */
function formatValues(values) {
  return values.length > 0 ? values.join(" / ") : "无";
}

/**
 * @param {string} message
 * @returns {{summary: string, details: string[]}}
 */
function splitDiagnosticMessage(message) {
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
function shouldFetchValidationDiagnostics(error) {
  return (
    error instanceof ApiError &&
    error.message.includes(LEGACY_READ_ONLY_VALIDATION_ERROR)
  );
}

/**
 * @param {SqlValidationReport} report
 */
function buildReadOnlyValidationDiagnosticMessage(report) {
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
function buildDmlValidationDiagnosticMessage(report) {
  return [
    "DML 提交未通过服务端受控校验，未向 Worker 提交执行请求。",
    `statementType=${report.statementType}`,
    `validationLevel=${report.validationLevel}`,
    `rejectionReasons=${formatValues(report.rejectionReasons)}`,
    `risks=${formatValues(report.risks)}`,
    `referencedObjects=${formatValues(report.referencedObjects)}`,
    `sqlHash=${report.sqlHash}`,
  ].join("\n");
}

/**
 * @param {SqlValidationReport} report
 */
function buildDmlRiskConfirmation(report) {
  return {
    contractVersion: "1.0",
    sqlHash: report.sqlHash,
    confirmedRisks: report.risks,
    confirmationCode: "CONFIRM_SQL_DML_RISK",
  };
}

/**
 * @param {SqlValidationReport} report
 */
function shouldAutoAnalyzeValidation(report) {
  if (report.validationLevel !== "REJECTED" || report.statementType !== "UNSUPPORTED") {
    return false;
  }
  return report.rejectionReasons.some((reason) => /syntax|parse|解析|语法/iu.test(reason));
}

/**
 * @param {SqlQueryRunResult} execution
 */
function shouldAutoAnalyzeExecution(execution) {
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
function buildAssistantDiagnosticContext(session) {
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
function createSqlIdempotencyKey(action) {
  const randomPart =
    typeof globalThis.crypto?.randomUUID === "function"
      ? globalThis.crypto.randomUUID()
      : `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return `sql:${action}:${randomPart}`;
}

/**
 * @param {unknown} row
 * @param {number} index
 */
function readResultCell(row, index) {
  if (!Array.isArray(row)) {
    return "";
  }
  const value = row[index];
  if (value === null || value === undefined) {
    return "";
  }
  return String(value);
}

/**
 * @param {string} value
 */
function splitCsv(value) {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter(Boolean);
}

/**
 * @param {string} targetEnvironment
 */
function normalizeSqlTargetEnvironment(targetEnvironment) {
  if (targetEnvironment === "development") {
    return "dev";
  }
  if (targetEnvironment === "test") {
    return "sit";
  }
  return targetEnvironment;
}
