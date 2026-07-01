import { useMemo, useState } from "react";
import {
  CheckCircle2,
  FileArchive,
  KeyRound,
  ListChecks,
  Package,
  Play,
  RefreshCw,
  Rocket,
  Server,
  ShieldCheck,
  UploadCloud,
} from "lucide-react";

import { StatusPill } from "../../components/data-display/StatusPill.jsx";
import { FeedbackState } from "../../components/feedback/FeedbackState.jsx";
import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import { Button } from "../../components/primitives/Button.jsx";
import {
  useReleaseApplications,
  useReleasePlans,
  useReleaseServers,
} from "./use-release-center.js";
import styles from "./ReleaseCenterPage.module.css";

/** @typedef {import("../../schemas/release-center-schemas.js").ReleaseApplication} ReleaseApplication */
/** @typedef {import("../../schemas/release-center-schemas.js").ReleasePlan} ReleasePlan */
/** @typedef {import("../../schemas/release-center-schemas.js").ReleaseServer} ReleaseServer */
/** @typedef {"plans" | "artifacts" | "applications" | "servers" | "policies" | "credentials"} ReleaseTabId */

const TABS = [
  { id: "plans", label: "发布单", icon: ListChecks },
  { id: "artifacts", label: "制品", icon: FileArchive },
  { id: "applications", label: "应用", icon: Package },
  { id: "servers", label: "服务器", icon: Server },
  { id: "policies", label: "策略", icon: ShieldCheck },
  { id: "credentials", label: "凭据", icon: KeyRound },
];

const TARGET_ENVIRONMENTS = ["dev", "sit", "uat"];

export function ReleaseCenterPage() {
  const [activeTab, setActiveTab] = useState(/** @type {ReleaseTabId} */ ("plans"));
  const [targetEnvironment, setTargetEnvironment] = useState("dev");
  const applicationsQuery = useReleaseApplications();
  const plansQuery = useReleasePlans();
  const serversQuery = useReleaseServers(targetEnvironment);
  const applications = useMemo(() => applicationsQuery.data ?? [], [applicationsQuery.data]);
  const plans = useMemo(() => plansQuery.data ?? [], [plansQuery.data]);
  const servers = useMemo(() => serversQuery.data ?? [], [serversQuery.data]);
  const selectedPlan = plans[0] ?? null;
  const selectedApplication =
    applications.find((application) => application.applicationId === selectedPlan?.applicationId) ??
    applications[0] ??
    null;

  return (
    <WorkspacePageFrame className={styles.releaseCanvas}>
      <WorkspaceStatusBar title="发布中心" />

      <main className={styles.releaseBody}>
        <section aria-label="发布中心概览" className={styles.summaryBand}>
          <div className={styles.summaryLead}>
            <span aria-hidden="true" className={styles.summaryIcon}>
              <Rocket size={19} />
            </span>
            <div>
              <span className={styles.kicker}>M09 / P2</span>
              <h2>非生产发布工作区</h2>
            </div>
          </div>

          <div aria-label="目标环境" className={styles.environmentSwitch}>
            {TARGET_ENVIRONMENTS.map((environment) => (
              <button
                aria-pressed={targetEnvironment === environment}
                className={`${styles.environmentButton} ${
                  targetEnvironment === environment ? styles.environmentButtonActive : ""
                }`}
                key={environment}
                onClick={() => setTargetEnvironment(environment)}
                type="button"
              >
                {environment}
              </button>
            ))}
          </div>

          <div className={styles.summaryActions}>
            <Button className={styles.actionButton} disabled variant="secondary">
              <UploadCloud aria-hidden="true" size={16} />
              上传 WAR
            </Button>
            <Button className={styles.actionButton} disabled>
              <Rocket aria-hidden="true" size={16} />
              新建发布单
            </Button>
          </div>
        </section>

        <section className={styles.workspaceGrid}>
          <section aria-label="发布中心配置" className={styles.primaryPanel}>
            <div aria-label="发布中心配置" className={styles.tabs} role="tablist">
              {TABS.map((tab) => {
                const Icon = tab.icon;
                const selected = activeTab === tab.id;
                return (
                  <button
                    aria-controls={`release-tabpanel-${tab.id}`}
                    aria-selected={selected}
                    className={`${styles.tabButton} ${selected ? styles.tabButtonActive : ""}`}
                    id={`release-tab-${tab.id}`}
                    key={tab.id}
                    onClick={() => setActiveTab(/** @type {ReleaseTabId} */ (tab.id))}
                    role="tab"
                    type="button"
                  >
                    <Icon aria-hidden="true" size={16} />
                    <span>{tab.label}</span>
                  </button>
                );
              })}
            </div>

            <div
              aria-labelledby={`release-tab-${activeTab}`}
              className={styles.tabPanel}
              id={`release-tabpanel-${activeTab}`}
              role="tabpanel"
            >
              <ReleaseTabPanel
                activeTab={activeTab}
                applications={applications}
                applicationsQuery={applicationsQuery}
                plans={plans}
                plansQuery={plansQuery}
                selectedApplication={selectedApplication}
                selectedPlan={selectedPlan}
                servers={servers}
                serversQuery={serversQuery}
                targetEnvironment={targetEnvironment}
              />
            </div>
          </section>

          <InventoryPanel
            applications={applications}
            applicationsQuery={applicationsQuery}
            servers={servers}
            serversQuery={serversQuery}
            targetEnvironment={targetEnvironment}
          />
        </section>
      </main>
    </WorkspacePageFrame>
  );
}

/**
 * @param {{
 *   activeTab: ReleaseTabId,
 *   applications: ReleaseApplication[],
 *   applicationsQuery: ReturnType<typeof useReleaseApplications>,
 *   plans: ReleasePlan[],
 *   plansQuery: ReturnType<typeof useReleasePlans>,
 *   selectedApplication: ReleaseApplication | null,
 *   selectedPlan: ReleasePlan | null,
 *   servers: ReleaseServer[],
 *   serversQuery: ReturnType<typeof useReleaseServers>,
 *   targetEnvironment: string,
 * }} props
 */
function ReleaseTabPanel({
  activeTab,
  applications,
  applicationsQuery,
  plans,
  plansQuery,
  selectedApplication,
  selectedPlan,
  servers,
  serversQuery,
  targetEnvironment,
}) {
  if (activeTab === "plans") {
    return (
      <PlansPanel
        applicationsQuery={applicationsQuery}
        plans={plans}
        plansQuery={plansQuery}
        selectedApplication={selectedApplication}
      />
    );
  }
  if (activeTab === "artifacts") {
    return <ArtifactsPanel selectedPlan={selectedPlan} targetEnvironment={targetEnvironment} />;
  }
  if (activeTab === "applications") {
    return <ApplicationsPanel applications={applications} query={applicationsQuery} />;
  }
  if (activeTab === "servers") {
    return <ServersPanel query={serversQuery} servers={servers} targetEnvironment={targetEnvironment} />;
  }
  if (activeTab === "policies") {
    return <PoliciesPanel />;
  }
  return <CredentialsPanel servers={servers} serversQuery={serversQuery} />;
}

/**
 * @param {{
 *   applicationsQuery: ReturnType<typeof useReleaseApplications>,
 *   plans: ReleasePlan[],
 *   plansQuery: ReturnType<typeof useReleasePlans>,
 *   selectedApplication: ReleaseApplication | null,
 * }} props
 */
function PlansPanel({ applicationsQuery, plans, plansQuery, selectedApplication }) {
  const queryState = queryFeedback([plansQuery, applicationsQuery], "发布单读取失败");
  if (queryState) {
    return queryState;
  }
  if (plans.length === 0) {
    return <FeedbackState message="当前环境暂无发布单。" state="empty" title="暂无发布单" />;
  }

  return (
    <section className={styles.planStack} aria-label="发布单列表">
      {plans.map((plan) => (
        <article className={styles.planRow} key={plan.releaseId}>
          <div className={styles.planMain}>
            <span className={styles.rowIcon} aria-hidden="true">
              <Rocket size={17} />
            </span>
            <div className={styles.planCopy}>
              <strong>{plan.releaseId}</strong>
              <span>
                应用 {selectedApplication?.displayName ?? plan.applicationId} / {plan.targetEnvironment}
              </span>
            </div>
          </div>
          <div className={styles.planMeta}>
            <StatusPill tone={statusTone(plan.status)}>{plan.status}</StatusPill>
            <span>{plan.artifactId}</span>
          </div>
          <ol className={styles.nodeSteps} aria-label={`${plan.releaseId} 节点`}>
            {plan.nodes.map((node) => (
              <li key={`${plan.releaseId}-${node.nodeId}`}>
                <span>#{node.sequence}</span>
                <strong>节点 {node.nodeId}</strong>
                <StatusPill tone={statusTone(node.status)}>{node.status}</StatusPill>
              </li>
            ))}
          </ol>
          <div className={styles.rowActions}>
            <Button className={styles.compactButton} disabled variant="secondary">
              <CheckCircle2 aria-hidden="true" size={15} />
              确认
            </Button>
            <Button className={styles.compactButton} disabled>
              <Play aria-hidden="true" size={15} />
              执行
            </Button>
          </div>
        </article>
      ))}
    </section>
  );
}

/**
 * @param {{selectedPlan: ReleasePlan | null, targetEnvironment: string}} props
 */
function ArtifactsPanel({ selectedPlan, targetEnvironment }) {
  return (
    <section className={styles.tablePanel} aria-label="制品记录">
      <div className={styles.tableHeader}>
        <span>制品 ID</span>
        <span>环境</span>
        <span>类型</span>
        <span>校验</span>
      </div>
      <div className={styles.tableRow}>
        <strong>{selectedPlan?.artifactId ?? "未选择"}</strong>
        <span>{targetEnvironment}</span>
        <span>WAR</span>
        <span>{selectedPlan?.parametersHash ?? "待记录"}</span>
      </div>
    </section>
  );
}

/**
 * @param {{applications: ReleaseApplication[], query: ReturnType<typeof useReleaseApplications>}} props
 */
function ApplicationsPanel({ applications, query }) {
  const queryState = queryFeedback([query], "应用读取失败");
  if (queryState) {
    return queryState;
  }
  if (applications.length === 0) {
    return <FeedbackState message="暂无可发布应用。" state="empty" title="暂无应用" />;
  }

  return (
    <section className={styles.tablePanel} aria-label="应用配置">
      <div className={styles.tableHeader}>
        <span>应用</span>
        <span>制品</span>
        <span>健康检查</span>
        <span>状态</span>
      </div>
      {applications.map((application) => (
        <div className={styles.tableRow} key={application.applicationId}>
          <strong>{application.displayName}</strong>
          <span>{application.artifactType}</span>
          <span>{application.healthCheckPath}</span>
          <StatusPill tone={application.enabled ? "success" : "danger"}>
            {application.enabled ? "启用" : "停用"}
          </StatusPill>
        </div>
      ))}
    </section>
  );
}

/**
 * @param {{
 *   query: ReturnType<typeof useReleaseServers>,
 *   servers: ReleaseServer[],
 *   targetEnvironment: string
 * }} props
 */
function ServersPanel({ query, servers, targetEnvironment }) {
  const queryState = queryFeedback([query], "服务器读取失败");
  if (queryState) {
    return queryState;
  }
  if (servers.length === 0) {
    return <FeedbackState message={`${targetEnvironment} 暂无服务器。`} state="empty" title="暂无服务器" />;
  }

  return (
    <section className={styles.tablePanel} aria-label="服务器配置">
      <div className={styles.tableHeader}>
        <span>节点</span>
        <span>类型</span>
        <span>策略</span>
        <span>凭据</span>
      </div>
      {servers.map((server) => (
        <div className={styles.tableRow} key={server.nodeId}>
          <strong>{server.nodeId}</strong>
          <span>{server.serverType}</span>
          <span>{server.managementMode}</span>
          <span>{server.credentialAlias ?? "未绑定"}</span>
        </div>
      ))}
    </section>
  );
}

function PoliciesPanel() {
  return (
    <section className={styles.policyGrid} aria-label="发布策略">
      <PolicyRow label="dev" value="直接创建" tone="success" />
      <PolicyRow label="sit" value="二次确认" tone="warning" />
      <PolicyRow label="uat" value="二次确认" tone="warning" />
      <PolicyRow label="最终状态" value="确定性检查" tone="info" />
      <PolicyRow label="日志分析" value="只读建议" tone="info" />
    </section>
  );
}

/**
 * @param {{
 *   servers: ReleaseServer[],
 *   serversQuery: ReturnType<typeof useReleaseServers>
 * }} props
 */
function CredentialsPanel({ servers, serversQuery }) {
  const queryState = queryFeedback([serversQuery], "凭据映射读取失败");
  if (queryState) {
    return queryState;
  }

  const aliases = [...new Set(servers.map((server) => server.credentialAlias).filter(Boolean))];
  if (aliases.length === 0) {
    return <FeedbackState message="当前环境暂无凭据别名。" state="empty" title="暂无凭据" />;
  }

  return (
    <section className={styles.tablePanel} aria-label="凭据别名">
      <div className={styles.tableHeader}>
        <span>别名</span>
        <span>来源</span>
        <span>状态</span>
        <span>操作</span>
      </div>
      {aliases.map((alias) => (
        <div className={styles.tableRow} key={alias}>
          <strong>{alias}</strong>
          <span>服务器配置</span>
          <StatusPill tone="success">已绑定</StatusPill>
          <Button className={styles.compactButton} disabled variant="secondary">
            <RefreshCw aria-hidden="true" size={15} />
            轮换
          </Button>
        </div>
      ))}
    </section>
  );
}

/**
 * @param {{
 *   applications: ReleaseApplication[],
 *   applicationsQuery: ReturnType<typeof useReleaseApplications>,
 *   servers: ReleaseServer[],
 *   serversQuery: ReturnType<typeof useReleaseServers>,
 *   targetEnvironment: string,
 * }} props
 */
function InventoryPanel({ applications, applicationsQuery, servers, serversQuery, targetEnvironment }) {
  const queryState = queryFeedback([applicationsQuery, serversQuery], "发布库存读取失败");
  return (
    <aside aria-label="发布中心库存" className={styles.inventoryPanel}>
      <div className={styles.inventoryHeader}>
        <span className={styles.kicker}>Catalog</span>
        <h2>配置快照</h2>
      </div>
      {queryState ?? (
        <div className={styles.inventorySections}>
          <section aria-label="应用库存" className={styles.inventorySection}>
            <span className={styles.sectionLabel}>应用</span>
            {applications.length === 0 ? (
              <p>暂无应用</p>
            ) : (
              applications.slice(0, 3).map((application) => (
                <div className={styles.inventoryRow} key={application.applicationId}>
                  <strong>{application.applicationId}</strong>
                  <span>{application.displayName}</span>
                </div>
              ))
            )}
          </section>
          <section aria-label="服务器库存" className={styles.inventorySection}>
            <span className={styles.sectionLabel}>服务器 / {targetEnvironment}</span>
            {servers.length === 0 ? (
              <p>暂无服务器</p>
            ) : (
              servers.slice(0, 4).map((server) => (
                <div className={styles.inventoryRow} key={server.nodeId}>
                  <strong>{server.nodeId}</strong>
                  <span>
                    {server.serverType} / {server.managementMode}
                  </span>
                </div>
              ))
            )}
          </section>
        </div>
      )}
    </aside>
  );
}

/**
 * @param {{label: string, value: string, tone: "info" | "success" | "warning" | "danger"}} props
 */
function PolicyRow({ label, value, tone }) {
  return (
    <div className={styles.policyRow}>
      <strong>{label}</strong>
      <StatusPill tone={tone}>{value}</StatusPill>
    </div>
  );
}

/**
 * @param {Array<{isLoading: boolean, isError: boolean, error: unknown}>} queries
 * @param {string} errorTitle
 */
function queryFeedback(queries, errorTitle) {
  if (queries.some((query) => query.isLoading)) {
    return <FeedbackState message="正在读取控制面数据。" state="loading" title="加载中" />;
  }
  const failedQuery = queries.find((query) => query.isError);
  if (failedQuery) {
    return (
      <FeedbackState
        message={readErrorMessage(failedQuery.error)}
        state="error"
        title={errorTitle}
      />
    );
  }
  return null;
}

/**
 * @param {string} status
 * @returns {"info" | "success" | "warning" | "danger"}
 */
function statusTone(status) {
  if (["SUCCEEDED", "READY"].includes(status)) {
    return "success";
  }
  if (["FAILED", "PARTIAL_FAILED", "ROLLBACK_FAILED", "MANUAL_INTERVENTION"].includes(status)) {
    return "danger";
  }
  if (["RUNNING", "WAIT_CONFIRM", "ROLLING_BACK"].includes(status)) {
    return "warning";
  }
  return "info";
}

/**
 * @param {unknown} error
 */
function readErrorMessage(error) {
  if (error instanceof Error) {
    return error.message;
  }
  return "请求失败";
}
