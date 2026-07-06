import { useMemo, useState } from "react";
import { useQuery } from "@tanstack/react-query";
import {
  LockKeyhole,
  Search,
} from "lucide-react";

import { loadRecentAuditEvents } from "../../api/audit-api.js";
import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import styles from "./AuditRecordsPage.module.css";

const ALL_ACTIONS = "全部 action";
const ALL_RESULTS = "全部结果";
const ALL_TIME = "全部时间";
const LAST_24_HOURS = "最近 24 小时";
const LAST_7_DAYS = "最近 7 天";
const DUPLICATE_COLLAPSE_WINDOW_MS = 1000;

const timeFilterOptions = [ALL_TIME, LAST_24_HOURS, LAST_7_DAYS];

export function AuditRecordsPage() {
  const [query, setQuery] = useState("");
  const [actionFilter, setActionFilter] = useState(ALL_ACTIONS);
  const [resultFilter, setResultFilter] = useState(ALL_RESULTS);
  const [timeFilter, setTimeFilter] = useState(ALL_TIME);
  const [selectedEventId, setSelectedEventId] = useState("");
  const auditQuery = useQuery({
    queryKey: ["audit-events", 200],
    queryFn: () => loadRecentAuditEvents({ limit: 200 }),
  });
  const entries = useMemo(
    () => auditQuery.data?.events.map(toAuditEntry) ?? [],
    [auditQuery.data?.events],
  );
  const actionOptions = useMemo(
    () => [ALL_ACTIONS, ...uniqueSorted(entries.map((entry) => entry.action))],
    [entries],
  );
  const resultOptions = useMemo(
    () => [ALL_RESULTS, ...uniqueSorted(entries.map((entry) => entry.result))],
    [entries],
  );
  const filteredEntries = useMemo(
    () => filterEntries(entries, { actionFilter, query, resultFilter, timeFilter }),
    [actionFilter, entries, query, resultFilter, timeFilter],
  );
  const visibleEntries = useMemo(
    () => collapseAdjacentDuplicateEntries(filteredEntries),
    [filteredEntries],
  );
  const collapsedDuplicateCount = filteredEntries.length - visibleEntries.length;
  const skillAuditEntries = useMemo(
    () => visibleEntries.filter(isSkillExecutionAudit).slice(0, 3),
    [visibleEntries],
  );
  const selectedEntry = useMemo(
    () =>
      visibleEntries.find((entry) => entry.eventId === selectedEventId) ??
      visibleEntries[0] ??
      null,
    [selectedEventId, visibleEntries],
  );

  return (
    <WorkspacePageFrame className={styles.auditCanvas}>
      <WorkspaceStatusBar title="审计记录" />

      <section aria-label="审计记录工作区" className={styles.workspaceBody}>
        <form
          aria-label="审计记录筛选"
          className={styles.filterBar}
          onSubmit={(event) => event.preventDefault()}
          role="search"
        >
          <div className={styles.filterControls}>
            <label className={styles.searchBox}>
              <Search aria-hidden="true" size={15} strokeWidth={2.5} />
              <span className={styles.visuallyHidden}>搜索操作者、action、resource、traceId</span>
              <input
                onChange={(event) => setQuery(event.target.value)}
                placeholder="搜索操作者、action、resource、traceId"
                type="search"
                value={query}
              />
            </label>

            <FilterSelect
              className={styles.actionFilterSelect}
              label="Action"
              onChange={setActionFilter}
              options={actionOptions}
              value={actionFilter}
            />
            <FilterSelect
              label="结果"
              onChange={setResultFilter}
              options={resultOptions}
              value={resultFilter}
            />
            <FilterSelect
              label="时间"
              onChange={setTimeFilter}
              options={timeFilterOptions}
              value={timeFilter}
            />
          </div>

          <span className={styles.filterCount}>
            {visibleEntries.length} / {entries.length} 总量
            {collapsedDuplicateCount > 0 ? ` · 合并 ${collapsedDuplicateCount}` : ""}
          </span>
        </form>

        {skillAuditEntries.length > 0 ? (
          <SkillAuditHighlights entries={skillAuditEntries} />
        ) : null}

        <div className={styles.auditLayout}>
          <section
            aria-label="审计账本"
            aria-labelledby="audit-ledger-title"
            className={styles.ledger}
          >
            <h2 id="audit-ledger-title">审计账本</h2>
            <div className={styles.auditChain}>
              {auditQuery.isLoading ? (
                <AuditSkeleton />
              ) : null}
              {auditQuery.isError ? (
                <StateMessage title="审计记录读取失败" />
              ) : null}
              {!auditQuery.isLoading && !auditQuery.isError && entries.length === 0 ? (
                <StateMessage title="暂无审计记录" />
              ) : null}
              {!auditQuery.isLoading && !auditQuery.isError && entries.length > 0 && visibleEntries.length === 0 ? (
                <StateMessage title="暂无匹配记录" />
              ) : null}
              {!auditQuery.isLoading && !auditQuery.isError
                ? visibleEntries.map((entry) => {
                    const isSelected = entry.eventId === selectedEntry?.eventId;

                    return (
                      <button
                        aria-pressed={isSelected}
                        className={`${styles.auditEntry} ${styles[entry.color]} ${isSelected ? styles.auditEntrySelected : ""}`}
                        key={entry.eventId}
                        onClick={() => setSelectedEventId(entry.eventId)}
                        type="button"
                      >
                        <div className={styles.auditTime}>
                          <strong>{entry.time}</strong>
                          <span>{entry.date}</span>
                        </div>
                        <div className={styles.auditMain}>
                          <strong>{entry.targetLabel}</strong>
                          <span>{entry.targetDetail}</span>
                          <small>{entry.action} · {entry.subject}</small>
                        </div>
                        <span className={styles.auditBadges}>
                          <span className={styles.auditHash}>{entry.traceId}</span>
                          {entry.duplicateCount > 1 ? (
                            <span className={styles.auditDuplicate}>合并 ×{entry.duplicateCount}</span>
                          ) : null}
                        </span>
                        <span className={styles.auditHash}>{entry.resultLabel}</span>
                      </button>
                    );
                  })
                : null}
            </div>
          </section>

          <aside aria-label="证据详情" className={styles.detail}>
            <h2>证据详情</h2>
            <div className={styles.detailRows}>
              <DetailRow label="Operator" value={selectedEntry?.subject ?? "-"} />
              <DetailRow label="Target" value={selectedEntry?.targetLabel ?? "-"} />
              <DetailRow label="Action" value={selectedEntry?.action ?? "-"} />
              <DetailRow label="Resource" value={selectedEntry?.resource ?? "-"} />
              <DetailRow
                label="Result"
                value={selectedEntry ? `${selectedEntry.resultLabel} (${selectedEntry.result})` : "-"}
              />
              <DetailRow label="Policy" value={selectedEntry?.policyVersion ?? "-"} />
              <DetailRow label="Request" value={selectedEntry?.requestId ?? "-"} />
              <DetailRow label="Trace" value={selectedEntry?.traceId ?? "-"} />
              <DetailRow label="Event" value={selectedEntry?.eventId ?? "-"} />
              {selectedEntry?.duplicateCount > 1 ? (
                <DetailRow
                  label="Merged"
                  value={`${selectedEntry.duplicateCount} 条相邻重复事件：${selectedEntry.duplicateEventIds.join(", ")}`}
                />
              ) : null}
              <DetailRow label="Occurred" value={selectedEntry?.occurredAtText ?? "-"} />
              <DetailRow label="Reason" value={selectedEntry?.reason || "-"} />
            </div>
            <div className={styles.retentionNote}>
              <LockKeyhole aria-hidden="true" size={16} strokeWidth={2.5} />
              <span>统一审计链路只读展示，授权结果以服务端策略为准。</span>
            </div>
          </aside>
        </div>
      </section>
    </WorkspacePageFrame>
  );
}

/**
 * @typedef {ReturnType<typeof toAuditEntry>} AuditEntry
 */

/**
 * @param {{ entries: AuditEntry[] }} props
 */
function SkillAuditHighlights({ entries }) {
  return (
    <section aria-label="最近 Skill 执行审计" className={styles.skillAuditHighlights}>
      <div className={styles.skillAuditTitle}>
        <strong>最近 Skill 执行审计</strong>
        <span>Agent Tool 授权结果</span>
      </div>
      <div className={styles.skillAuditList}>
        {entries.map((entry) => (
          <article
            className={`${styles.skillAuditItem} ${styles[entry.color]}`}
            key={`${entry.eventId}-highlight`}
          >
            <strong>{entry.targetLabel}</strong>
            <span>{entry.resource}</span>
            <span>{entry.resultLabel}</span>
            <small>{entry.action} · {entry.traceId}</small>
          </article>
        ))}
      </div>
    </section>
  );
}

function AuditSkeleton() {
  return [0, 1, 2].map((item) => (
    <div aria-hidden="true" className={styles.skeletonEntry} key={item} />
  ));
}

/**
 * @param {{ title: string }} props
 */
function StateMessage({ title }) {
  return (
    <div className={styles.emptyState}>
      <strong>{title}</strong>
    </div>
  );
}

/**
 * @param {{ label: string, value: string }} props
 */
function DetailRow({ label, value }) {
  return (
    <div className={styles.detailRow}>
      <strong>{label}</strong>
      <span>{value}</span>
    </div>
  );
}

/**
 * @param {import("../../schemas/audit-schemas.js").AuditEvent} event
 */
function toAuditEntry(event) {
  const occurredAt = new Date(event.timestamp);
  const target = describeAuditTarget(event.action, event.resource);
  return {
    action: event.action,
    color: auditTone(event.result),
    date: formatAuditDate(occurredAt),
    dedupeKey: auditDedupeKey(event),
    duplicateCount: 1,
    duplicateEventIds: [event.eventId],
    eventId: event.eventId,
    occurredAt,
    occurredAtText: event.timestamp,
    policyVersion: event.policyVersion,
    reason: event.reason,
    requestId: event.requestId,
    resource: event.resource,
    result: event.result,
    resultLabel: auditResultLabel(event.result),
    subject: event.subject,
    targetDetail: target.detail,
    targetLabel: target.label,
    time: formatAuditTime(occurredAt, event.timestamp),
    traceId: event.traceId,
  };
}

/**
 * @param {string} result
 */
function auditTone(result) {
  if (result.startsWith("DENY")) return "red";
  if (result === "ALLOW") return "green";
  return "dark";
}

/**
 * @param {string} result
 */
function auditResultLabel(result) {
  if (result.startsWith("DENY")) return "拒绝";
  if (result === "ALLOW") return "允许";
  return result;
}

/**
 * @param {string} action
 * @param {string} resource
 */
function describeAuditTarget(action, resource) {
  return {
    detail: resource || action,
    label: auditTargetLabel(action),
  };
}

/**
 * @param {string} action
 */
function auditTargetLabel(action) {
  if (action === "internal.audit.read") return "审计记录读取";
  if (action === "internal.agent.tool.execute") return "Skill 执行授权";
  if (action === "internal.agent.diagnostics.read") return "Agent 诊断读取";
  if (action === "release.plan.execute") return "发布计划执行";
  if (action.startsWith("release.")) return `发布中心${auditActionLabel(action)}`;
  if (action.startsWith("internal.sql-workbench.")) return `SQL 工作台${auditActionLabel(action)}`;
  if (action.startsWith("internal.skills.")) return `Skill 注册中心${auditActionLabel(action)}`;
  if (action.startsWith("internal.routing.skills.")) return `Skill 路由${auditActionLabel(action)}`;
  if (action.startsWith("internal.modules.")) return `模块清单${auditActionLabel(action)}`;
  if (action.startsWith("internal.model-providers.")) return `模型供应方${auditActionLabel(action)}`;
  if (action.startsWith("internal.tool-center.")) return `工具中心${auditActionLabel(action)}`;
  if (action.startsWith("internal.health.")) return `控制面健康检查${auditActionLabel(action)}`;
  if (action.startsWith("internal.diagnostics.")) return `只读诊断${auditActionLabel(action)}`;
  return action;
}

/**
 * @param {string} action
 */
function auditActionLabel(action) {
  if (action.endsWith(".read")) return "读取";
  if (action.endsWith(".create")) return "创建";
  if (action.endsWith(".update")) return "更新";
  if (action.endsWith(".delete")) return "删除";
  if (action.endsWith(".execute")) return "执行";
  if (action.endsWith(".validate")) return "校验";
  if (action.endsWith(".probe")) return "探测";
  if (action.endsWith(".run")) return "运行";
  if (action.endsWith(".use")) return "使用";
  if (action.endsWith(".write")) return "写入";
  if (action.endsWith(".switch")) return "切换";
  if (action.endsWith(".rotate")) return "轮换";
  if (action.endsWith(".repair")) return "修复";
  return "访问";
}

/**
 * @param {AuditEntry} entry
 */
function isSkillExecutionAudit(entry) {
  return (
    entry.action === "internal.agent.tool.execute" ||
    entry.action.endsWith(".tool.execute")
  );
}

/**
 * @param {AuditEntry[]} entries
 * @returns {AuditEntry[]}
 */
function collapseAdjacentDuplicateEntries(entries) {
  /** @type {AuditEntry[]} */
  const collapsed = [];
  for (const entry of entries) {
    const previous = collapsed.at(-1);
    if (previous && isAdjacentDuplicate(previous, entry)) {
      previous.duplicateCount += 1;
      previous.duplicateEventIds = [...previous.duplicateEventIds, entry.eventId];
      continue;
    }
    collapsed.push({
      ...entry,
      duplicateCount: 1,
      duplicateEventIds: [entry.eventId],
    });
  }
  return collapsed;
}

/**
 * @param {AuditEntry} previous
 * @param {AuditEntry} next
 */
function isAdjacentDuplicate(previous, next) {
  if (previous.dedupeKey !== next.dedupeKey) {
    return false;
  }
  const previousTime = previous.occurredAt.getTime();
  const nextTime = next.occurredAt.getTime();
  if (Number.isNaN(previousTime) || Number.isNaN(nextTime)) {
    return true;
  }
  return Math.abs(previousTime - nextTime) <= DUPLICATE_COLLAPSE_WINDOW_MS;
}

/**
 * @param {AuditEntry[]} entries
 * @param {{
 *   actionFilter: string,
 *   query: string,
 *   resultFilter: string,
 *   timeFilter: string,
 * }} filters
 */
function filterEntries(entries, filters) {
  const normalizedQuery = filters.query.trim().toLowerCase();
  return entries.filter((entry) => {
    const matchesQuery = normalizedQuery.length === 0 ||
      [
        entry.action,
        entry.targetLabel,
        entry.targetDetail,
        entry.resource,
        entry.result,
        entry.resultLabel,
        entry.subject,
        entry.traceId,
        entry.requestId,
        entry.reason,
      ]
        .join(" ")
        .toLowerCase()
        .includes(normalizedQuery);
    const matchesAction = filters.actionFilter === ALL_ACTIONS ||
      entry.action === filters.actionFilter;
    const matchesResult = filters.resultFilter === ALL_RESULTS ||
      entry.result === filters.resultFilter;
    const matchesTime = isWithinTimeRange(entry.occurredAt, filters.timeFilter);

    return matchesQuery && matchesAction && matchesResult && matchesTime;
  });
}

/**
 * @param {import("../../schemas/audit-schemas.js").AuditEvent} event
 */
function auditDedupeKey(event) {
  return [
    event.requestId,
    event.traceId,
    event.subject,
    event.action,
    event.resource,
    event.result,
    event.policyVersion,
  ].join("|");
}

/**
 * @param {Date} occurredAt
 * @param {string} timeFilter
 */
function isWithinTimeRange(occurredAt, timeFilter) {
  if (timeFilter === ALL_TIME) {
    return true;
  }
  if (Number.isNaN(occurredAt.getTime())) {
    return false;
  }
  const ageMs = Date.now() - occurredAt.getTime();
  if (timeFilter === LAST_24_HOURS) {
    return ageMs >= 0 && ageMs <= 24 * 60 * 60 * 1000;
  }
  if (timeFilter === LAST_7_DAYS) {
    return ageMs >= 0 && ageMs <= 7 * 24 * 60 * 60 * 1000;
  }
  return true;
}

/**
 * @param {Date} occurredAt
 * @param {string} fallback
 */
function formatAuditTime(occurredAt, fallback) {
  if (Number.isNaN(occurredAt.getTime())) {
    return fallback;
  }
  return new Intl.DateTimeFormat("zh-CN", {
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hour12: false,
  }).format(occurredAt);
}

/**
 * @param {Date} occurredAt
 */
function formatAuditDate(occurredAt) {
  if (Number.isNaN(occurredAt.getTime())) {
    return "-";
  }
  return new Intl.DateTimeFormat("zh-CN", {
    month: "2-digit",
    day: "2-digit",
  }).format(occurredAt);
}

/**
 * @param {string[]} values
 */
function uniqueSorted(values) {
  return Array.from(new Set(values.filter(Boolean))).sort((left, right) =>
    left.localeCompare(right),
  );
}

/**
 * @param {{
 *   className?: string,
 *   label: string,
 *   onChange: (value: string) => void,
 *   options: string[],
 *   value: string,
 * }} props
 */
function FilterSelect({ className = "", label, onChange, options, value }) {
  return (
    <label className={`${styles.filterSelect} ${className}`}>
      <span>{label}</span>
      <select
        aria-label={label === "Action" ? "Action 筛选" : `${label}筛选`}
        onChange={(event) => onChange(event.target.value)}
        value={value}
      >
        {options.map((option) => (
          <option key={option} value={option}>
            {option}
          </option>
        ))}
      </select>
    </label>
  );
}
