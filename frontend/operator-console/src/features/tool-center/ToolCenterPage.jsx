import {
  Braces,
  Check,
  Copy,
  ListCollapse,
  ListTree,
  LoaderCircle,
  Maximize2,
  Minimize2,
  Plus,
  Search,
  SendHorizontal,
  Settings2,
  ShieldCheck,
  Sparkles,
  Trash2,
  X,
} from "lucide-react";
import { useMemo, useState } from "react";
import { JsonView, allExpanded, collapseAllNested } from "react-json-view-lite";

import toolbarStyles from "../../components/layout/PageToolbar.module.css";
import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import { Button } from "../../components/primitives/Button.jsx";
import { repairJsonWithAssistant } from "../../api/tool-center-api.js";
import {
  deriveRequestOrigin,
  findJsonHeroMatches,
  formatJsonDocument,
  formatJsonHeroNodeValue,
  minifyJsonDocument,
  parseJsonForHeroView,
  previewSecretInput,
  validateAllowlistDraft,
} from "./tool-center-utils.js";
import styles from "./ToolCenterPage.module.css";

const toolModes = [
  {
    id: "JSON Formatter",
    icon: Braces,
    label: "Json Helper",
  },
  {
    id: "API Caller",
    icon: SendHorizontal,
    label: "API Caller",
  },
];
const initialJson = "";
/** @type {{ ok: boolean, errors: string[] }} */
const initialValidation = { ok: true, errors: [] };
const defaultAllowlistDraft = {
  targetName: "queFork",
  origin: "https://api.quefork.internal",
  environmentLabel: "test",
  methods: ["GET", "POST"],
  timeoutSeconds: 30,
  maxRequestBytes: 65536,
  maxResponseBytes: 1048576,
};

/**
 * @typedef {{ enabled: boolean, id: string, key: string, value: string }} KeyValueRow
 * @typedef {"no-auth" | "basic" | "bearer" | "api-key"} AuthType
 * @typedef {"all" | "root"} JsonExpandMode
 * @typedef {"input" | "output" | "structure"} JsonPanelId
 * @typedef {"idle" | "pending" | "success" | "error"} JsonAssistantStatusKind
 * @typedef {"none" | "form-data" | "form-urlencoded" | "raw" | "binary" | "graphql"} RequestBodyType
 * @typedef {"text" | "javascript" | "json" | "html" | "xml"} RawBodyFormat
 */

/**
 * @param {string} prefix
 * @param {number} index
 * @returns {KeyValueRow}
 */
function createEmptyKeyValueRow(prefix, index) {
  return { enabled: false, id: `${prefix}-${index}`, key: "", value: "" };
}

function createJsonRepairIdempotencyKey() {
  if (typeof crypto !== "undefined" && typeof crypto.randomUUID === "function") {
    return `json-repair-${crypto.randomUUID()}`;
  }
  return `json-repair-${Date.now()}-${Math.random().toString(36).slice(2)}`;
}

/** @type {KeyValueRow[]} */
const initialHeaderRows = [
  { enabled: true, id: "header-1", key: "Accept", value: "application/json" },
  createEmptyKeyValueRow("header", 2),
];
/** @type {KeyValueRow[]} */
const initialParamRows = [
  { enabled: false, id: "param-1", key: "include_details", value: "true" },
  { enabled: false, id: "param-2", key: "expand", value: "customer,payment" },
  createEmptyKeyValueRow("param", 3),
];
/** @type {KeyValueRow[]} */
const initialBodyRows = [createEmptyKeyValueRow("body-field", 1)];
const authTypeOptions = [
  { label: "No Auth", value: "no-auth" },
  { label: "Basic Auth", value: "basic" },
  { label: "Bearer Token", value: "bearer" },
  { label: "API Key", value: "api-key" },
];
const bodyTypeOptions = [
  { label: "none", value: "none" },
  { label: "form-data", value: "form-data" },
  { label: "x-www-form-urlencoded", value: "form-urlencoded" },
  { label: "raw", value: "raw" },
  { label: "binary", value: "binary" },
  { label: "GraphQL", value: "graphql" },
];
const rawBodyFormatOptions = [
  { label: "Text", value: "text" },
  { label: "JavaScript", value: "javascript" },
  { label: "JSON", value: "json" },
  { label: "HTML", value: "html" },
  { label: "XML", value: "xml" },
];
const responseTabs = ["Body", "Cookies", "Headers (7)", "Test", "History"];
const responseSample = `{
  "id": "ord_1234567890",
  "customer_id": "cust_9876543210",
  "status": "completed",
  "items": [
    {
      "product_id": "prod_111",
      "name": "Wireless Headphones",
      "quantity": 1,
      "price": 79.99,
      "subtotal": 79.99
    }
  ]
}`;
const terminalEntries = [
  "[REQUEST] GET /api/health",
  "[RESPONSE] 200 OK · 3ms",
  "",
  "[REQUEST] POST /api/orders",
  "[PROCESS] Validating payload...",
  "[PROCESS] Persisting order...",
  "[RESPONSE] 201 Created · 17ms",
  "",
  "[REQUEST] GET /api/orders/ord_1234567890",
  "[RESPONSE] 200 OK · 5ms",
];
/** @type {import("react-json-view-lite").Props["style"]} */
const jsonLiteStyles = {
  container: styles.jsonLiteContainer,
  childFieldsContainer: styles.jsonLiteChildFields,
  basicChildStyle: styles.jsonLiteNode,
  collapseIcon: styles.jsonLiteCollapseIcon,
  expandIcon: styles.jsonLiteExpandIcon,
  collapsedContent: styles.jsonLiteCollapsedContent,
  label: styles.jsonLiteLabel,
  clickableLabel: styles.jsonLiteClickableLabel,
  nullValue: styles.jsonLiteNull,
  undefinedValue: styles.jsonLiteNull,
  numberValue: styles.jsonLiteNumber,
  stringValue: styles.jsonLiteString,
  booleanValue: styles.jsonLiteBoolean,
  otherValue: styles.jsonLiteOther,
  punctuation: styles.jsonLitePunctuation,
  stringifyStringValues: true,
};

export function ToolCenterPage() {
  const [activeTab, setActiveTab] = useState("JSON Formatter");
  const activeTool = toolModes.find((tool) => tool.id === activeTab) ?? toolModes[0];

  return (
    <WorkspacePageFrame className={styles.toolCanvas}>
      <WorkspaceStatusBar title="工具中心" />

      <ToolCenterToolbar activeTab={activeTab} onSelectTab={setActiveTab} />

      <main aria-label="工具中心工作区" className={styles.workspaceBody}>
        <section aria-label={`${activeTool.label} 工作区`} className={styles.toolWorkbench}>
          {activeTab === "JSON Formatter" ? <JsonFormatterPanel /> : null}
          {activeTab === "API Caller" ? <ApiCallerPanel /> : null}
        </section>
      </main>
    </WorkspacePageFrame>
  );
}

export function ApiCallerSettingsPage() {
  return (
    <WorkspacePageFrame className={styles.toolCanvas}>
      <WorkspaceStatusBar title="API Caller 设置" />

      <main aria-label="API Caller 设置工作区" className={styles.workspaceBody}>
        <section aria-label="API Caller 设置" className={styles.toolWorkbench}>
          <div className={styles.settingsPageLayout}>
            <ApiCallerSettingsPanel />
          </div>
        </section>
      </main>
    </WorkspacePageFrame>
  );
}

/**
 * @param {{ activeTab: string, onSelectTab: (tab: string) => void }} props
 */
function ToolCenterToolbar({ activeTab, onSelectTab }) {
  return (
    <section
      aria-label="工具中心工具栏"
      className={`${styles.toolToolbar} ${toolbarStyles.surface}`}
      role="toolbar"
    >
      <nav aria-label="工具中心工具切换" className={styles.toolTabs} role="tablist">
        {toolModes.map((toolMode) => (
          <ToolTabButton
            active={activeTab === toolMode.id}
            icon={toolMode.icon}
            key={toolMode.id}
            label={toolMode.label}
            onClick={() => onSelectTab(toolMode.id)}
          />
        ))}
      </nav>
    </section>
  );
}

/**
 * @param {{
 *   active: boolean,
 *   icon: import("lucide-react").LucideIcon,
 *   label: string,
 *   onClick: () => void,
 * }} props
 */
function ToolTabButton({ active, icon: Icon, label, onClick }) {
  return (
    <button
      aria-selected={active}
      className={active ? styles.activeTab : ""}
      onClick={onClick}
      role="tab"
      type="button"
    >
      <Icon aria-hidden="true" size={15} strokeWidth={2.35} />
      <span>{label}</span>
    </button>
  );
}

/**
 * @param {{
 *   expanded: boolean,
 *   label: string,
 *   onClick: () => void,
 * }} props
 */
function PanelExpandButton({ expanded, label, onClick }) {
  const Icon = expanded ? Minimize2 : Maximize2;
  const actionLabel = expanded ? "恢复默认布局" : `展开${label}面板`;
  return (
    <button
      aria-label={actionLabel}
      className={styles.jsonTransformIconButton}
      onClick={onClick}
      title={actionLabel}
      type="button"
    >
      <Icon aria-hidden="true" size={16} />
    </button>
  );
}

function JsonFormatterPanel() {
  const [source, setSource] = useState(initialJson);
  const [output, setOutput] = useState("");
  const [error, setError] = useState("");
  const [expandedPanel, setExpandedPanel] = useState(/** @type {JsonPanelId | null} */ (null));
  const parsedJson = useMemo(() => parseJsonForHeroView(source), [source]);
  const [selectedPath, setSelectedPath] = useState("$");
  const [expandMode, setExpandMode] = useState(/** @type {JsonExpandMode} */ ("root"));
  const [searchQuery, setSearchQuery] = useState("");
  const [copyStatus, setCopyStatus] = useState("");
  const [repairPending, setRepairPending] = useState(false);
  const [assistantMessageOpen, setAssistantMessageOpen] = useState(false);
  const [assistantStatus, setAssistantStatus] = useState(
    /** @type {{ kind: JsonAssistantStatusKind, message: string }} */ ({ kind: "idle", message: "" }),
  );
  const sourceHasContent = source.trim().length > 0;
  const assistantFullMessage =
    assistantStatus.kind === "pending"
      ? "Agent 正在运行。自定义 Skill 正在 AI 修补 JSON。AI 修补调用中。"
      : assistantStatus.message;
  const searchResult = useMemo(
    () => (parsedJson.ok ? findJsonHeroMatches(parsedJson.root, searchQuery) : { matchingPaths: [], ancestorPaths: [] }),
    [parsedJson, searchQuery],
  );

  /**
   * @param {{ kind: JsonAssistantStatusKind, message: string }} status
   */
  function updateAssistantStatus(status) {
    setAssistantStatus(status);
    setAssistantMessageOpen(false);
  }

  /**
   * @param {string} value
   */
  function replaceJsonSource(value) {
    setSource(value);
    setSelectedPath("$");
    setExpandMode("root");
    setSearchQuery("");
    setCopyStatus("");
  }

  /**
   * @param {string} value
   */
  function updateSource(value) {
    replaceJsonSource(value);
    updateAssistantStatus({ kind: "idle", message: "" });
  }

  /**
   * @param {"format" | "minify"} kind
   */
  function applyJsonTransform(kind) {
    const result = kind === "format" ? formatJsonDocument(source) : minifyJsonDocument(source);
    if (result.ok) {
      setOutput(result.value);
      setError("");
      updateAssistantStatus({ kind: "idle", message: "" });
      return;
    }
    setError(result.error);
    updateAssistantStatus({ kind: "idle", message: "" });
  }

  async function applyJsonRepair() {
    const localResult = formatJsonDocument(source);
    if (localResult.ok) {
      setOutput(localResult.value);
      setError("");
      updateAssistantStatus({ kind: "success", message: "JSON 已可解析，无需 AI 修补。" });
      return;
    }

    setError(localResult.error);
    setRepairPending(true);
    updateAssistantStatus({ kind: "pending", message: "正在通过自定义 Skill 做 AI 修补。" });
    try {
      const response = await repairJsonWithAssistant({
        contractVersion: "1.0",
        assistantAction: "REPAIR_JSON",
        source,
        parseError: localResult.error,
        idempotencyKey: createJsonRepairIdempotencyKey(),
      });
      if (response.status === "SUCCEEDED" && response.repairedJson) {
        const verified = formatJsonDocument(response.repairedJson);
        if (verified.ok) {
          replaceJsonSource(verified.value);
          setOutput(verified.value);
          setError("");
          updateAssistantStatus({ kind: "success", message: response.summary });
          return;
        }
        setError(verified.error);
        updateAssistantStatus({ kind: "error", message: "AI 修复结果未通过本地 JSON 校验。" });
        return;
      }
      updateAssistantStatus({
        kind: "error",
        message: response.failureReason || response.summary,
      });
    } catch (repairError) {
      const message = repairError instanceof Error ? repairError.message : "AI 修补请求失败。";
      updateAssistantStatus({ kind: "error", message });
    } finally {
      setRepairPending(false);
    }
  }

  /**
   * @param {JsonPanelId} panelId
   */
  function isPanelVisible(panelId) {
    return expandedPanel === null || expandedPanel === panelId;
  }

  /**
   * @param {JsonPanelId} panelId
   */
  function togglePanelExpansion(panelId) {
    setExpandedPanel((currentPanel) => (currentPanel === panelId ? null : panelId));
  }

  return (
    <section
      aria-label="输入"
      className={expandedPanel ? `${styles.jsonHeroGrid} ${styles.jsonHeroGridExpanded}` : styles.jsonHeroGrid}
    >
      {isPanelVisible("input") ? (
      <div className={expandedPanel === "input" ? `${styles.editorPanel} ${styles.expandedJsonPanel}` : styles.editorPanel}>
        <div className={styles.jsonPanelHeader}>
          <PanelTitle icon={Braces} title="输入" />
          <div className={styles.jsonPanelHeaderActions}>
            <button
              aria-label="AI 修补 JSON"
              className={styles.jsonTransformIconButton}
              disabled={repairPending}
              onClick={() => void applyJsonRepair()}
              title="AI 修补 JSON"
              type="button"
            >
              {repairPending ? (
                <LoaderCircle aria-hidden="true" className={styles.jsonRunningIcon} size={16} />
              ) : (
                <Sparkles aria-hidden="true" size={16} />
              )}
            </button>
            <button
              aria-label="格式化 JSON"
              className={styles.jsonTransformIconButton}
              onClick={() => applyJsonTransform("format")}
              title="格式化 JSON"
              type="button"
            >
              <Braces aria-hidden="true" size={16} />
            </button>
            <PanelExpandButton
              expanded={expandedPanel === "input"}
              label="输入"
              onClick={() => togglePanelExpansion("input")}
            />
          </div>
        </div>
        <label className={styles.textareaField}>
          <textarea aria-label="JSON 输入" onChange={(event) => updateSource(event.target.value)} value={source} />
        </label>
        <div className={styles.jsonPanelFooter}>
          {assistantStatus.kind !== "idle" ? (
            <div
              aria-label={assistantStatus.kind === "pending" ? "Agent 正在运行" : undefined}
              aria-live="polite"
              className={`${styles.jsonAssistantStatus} ${styles[`jsonAssistantStatus${assistantStatus.kind}`]}`}
              role={assistantStatus.kind === "error" ? "alert" : "status"}
            >
              <button
                aria-expanded={assistantMessageOpen}
                aria-haspopup="dialog"
                aria-label="查看完整状态消息"
                className={styles.jsonAssistantStatusButton}
                onClick={() => setAssistantMessageOpen((isOpen) => !isOpen)}
                title={assistantFullMessage}
                type="button"
              >
                {assistantStatus.kind === "pending" ? (
                  <>
                    <span aria-hidden="true" className={styles.jsonAgentPulse} />
                    <span className={styles.jsonAssistantStatusText}>
                      <strong>Agent 正在运行</strong>
                      <span>自定义 Skill 正在 AI 修补 JSON</span>
                      <span className={styles.jsonAgentPhase}>AI 修补调用中</span>
                    </span>
                  </>
                ) : (
                  <span className={styles.jsonAssistantStatusText}>{assistantStatus.message}</span>
                )}
              </button>
              {assistantMessageOpen ? (
                <div aria-label="完整状态消息" className={styles.jsonAssistantPopover} role="dialog">
                  <p>{assistantFullMessage}</p>
                  <button
                    aria-label="关闭完整状态消息"
                    className={styles.jsonAssistantPopoverClose}
                    onClick={() => setAssistantMessageOpen(false)}
                    type="button"
                  >
                    <X aria-hidden="true" size={14} />
                  </button>
                </div>
              ) : null}
            </div>
          ) : null}
        </div>
      </div>
      ) : null}
      {isPanelVisible("output") ? (
      <div className={expandedPanel === "output" ? `${styles.editorPanel} ${styles.expandedJsonPanel}` : styles.editorPanel}>
        <div className={styles.jsonPanelHeader}>
          <PanelTitle icon={ShieldCheck} title="输出" />
          <div className={styles.jsonPanelHeaderActions}>
            <button
              aria-label="压缩 JSON"
              className={styles.jsonTransformIconButton}
              onClick={() => applyJsonTransform("minify")}
              title="压缩 JSON"
              type="button"
            >
              <Braces aria-hidden="true" size={16} />
            </button>
            <PanelExpandButton
              expanded={expandedPanel === "output"}
              label="输出"
              onClick={() => togglePanelExpansion("output")}
            />
          </div>
        </div>
        <label className={styles.textareaField}>
          <textarea aria-label="JSON 输出" readOnly value={output} />
        </label>
        <div className={styles.jsonPanelFooter}>
          {error ? (
            <span className={`${styles.jsonPanelFooterMessage} ${styles.jsonPanelFooterError}`} role="alert">
              {error}
            </span>
          ) : null}
        </div>
      </div>
      ) : null}
      {isPanelVisible("structure") ? (
      <JsonHeroBrowserPanel
        copyStatus={copyStatus}
        expanded={expandedPanel === "structure"}
        expandMode={expandMode}
        onCopyStatus={setCopyStatus}
        onExpandModeChange={setExpandMode}
        onToggleExpand={() => togglePanelExpansion("structure")}
        onSearchQueryChange={setSearchQuery}
        onSelectPath={setSelectedPath}
        parseResult={parsedJson}
        searchQuery={searchQuery}
        searchResult={searchResult}
        selectedPath={selectedPath}
        sourceHasContent={sourceHasContent}
      />
      ) : null}
    </section>
  );
}

/**
 * @param {{
 *   copyStatus: string,
 *   expanded: boolean,
 *   expandMode: JsonExpandMode,
 *   onCopyStatus: (value: string) => void,
 *   onExpandModeChange: (value: JsonExpandMode) => void,
 *   onToggleExpand: () => void,
 *   onSearchQueryChange: (value: string) => void,
 *   onSelectPath: (value: string) => void,
 *   parseResult: ReturnType<typeof parseJsonForHeroView>,
 *   searchQuery: string,
 *   searchResult: ReturnType<typeof findJsonHeroMatches>,
 *   selectedPath: string,
 *   sourceHasContent: boolean,
 * }} props
 */
function JsonHeroBrowserPanel({
  copyStatus,
  expanded,
  expandMode,
  onCopyStatus,
  onExpandModeChange,
  onToggleExpand,
  onSearchQueryChange,
  onSelectPath,
  parseResult,
  searchQuery,
  searchResult,
  selectedPath,
  sourceHasContent,
}) {
  const matchingNodes = useMemo(
    () =>
      parseResult.ok
        ? searchResult.matchingPaths
            .map((path) => findJsonHeroNodeByPath(parseResult.root, path))
            .filter((node) => node !== null)
        : [],
    [parseResult, searchResult.matchingPaths],
  );
  const selectedNode = parseResult.ok ? (findJsonHeroNodeByPath(parseResult.root, selectedPath) ?? parseResult.root) : null;
  const jsonLiteData = parseResult.ok && isJsonLiteData(parseResult.root.value) ? parseResult.root.value : null;
  const shouldExpandNode = searchQuery.trim() || expandMode === "all" ? allExpanded : collapseAllNested;

  function expandAll() {
    onExpandModeChange("all");
  }

  function collapseAll() {
    onExpandModeChange("root");
  }

  async function copyPath() {
    if (!selectedNode) {
      return;
    }
    await navigator.clipboard.writeText(selectedNode.path);
    onCopyStatus("已复制路径");
  }

  async function copyValue() {
    if (!selectedNode) {
      return;
    }
    await navigator.clipboard.writeText(formatJsonHeroNodeValue(selectedNode));
    onCopyStatus("已复制值");
  }

  return (
    <section aria-label="JSON 结构浏览" className={expanded ? `${styles.jsonHeroPanel} ${styles.expandedJsonPanel}` : styles.jsonHeroPanel}>
      <div className={styles.jsonHeroHeader}>
        <PanelTitle icon={ListTree} title="结构浏览" />
        <div className={styles.jsonHeroHeaderControls}>
          <div className={styles.jsonHeroActions}>
            {parseResult.ok && selectedNode ? (
              <>
                <button aria-label="复制路径" onClick={copyPath} title="复制路径" type="button">
                  <Copy aria-hidden="true" size={14} />
                </button>
                <button aria-label="复制值" onClick={copyValue} title="复制值" type="button">
                  <Check aria-hidden="true" size={14} />
                </button>
                <button aria-label="全部展开" onClick={expandAll} title="全部展开" type="button">
                  <ListTree aria-hidden="true" size={14} />
                </button>
                <button aria-label="全部折叠" onClick={collapseAll} title="全部折叠" type="button">
                  <ListCollapse aria-hidden="true" size={14} />
                </button>
              </>
            ) : null}
            <PanelExpandButton expanded={expanded} label="结构浏览" onClick={onToggleExpand} />
          </div>
        </div>
      </div>
      <div className={styles.jsonPanelBody}>
        {parseResult.ok ? (
          <>
            <label className={styles.jsonHeroSearch}>
              <span>搜索 JSON 结构</span>
              <input
                aria-label="搜索 JSON 结构"
                onChange={(event) => onSearchQueryChange(event.target.value)}
                value={searchQuery}
              />
              <Search aria-hidden="true" size={15} />
            </label>
            <div className={styles.jsonHeroTree} data-json-viewer="react-json-view-lite">
              {jsonLiteData ? (
                <JsonView
                  aria-label="JSON view"
                  clickToExpandNode
                  data={/** @type {Record<string, unknown> | unknown[]} */ (jsonLiteData)}
                  shouldExpandNode={shouldExpandNode}
                  style={jsonLiteStyles}
                />
              ) : (
                <pre className={styles.jsonHeroScalar}>{formatJsonHeroNodeValue(parseResult.root)}</pre>
              )}
            </div>
            {searchQuery.trim() ? (
              <div aria-label="JSON 搜索命中" className={styles.jsonHeroMatches} role="list">
                {matchingNodes.length ? (
                  matchingNodes.map((node) => (
                    <div key={node.path} role="listitem">
                      <button
                        aria-label={`选择 ${node.path} ${node.kind} ${node.preview}`}
                        aria-pressed={selectedPath === node.path}
                        className={selectedPath === node.path ? styles.selectedJsonHeroMatch : ""}
                        onClick={() => onSelectPath(node.path)}
                        type="button"
                      >
                        <span>{node.path}</span>
                        <strong>{node.kind}</strong>
                        <em>{node.preview}</em>
                      </button>
                    </div>
                  ))
                ) : (
                  <p className={styles.jsonHeroNoMatches}>没有命中</p>
              )}
            </div>
          ) : null}
        </>
        ) : null}
      </div>
      <div className={`${styles.jsonPanelFooter} ${styles.jsonHeroFooter}`}>
        <div className={styles.jsonHeroFooterSummary}>
          {parseResult.ok ? (
            <div className={styles.jsonHeroSummary}>
              <span>{parseResult.root.kind}</span>
              <strong>{parseResult.root.preview}</strong>
            </div>
          ) : null}
        </div>
        <div className={styles.jsonHeroFooterStatus}>
          {!parseResult.ok && sourceHasContent ? (
            <span className={`${styles.jsonPanelFooterMessage} ${styles.jsonPanelFooterError}`} role="alert">
              {parseResult.error}
            </span>
          ) : copyStatus ? (
            <span aria-label={copyStatus} className={styles.jsonHeroCopyStatus} role="status" title={copyStatus}>
              <Check aria-hidden="true" size={12} />
            </span>
          ) : null}
        </div>
      </div>
    </section>
  );
}

/**
 * @param {import("./tool-center-utils.js").JsonHeroNode} node
 * @param {string} path
 * @returns {import("./tool-center-utils.js").JsonHeroNode | null}
 */
function findJsonHeroNodeByPath(node, path) {
  if (node.path === path) {
    return node;
  }
  for (const child of node.children) {
    const result = findJsonHeroNodeByPath(child, path);
    if (result) {
      return result;
    }
  }
  return null;
}

/**
 * @param {unknown} value
 */
function isJsonLiteData(value) {
  return value !== null && (Array.isArray(value) || typeof value === "object");
}

function ApiCallerPanel() {
  const [url, setUrl] = useState("https://api.quefork.internal/orders/42");
  const [method, setMethod] = useState("GET");
  const [activeRequestTab, setActiveRequestTab] = useState("params");
  const [activeResponseTab, setActiveResponseTab] = useState("Body");
  const [paramRows, setParamRows] = useState(initialParamRows);
  const [nextParamIndex, setNextParamIndex] = useState(4);
  const [headerRows, setHeaderRows] = useState(initialHeaderRows);
  const [nextHeaderIndex, setNextHeaderIndex] = useState(3);
  const [bodyType, setBodyType] = useState(/** @type {RequestBodyType} */ ("none"));
  const [rawBodyFormat, setRawBodyFormat] = useState(/** @type {RawBodyFormat} */ ("json"));
  const [rawBody, setRawBody] = useState("");
  const [bodyRows, setBodyRows] = useState(initialBodyRows);
  const [nextBodyRowIndex, setNextBodyRowIndex] = useState(2);
  const [authType, setAuthType] = useState(/** @type {AuthType} */ ("no-auth"));
  const [basicUsername, setBasicUsername] = useState("");
  const [basicPassword, setBasicPassword] = useState("");
  const [bearerToken, setBearerToken] = useState("");
  const [apiKeyName, setApiKeyName] = useState("");
  const [apiKeyValue, setApiKeyValue] = useState("");
  const [apiKeyTarget, setApiKeyTarget] = useState("header");
  const originResult = useMemo(() => deriveRequestOrigin(url), [url]);
  const paramCount = useMemo(() => countKeyValueRows(paramRows), [paramRows]);
  const headerCount = useMemo(() => countKeyValueRows(headerRows), [headerRows]);
  const bodyFieldCount = useMemo(() => countKeyValueRows(bodyRows), [bodyRows]);
  const usesBodyFields = bodyType === "form-urlencoded" || bodyType === "form-data";
  const bodySummary = formatBodySummary(bodyType, rawBody, bodyFieldCount);
  const requestTabs = [
    { id: "params", label: `Params (${paramCount})` },
    { id: "auth", label: "Auth" },
    { id: "headers", label: `Headers (${headerCount})` },
    { id: "body", label: "Body" },
    { id: "scripts", label: "Scripts" },
  ];

  /**
   * @param {{ rows: KeyValueRow[], nextIndex: number }} result
   */
  function commitParamRows(result) {
    setParamRows(result.rows);
    setNextParamIndex(result.nextIndex);
    setUrl((currentUrl) => syncUrlWithParamRows(currentUrl, result.rows));
  }

  /**
   * @param {string} id
   * @param {"key" | "value"} field
   * @param {string} value
   */
  function updateParamRow(id, field, value) {
    const result = updateKeyValueRows(paramRows, id, field, value, "param", nextParamIndex);
    commitParamRows(result);
  }

  /**
   * @param {string} id
   * @param {boolean} enabled
   */
  function toggleParamRow(id, enabled) {
    const result = updateKeyValueRows(paramRows, id, "enabled", enabled, "param", nextParamIndex);
    commitParamRows(result);
  }

  /**
   * @param {string} id
   */
  function removeParamRow(id) {
    const result = removeKeyValueRow(paramRows, id, "param", nextParamIndex);
    commitParamRows(result);
  }

  /**
   * @param {string} id
   * @param {"key" | "value"} field
   * @param {string} value
   */
  function updateHeaderRow(id, field, value) {
    const result = updateKeyValueRows(headerRows, id, field, value, "header", nextHeaderIndex);
    setHeaderRows(result.rows);
    setNextHeaderIndex(result.nextIndex);
  }

  /**
   * @param {string} id
   * @param {boolean} enabled
   */
  function toggleHeaderRow(id, enabled) {
    const result = updateKeyValueRows(headerRows, id, "enabled", enabled, "header", nextHeaderIndex);
    setHeaderRows(result.rows);
    setNextHeaderIndex(result.nextIndex);
  }

  /**
   * @param {string} id
   */
  function removeHeaderRow(id) {
    const result = removeKeyValueRow(headerRows, id, "header", nextHeaderIndex);
    setHeaderRows(result.rows);
    setNextHeaderIndex(result.nextIndex);
  }

  /**
   * @param {string} id
   * @param {"key" | "value"} field
   * @param {string} value
   */
  function updateBodyRow(id, field, value) {
    const result = updateKeyValueRows(bodyRows, id, field, value, "body-field", nextBodyRowIndex);
    setBodyRows(result.rows);
    setNextBodyRowIndex(result.nextIndex);
  }

  /**
   * @param {string} id
   * @param {boolean} enabled
   */
  function toggleBodyRow(id, enabled) {
    const result = updateKeyValueRows(bodyRows, id, "enabled", enabled, "body-field", nextBodyRowIndex);
    setBodyRows(result.rows);
    setNextBodyRowIndex(result.nextIndex);
  }

  /**
   * @param {string} id
   */
  function removeBodyRow(id) {
    const result = removeKeyValueRow(bodyRows, id, "body-field", nextBodyRowIndex);
    setBodyRows(result.rows);
    setNextBodyRowIndex(result.nextIndex);
  }

  return (
    <section aria-label="API Caller" className={styles.callerLayout}>
      <div className={styles.postmanClient}>
        <div className={styles.requestTabStrip}>
          <button aria-current="page" className={styles.requestDocumentTab} type="button">
            <span className={styles.methodBadge}>{method}</span>
            Get Order
          </button>
          <button aria-label="新建请求" className={styles.addDocumentTab} disabled type="button">
            <Plus aria-hidden="true" size={16} />
          </button>
          <div className={styles.requestMetaActions}>
            <button disabled type="button">
              Save
            </button>
            <button disabled type="button">
              Share
            </button>
          </div>
        </div>

        <div className={styles.postmanRequestLine}>
          <label>
            <span>HTTP 方法</span>
            <select
              aria-label="HTTP 方法"
              className={styles.methodSelect}
              onChange={(event) => setMethod(event.target.value)}
              value={method}
            >
              {["GET", "POST", "PUT", "PATCH", "DELETE"].map((item) => (
                <option key={item}>{item}</option>
              ))}
            </select>
          </label>
          <label>
            <span>请求 URL</span>
            <input aria-label="请求 URL" onChange={(event) => setUrl(event.target.value)} value={url} />
          </label>
          <button className={styles.sendButton} disabled type="button">
            <SendHorizontal aria-hidden="true" size={15} /> 发送请求
          </button>
        </div>

        <nav aria-label="API Caller 请求配置" className={styles.requestConfigTabs} role="tablist">
          {requestTabs.map((tab) => (
            <button
              aria-selected={activeRequestTab === tab.id}
              className={activeRequestTab === tab.id ? styles.activeRequestTab : ""}
              key={tab.id}
              onClick={() => setActiveRequestTab(tab.id)}
              role="tab"
              type="button"
            >
              {tab.label}
            </button>
          ))}
        </nav>

        <section aria-label="API Caller 请求编辑区" className={styles.requestEditor}>
          {activeRequestTab === "params" ? (
            <KeyValueEditor
              emptyLabel="未配置 Params"
              labelPrefix="Param"
              onRemove={removeParamRow}
              onToggle={toggleParamRow}
              onUpdate={updateParamRow}
              rows={paramRows}
              title="Params"
            />
          ) : null}
          {activeRequestTab === "auth" ? (
            <AuthDraftPane
              apiKeyName={apiKeyName}
              apiKeyTarget={apiKeyTarget}
              apiKeyValue={apiKeyValue}
              authType={authType}
              basicPassword={basicPassword}
              basicUsername={basicUsername}
              bearerToken={bearerToken}
              onApiKeyNameChange={setApiKeyName}
              onApiKeyTargetChange={setApiKeyTarget}
              onApiKeyValueChange={setApiKeyValue}
              onAuthTypeChange={setAuthType}
              onBasicPasswordChange={setBasicPassword}
              onBasicUsernameChange={setBasicUsername}
              onBearerTokenChange={setBearerToken}
            />
          ) : null}
          {activeRequestTab === "headers" ? (
            <KeyValueEditor
              emptyLabel="未配置 Header"
              labelPrefix="Header"
              onRemove={removeHeaderRow}
              onToggle={toggleHeaderRow}
              onUpdate={updateHeaderRow}
              rows={headerRows}
              title="Headers"
            />
          ) : null}
          {activeRequestTab === "body" ? (
            <section aria-label="Body" className={styles.bodyPane}>
              <fieldset aria-label="请求体类型" className={styles.bodyTypeRadioGroup} role="radiogroup">
                {bodyTypeOptions.map((option) => (
                  <label className={styles.bodyTypeRadio} key={option.value}>
                    <input
                      checked={bodyType === option.value}
                      name="api-caller-body-type"
                      onChange={() => setBodyType(/** @type {RequestBodyType} */ (option.value))}
                      type="radio"
                    />
                    <span>{option.label}</span>
                  </label>
                ))}
                {bodyType === "raw" ? (
                  <select
                    aria-label="Raw Body 类型"
                    className={styles.rawBodyFormatSelect}
                    onChange={(event) => setRawBodyFormat(/** @type {RawBodyFormat} */ (event.target.value))}
                    value={rawBodyFormat}
                  >
                    {rawBodyFormatOptions.map((option) => (
                      <option key={option.value} value={option.value}>
                        {option.label}
                      </option>
                    ))}
                  </select>
                ) : null}
              </fieldset>
              <div className={styles.bodyDraftSurface}>
                {bodyType === "none" ? (
                  <p className={styles.emptyBodyState}>This request does not have a body</p>
                ) : usesBodyFields ? (
                  <KeyValueEditor
                    emptyLabel="未配置 Body 字段"
                    labelPrefix="Body"
                    onRemove={removeBodyRow}
                    onToggle={toggleBodyRow}
                    onUpdate={updateBodyRow}
                    rows={bodyRows}
                    title="Body"
                  />
                ) : (
                  <label className={styles.textareaField}>
                    <textarea
                      aria-label="Body 内容"
                      onChange={(event) => setRawBody(event.target.value)}
                      placeholder={bodyType === "raw" ? '{"dryRun":true}' : ""}
                      value={rawBody}
                    />
                  </label>
                )}
              </div>
            </section>
          ) : null}
          {activeRequestTab === "scripts" ? (
            <section aria-label="Scripts" className={styles.placeholderPane}>
              <h3>Pre-request Script</h3>
              <p>当前只保留草稿区，不执行浏览器脚本。</p>
            </section>
          ) : null}
        </section>

        <section aria-label="API Caller 响应区" className={styles.responsePanel}>
          <div className={styles.responseToolbar}>
            <nav aria-label="API Caller 响应标签" className={styles.responseTabs} role="tablist">
              {responseTabs.map((tab) => (
                <button
                  aria-selected={activeResponseTab === tab}
                  className={activeResponseTab === tab ? styles.activeResponseTab : ""}
                  key={tab}
                  onClick={() => setActiveResponseTab(tab)}
                  role="tab"
                  type="button"
                >
                  {tab}
                </button>
              ))}
            </nav>
            <div className={styles.responseMetrics}>
              <span className={styles.statusOk}>200 OK</span>
              <span>370 ms</span>
              <span>866 KB</span>
              <button disabled type="button">
                Save Response
              </button>
            </div>
          </div>
          <div className={styles.responseBodyToolbar}>
            <span>JSON</span>
          </div>
          <pre className={styles.responseBody}>{responseSample}</pre>
        </section>

        <section aria-label="API Caller Terminal" className={styles.terminalPanel}>
          <div className={styles.terminalToolbar}>
            <strong>Terminal</strong>
            <span>Console</span>
          </div>
          <pre>{terminalEntries.join("\n")}</pre>
        </section>
      </div>

      <ApiCallerAssistantPanel bodySummary={bodySummary} headerCount={headerCount} method={method} originResult={originResult} />
    </section>
  );
}

/**
 * @param {{
 *   apiKeyName: string,
 *   apiKeyTarget: string,
 *   apiKeyValue: string,
 *   authType: AuthType,
 *   basicPassword: string,
 *   basicUsername: string,
 *   bearerToken: string,
 *   onApiKeyNameChange: (value: string) => void,
 *   onApiKeyTargetChange: (value: string) => void,
 *   onApiKeyValueChange: (value: string) => void,
 *   onAuthTypeChange: (value: AuthType) => void,
 *   onBasicPasswordChange: (value: string) => void,
 *   onBasicUsernameChange: (value: string) => void,
 *   onBearerTokenChange: (value: string) => void,
 * }} props
 */
function AuthDraftPane({
  apiKeyName,
  apiKeyTarget,
  apiKeyValue,
  authType,
  basicPassword,
  basicUsername,
  bearerToken,
  onApiKeyNameChange,
  onApiKeyTargetChange,
  onApiKeyValueChange,
  onAuthTypeChange,
  onBasicPasswordChange,
  onBasicUsernameChange,
  onBearerTokenChange,
}) {
  return (
    <section aria-label="Auth" className={styles.authPane}>
      <div className={styles.authMethodColumn}>
        <label className={`${styles.field} ${styles.authTypeField}`}>
          <span>Auth Type</span>
          <select
            aria-label="Auth Type"
            onChange={(event) => onAuthTypeChange(/** @type {AuthType} */ (event.target.value))}
            value={authType}
          >
            {authTypeOptions.map((option) => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
      </div>

      <div className={styles.authDetailColumn}>
        {authType === "basic" ? (
          <div className={styles.authCredentialGrid}>
            <label htmlFor="basic-auth-username">Username</label>
            <input
              aria-label="Username"
              id="basic-auth-username"
              onChange={(event) => onBasicUsernameChange(event.target.value)}
              placeholder="Username"
              value={basicUsername}
            />
            <label htmlFor="basic-auth-password">Password</label>
            <input
              aria-label="Password"
              autoComplete="new-password"
              id="basic-auth-password"
              onChange={(event) => onBasicPasswordChange(event.target.value)}
              placeholder="Password"
              type="password"
              value={basicPassword}
            />
          </div>
        ) : null}

        {authType === "bearer" ? (
          <div className={styles.authCredentialGrid}>
            <label htmlFor="bearer-token">Token</label>
            <input
              aria-label="Bearer Token"
              autoComplete="new-password"
              id="bearer-token"
              onChange={(event) => onBearerTokenChange(event.target.value)}
              placeholder="Token"
              type="password"
              value={bearerToken}
            />
            <span aria-hidden="true" />
            <small className={styles.authSecretPreview}>{previewSecretInput(bearerToken, "Bearer Token")}</small>
          </div>
        ) : null}

        {authType === "api-key" ? (
          <div className={styles.authCredentialGrid}>
            <label htmlFor="api-key-name">Key</label>
            <input
              aria-label="API Key Name"
              id="api-key-name"
              onChange={(event) => onApiKeyNameChange(event.target.value)}
              placeholder="Key"
              value={apiKeyName}
            />
            <label htmlFor="api-key-value">Value</label>
            <input
              aria-label="API Key Value"
              autoComplete="new-password"
              id="api-key-value"
              onChange={(event) => onApiKeyValueChange(event.target.value)}
              placeholder="Value"
              type="password"
              value={apiKeyValue}
            />
            <label htmlFor="api-key-target">Add to</label>
            <select
              aria-label="Add API Key To"
              id="api-key-target"
              onChange={(event) => onApiKeyTargetChange(event.target.value)}
              value={apiKeyTarget}
            >
              <option value="header">Header</option>
              <option value="query">Query Params</option>
            </select>
          </div>
        ) : null}
      </div>
    </section>
  );
}

/**
 * @param {{
 *   bodySummary: string,
 *   headerCount: number,
 *   method: string,
 *   originResult: ReturnType<typeof deriveRequestOrigin>
 * }} props
 */
function ApiCallerAssistantPanel({ bodySummary, headerCount, method, originResult }) {
  return (
    <aside aria-label="API Caller AI 助手" className={styles.apiAssistantPanel}>
      <div className={styles.assistantToolbar}>
        <button aria-current="page" type="button">
          AI
        </button>
        <button disabled type="button">
          Docs
        </button>
        <button disabled type="button">
          Code
        </button>
      </div>
      <section className={styles.assistantSection}>
        <h3>Workspace Overview</h3>
        <p>Orders API Workspace</p>
        <ol>
          <li>Set client_id and client_secret in collection variables.</li>
          <li>Run Get Token before protected requests.</li>
          <li>Use Get Order to inspect the latest response body.</li>
        </ol>
      </section>
      <section className={styles.assistantSection}>
        <h3>Request Draft</h3>
        <dl className={styles.assistantFacts}>
          <div>
            <dt>Method</dt>
            <dd>{method}</dd>
          </div>
          <div>
            <dt>Origin</dt>
            <dd>{originResult.ok ? originResult.origin : originResult.error}</dd>
          </div>
          <div>
            <dt>Headers</dt>
            <dd>{headerCount}</dd>
          </div>
          <div>
            <dt>Body</dt>
            <dd>{bodySummary}</dd>
          </div>
        </dl>
      </section>
      <section className={styles.assistantSection}>
        <h3>Variables</h3>
        <ul>
          <li>{"{{baseURL}}"}: base domain for the selected environment</li>
          <li>{"{{orderId}}"}: dynamic order id in path segments</li>
        </ul>
      </section>
      <label className={styles.assistantPrompt}>
        <span>Ask AI</span>
        <textarea disabled placeholder="Describe what you need. Press / to browse Skills." />
      </label>
    </aside>
  );
}

/**
 * @param {KeyValueRow} row
 */
function isEmptyKeyValueRow(row) {
  return !row.key.trim() && !row.value.trim();
}

/**
 * @param {string} sourceUrl
 * @param {KeyValueRow[]} rows
 */
function syncUrlWithParamRows(sourceUrl, rows) {
  const hashIndex = sourceUrl.indexOf("#");
  const urlBeforeHash = hashIndex >= 0 ? sourceUrl.slice(0, hashIndex) : sourceUrl;
  const hash = hashIndex >= 0 ? sourceUrl.slice(hashIndex) : "";
  const queryIndex = urlBeforeHash.indexOf("?");
  const baseUrl = queryIndex >= 0 ? urlBeforeHash.slice(0, queryIndex) : urlBeforeHash;
  const query = new URLSearchParams();

  for (const row of rows) {
    const key = row.key.trim();
    if (row.enabled && key) {
      query.append(key, row.value);
    }
  }

  const queryText = query.toString();
  return `${baseUrl}${queryText ? `?${queryText}` : ""}${hash}`;
}

/**
 * @param {KeyValueRow[]} rows
 * @param {string} prefix
 * @param {number} nextIndex
 * @returns {{ rows: KeyValueRow[], nextIndex: number }}
 */
function normalizeKeyValueRows(rows, prefix, nextIndex) {
  const normalizedRows = rows.map((row) => (canToggleKeyValueRow(row) ? row : { ...row, enabled: false }));
  while (
    normalizedRows.length > 1 &&
    isEmptyKeyValueRow(normalizedRows[normalizedRows.length - 1]) &&
    isEmptyKeyValueRow(normalizedRows[normalizedRows.length - 2])
  ) {
    normalizedRows.pop();
  }

  if (normalizedRows.length === 0 || normalizedRows[normalizedRows.length - 1].key.trim()) {
    return {
      rows: [...normalizedRows, createEmptyKeyValueRow(prefix, nextIndex)],
      nextIndex: nextIndex + 1,
    };
  }

  return { rows: normalizedRows, nextIndex };
}

/**
 * @param {KeyValueRow[]} rows
 * @param {string} id
 * @param {"enabled" | "key" | "value"} field
 * @param {boolean | string} value
 * @param {string} prefix
 * @param {number} nextIndex
 * @returns {{ rows: KeyValueRow[], nextIndex: number }}
 */
function updateKeyValueRows(rows, id, field, value, prefix, nextIndex) {
  const updatedRows = rows.map((row) => (row.id === id ? { ...row, [field]: value } : row));
  return normalizeKeyValueRows(updatedRows, prefix, nextIndex);
}

/**
 * @param {KeyValueRow} row
 */
function canToggleKeyValueRow(row) {
  return Boolean(row.key.trim() || row.value.trim());
}

/**
 * @param {KeyValueRow[]} rows
 * @param {string} id
 * @param {string} prefix
 * @param {number} nextIndex
 * @returns {{ rows: KeyValueRow[], nextIndex: number }}
 */
function removeKeyValueRow(rows, id, prefix, nextIndex) {
  return normalizeKeyValueRows(
    rows.filter((row) => row.id !== id),
    prefix,
    nextIndex,
  );
}

/**
 * @param {KeyValueRow[]} rows
 */
function countKeyValueRows(rows) {
  return rows.filter((row) => row.key.trim()).length;
}

/**
 * @param {RequestBodyType} bodyType
 * @param {string} rawBody
 * @param {number} bodyFieldCount
 */
function formatBodySummary(bodyType, rawBody, bodyFieldCount) {
  if (bodyType === "none") {
    return "未配置请求体";
  }
  if (bodyType === "form-urlencoded" || bodyType === "form-data") {
    return bodyFieldCount > 0 ? `请求体 ${bodyFieldCount} 个字段` : "未配置请求体字段";
  }
  return rawBody ? `请求体 ${rawBody.length} 个字符` : "未配置请求体";
}

/**
 * @param {{
 *   emptyLabel: string,
 *   labelPrefix: string,
 *   onRemove: (id: string) => void,
 *   onToggle: (id: string, enabled: boolean) => void,
 *   onUpdate: (id: string, field: "key" | "value", value: string) => void,
 *   rows: KeyValueRow[],
 *   title: string,
 * }} props
 */
function KeyValueEditor({ emptyLabel, labelPrefix, onRemove, onToggle, onUpdate, rows, title }) {
  return (
    <section aria-label={title} className={styles.keyValueSection}>
      <div aria-label={`${title} 键值表`} className={styles.keyValueTable} role="table">
        <div className={styles.keyValueTableHead} role="row">
          <span aria-hidden="true" />
          <span role="columnheader">Key</span>
          <span role="columnheader">Value</span>
          <span aria-hidden="true" />
        </div>
        {rows.map((row, index) => {
          const rowNumber = index + 1;
          const toggleDisabled = !canToggleKeyValueRow(row);
          return (
            <div className={styles.keyValueRow} key={row.id} role="row">
              <label className={styles.keyValueToggleCell}>
                <input
                  checked={row.enabled && !toggleDisabled}
                  disabled={toggleDisabled}
                  onChange={(event) => onToggle(row.id, event.target.checked)}
                  type="checkbox"
                />
                <span>{`启用 ${labelPrefix} ${rowNumber}`}</span>
              </label>
              <label>
                <span>Key</span>
                <input
                  aria-label={`${labelPrefix} ${rowNumber} Key`}
                  onChange={(event) => onUpdate(row.id, "key", event.target.value)}
                  value={row.key}
                />
              </label>
              <label>
                <span>Value</span>
                <input
                  aria-label={`${labelPrefix} ${rowNumber} Value`}
                  onChange={(event) => onUpdate(row.id, "value", event.target.value)}
                  value={row.value}
                />
              </label>
              <button
                aria-label={`删除 ${labelPrefix} ${rowNumber}`}
                className={styles.iconButton}
                onClick={() => onRemove(row.id)}
                type="button"
              >
                <Trash2 aria-hidden="true" size={15} strokeWidth={2.4} />
              </button>
            </div>
          );
        })}
        {rows.length === 0 ? <p className={styles.emptyKeyValueRows}>{emptyLabel}</p> : null}
      </div>
    </section>
  );
}

function ApiCallerSettingsPanel() {
  const [draft, setDraft] = useState(defaultAllowlistDraft);
  const [validation, setValidation] = useState(initialValidation);

  /**
   * @param {keyof typeof defaultAllowlistDraft} field
   * @param {string | string[] | number} value
   */
  function updateDraft(field, value) {
    setDraft((current) => ({ ...current, [field]: value }));
  }

  function validateDraft() {
    setValidation(validateAllowlistDraft(draft));
  }

  return (
    <section aria-label="API Caller 管理员设置" className={styles.apiCallerSettingsPanel}>
      <PanelTitle icon={Settings2} title="Allowlist 设置" />
      <div className={styles.formGrid}>
        <label className={styles.field}>
          <span>目标系统</span>
          <input aria-label="目标系统" onChange={(event) => updateDraft("targetName", event.target.value)} value={draft.targetName} />
        </label>
        <label className={styles.field}>
          <span>允许域名</span>
          <input aria-label="允许域名" onChange={(event) => updateDraft("origin", event.target.value)} value={draft.origin} />
        </label>
        <label className={styles.field}>
          <span>环境标签</span>
          <input
            aria-label="环境标签"
            onChange={(event) => updateDraft("environmentLabel", event.target.value)}
            value={draft.environmentLabel}
          />
        </label>
        <label className={styles.field}>
          <span>超时秒数</span>
          <input
            aria-label="超时秒数"
            min="1"
            onChange={(event) => updateDraft("timeoutSeconds", Number(event.target.value))}
            type="number"
            value={draft.timeoutSeconds}
          />
        </label>
      </div>
      <div className={styles.actionRow}>
        <Button onClick={validateDraft} type="button" variant="secondary">
          校验 allowlist 草稿
        </Button>
        <Button disabled type="button">
          保存 allowlist 草稿
        </Button>
      </div>
      <ValidationMessage validation={validation} />
    </section>
  );
}

/**
 * @param {{ validation: {ok: boolean, errors: string[]} }} props
 */
function ValidationMessage({ validation }) {
  if (validation.ok) {
    return (
      <div className={styles.successMessage} role="status">
        allowlist 草稿校验通过
      </div>
    );
  }
  return (
    <div className={styles.errorMessage} role="alert">
      {validation.errors.map((error) => (
        <p key={error}>{error}</p>
      ))}
    </div>
  );
}

/**
 * @param {{
 *   icon: import("lucide-react").LucideIcon,
 *   subtitle?: string,
 *   title: string
 * }} props
 */
function PanelTitle({ icon: Icon, subtitle, title }) {
  return (
    <div className={subtitle ? `${styles.panelTitle} ${styles.panelTitleWithSubtitle}` : styles.panelTitle}>
      <span aria-hidden="true">
        <Icon size={18} strokeWidth={2.35} />
      </span>
      <div>
        <h3>{title}</h3>
        {subtitle ? <p>{subtitle}</p> : null}
      </div>
    </div>
  );
}
