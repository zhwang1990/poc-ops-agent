import {
  Bot,
  Braces,
  DatabaseZap,
  KeyRound,
  SendHorizontal,
  Settings2,
  ShieldCheck,
  WandSparkles,
} from "lucide-react";
import { useMemo, useState } from "react";

import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import { Badge } from "../../components/primitives/Badge.jsx";
import { Button } from "../../components/primitives/Button.jsx";
import {
  deriveRequestOrigin,
  formatJsonDocument,
  minifyJsonDocument,
  previewSecretInput,
  validateAllowlistDraft,
} from "./tool-center-utils.js";
import styles from "./ToolCenterPage.module.css";

const tabs = ["JSON Formatter", "API Caller", "API Caller 设置"];
const initialJson = "{\"service\":\"queFork\",\"enabled\":true}";
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

export function ToolCenterPage() {
  const [activeTab, setActiveTab] = useState("JSON Formatter");

  return (
    <WorkspacePageFrame className={styles.toolCanvas}>
      <WorkspaceStatusBar title="工具中心" />

      <main className={styles.workspaceBody}>
        <header className={styles.pageHeader}>
          <div>
            <Badge tone="info">M09 / Tool Center</Badge>
            <h2>工具目录</h2>
            <p>
              内置 JSON Formatter、API Caller 和 API Caller 设置。真实请求执行与 allowlist 保存必须由后端策略和审计链路开放。
            </p>
          </div>
          <div aria-label="安全边界" className={styles.boundaryStrip}>
            <span>
              <ShieldCheck aria-hidden="true" size={15} /> 服务端 allowlist
            </span>
            <span>
              <DatabaseZap aria-hidden="true" size={15} /> 不保存临时凭据
            </span>
            <span>
              <Bot aria-hidden="true" size={15} /> AI 只生成草稿
            </span>
          </div>
        </header>

        <nav aria-label="工具中心工具切换" className={styles.toolTabs} role="tablist">
          {tabs.map((tab) => (
            <button
              aria-selected={activeTab === tab}
              className={activeTab === tab ? styles.activeTab : ""}
              key={tab}
              onClick={() => setActiveTab(tab)}
              role="tab"
              type="button"
            >
              {tab}
            </button>
          ))}
        </nav>

        {activeTab === "JSON Formatter" ? <JsonFormatterPanel /> : null}
        {activeTab === "API Caller" ? <ApiCallerPanel /> : null}
        {activeTab === "API Caller 设置" ? <ApiCallerSettingsPanel /> : null}
      </main>
    </WorkspacePageFrame>
  );
}

function JsonFormatterPanel() {
  const [source, setSource] = useState(initialJson);
  const [output, setOutput] = useState("");
  const [error, setError] = useState("");

  /**
   * @param {"format" | "minify"} kind
   */
  function applyJsonTransform(kind) {
    const result = kind === "format" ? formatJsonDocument(source) : minifyJsonDocument(source);
    if (result.ok) {
      setOutput(result.value);
      setError("");
      return;
    }
    setError(result.error);
  }

  return (
    <section aria-label="JSON Formatter" className={styles.toolGrid}>
      <div className={styles.editorPanel}>
        <PanelTitle icon={Braces} title="JSON Formatter" subtitle="本地解析、格式化和压缩，不上传内容。" />
        <label className={styles.textareaField}>
          <span>JSON 输入</span>
          <textarea aria-label="JSON 输入" onChange={(event) => setSource(event.target.value)} value={source} />
        </label>
        <div className={styles.actionRow}>
          <Button onClick={() => applyJsonTransform("format")} type="button">
            <WandSparkles aria-hidden="true" size={16} /> 格式化 JSON
          </Button>
          <Button onClick={() => applyJsonTransform("minify")} type="button" variant="secondary">
            <Braces aria-hidden="true" size={16} /> 压缩 JSON
          </Button>
        </div>
      </div>
      <div className={styles.editorPanel}>
        <PanelTitle icon={ShieldCheck} title="输出" subtitle="解析错误只在本地显示。" />
        {error ? (
          <div className={styles.errorMessage} role="alert">
            {error}
          </div>
        ) : null}
        <label className={styles.textareaField}>
          <span>JSON 输出</span>
          <textarea aria-label="JSON 输出" readOnly value={output} />
        </label>
      </div>
    </section>
  );
}

function ApiCallerPanel() {
  const [url, setUrl] = useState("https://api.quefork.internal/orders/42");
  const [method, setMethod] = useState("GET");
  const [secret, setSecret] = useState("");
  const originResult = useMemo(() => deriveRequestOrigin(url), [url]);

  return (
    <section aria-label="API Caller" className={styles.callerLayout}>
      <div className={styles.editorPanel}>
        <PanelTitle icon={SendHorizontal} title="API Caller" subtitle="输入完整 URL；环境信息由域名规则在服务端识别。" />
        <div className={styles.requestLine}>
          <label>
            <span>HTTP 方法</span>
            <select aria-label="HTTP 方法" onChange={(event) => setMethod(event.target.value)} value={method}>
              {["GET", "POST", "PUT", "PATCH", "DELETE"].map((item) => (
                <option key={item}>{item}</option>
              ))}
            </select>
          </label>
          <label>
            <span>请求 URL</span>
            <input aria-label="请求 URL" onChange={(event) => setUrl(event.target.value)} value={url} />
          </label>
        </div>
        <label className={styles.textareaField}>
          <span>请求 Body</span>
          <textarea aria-label="请求 Body" />
        </label>
        <label className={styles.field}>
          <span>临时凭据</span>
          <input
            aria-label="临时凭据"
            autoComplete="new-password"
            onChange={(event) => setSecret(event.target.value)}
            type="password"
            value={secret}
          />
          <small>{previewSecretInput(secret)}</small>
        </label>
        <div className={styles.actionRow}>
          <Button disabled type="button">
            <SendHorizontal aria-hidden="true" size={16} /> 发送请求
          </Button>
          <Button disabled type="button" variant="secondary">
            <WandSparkles aria-hidden="true" size={16} /> AI 生成请求草稿
          </Button>
        </div>
      </div>

      <aside aria-label="API Caller 请求上下文" className={styles.sidePanel}>
        <PanelTitle icon={ShieldCheck} title="执行边界" subtitle="真实调用由后端 EasyPostman 适配和 Worker 出口控制。" />
        <dl className={styles.factList}>
          <div>
            <dt>推导域名</dt>
            <dd>{originResult.ok ? originResult.origin : originResult.error}</dd>
          </div>
          <div>
            <dt>服务端执行</dt>
            <dd>未接入</dd>
          </div>
          <div>
            <dt>RAG 测试数据</dt>
            <dd>预留，当前不检索</dd>
          </div>
        </dl>
      </aside>
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
    <section aria-label="API Caller 管理员设置" className={styles.settingsLayout}>
      <div className={styles.editorPanel}>
        <PanelTitle icon={Settings2} title="API Caller 设置" subtitle="管理员维护域名级 allowlist；首版页面只校验草稿，不保存配置。" />
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
      </div>
      <aside className={styles.sidePanel}>
        <PanelTitle icon={KeyRound} title="管理边界" subtitle="服务端接口完成前不写入配置事实源。" />
        <ul className={styles.boundaryList}>
          <li>只允许域名级 allowlist，不配置单个接口路径。</li>
          <li>不允许首版通配域名和默认本机地址。</li>
          <li>保存、启停和审计事件由后端策略动作实现。</li>
        </ul>
      </aside>
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
 *   subtitle: string,
 *   title: string
 * }} props
 */
function PanelTitle({ icon: Icon, subtitle, title }) {
  return (
    <div className={styles.panelTitle}>
      <span aria-hidden="true">
        <Icon size={18} strokeWidth={2.35} />
      </span>
      <div>
        <h3>{title}</h3>
        <p>{subtitle}</p>
      </div>
    </div>
  );
}
