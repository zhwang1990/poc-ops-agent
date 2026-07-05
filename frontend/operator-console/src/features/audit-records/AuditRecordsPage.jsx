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

const timeFilterOptions = [ALL_TIME, LAST_24_HOURS, LAST_7_DAYS];

export function AuditRecordsPage() {
  const [query, setQuery] = useState("");
  const [actionFilter, setActionFilter] = useState(ALL_ACTIONS);
  const [resultFilter, setResultFilter] = useState(ALL_RESULTS);
  const [timeFilter, setTimeFilter] = useState(ALL_TIME);
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
  const skillAuditEntries = useMemo(
    () => filteredEntries.filter(isSkillExecutionAudit).slice(0, 3),
    [filteredEntries],
  );
  const latestEntry = filteredEntries[0] ?? entries[0] ?? null;

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

          <span className={styles.filterCount}>
            {filteredEntries.length} 条记录 / {entries.length} 总量
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
              {!auditQuery.isLoading && !auditQuery.isError && entries.length > 0 && filteredEntries.length === 0 ? (
                <StateMessage title="暂无匹配记录" />
              ) : null}
              {!auditQuery.isLoading && !auditQuery.isError
                ? filteredEntries.map((entry) => (
                    <article
                      className={`${styles.auditEntry} ${styles[entry.color]}`}
                      key={entry.eventId}
                    >
                      <div className={styles.auditTime}>
                        <strong>{entry.time}</strong>
                        <span>{entry.date}</span>
                      </div>
                      <div className={styles.auditMain}>
                        <strong>{entry.action}</strong>
                        <span>{entry.resource}</span>
                        <small>{entry.subject}</small>
                      </div>
                      <span className={styles.auditHash}>{entry.traceId}</span>
                      <span className={styles.auditHash}>{entry.result}</span>
                    </article>
                  ))
                : null}
            </div>
          </section>

          <aside aria-label="证据详情" className={styles.detail}>
            <h2>证据详情</h2>
            <div className={styles.detailRows}>
              <DetailRow label="Operator" value={latestEntry?.subject ?? "-"} />
              <DetailRow label="Policy" value={latestEntry?.policyVersion ?? "-"} />
              <DetailRow label="Request" value={latestEntry?.requestId ?? "-"} />
              <DetailRow label="Reason" value={latestEntry?.reason || "-"} />
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
 * @param {{ entries: ReturnType<typeof toAuditEntry>[] }} props
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
            <strong>{entry.resource}</strong>
            <span>{entry.action}</span>
            <span>{entry.result}</span>
            <small>{entry.traceId}</small>
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
  return {
    action: event.action,
    color: auditTone(event.result),
    date: formatAuditDate(occurredAt),
    eventId: event.eventId,
    occurredAt,
    policyVersion: event.policyVersion,
    reason: event.reason,
    requestId: event.requestId,
    resource: event.resource,
    result: event.result,
    subject: event.subject,
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
 * @param {ReturnType<typeof toAuditEntry>} entry
 */
function isSkillExecutionAudit(entry) {
  return (
    entry.action === "internal.agent.tool.execute" ||
    entry.action.endsWith(".tool.execute")
  );
}

/**
 * @param {ReturnType<typeof toAuditEntry>[]} entries
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
        entry.resource,
        entry.result,
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
 *   label: string,
 *   onChange: (value: string) => void,
 *   options: string[],
 *   value: string,
 * }} props
 */
function FilterSelect({ label, onChange, options, value }) {
  return (
    <label className={styles.filterSelect}>
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
