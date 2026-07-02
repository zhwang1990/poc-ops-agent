import { Activity, RotateCcw, Workflow } from "lucide-react";
import { useQuery } from "@tanstack/react-query";

import { loadWorkflowEvents } from "../../api/agent-api.js";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import styles from "./WorkflowEventsPage.module.css";

const filters = [
  ["类型", "全部"],
  ["状态", "连续"],
  ["环境", "dev"],
];

const workflowEvents = [
  {
    badge: "persisted",
    description: "commandId 与 operatorId 已写入执行事实源",
    sequence: "seq 001",
    time: "10:42:11",
    title: "WORKFLOW_STARTED",
    tone: "blue",
  },
  {
    badge: "authorized",
    description: "策略版本 policy-v1 返回 READ_ONLY 执行边界",
    sequence: "seq 002",
    time: "10:42:13",
    title: "POLICY_EVALUATED",
    tone: "red",
  },
  {
    badge: "routed",
    description: "node-health-read@1.1.0 已匹配并完成 schema 校验",
    sequence: "seq 003",
    time: "10:42:15",
    title: "SKILL_ROUTED",
    tone: "green",
  },
  {
    badge: "accepted",
    description: "受限 Worker 接收请求，幂等键已锁定",
    sequence: "seq 004",
    time: "10:42:18",
    title: "WORKER_ACCEPTED",
    tone: "yellow",
  },
  {
    badge: "completed",
    description: "只读诊断摘要写入事件流，等待审计封存",
    sequence: "seq 005",
    time: "10:42:26",
    title: "WORKFLOW_COMPLETED",
    tone: "dark",
  },
];

const statusCards = [
  ["SSE", "connected / lastEventId=005", "green"],
  ["Workflow", "wf-ops-20260611-042", "blue"],
  ["Policy", "policy-v1 / READ_ONLY", "red"],
  ["Checkpoint", "sequence 连续，0 gap", "yellow"],
  ["Recovery", "支持从 seq 005 继续回放", "dark"],
];

export function WorkflowEventsPage() {
  const lastWorkflowId = readLastWorkflowId();
  const eventsQuery = useQuery({
    enabled: Boolean(lastWorkflowId),
    queryKey: ["workflow-events", lastWorkflowId],
    queryFn: () => loadWorkflowEvents(lastWorkflowId ?? ""),
  });
  const liveEvents = eventsQuery.data?.map(toWorkflowEvent) ?? [];
  const displayedEvents = liveEvents.length > 0 ? liveEvents : workflowEvents;
  const displayedStatusCards = liveEvents.length > 0
    ? [
        ["SSE", eventsQuery.isFetching ? "loading" : "replayed", "green"],
        ["Workflow", lastWorkflowId ?? "pending", "blue"],
        ["Policy", "policy-v1 / READ_ONLY", "red"],
        ["Checkpoint", `${liveEvents.length} events / 0 gap`, "yellow"],
        ["Recovery", "支持从 seq 继续回放", "dark"],
      ]
    : statusCards;
  const workflowCounter = liveEvents.length > 0
    ? `${liveEvents.length} 条事件 / 0 gap`
    : "13 条事件 / 0 gap";

  return (
    <WorkspacePageFrame>
      <WorkspaceStatusBar title="工作流事件" />
      <section aria-label="工作流事件工作区" className={styles.workflowCanvas}>
        <div className={styles.summaryGrid}>
          <section aria-label="语义事件流" className={styles.heroPanel}>
            <span aria-hidden="true" className={styles.panelMark}>
              <Workflow size={24} strokeWidth={2.5} />
            </span>
            <div>
              <h2>语义事件流</h2>
              <p>从启动、路由、Worker 接收到结果写入，按 sequence 连续展示。</p>
            </div>
          </section>
          <section aria-label="恢复检查" className={styles.statePanel}>
            <div>
              <h2>恢复检查</h2>
              <p>事件已持久化，SSE 可重连，缺口可用 sequence 回放补齐。</p>
            </div>
            <div aria-hidden="true" className={styles.pulseGrid}>
              {Array.from({ length: 18 }, (_, index) => (
                <span key={index} />
              ))}
            </div>
          </section>
        </div>

        <section aria-label="工作流事件筛选" className={styles.filterBar} role="search">
          <span className={styles.filterSearch}>搜索 workflowId / sequence / eventType</span>
          {filters.map(([label, value]) => (
            <span className={styles.filterSelect} key={label}>
              <b>{label}</b>
              <span>{value}</span>
            </span>
          ))}
          <span className={styles.workflowCounter}>{workflowCounter}</span>
        </section>

        <div className={styles.workflowLayout}>
          <section aria-label="事件流主轴" className={styles.workflowStream}>
            <PanelTitle icon={Activity} title="事件流主轴" />
            <div className={styles.eventStream}>
              {displayedEvents.map((event) => (
                <WorkflowEvent event={event} key={event.sequence} />
              ))}
            </div>
          </section>

          <aside aria-label="状态快照" className={styles.workflowSide}>
            <PanelTitle icon={RotateCcw} title="状态快照" />
            <div className={styles.sideStack}>
              {displayedStatusCards.map(([title, value, tone]) => (
                <StatusCard key={title} title={title} tone={tone} value={value} />
              ))}
            </div>
          </aside>
        </div>
      </section>
    </WorkspacePageFrame>
  );
}

function readLastWorkflowId() {
  try {
    return window.localStorage?.getItem("ops-agent:last-workflow-id") ?? null;
  } catch {
    return null;
  }
}

/**
 * @param {import("../../schemas/agent-schemas.js").SemanticEvent} event
 */
function toWorkflowEvent(event) {
  return {
    badge: eventBadge(event),
    description: eventDescription(event),
    sequence: `seq ${String(event.sequence).padStart(3, "0")}`,
    time: formatEventTime(event.timestamp),
    title: event.type,
    tone: eventTone(event.type),
  };
}

/**
 * @param {import("../../schemas/agent-schemas.js").SemanticEvent} event
 */
function eventDescription(event) {
  const payload = event.payload;
  if ("skillId" in payload && "skillVersion" in payload) {
    return `${payload.skillId}@${payload.skillVersion}`;
  }
  if ("outputSchemaId" in payload) {
    return payload.outputSchemaId;
  }
  if ("progressKind" in payload) {
    return `${payload.progressKind}: ${payload.message}`;
  }
  return event.workflowId;
}

/**
 * @param {import("../../schemas/agent-schemas.js").SemanticEvent} event
 */
function eventBadge(event) {
  const payload = event.payload;
  if ("status" in payload) {
    return payload.status.toLowerCase();
  }
  if ("targetEnvironment" in payload) {
    return payload.targetEnvironment;
  }
  if ("sensitiveContentSuppressed" in payload) {
    return payload.sensitiveContentSuppressed ? "suppressed" : "sanitized";
  }
  return "persisted";
}

/**
 * @param {string} type
 */
function eventTone(type) {
  if (type.endsWith("REJECTED") || type.endsWith("FAILED")) return "red";
  if (type.endsWith("COMPLETED")) return "green";
  if (type.endsWith("REQUESTED")) return "blue";
  return "dark";
}

/**
 * @param {string} timestamp
 */
function formatEventTime(timestamp) {
  const time = timestamp.match(/T(\d{2}:\d{2}:\d{2})/u);
  return time?.[1] ?? timestamp;
}

/**
 * @param {{
 *   event: {
 *     badge: string,
 *     description: string,
 *     sequence: string,
 *     time: string,
 *     title: string,
 *     tone: string,
 *   },
 * }} props
 */
function WorkflowEvent({ event }) {
  return (
    <article className={`${styles.workflowEvent} ${styles[event.tone]}`}>
      <div className={styles.eventSequence}>
        <span>{event.sequence}</span>
        <span>{event.time}</span>
      </div>
      <div className={styles.eventMain}>
        <strong>{event.title}</strong>
        <span>{event.description}</span>
      </div>
      <span className={styles.eventBadge}>{event.badge}</span>
    </article>
  );
}

/**
 * @param {{icon: import("lucide-react").LucideIcon, title: string}} props
 */
function PanelTitle({ icon: Icon, title }) {
  return (
    <h2 className={styles.panelTitle}>
      <span aria-hidden="true">
        <Icon size={17} strokeWidth={2.6} />
      </span>
      {title}
    </h2>
  );
}

/**
 * @param {{title: string, tone: string, value: string}} props
 */
function StatusCard({ title, tone, value }) {
  return (
    <article className={`${styles.statusCard} ${styles[tone]}`}>
      <strong>{title}</strong>
      <span>{value}</span>
    </article>
  );
}
