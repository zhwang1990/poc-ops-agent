import {
  Bot,
  ClipboardCheck,
  GitBranch,
  MessagesSquare,
  SendHorizontal,
  ShieldCheck,
  UserRound,
} from "lucide-react";
import { Fragment, useEffect, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

import { NaturalLanguageDialog } from "../../components/conversation/NaturalLanguageDialog.jsx";
import toolbarStyles from "../../components/layout/PageToolbar.module.css";
import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import { Badge } from "../../components/primitives/Badge.jsx";
import { Button } from "../../components/primitives/Button.jsx";
import { Dialog } from "../../components/primitives/Dialog.jsx";
import { weatherCurrentOutputSchema } from "../../schemas/agent-schemas.js";
import { useAgentDiagnosticTask } from "./use-agent-diagnostic-task.js";
import styles from "./AgentWorkspacePage.module.css";

const secondaryConversationActions = ["分享", "接管状态"];
const agentRequestScope = {
  targetEnvironment: "development",
  policy: "READ_ONLY",
  inputParameters: {},
};
const agentIonSpecs = [
  ["agentIonTiny", "agentIonBlue", "agentIonLaneOne"],
  ["agentIonSmall", "agentIonRed", "agentIonLaneTwo"],
  ["agentIonMedium", "agentIonGreen", "agentIonLaneThree"],
  ["agentIonTiny", "agentIonGold", "agentIonLaneFour"],
  ["agentIonSmall", "agentIonBlue", "agentIonLaneFive"],
  ["agentIonTiny", "agentIonGreen", "agentIonLaneSix"],
  ["agentIonMedium", "agentIonRed", "agentIonLaneSeven"],
  ["agentIonSmall", "agentIonGold", "agentIonLaneEight"],
  ["agentIonTiny", "agentIonBlue", "agentIonLaneNine"],
  ["agentIonSmall", "agentIonGreen", "agentIonLaneTen"],
  ["agentIonTiny", "agentIonRed", "agentIonLaneEleven"],
  ["agentIonMedium", "agentIonBlue", "agentIonLaneTwelve"],
];
const agentMarkdownRemarkPlugins = [remarkGfm];
/** @type {import("react-markdown").Components} */
const agentMarkdownComponents = {
  table({ node, ...props }) {
    void node;
    return <table aria-label="Agent 摘要表格" {...props} />;
  },
  a({ node, children }) {
    void node;
    return <span className={styles.markdownLinkText}>{children}</span>;
  },
};

export function AgentWorkspacePage() {
  const [taskGoal, setTaskGoal] = useState("");
  const [activeDetailPanel, setActiveDetailPanel] = useState(
    /** @type {"task" | "skill" | "chain" | null} */ (null),
  );
  const agentTask = useAgentDiagnosticTask();
  const canSubmitAgentTask = taskGoal.trim().length > 0 && agentTask.status !== "running";
  /**
   * @param {string} [goal]
   */
  const submitAgentTask = (goal = taskGoal) => {
    const currentGoal = goal.trim();
    if (!currentGoal || agentTask.status === "running") {
      return;
    }
    void agentTask.run(currentGoal);
    setTaskGoal("");
  };

  return (
    <WorkspacePageFrame className={styles.agentCanvas}>
      <div aria-hidden="true" className={styles.agentIonField}>
        {agentIonSpecs.map(([size, tone, lane], index) => (
          <i
            aria-hidden="true"
            className={[styles.agentIon, styles[size], styles[tone], styles[lane]]
              .filter(Boolean)
              .join(" ")}
            data-agent-ion=""
            key={`${lane}-${index}`}
          />
        ))}
      </div>
      <WorkspaceStatusBar title="Agent 工作区" />

      <ConversationToolbar />

      <section className={styles.agentLayout}>
        <div className={styles.exchangeWindow}>
          <div className={styles.exchangeHead}>
            <div>
              <h2>工作会话</h2>
              <span>单会话跟踪 · 1 个 workflow · policy-v1</span>
              <small className={styles.operatorScope}>ROLE_agent-reader · policy-v1 · READ_ONLY</small>
            </div>
            <Badge className={styles.liveBadge} tone="success">
              会话已确权
            </Badge>
          </div>

          <div className={styles.exchangeBody}>
            {agentTask.exchanges.map((exchange) => (
              <Fragment key={exchange.id}>
                <Message author="操作员" tone="operator">
                  {exchange.userIntent}
                </Message>
                <Message author="Agent" tone="agent">
                  <AgentTaskChatReply exchange={exchange} />
                </Message>
              </Fragment>
            ))}
          </div>

          <div className={styles.exchangeComposer}>
            <NaturalLanguageDialog
              ariaLabel="任务目标输入区"
              className={styles.composerBox}
              disabled={agentTask.status === "running"}
              inputClassName={styles.composerInput}
              inputLabel="任务目标"
              onChange={setTaskGoal}
              onSubmit={() => submitAgentTask()}
              placeholder="输入任务目标，例如：检查 payment-api 最近错误、确认订单库慢查询趋势，并给出只读排查计划。"
              submitAriaLabel="发送任务"
              submitClassName={styles.sendButton}
              submitDisabled={!canSubmitAgentTask}
              submitIcon={<SendHorizontal aria-hidden="true" size={18} />}
              value={taskGoal}
              variant="agent-composer"
            />
          </div>
        </div>

        <aside className={styles.agentSide}>
          <TaskDetailPanel
            onShowDetail={() => setActiveDetailPanel("task")}
            task={agentTask}
          />
          <SkillEventPanel
            onShowDetail={() => setActiveDetailPanel("skill")}
            task={agentTask}
          />
          <SessionContextPanel
            onShowDetail={() => setActiveDetailPanel("chain")}
            task={agentTask}
          />
        </aside>
      </section>

      <AgentDetailDialog
        activePanel={activeDetailPanel}
        onClose={() => setActiveDetailPanel(null)}
        task={agentTask}
      />
    </WorkspacePageFrame>
  );
}

/**
 * @param {import("../../schemas/agent-schemas.js").AgentTaskResult} result
 * @returns {import("../../schemas/agent-schemas.js").WeatherCurrentOutput | null}
 */
function toWeatherOutput(result) {
  const weatherResult = result.toolResults.find((toolResult) =>
    toolResult.outputSchemaId.includes("weather-current-read"),
  );
  if (!weatherResult) {
    return null;
  }
  const parsed = weatherCurrentOutputSchema.safeParse(weatherResult.output);
  return parsed.success ? parsed.data : null;
}

/**
 * @param {import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskStatus} status
 */
function taskStateLabel(status) {
  const labels = {
    idle: "未开始",
    running: "执行中",
    succeeded: "已完成",
    failed: "失败",
    denied: "已拒绝",
    contractError: "契约错误",
  };
  return labels[status] ?? "未知";
}

/**
 * @param {import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState} task
 */
function latestUserIntent(task) {
  return task.userIntent ?? task.exchanges.at(-1)?.userIntent ?? "等待发送";
}

/**
 * @param {import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState} task
 */
function currentInputStatusLabel(task) {
  if (task.status === "idle") {
    return "等待发送";
  }
  return taskStateLabel(task.status);
}

/**
 * @param {import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState} task
 * @returns {"info" | "ok" | "danger"}
 */
function taskTone(task) {
  if (
    task.status === "denied" ||
    task.status === "failed" ||
    task.status === "contractError" ||
    task.result?.status === "FAILED_TERMINAL" ||
    task.result?.status === "REJECTED" ||
    task.result?.status === "AGENT_RUNTIME_FAILED"
  ) {
    return "danger";
  }
  if (task.status === "succeeded" || task.result?.status === "SUCCEEDED") {
    return "ok";
  }
  return "info";
}

/**
 * @param {string} outputSchemaId
 */
function parseOutputSchemaId(outputSchemaId) {
  const [skillId = "unknown-skill", skillVersion = "unknown", schemaRole = "output"] =
    outputSchemaId.split(":");
  return { skillId, skillVersion, schemaRole };
}

/**
 * @typedef {{ label: string, value: string, tone?: "default" | "info" | "ok" | "danger" }} PanelFact
 */

/**
 * @param {import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState} task
 * @returns {PanelFact[]}
 */
function buildTaskPanelFacts(task) {
  const result = task.result;
  return [
    { label: "策略", value: agentRequestScope.policy, tone: "ok" },
    { label: "环境", value: agentRequestScope.targetEnvironment, tone: "info" },
    { label: "workflow", value: task.workflowId ?? "pending", tone: task.workflowId ? "info" : "default" },
    {
      label: "Skill 调用",
      value: result ? `${result.toolResults.length}/${result.toolCallCount}` : "0/0",
      tone: "info",
    },
  ];
}

/**
 * @param {import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState} task
 * @returns {PanelFact[]}
 */
function buildSkillPanelFacts(task) {
  const toolResults = task.result?.toolResults ?? [];
  const firstToolResult = toolResults[0];
  const firstToolDescriptor = firstToolResult
    ? parseOutputSchemaId(firstToolResult.outputSchemaId)
    : null;
  return [
    { label: "路由", value: firstToolDescriptor?.skillId ?? "服务端待路由", tone: firstToolDescriptor ? "ok" : "info" },
    { label: "授权", value: "READ_ONLY", tone: "ok" },
    { label: "workflow", value: task.workflowId ?? "pending", tone: task.workflowId ? "info" : "default" },
    {
      label: "Worker",
      value: toolResults.length > 0 ? `${toolResults.length} 次只读调用` : "等待调用",
      tone: toolResults.length > 0 ? "ok" : "info",
    },
  ];
}

/**
 * @param {import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState} task
 * @returns {PanelFact[]}
 */
function buildChainPanelFacts(task) {
  const toolResults = task.result?.toolResults ?? [];
  return [
    { label: "Runtime", value: task.status === "idle" ? "等待接收" : "已接收", tone: "info" },
    { label: "workflow", value: task.workflowId ?? "pending", tone: task.workflowId ? "info" : "default" },
    {
      label: "Tool 调用",
      value: task.result ? `${toolResults.length}/${task.result.toolCallCount}` : "0/0",
      tone: toolResults.length > 0 ? "ok" : "info",
    },
    { label: "审计", value: "policy-v1", tone: "ok" },
  ];
}

/**
 * @param {import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState} task
 * @returns {Array<{ label: string, value: string, tone: "info" | "ok" | "danger" }>}
 */
function buildChainSteps(task) {
  const toolResults = task.result?.toolResults ?? [];
  /** @type {Array<{ label: string, value: string, tone: "info" | "ok" | "danger" }>} */
  const toolResultSteps = toolResults.map((toolResult) => {
    const descriptor = parseOutputSchemaId(toolResult.outputSchemaId);
    const tone = /** @type {"ok" | "danger"} */ (
      toolResult.status === "SUCCEEDED" ? "ok" : "danger"
    );
    return {
      label: `${descriptor.skillId}@${descriptor.skillVersion}`,
      value: toolResult.status,
      tone,
    };
  });
  const resultTone = taskTone(task);
  return [
    { label: "操作员意图", value: latestUserIntent(task), tone: "info" },
    { label: "Agent Runtime", value: task.status === "idle" ? "等待发送" : "已接收", tone: "info" },
    { label: "READ_ONLY 策略", value: "policy-v1", tone: "ok" },
    { label: "M05 workflow", value: task.workflowId ?? "pending", tone: "info" },
    {
      label: "M07 Worker",
      value: toolResults.length > 0 ? `${toolResults.length} 次只读调用` : "等待 Skill 调用",
      tone: "info",
    },
    ...toolResultSteps,
    { label: "结果", value: task.result?.status ?? taskStateLabel(task.status), tone: resultTone },
  ];
}

/**
 * @param {import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState} task
 * @returns {Array<{ label: string, value: string, tone: "info" | "ok" | "danger" }>}
 */
function buildAgentFlowSteps(task) {
  const toolResults = task.result?.toolResults ?? [];
  return [
    { label: "意图接入", value: latestUserIntent(task), tone: "info" },
    { label: "Agent Runtime", value: task.status === "idle" ? "等待发送" : "已接收", tone: "info" },
    { label: "策略校验", value: agentRequestScope.policy, tone: "ok" },
    { label: "工作流登记", value: task.workflowId ?? "pending", tone: "info" },
    {
      label: "Worker 调度",
      value: toolResults.length > 0 ? `${toolResults.length} 次只读调用` : "等待 Skill 调用",
      tone: "info",
    },
    { label: "结果归档", value: task.result?.status ?? taskStateLabel(task.status), tone: taskTone(task) },
  ];
}

/**
 * @param {import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState} task
 * @returns {Array<{ label: string, value: string, tone: "info" | "ok" | "danger" }>}
 */
function buildSkillFlowSteps(task) {
  const toolResults = task.result?.toolResults ?? [];
  const firstToolResult = toolResults[0];
  const firstToolDescriptor = firstToolResult
    ? parseOutputSchemaId(firstToolResult.outputSchemaId)
    : null;
  return [
    {
      label: "路由匹配",
      value: firstToolDescriptor?.skillId ?? "等待 Skill 路由",
      tone: firstToolDescriptor ? "ok" : "info",
    },
    { label: "授权校验", value: "READ_ONLY / policy-v1", tone: "ok" },
    { label: "M05 Tool Step", value: task.workflowId ?? "pending", tone: "info" },
    {
      label: "Worker 只读执行",
      value: toolResults.length > 0 ? `${toolResults.length} 次调用` : "等待调用",
      tone: toolResults.length > 0 ? "ok" : "info",
    },
    {
      label: "输出契约校验",
      value: firstToolResult?.outputSchemaId ?? "等待输出",
      tone: firstToolResult?.status === "SUCCEEDED" ? "ok" : "info",
    },
  ];
}

/**
 * @param {import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState} task
 * @returns {Array<{ label: string, value: string, tone: "info" | "ok" | "danger" }>}
 */
function buildSkillRouteSteps(task) {
  const toolResults = task.result?.toolResults ?? [];
  const firstToolResult = toolResults[0];
  const firstToolDescriptor = firstToolResult
    ? parseOutputSchemaId(firstToolResult.outputSchemaId)
    : null;

  return [
    { label: "路由请求", value: task.status === "idle" ? "等待发送" : "已接收", tone: "info" },
    {
      label: "目录筛选",
      value: firstToolDescriptor?.skillId ?? "等待服务端候选",
      tone: firstToolDescriptor ? "ok" : "info",
    },
    { label: "策略校验", value: agentRequestScope.policy, tone: "ok" },
    {
      label: "参数契约",
      value: firstToolDescriptor ? `${firstToolDescriptor.skillId} input.schema.json` : "等待 input.schema.json",
      tone: firstToolDescriptor ? "ok" : "info",
    },
    {
      label: "只读执行",
      value: toolResults.length > 0 ? `${toolResults.length} 次 Tool Call` : "等待 Worker",
      tone: toolResults.length > 0 ? "ok" : "info",
    },
    {
      label: "输出契约",
      value: firstToolDescriptor ? `${firstToolDescriptor.skillId} output.schema.json` : "等待 output.schema.json",
      tone: firstToolResult?.status === "SUCCEEDED" ? "ok" : "info",
    },
  ];
}

/**
 * @param {unknown} value
 */
function formatJson(value) {
  return JSON.stringify(value, null, 2);
}

function ConversationToolbar() {
  return (
    <section
      aria-label="会话工具栏"
      className={`${styles.conversationToolbar} ${toolbarStyles.surface}`}
    >
      <span className={styles.conversationToolbarButton}>
        <span
          aria-hidden="true"
          className={styles.sessionStackIcon}
          data-session-icon="active-conversation"
        >
          <MessagesSquare size={16} strokeWidth={2.15} />
          <i className={styles.sessionIconNode} />
        </span>
        <span className={styles.sessionButtonLabel}>会话 1</span>
      </span>
      <div className={styles.conversationToolbarMain}>
        <strong>当前会话</strong>
        <small>1 个 workflow · 1 分钟前更新</small>
      </div>
      <div className={`${styles.conversationToolbarActions} ${toolbarStyles.actionGroup}`}>
        {secondaryConversationActions.map((action) => (
          <button
            className={`${toolbarStyles.button} ${toolbarStyles.neutral}`}
            key={action}
            type="button"
          >
            {action}
          </button>
        ))}
        <button className={`${toolbarStyles.button} ${toolbarStyles.primary}`} type="button">
          + 新建会话
        </button>
      </div>
    </section>
  );
}

/**
 * @param {{
 *   author: string,
 *   children: import("react").ReactNode,
 *   tone: "agent" | "operator",
 * }} props
 */
function Message({ author, children, tone }) {
  const RoleIcon = tone === "operator" ? UserRound : Bot;

  return (
    <article className={`${styles.message} ${styles[tone]}`} data-message-tone={tone}>
      <strong>
        <span aria-hidden="true" className={styles.messageRoleIcon}>
          <RoleIcon size={15} strokeWidth={2.6} />
        </span>
        <span className={styles.messageTitleText}>{author}</span>
      </strong>
      <span aria-hidden="true" className={styles.messageDivider} />
      <div className={styles.messageContent}>{children}</div>
    </article>
  );
}

/**
 * @param {{ exchange: import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskExchange }} props
 */
function AgentTaskChatReply({ exchange }) {
  if (exchange.status === "running") {
    return <RunningAgentTaskReply exchange={exchange} />;
  }

  if (exchange.result) {
    const weatherOutput = toWeatherOutput(exchange.result);
    return (
      <>
        <AgentMarkdownSummary summary={exchange.result.summary} />
        {weatherOutput ? <WeatherCurrentResult output={weatherOutput} /> : null}
      </>
    );
  }

  return (
    <p>
      {exchange.errorCode ? `${exchange.errorCode}: ` : ""}
      {exchange.errorMessage ?? "Agent 诊断请求失败"}
    </p>
  );
}

/**
 * @param {{ exchange: import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskExchange }} props
 */
function RunningAgentTaskReply({ exchange }) {
  const elapsedSeconds = useElapsedSeconds(exchange.startedAt);

  return (
    <>
      <p>正在执行只读诊断。</p>
      <div
        aria-label={`Agent 正在执行，已耗时 ${formatElapsedDuration(elapsedSeconds)}`}
        className={styles.executionProgress}
        data-agent-execution-indicator="running"
        role="status"
      >
        <span aria-hidden="true" className={styles.executionInfoMark}>
          i
        </span>
        <span className={styles.executionProgressCopy}>
          <strong>执行中</strong>
          <span>耗时 {formatElapsedDuration(elapsedSeconds)}</span>
        </span>
        <span aria-hidden="true" className={styles.executionProgressTrack}>
          <i />
        </span>
      </div>
    </>
  );
}

/**
 * @param {number | undefined} startedAt
 */
function useElapsedSeconds(startedAt) {
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    if (typeof startedAt !== "number") {
      return undefined;
    }
    const intervalId = window.setInterval(() => {
      setNow(Date.now());
    }, 1000);
    return () => window.clearInterval(intervalId);
  }, [startedAt]);

  if (typeof startedAt !== "number") {
    return 0;
  }

  return Math.max(0, Math.floor((now - startedAt) / 1000));
}

/**
 * @param {number} elapsedSeconds
 */
function formatElapsedDuration(elapsedSeconds) {
  const totalSeconds = Math.max(0, Math.floor(elapsedSeconds));
  const seconds = totalSeconds % 60;
  const minutes = Math.floor(totalSeconds / 60) % 60;
  const hours = Math.floor(totalSeconds / 3600);
  const paddedSeconds = String(seconds).padStart(2, "0");
  const paddedMinutes = String(minutes).padStart(2, "0");

  if (hours > 0) {
    return `${hours}:${paddedMinutes}:${paddedSeconds}`;
  }

  return `${paddedMinutes}:${paddedSeconds}`;
}

/**
 * @param {{ summary: string }} props
 */
function AgentMarkdownSummary({ summary }) {
  return (
    <div className={styles.agentMarkdownSummary}>
      <ReactMarkdown
        components={agentMarkdownComponents}
        remarkPlugins={agentMarkdownRemarkPlugins}
        skipHtml
      >
        {summary}
      </ReactMarkdown>
    </div>
  );
}

/**
 * @param {{ output: import("../../schemas/agent-schemas.js").WeatherCurrentOutput }} props
 */
function WeatherCurrentResult({ output }) {
  const observedAt = output.observationTime ?? output.observedAt;
  return (
    <div className={styles.nodeHealthResult}>
      <strong>{output.location}</strong>
      <span>{output.condition}</span>
      <span>{formatTemperature(output.temperatureCelsius)}°C</span>
      {typeof output.humidityPercent === "number" ? (
        <span>湿度 {output.humidityPercent}%</span>
      ) : null}
      {typeof output.windSpeedKph === "number" ? (
        <span>风速 {output.windSpeedKph} kph</span>
      ) : null}
      {observedAt ? <small>{observedAt}</small> : null}
    </div>
  );
}

/**
 * @param {number} value
 */
function formatTemperature(value) {
  return Number.isInteger(value) ? String(value) : value.toFixed(1);
}

/**
 * @param {{
 *   onShowDetail: () => void,
 *   task: import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState,
 * }} props
 */
function TaskDetailPanel({ onShowDetail, task }) {
  const result = task.result;
  const hasWorkflowError = taskTone(task) === "danger";
  const statusLabel = currentInputStatusLabel(task);
  const summaryText =
    task.status === "contractError"
      ? "请求未通过契约校验。"
      : hasWorkflowError
        ? "Agent 诊断请求失败。"
        : result
          ? "只读诊断结果已归档。"
          : task.status === "running"
            ? "只读诊断正在执行。"
            : "等待发送只读诊断。";

  return (
    <section className={styles.agentPanel}>
      <h3>
        <span aria-hidden="true" className={`${styles.panelIcon} ${styles.panelIconTask}`}>
          <ClipboardCheck size={15} strokeWidth={2.6} />
        </span>
        对话执行状态
      </h3>
      <PanelSummary tone={taskTone(task)} value={statusLabel}>
        {summaryText}
      </PanelSummary>
      <PanelFactList facts={buildTaskPanelFacts(task)} />
      <Button
        aria-label="查看对话执行详情"
        className={styles.detailButton}
        onClick={onShowDetail}
        variant="secondary"
      >
        查看详情
      </Button>
    </section>
  );
}

/**
 * @param {{
 *   task: import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState,
 *   onShowDetail: () => void,
 * }} props
 */
function SkillEventPanel({ onShowDetail, task }) {
  const toolResults = task.result?.toolResults ?? [];
  const latestEventType = task.result
    ? "AGENT_TASK_RESULT"
    : task.status === "running"
      ? "AGENT_TASK_RUNNING"
      : task.errorCode ?? "等待发送";
  const sequenceValue = task.result ? `tools ${task.result.toolCallCount}` : "main";
  const firstToolResult = toolResults[0];
  const firstToolDescriptor = firstToolResult
    ? parseOutputSchemaId(firstToolResult.outputSchemaId)
    : null;
  const routeSummary =
    toolResults.length > 0
      ? `${sequenceValue} · 已执行 ${toolResults.length} 个只读 Skill，首个 ${firstToolDescriptor?.skillId ?? "unknown-skill"}。`
      : "提交后由服务端路由";

  return (
    <section className={styles.agentPanel}>
      <h3>
        <span aria-hidden="true" className={`${styles.panelIcon} ${styles.panelIconSkill}`}>
          <GitBranch size={15} strokeWidth={2.6} />
        </span>
        {toolResults.length > 0 ? "已执行 Skill" : "Skill 路由状态"}
      </h3>
      <PanelSummary tone={toolResults.length > 0 ? "ok" : taskTone(task)} value={latestEventType}>
        {routeSummary}
      </PanelSummary>
      <PanelFactList facts={buildSkillPanelFacts(task)} />
      <Button
        aria-label="查看 Skill 调用详情"
        className={styles.detailButton}
        onClick={onShowDetail}
        variant="secondary"
      >
        查看详情
      </Button>
    </section>
  );
}

/**
 * @param {{ toolResult: import("../../schemas/agent-schemas.js").AgentToolResult }} props
 */
function ToolResultCard({ toolResult }) {
  const descriptor = parseOutputSchemaId(toolResult.outputSchemaId);
  const hasError = Boolean(toolResult.errorCode || toolResult.errorMessage);

  return (
    <article className={styles.skillResultCard} aria-label={`Skill 调用 ${descriptor.skillId}`}>
      <div className={styles.skillResultHeader}>
        <strong>{descriptor.skillId}</strong>
        <span>{descriptor.skillVersion}</span>
      </div>
      <div className={styles.skillMetaGrid}>
        <MiniRow label="toolCall" value={toolResult.toolCallId} />
        <MiniRow label="schema" value={toolResult.outputSchemaId} />
        <MiniRow label="状态" tone={hasError ? "danger" : "ok"} value={toolResult.status} />
        <MiniRow label="完成时间" tone="info" value={toolResult.completedAt} />
      </div>
      <div className={styles.ioBlock}>
        <span>Agent 请求入参</span>
        <pre>{formatJson(agentRequestScope.inputParameters)}</pre>
        <small>Skill 原始入参未包含在 AgentTaskResult 中；当前仅展示发起请求的 inputParameters。</small>
      </div>
      <div className={styles.ioBlock}>
        <span>Skill 出参</span>
        <pre>{formatJson(toolResult.output)}</pre>
      </div>
      {hasError ? (
        <div className={`${styles.statusNote} ${styles.errorNote}`}>
          <ShieldCheck aria-hidden="true" size={16} />
          <span>
            {toolResult.errorCode ? `${toolResult.errorCode}: ` : ""}
            {toolResult.errorMessage ?? "Skill 调用失败"}
          </span>
        </div>
      ) : null}
    </article>
  );
}

/**
 * @param {{
 *   onShowDetail: () => void,
 *   task: import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState,
 * }} props
 */
function SessionContextPanel({ onShowDetail, task }) {
  const toolResults = task.result?.toolResults ?? [];
  const chainSummary =
    toolResults.length > 0
      ? `${toolResults.length} 次只读调用已归档。`
      : task.workflowId
        ? "只读执行链已登记。"
        : "等待任务进入执行链。";

  return (
    <section className={`${styles.agentPanel} ${styles.scanLine}`}>
      <h3>
        <span aria-hidden="true" className={`${styles.panelIcon} ${styles.panelIconSession}`}>
          <ShieldCheck size={15} strokeWidth={2.6} />
        </span>
        执行链
      </h3>
      <PanelSummary tone={taskTone(task)} value={currentInputStatusLabel(task)}>
        {chainSummary}
      </PanelSummary>
      <PanelFactList facts={buildChainPanelFacts(task)} />
      <Button
        aria-label="查看执行链详情"
        className={styles.detailButton}
        onClick={onShowDetail}
        variant="secondary"
      >
        查看详情
      </Button>
    </section>
  );
}

/**
 * @param {{
 *   activePanel: "task" | "skill" | "chain" | null,
 *   onClose: () => void,
 *   task: import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState,
 * }} props
 */
function AgentDetailDialog({ activePanel, onClose, task }) {
  if (!activePanel) {
    return null;
  }

  const titleByPanel = {
    task: "对话执行详情",
    skill: "Skill 调用详情",
    chain: "执行链详情",
  };
  const iconByPanel = {
    task: <ClipboardCheck size={18} strokeWidth={2.5} />,
    skill: <GitBranch size={18} strokeWidth={2.5} />,
    chain: <ShieldCheck size={18} strokeWidth={2.5} />,
  };
  const descriptionByPanel = {
    task: "查看只读诊断请求的状态、策略、workflow、task 和输入意图。",
    skill: "查看服务端 Skill 路由、入参出参契约和只读调用结果。",
    chain: "查看 Agent 主链路、Skill 调用流程和可审计执行明细。",
  };

  return (
    <Dialog
      closeLabel="关闭详情"
      description={descriptionByPanel[activePanel]}
      icon={iconByPanel[activePanel]}
      onClose={onClose}
      open
      size="wide"
      title={titleByPanel[activePanel]}
    >
      {activePanel === "task" ? <TaskDetailDialogContent task={task} /> : null}
      {activePanel === "skill" ? <SkillDetailDialogContent task={task} /> : null}
      {activePanel === "chain" ? <ExecutionChainDialogContent task={task} /> : null}
    </Dialog>
  );
}

/**
 * @param {{ task: import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState }} props
 */
function TaskDetailDialogContent({ task }) {
  const result = task.result;
  const hasWorkflowError = taskTone(task) === "danger";

  return (
    <>
      <section className={styles.detailDialogSection}>
        <DetailRow label="状态" tone={taskTone(task)} value={result?.status ?? taskStateLabel(task.status)} />
        <DetailRow label="策略" tone="ok" value={agentRequestScope.policy} />
        <DetailRow label="环境" tone="info" value={agentRequestScope.targetEnvironment} />
        <DetailRow label="workflow" value={task.workflowId ?? "pending"} />
        <DetailRow label="task" value={result?.taskId ?? "pending"} />
        <DetailRow
          label="Skill 调用"
          tone="info"
          value={result ? `${result.toolResults.length}/${result.toolCallCount}` : "0/0"}
        />
        {result ? <DetailRow label="完成时间" tone="info" value={result.completedAt} /> : null}
      </section>
      <section className={styles.detailDialogSection}>
        <h4>输入意图</h4>
        <p>{latestUserIntent(task)}</p>
      </section>
      {hasWorkflowError ? (
        <div className={`${styles.statusNote} ${styles.errorNote}`}>
          <ShieldCheck aria-hidden="true" size={16} />
          <span>
            {task.errorCode ? `${task.errorCode}: ` : ""}
            {task.errorMessage ?? result?.summary ?? "Agent 诊断请求失败"}
          </span>
        </div>
      ) : null}
    </>
  );
}

/**
 * @param {{
 *   task: import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState,
 * }} props
 */
function SkillDetailDialogContent({ task }) {
  const toolResults = task.result?.toolResults ?? [];

  if (toolResults.length > 0) {
    return (
      <>
        <SkillRouteAnimation task={task} />
        <SkillContractRequirements task={task} />
        <div className={styles.skillResultList}>
          {toolResults.map((toolResult) => (
            <ToolResultCard key={toolResult.toolCallId} toolResult={toolResult} />
          ))}
        </div>
      </>
    );
  }

  return (
    <>
      <SkillRouteAnimation task={task} />
      <SkillContractRequirements task={task} />
      <section className={styles.detailDialogSection}>
        <DetailRow
          label="候选状态"
          tone="info"
          value="提交后由服务端路由"
        />
        <DetailRow label="候选 Skill" tone="info" value="等待任务提交" />
        <DetailRow
          label="候选风险"
          tone="info"
          value="未判定"
        />
      </section>
    </>
  );
}

/**
 * @param {{ task: import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState }} props
 */
function ExecutionChainDialogContent({ task }) {
  const chainSteps = buildChainSteps(task);

  return (
    <>
      <ExecutionFlowAnimation task={task} />
      <div aria-label="执行链明细" className={styles.executionChain}>
        {chainSteps.map((step, index) => (
          <div className={styles.chainStep} key={`${step.label}-${index}`}>
            <span>{step.label}</span>
            <strong data-tone={step.tone}>{step.value}</strong>
          </div>
        ))}
      </div>
    </>
  );
}

/**
 * @param {{ task: import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState }} props
 */
function ExecutionFlowAnimation({ task }) {
  const agentSteps = buildAgentFlowSteps(task);
  const skillSteps = buildSkillFlowSteps(task);

  return (
    <section
      aria-label="执行流程动画"
      className={styles.flowAnimation}
      data-flow-animation="agent-chain"
    >
      <div className={styles.flowAnimationHeader}>
        <span>流程演示</span>
        <strong>{task.result?.status ?? taskStateLabel(task.status)}</strong>
      </div>
      <FlowTrack steps={agentSteps} title="Agent 主链路" />
      <FlowTrack steps={skillSteps} title="Skill 调用流程" />
    </section>
  );
}

/**
 * @param {{ task: import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState }} props
 */
function SkillRouteAnimation({ task }) {
  const skillRouteSteps = buildSkillRouteSteps(task);
  const routeStatus = task.result?.toolCallCount
    ? `tools ${task.result.toolCallCount}`
    : taskStateLabel(task.status);
  const routeSummary = `${routeStatus} · ${skillRouteSteps.length} 步`;

  return (
    <section
      aria-label="Skill 路由动画"
      className={styles.flowAnimation}
      data-flow-animation="skill-route"
    >
      <FlowTrack steps={skillRouteSteps} summary={routeSummary} title="Skill 路由流程" />
    </section>
  );
}

/**
 * @param {{ task: import("./use-agent-diagnostic-task.js").AgentDiagnosticTaskState }} props
 */
function SkillContractRequirements({ task }) {
  const firstToolResult = task.result?.toolResults.at(0);
  const firstToolDescriptor = firstToolResult
    ? parseOutputSchemaId(firstToolResult.outputSchemaId)
    : null;
  const skillLabel = firstToolDescriptor
    ? `${firstToolDescriptor.skillId}@${firstToolDescriptor.skillVersion}`
    : "等待 Skill 路由";

  return (
    <section aria-label="Skill 入参出参要求" className={styles.skillContractGrid}>
      <div className={styles.skillContractBlock}>
        <span>入参要求</span>
        <strong>Agent inputParameters</strong>
        <small>{skillLabel} · M03 input.schema.json · READ_ONLY</small>
        <pre>{formatJson(agentRequestScope.inputParameters)}</pre>
      </div>
      <div className={styles.skillContractBlock}>
        <span>出参要求</span>
        <strong>{firstToolDescriptor ? `${firstToolDescriptor.skillId} output.schema.json` : "等待输出契约"}</strong>
        <small>Worker 输出必须匹配 outputSchemaId，前端只渲染契约校验后的结果。</small>
        <pre>
          {formatJson({
            outputSchemaId: firstToolResult?.outputSchemaId ?? "pending",
            required: "output.schema.json",
          })}
        </pre>
      </div>
    </section>
  );
}

/**
 * @param {{
 *   steps: Array<{ label: string, value: string, tone: "info" | "ok" | "danger" }>,
 *   summary?: string,
 *   title: string,
 * }} props
 */
function FlowTrack({ steps, summary, title }) {
  return (
    <section className={styles.flowTrack}>
      <header className={styles.flowTrackHeader}>
        <span>{title}</span>
        <strong>{summary ?? `${steps.length} 步`}</strong>
      </header>
      <div className={styles.flowStepList}>
        {steps.map((step, index) => (
          <article
            className={styles.flowStep}
            data-flow-step=""
            data-tone={step.tone}
            key={`${title}-${step.label}-${index}`}
            style={{ animationDelay: `${index * 110}ms` }}
          >
            <i aria-hidden="true" className={styles.flowStepMarker} />
            <span>{step.label}</span>
            <strong>{step.value}</strong>
          </article>
        ))}
      </div>
    </section>
  );
}

/**
 * @param {{label: string, tone?: "default" | "info" | "ok" | "danger", value: string}} props
 */
function DetailRow({ label, tone = "default", value }) {
  return (
    <div className={styles.detailRow}>
      <span>{label}</span>
      <strong data-tone={tone}>{value}</strong>
    </div>
  );
}

/**
 * @param {{children: string, tone?: "default" | "info" | "ok" | "danger", value: string}} props
 */
function PanelSummary({ children, tone = "default", value }) {
  return (
    <div className={styles.panelSummary}>
      <span className={styles.panelSummaryLabel}>状态</span>
      <strong data-tone={tone}>{value}</strong>
      <p>{children}</p>
    </div>
  );
}

/**
 * @param {{facts: PanelFact[]}} props
 */
function PanelFactList({ facts }) {
  return (
    <dl className={styles.panelFactList}>
      {facts.map((fact) => (
        <div className={styles.panelFact} key={`${fact.label}-${fact.value}`}>
          <dt>{fact.label}</dt>
          <dd data-tone={fact.tone ?? "default"}>{fact.value}</dd>
        </div>
      ))}
    </dl>
  );
}

/**
 * @param {{label: string, tone?: "default" | "info" | "ok" | "danger", value: string}} props
 */
function MiniRow({ label, tone = "default", value }) {
  return (
    <div className={styles.miniRow}>
      <span>{label}</span>
      <strong data-tone={tone}>{value}</strong>
    </div>
  );
}
