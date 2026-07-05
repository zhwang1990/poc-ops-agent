import {
  AudioLines,
  Bot,
  Boxes,
  DatabaseZap,
  ExternalLink,
  FileClock,
  Network,
  SearchCheck,
  ServerCog,
  Wrench,
  Workflow,
} from "lucide-react";
import { Link } from "react-router-dom";

import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import styles from "./OverviewPage.module.css";

const statusMetrics = [
  { label: "已开放入口", value: "3", detail: "Agent / SQL / Tools", tone: "info" },
  { label: "只读 Skill", value: "5", detail: "签名注册", tone: "accent" },
  { label: "治理观察面", value: "2", detail: "事件 / 审计", tone: "green" },
  { label: "受限能力", value: "4", detail: "占位禁用", tone: "warning" },
];

const primaryEntries = [
  {
    icon: Bot,
    label: "Agent 工作区",
    module: "M04 / M05 / M09",
    status: "已接入",
    tone: "info",
    to: "/agent",
    summary: "任务会话、候选 Skill、工作流状态和强类型事件集中在一个操作视图。",
    boundary: "发送任务仍等待通用 Agent 执行接口开放。",
  },
  {
    icon: SearchCheck,
    label: "SQL 工作区",
    module: "M08 / M09",
    status: "已接入",
    tone: "deep",
    to: "/sql",
    summary: "连接目录、SQL 校验、只读风险报告和 DML 预检保持服务端判定。",
    boundary: "P1 不开放生产写入、提交或回滚。",
  },
  {
    icon: Wrench,
    label: "工具中心",
    module: "M09 / M02 / M07",
    status: "页面规划",
    tone: "quick",
    to: "/tools",
    summary: "JSON 本地格式化、API Caller 请求草稿和管理员 allowlist 设置集中在一个工具视图。",
    boundary: "首版页面不直接调用目标系统，真实执行必须经过服务端 allowlist、策略和审计。",
  },
];

const diagnosticQueue = [
  {
    icon: ServerCog,
    title: "node-a 健康排查",
    module: "M04 / M05",
    skill: "node-health-read@1.1.0",
    status: "只读执行中",
    checks: ["策略通过", "审计挂载", "Worker 已接收"],
    tone: "info",
  },
  {
    icon: Network,
    title: "payment-api 依赖巡检",
    module: "M08 / M10",
    skill: "service-dependency-read@0.9.0",
    status: "候选 Skill",
    checks: ["Runbook 引用", "SSE 待接入", "拒绝写动作"],
    tone: "teal",
  },
  {
    icon: DatabaseZap,
    title: "订单库慢查询趋势",
    module: "M08 / M09",
    skill: "sql-risk-read@0.8.2",
    status: "预检草案",
    checks: ["只读连接", "DML 预检", "结果脱敏"],
    tone: "deep",
  },
];

const capabilityRows = [
  {
    icon: Network,
    label: "RAG 问答",
    owner: "知识检索入口",
    status: "待接入",
    tone: "teal",
    to: "/rag",
  },
  {
    icon: Boxes,
    label: "Skill 注册中心",
    owner: "Skill 元数据",
    status: "占位",
    tone: "warning",
    to: "/skills",
  },
  {
    icon: Workflow,
    label: "工作流事件",
    owner: "sequence / SSE",
    status: "待接入",
    tone: "green",
    to: "/workflow-events",
  },
  {
    icon: FileClock,
    label: "审计记录",
    owner: "授权证据链",
    status: "待接入",
    tone: "slate",
    to: "/audit",
  },
  {
    icon: AudioLines,
    label: "会议录制纪要",
    owner: "会议归档",
    status: "待评审",
    tone: "meeting",
    to: "/meeting-notes",
  },
  {
    icon: ExternalLink,
    label: "快捷连接",
    owner: "链接目录",
    status: "后续切片",
    tone: "quick",
    disabled: true,
  },
];

const overviewGuideItems = [
  {
    title: "先选工作区",
    detail: "查服务或任务用 Agent，查 SQL 风险用 SQL 工作区。",
  },
  {
    title: "只做诊断",
    detail: "这里展示排查入口和状态，不会直接修改生产环境。",
  },
  {
    title: "后续再接入",
    detail: "RAG、审计和工作流入口先保留位置，后续接真实能力。",
  },
];

export function OverviewPage() {
  return (
    <WorkspacePageFrame className={styles.overviewCanvas}>
      <WorkspaceStatusBar title="平台总览" />

      <div className={styles.overviewGrid}>
        <div className={styles.primaryColumn}>
          <section aria-label="总览功能状态" className={styles.statePanel}>
            <section aria-label="总览使用步骤" className={styles.guidePanel}>
              <div className={styles.guideList}>
                {overviewGuideItems.map((item, index) => (
                  <article className={styles.guideItem} key={item.title}>
                    <span className={styles.guideIndex}>{index + 1}</span>
                    <strong>{item.title}</strong>
                    <p>{item.detail}</p>
                  </article>
                ))}
              </div>
            </section>
            <div aria-label="总览指标" className={styles.statusStrip}>
              {statusMetrics.map((metric) => (
                <article className={`${styles.metricTile} ${styles[metric.tone]}`} key={metric.label}>
                  <span>{metric.label}</span>
                  <strong>{metric.value}</strong>
                  <small>{metric.detail}</small>
                </article>
              ))}
            </div>
          </section>

          <section aria-labelledby="available-entry-title" className={styles.entryPanel}>
            <div className={styles.sectionHeading}>
              <h3 id="available-entry-title">可用工作入口</h3>
              <span>只读链路</span>
            </div>
            <div className={styles.entryGrid}>
              {primaryEntries.map((entry) => (
                <PrimaryEntry entry={entry} key={entry.label} />
              ))}
            </div>
          </section>

          <section aria-labelledby="diagnostic-queue-title" className={styles.queuePanel}>
            <div className={styles.sectionHeading}>
              <h3 id="diagnostic-queue-title">只读诊断队列</h3>
              <span>模拟工作流</span>
            </div>
            <div className={styles.queueGrid}>
              {diagnosticQueue.map((item) => (
                <DiagnosticQueueItem item={item} key={item.title} />
              ))}
            </div>
          </section>

          <section aria-labelledby="capability-map-title" className={styles.capabilityPanel}>
            <div className={styles.sectionHeading}>
              <h3 id="capability-map-title">后续能力地图</h3>
              <span>占位与规划</span>
            </div>
            <div className={styles.capabilityMap}>
              {capabilityRows.map((item) => (
                <CapabilityRow item={item} key={item.label} />
              ))}
            </div>
          </section>
        </div>
      </div>
    </WorkspacePageFrame>
  );
}

/**
 * @param {{
 *   item: {
 *     checks: string[],
 *     icon: import("lucide-react").LucideIcon,
 *     module: string,
 *     skill: string,
 *     status: string,
 *     title: string,
 *     tone: string,
 *   }
 * }} props
 */
function DiagnosticQueueItem({ item }) {
  const Icon = item.icon;

  return (
    <article className={`${styles.queueItem} ${styles[item.tone]}`}>
      <span aria-hidden="true" className={styles.queueIcon}>
        <Icon size={18} strokeWidth={2.3} />
      </span>
      <span className={styles.queueStatus}>{item.status}</span>
      <strong>{item.title}</strong>
      <small>{item.module}</small>
      <p>{item.skill}</p>
      <div className={styles.queueChecks}>
        {item.checks.map((check) => (
          <span key={check}>{check}</span>
        ))}
      </div>
    </article>
  );
}

/**
 * @param {{
 *   entry: {
 *     boundary: string,
 *     icon: import("lucide-react").LucideIcon,
 *     label: string,
 *     module: string,
 *     status: string,
 *     summary: string,
 *     to: string,
 *     tone: string,
 *   }
 * }} props
 */
function PrimaryEntry({ entry }) {
  const Icon = entry.icon;

  return (
    <Link className={`${styles.entryCard} ${styles[entry.tone]}`} to={entry.to}>
      <span aria-hidden="true" className={styles.entryIcon}>
        <Icon size={22} strokeWidth={2.25} />
      </span>
      <span className={styles.entryMeta}>{entry.module}</span>
      <strong>{entry.label}</strong>
      <span className={styles.entryStatus}>{entry.status}</span>
      <p>{entry.summary}</p>
      <small>{entry.boundary}</small>
    </Link>
  );
}

/**
 * @param {{
 *   item: {
 *     icon: import("lucide-react").LucideIcon,
 *     label: string,
 *     owner: string,
 *     status: string,
 *     to?: string,
 *     disabled?: boolean,
 *     tone: string,
 *   }
 * }} props
 */
function CapabilityRow({ item }) {
  const Icon = item.icon;
  const content = (
    <>
      <span aria-hidden="true" className={styles.rowIcon}>
        <Icon size={17} strokeWidth={2.3} />
      </span>
      <span className={styles.rowText}>
        <strong>{item.label}</strong>
        <small>{item.owner}</small>
      </span>
      <span className={styles.rowStatus}>{item.status}</span>
    </>
  );

  if (item.disabled) {
    return (
      <article
        aria-disabled="true"
        className={`${styles.capabilityRow} ${styles[item.tone]}`}
      >
        {content}
      </article>
    );
  }

  return (
    <Link className={`${styles.capabilityRow} ${styles[item.tone]}`} to={item.to ?? "/"}>
      {content}
    </Link>
  );
}
