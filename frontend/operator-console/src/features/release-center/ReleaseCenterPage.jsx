import { useMemo, useRef, useState } from "react";
import {
  BadgeCheck,
  CheckCircle2,
  Code2,
  Copy,
  FileArchive,
  KeyRound,
  ListChecks,
  Network,
  Package,
  Pencil,
  Play,
  PlusCircle,
  RefreshCw,
  Rocket,
  Server,
  ShieldCheck,
  Trash2,
  UploadCloud,
} from "lucide-react";

import { StatusPill } from "../../components/data-display/StatusPill.jsx";
import { FeedbackState } from "../../components/feedback/FeedbackState.jsx";
import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import { Button } from "../../components/primitives/Button.jsx";
import { Dialog } from "../../components/primitives/Dialog.jsx";
import {
  useReleaseApplications,
  useReleaseArtifacts,
  useConfirmReleasePlan,
  useCreateReleasePlan,
  useDeleteReleaseServer,
  useExecuteReleasePlan,
  useReleasePlans,
  useReleaseServers,
  useSaveReleaseServer,
  useUploadTomcatWar,
} from "./use-release-center.js";
import styles from "./ReleaseCenterPage.module.css";

/** @typedef {import("../../schemas/release-center-schemas.js").ReleaseApplication} ReleaseApplication */
/** @typedef {import("../../schemas/release-center-schemas.js").ReleaseArtifact} ReleaseArtifact */
/** @typedef {import("../../schemas/release-center-schemas.js").ReleasePlan} ReleasePlan */
/** @typedef {import("../../schemas/release-center-schemas.js").ReleaseServer} ReleaseServer */
/** @typedef {"plans" | "artifacts" | "applications" | "servers" | "policies" | "credentials"} ReleaseTabId */
/** @typedef {"PENDING_TARGETS" | "SCRIPT_PROFILE" | "ARTIFACT"} ReleaseArtifactMode */
/** @typedef {ReleaseApplication & { source?: "CATALOG" | "SCRIPT_PROFILE" }} ReleaseApplicationTarget */

const TABS = [
  { id: "plans", label: "发布单", icon: ListChecks },
  { id: "artifacts", label: "制品", icon: FileArchive },
  { id: "applications", label: "应用", icon: Package },
  { id: "servers", label: "服务器", icon: Server },
  { id: "policies", label: "策略", icon: ShieldCheck },
  { id: "credentials", label: "凭据", icon: KeyRound },
];

const TARGET_ENVIRONMENTS = [
  { id: "dev", label: "DEV", icon: Code2 },
  { id: "sit", label: "SIT", icon: Network },
  { id: "uat", label: "UAT", icon: BadgeCheck },
];
const SERVER_TYPE_OPTIONS = ["TOMCAT", "LIBERTY"];
const MANAGEMENT_MODE_OPTIONS_BY_SERVER_TYPE = {
  TOMCAT: ["TOMCAT_WAR_UPLOAD", "TOMCAT_MANAGER_API"],
  LIBERTY: ["LIBERTY_SCRIPT_PROFILE"],
};

/**
 * @typedef {{
 *   nodeId: string,
 *   targetEnvironment: string,
 *   serverType: string,
 *   managementMode: string,
 *   managementEndpoint: string,
 *   applicationPath: string,
 *   credentialAlias: string,
 *   scriptProfileId: string,
 *   scriptParameters: Array<{id: string, name: string, value: string}>,
 *   enabled: boolean,
 * }} ReleaseServerForm
 */

export function ReleaseCenterPage() {
  const [activeTab, setActiveTab] = useState(/** @type {ReleaseTabId} */ ("plans"));
  const [targetEnvironment, setTargetEnvironment] = useState("dev");
  const [createDialogOpen, setCreateDialogOpen] = useState(false);
  const applicationsQuery = useReleaseApplications();
  const artifactsQuery = useReleaseArtifacts(targetEnvironment);
  const plansQuery = useReleasePlans();
  const serversQuery = useReleaseServers(targetEnvironment);
  const uploadMutation = useUploadTomcatWar();
  const createPlanMutation = useCreateReleasePlan();
  const fileInputRef = useRef(/** @type {HTMLInputElement | null} */ (null));
  const applications = useMemo(() => applicationsQuery.data ?? [], [applicationsQuery.data]);
  const artifacts = useMemo(() => artifactsQuery.data ?? [], [artifactsQuery.data]);
  const plans = useMemo(() => plansQuery.data ?? [], [plansQuery.data]);
  const servers = useMemo(() => serversQuery.data ?? [], [serversQuery.data]);
  const selectedPlan = plans[0] ?? null;
  const selectedApplication =
    applications.find((application) => application.applicationId === selectedPlan?.applicationId) ??
    applications[0] ??
    null;
  const enabledServers = useMemo(() => servers.filter((server) => server.enabled), [servers]);
  const inferredScriptApplication = useMemo(
    () => inferLibertyScriptApplication(enabledServers),
    [enabledServers],
  );
  const selectedReleaseApplication = selectedApplication ?? inferredScriptApplication;
  const selectedArtifact =
    artifacts.find((artifact) => artifact.applicationId === selectedApplication?.applicationId) ??
    artifacts[0] ??
    null;
  const releaseArtifactMode = releaseArtifactModeFor(enabledServers);
  const artifactRequired = releaseArtifactMode === "ARTIFACT";
  const scriptArtifactPathMissing =
    releaseArtifactMode === "SCRIPT_PROFILE" && !hasCompleteLibertySharedArtifactPaths(enabledServers);
  const canUpload = Boolean(selectedApplication) && !uploadMutation.isPending;
  const canSubmitCreatePlan =
    Boolean(
      selectedReleaseApplication &&
        enabledServers.length > 0 &&
        (!artifactRequired || selectedArtifact) &&
        !scriptArtifactPathMissing,
    ) &&
    !createPlanMutation.isPending;

  function handleUploadClick() {
    fileInputRef.current?.click();
  }

  /**
   * @param {React.ChangeEvent<HTMLInputElement>} event
   */
  function handleFileChange(event) {
    const file = event.currentTarget.files?.[0];
    if (!file || !selectedApplication) {
      return;
    }
    uploadMutation.mutate({
      applicationId: selectedApplication.applicationId,
      targetEnvironment,
      file,
    });
    event.currentTarget.value = "";
  }

  function handleCreatePlan() {
    if (
      !selectedReleaseApplication ||
      enabledServers.length === 0 ||
      (artifactRequired && !selectedArtifact) ||
      scriptArtifactPathMissing
    ) {
      return;
    }
    const artifactFields = artifactRequired && selectedArtifact
      ? {
          artifactId: selectedArtifact.artifactId,
          parametersHash: selectedArtifact.checksum,
        }
      : {};
    createPlanMutation.mutate({
        applicationId: selectedReleaseApplication.applicationId,
        targetEnvironment,
        nodeIds: enabledServers.map((server) => server.nodeId),
        ...artifactFields,
      },
      {
        onSuccess: () => setCreateDialogOpen(false),
      });
  }

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
            {TARGET_ENVIRONMENTS.map((environment) => {
              const EnvironmentIcon = environment.icon;
              const selected = targetEnvironment === environment.id;
              return (
                <button
                  aria-pressed={selected}
                  className={`${styles.environmentButton} ${selected ? styles.environmentButtonActive : ""}`}
                  key={environment.id}
                  onClick={() => setTargetEnvironment(environment.id)}
                  type="button"
                >
                  <EnvironmentIcon aria-hidden="true" className={styles.environmentIcon} size={13} />
                  <span>{environment.label}</span>
                </button>
              );
            })}
          </div>

          <div className={styles.summaryActions}>
            <input
              accept=".war"
              aria-label="选择 WAR 制品"
              hidden
              onChange={handleFileChange}
              ref={fileInputRef}
              type="file"
            />
            <Button
              className={styles.actionButton}
              disabled={!canUpload}
              onClick={handleUploadClick}
              variant="secondary"
            >
              <UploadCloud aria-hidden="true" size={16} />
              上传 WAR
            </Button>
            <Button
              className={styles.actionButton}
              disabled={createPlanMutation.isPending}
              onClick={() => setCreateDialogOpen(true)}
            >
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
                artifacts={artifacts}
                artifactsQuery={artifactsQuery}
                plans={plans}
                plansQuery={plansQuery}
                selectedApplication={selectedApplication}
                servers={servers}
                serversQuery={serversQuery}
                targetEnvironment={targetEnvironment}
              />
            </div>
          </section>

          <InventoryPanel
            applications={applications}
            applicationsQuery={applicationsQuery}
            artifacts={artifacts}
            artifactsQuery={artifactsQuery}
            servers={servers}
            serversQuery={serversQuery}
            targetEnvironment={targetEnvironment}
          />
        </section>
      </main>
      <CreatePlanDialog
        canSubmit={canSubmitCreatePlan}
        enabledServers={enabledServers}
        isPending={createPlanMutation.isPending}
        onClose={() => setCreateDialogOpen(false)}
        onSubmit={handleCreatePlan}
        open={createDialogOpen}
        releaseArtifactMode={releaseArtifactMode}
        selectedApplication={selectedReleaseApplication}
        selectedArtifact={selectedArtifact}
        scriptArtifactPathMissing={scriptArtifactPathMissing}
        targetEnvironment={targetEnvironment}
      />
    </WorkspacePageFrame>
  );
}

/**
 * @param {{
 *   canSubmit: boolean,
 *   enabledServers: ReleaseServer[],
 *   isPending: boolean,
 *   onClose: () => void,
 *   onSubmit: () => void,
 *   open: boolean,
 *   releaseArtifactMode: ReleaseArtifactMode,
 *   selectedApplication: ReleaseApplicationTarget | null,
 *   selectedArtifact: ReleaseArtifact | null,
 *   scriptArtifactPathMissing: boolean,
 *   targetEnvironment: string,
 * }} props
 */
function CreatePlanDialog({
  canSubmit,
  enabledServers,
  isPending,
  onClose,
  onSubmit,
  open,
  releaseArtifactMode,
  selectedApplication,
  selectedArtifact,
  scriptArtifactPathMissing,
  targetEnvironment,
}) {
  const missingItems = [
    selectedApplication ? null : "缺少已启用应用",
    releaseArtifactMode === "ARTIFACT" && !selectedArtifact ? "缺少可发布制品" : null,
    scriptArtifactPathMissing ? "缺少 Liberty 制品共享路径" : null,
    enabledServers.length > 0 ? null : "缺少可用发布节点",
  ].filter(Boolean);

  return (
    <Dialog
      closeLabel="关闭新建发布单"
      description="发布单会绑定当前环境、制品 checksum 或脚本参数哈希，以及已启用节点，执行仍由服务端策略与 Worker 控制。"
      eyebrow={`Release / ${targetEnvironment}`}
      icon={<Rocket size={18} />}
      onClose={onClose}
      open={open}
      size="wide"
      title="新建发布单"
    >
      <div className={styles.createPlanDialog}>
        <div className={styles.createPlanGrid}>
          <CreatePlanField
            label="应用"
            value={selectedApplication ? `${selectedApplication.displayName} / ${selectedApplication.applicationId}` : "未配置"}
          />
          <CreatePlanField
            label="制品"
            tone={
              (releaseArtifactMode === "ARTIFACT" && !selectedArtifact) || scriptArtifactPathMissing
                ? "warning"
                : "default"
            }
            value={artifactSummary(selectedArtifact, releaseArtifactMode, enabledServers)}
          />
          <CreatePlanField
            label="节点"
            tone={enabledServers.length > 0 ? "default" : "warning"}
            value={enabledServers.length > 0 ? enabledServers.map((server) => server.nodeId).join(", ") : "缺少可用发布节点"}
          />
        </div>

        {missingItems.length > 0 ? (
          <div className={styles.createPlanWarnings} role="status">
            {missingItems.map((item) => (
              <span key={item}>{item}</span>
            ))}
          </div>
        ) : null}

        <div className={styles.dialogActions}>
          <Button onClick={onClose} variant="secondary">
            取消
          </Button>
          <Button disabled={!canSubmit || isPending} onClick={onSubmit}>
            创建发布单
          </Button>
        </div>
      </div>
    </Dialog>
  );
}

/**
 * @param {ReleaseServer[]} servers
 * @returns {ReleaseArtifactMode}
 */
function releaseArtifactModeFor(servers) {
  if (servers.length === 0) {
    return "PENDING_TARGETS";
  }
  return usesOnlyLibertyScriptProfiles(servers) ? "SCRIPT_PROFILE" : "ARTIFACT";
}

/**
 * @param {ReleaseServer[]} servers
 */
function usesOnlyLibertyScriptProfiles(servers) {
  return servers.every(
    (server) => server.serverType === "LIBERTY" && server.managementMode === "LIBERTY_SCRIPT_PROFILE",
  );
}

/**
 * @param {ReleaseServer[]} servers
 * @returns {ReleaseApplicationTarget | null}
 */
function inferLibertyScriptApplication(servers) {
  if (servers.length === 0 || !usesOnlyLibertyScriptProfiles(servers)) {
    return null;
  }
  const applicationNames = servers.map((server) => scriptParameterValue(server, "applicationName").trim());
  if (applicationNames.some((applicationName) => applicationName.length === 0)) {
    return null;
  }
  const uniqueApplicationNames = new Set(applicationNames);
  if (uniqueApplicationNames.size !== 1) {
    return null;
  }
  const applicationId = applicationNames[0];
  return {
    applicationId,
    displayName: `${applicationId}（脚本参数）`,
    artifactType: "WAR",
    healthCheckPath: "/health",
    enabled: true,
    source: "SCRIPT_PROFILE",
  };
}

/**
 * @param {ReleaseArtifact | null} selectedArtifact
 * @param {ReleaseArtifactMode} releaseArtifactMode
 * @param {ReleaseServer[]} enabledServers
 */
function artifactSummary(selectedArtifact, releaseArtifactMode, enabledServers) {
  if (releaseArtifactMode === "PENDING_TARGETS") {
    return "等待发布节点确定发布方式";
  }
  if (releaseArtifactMode === "SCRIPT_PROFILE") {
    const paths = libertySharedArtifactPaths(enabledServers);
    if (paths.length === 0) {
      return "缺少 Liberty 制品共享路径";
    }
    const uniquePaths = [...new Set(paths)];
    return uniquePaths.length === 1 ? uniquePaths[0] : uniquePaths.join(", ");
  }
  if (selectedArtifact) {
    return `${selectedArtifact.artifactId} / ${selectedArtifact.checksum}`;
  }
  return "缺少可发布制品";
}

/**
 * @param {{ label: string, tone?: "default" | "warning", value: string }} props
 */
function CreatePlanField({ label, tone = "default", value }) {
  return (
    <div className={styles.createPlanField} data-tone={tone}>
      <span>{label}</span>
      <strong>{value}</strong>
    </div>
  );
}

/**
 * @param {{
 *   activeTab: ReleaseTabId,
 *   applications: ReleaseApplication[],
 *   applicationsQuery: ReturnType<typeof useReleaseApplications>,
 *   artifacts: ReleaseArtifact[],
 *   artifactsQuery: ReturnType<typeof useReleaseArtifacts>,
 *   plans: ReleasePlan[],
 *   plansQuery: ReturnType<typeof useReleasePlans>,
 *   selectedApplication: ReleaseApplication | null,
 *   servers: ReleaseServer[],
 *   serversQuery: ReturnType<typeof useReleaseServers>,
 *   targetEnvironment: string,
 * }} props
 */
function ReleaseTabPanel({
  activeTab,
  applications,
  applicationsQuery,
  artifacts,
  artifactsQuery,
  plans,
  plansQuery,
  selectedApplication,
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
    return (
      <ArtifactsPanel
        artifacts={artifacts}
        query={artifactsQuery}
        targetEnvironment={targetEnvironment}
      />
    );
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
  const confirmMutation = useConfirmReleasePlan();
  const executeMutation = useExecuteReleasePlan();
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
            <span>{plan.artifactId ?? "SCRIPT_PROFILE"}</span>
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
            <Button
              className={styles.compactButton}
              disabled={plan.status !== "WAIT_CONFIRM" || confirmMutation.isPending}
              onClick={() =>
                confirmMutation.mutate({
                  releaseId: plan.releaseId,
                  input: {
                    confirmationId: `confirm-${plan.releaseId}`,
                    parametersHash: plan.parametersHash,
                  },
                })
              }
              variant="secondary"
            >
              <CheckCircle2 aria-hidden="true" size={15} />
              确认
            </Button>
            <Button
              className={styles.compactButton}
              disabled={!["DRAFT", "READY"].includes(plan.status) || executeMutation.isPending}
              onClick={() => executeMutation.mutate(plan.releaseId)}
            >
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
 * @param {{
 *   artifacts: ReleaseArtifact[],
 *   query: ReturnType<typeof useReleaseArtifacts>,
 *   targetEnvironment: string
 * }} props
 */
function ArtifactsPanel({ artifacts, query, targetEnvironment }) {
  const queryState = queryFeedback([query], "制品读取失败");
  if (queryState) {
    return queryState;
  }
  if (artifacts.length === 0) {
    return <FeedbackState message={`${targetEnvironment} 暂无 WAR 制品。`} state="empty" title="暂无制品" />;
  }

  return (
    <section className={styles.tablePanel} aria-label="制品记录">
      <div className={styles.tableHeader}>
        <span>制品 ID</span>
        <span>环境</span>
        <span>类型</span>
        <span>校验</span>
      </div>
      {artifacts.map((artifact) => (
        <div className={styles.tableRow} key={artifact.artifactId}>
          <strong>{artifact.artifactId}</strong>
          <span>{artifact.targetEnvironment}</span>
          <span>{artifact.artifactType}</span>
          <span>{artifact.checksum}</span>
        </div>
      ))}
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
  const [serverDialogOpen, setServerDialogOpen] = useState(false);
  const [editingServer, setEditingServer] = useState(/** @type {ReleaseServer | null} */ (null));
  const [pendingDeleteNodeId, setPendingDeleteNodeId] = useState("");
  const [copiedPathNodeId, setCopiedPathNodeId] = useState("");
  const saveServerMutation = useSaveReleaseServer();
  const deleteServerMutation = useDeleteReleaseServer();
  const queryState = queryFeedback([query], "服务器读取失败");
  if (queryState) {
    return queryState;
  }

  /**
   * @param {ReleaseServer} server
   */
  function openEditServer(server) {
    setEditingServer(server);
    setServerDialogOpen(true);
  }

  function openCreateServer() {
    setEditingServer(null);
    setServerDialogOpen(true);
  }

  /**
   * @param {ReleaseServer} server
   */
  function deleteServer(server) {
    deleteServerMutation.mutate(
      { nodeId: server.nodeId, targetEnvironment: server.targetEnvironment },
      { onSuccess: () => setPendingDeleteNodeId("") },
    );
  }

  /**
   * @param {string} nodeId
   * @param {string} artifactPath
   */
  function copyArtifactPath(nodeId, artifactPath) {
    if (navigator.clipboard) {
      void navigator.clipboard.writeText(artifactPath);
    }
    setCopiedPathNodeId(nodeId);
  }

  return (
    <>
      <section className={styles.panelStack} aria-label="服务器配置">
        <div className={styles.panelHeader}>
          <div className={styles.panelTitle}>
            <span className={styles.kicker}>Servers / {targetEnvironment}</span>
            <strong>发布节点</strong>
          </div>
          <Button
            aria-label="Add release server"
            className={styles.compactActionButton}
            disabled={saveServerMutation.isPending}
            onClick={openCreateServer}
            variant="secondary"
          >
            <PlusCircle aria-hidden="true" size={15} />
            新增服务器
          </Button>
        </div>

        {servers.length === 0 ? (
          <FeedbackState
            message={`${targetEnvironment} 暂无服务器。`}
            state="empty"
            title="暂无服务器"
          />
        ) : (
          <section className={styles.tablePanel} aria-label="服务器配置列表">
            <div className={`${styles.tableHeader} ${styles.serverTableRow}`}>
              <span>节点</span>
              <span>类型</span>
              <span>策略</span>
              <span>制品 / 路径</span>
              <span>操作</span>
            </div>
            {servers.map((server) => (
              <div className={`${styles.tableRow} ${styles.serverTableRow}`} key={server.nodeId}>
                <div className={styles.serverIdentity}>
                  <strong>{server.nodeId}</strong>
                  <span>{server.applicationPath ?? "未配置应用路径"}</span>
                </div>
                <span>{server.serverType}</span>
                <span>{server.managementMode}</span>
                <ServerArtifactCell
                  copied={copiedPathNodeId === server.nodeId}
                  onCopy={(artifactPath) => copyArtifactPath(server.nodeId, artifactPath)}
                  server={server}
                />
                <div className={`${styles.rowActions} ${styles.serverRowActions}`}>
                  <Button
                    aria-label={`Edit ${server.nodeId}`}
                    className={styles.iconButton}
                    disabled={saveServerMutation.isPending}
                    onClick={() => openEditServer(server)}
                    title="编辑服务器"
                    variant="secondary"
                  >
                    <Pencil aria-hidden="true" size={15} />
                  </Button>
                  {pendingDeleteNodeId === server.nodeId ? (
                    <Button
                      aria-label={`Confirm delete ${server.nodeId}`}
                      className={styles.compactDangerButton}
                      disabled={deleteServerMutation.isPending}
                      onClick={() => deleteServer(server)}
                      variant="danger"
                    >
                      确认删除
                    </Button>
                  ) : (
                    <Button
                      aria-label={`Delete ${server.nodeId}`}
                      className={styles.iconButton}
                      disabled={deleteServerMutation.isPending}
                      onClick={() => setPendingDeleteNodeId(server.nodeId)}
                      title="删除服务器"
                      variant="secondary"
                    >
                      <Trash2 aria-hidden="true" size={15} />
                    </Button>
                  )}
                </div>
              </div>
            ))}
          </section>
        )}
      </section>

      {serverDialogOpen ? (
        <ServerDialog
          error={saveServerMutation.error}
          isPending={saveServerMutation.isPending}
          key={`${targetEnvironment}-${editingServer?.nodeId ?? "new"}`}
          initialServer={editingServer}
          onClose={() => {
            setServerDialogOpen(false);
            setEditingServer(null);
          }}
          onSubmit={(server) =>
            saveServerMutation.mutate(server, {
              onSuccess: () => {
                setServerDialogOpen(false);
                setEditingServer(null);
              },
            })
          }
          open={serverDialogOpen}
          targetEnvironment={targetEnvironment}
        />
      ) : null}
    </>
  );
}

/**
 * @param {{
 *   copied: boolean,
 *   onCopy: (artifactPath: string) => void,
 *   server: ReleaseServer,
 * }} props
 */
function ServerArtifactCell({ copied, onCopy, server }) {
  const artifactPath = scriptParameterValue(server, "artifactPath");
  if (server.serverType !== "LIBERTY") {
    return <span>平台上传 WAR</span>;
  }
  if (!artifactPath) {
    return <span>缺少 artifactPath 参数</span>;
  }
  return (
    <div className={styles.artifactPathCell}>
      {isSharedArtifactPath(artifactPath) ? (
        <span title={artifactPath}>{artifactPath}</span>
      ) : isHttpUrl(artifactPath) ? (
        <a href={artifactPath} rel="noreferrer" target="_blank">
          {artifactPath}
        </a>
      ) : (
        <span data-tone="warning" title={artifactPath}>
          制品路径必须以 \\ 开头
        </span>
      )}
      <Button
        aria-label={`Copy artifact path for ${server.nodeId}`}
        className={styles.iconButton}
        onClick={() => onCopy(artifactPath)}
        title={copied ? "已复制" : "复制制品路径"}
        variant="secondary"
      >
        <Copy aria-hidden="true" size={15} />
      </Button>
    </div>
  );
}

/**
 * @param {{
 *   error: unknown,
 *   initialServer: ReleaseServer | null,
 *   isPending: boolean,
 *   onClose: () => void,
 *   onSubmit: (server: unknown) => void,
 *   open: boolean,
 *   targetEnvironment: string,
 * }} props
 */
function ServerDialog({ error, initialServer, isPending, onClose, onSubmit, open, targetEnvironment }) {
  const [form, setForm] = useState(() =>
    initialServer ? createServerFormFromServer(initialServer) : createDefaultServerForm(targetEnvironment),
  );
  const editing = Boolean(initialServer);
  const isScriptProfileMode = form.managementMode === "LIBERTY_SCRIPT_PROFILE";
  const credentialReady = isScriptProfileMode || Boolean(form.credentialAlias.trim());
  const scriptProfileReady =
    !isScriptProfileMode ||
    Boolean(
      form.scriptProfileId.trim() &&
        form.scriptParameters.length > 0 &&
        form.scriptParameters.every((parameter) => parameter.name.trim() && parameter.value.trim()),
    );

  const canSubmit =
    Boolean(
      form.nodeId.trim() &&
        form.managementEndpoint.trim() &&
        form.applicationPath.trim() &&
        credentialReady &&
        scriptProfileReady,
    ) && !isPending;

  /**
   * @param {keyof ReleaseServerForm} field
   * @param {string | boolean} value
   */
  function updateField(field, value) {
    setForm((current) => {
      if (field === "serverType" && typeof value === "string") {
        const nextModes = managementModeOptions(value);
        return {
          ...current,
          serverType: value,
          managementMode: nextModes[0],
        };
      }
      return { ...current, [field]: value };
    });
  }

  /**
   * @param {string} id
   * @param {"name" | "value"} field
   * @param {string} value
   */
  function updateScriptParameter(id, field, value) {
    setForm((current) => ({
      ...current,
      scriptParameters: current.scriptParameters.map((parameter) =>
        parameter.id === id ? { ...parameter, [field]: value } : parameter,
      ),
    }));
  }

  function addScriptParameter() {
    setForm((current) => ({
      ...current,
      scriptParameters: [
        ...current.scriptParameters,
        { id: `param-${Date.now()}`, name: "", value: "" },
      ],
    }));
  }

  /**
   * @param {string} id
   */
  function removeScriptParameter(id) {
    setForm((current) => ({
      ...current,
      scriptParameters: current.scriptParameters.filter((parameter) => parameter.id !== id),
    }));
  }

  /**
   * @param {React.FormEvent<HTMLFormElement>} event
   */
  function handleSubmit(event) {
    event.preventDefault();
    if (!canSubmit) {
      return;
    }
    const scriptParameters = form.scriptParameters.map((parameter) => ({
      name: parameter.name.trim(),
      value: parameter.value.trim(),
    }));
    onSubmit({
      nodeId: form.nodeId.trim(),
      targetEnvironment: form.targetEnvironment,
      serverType: form.serverType,
      managementMode: form.managementMode,
      managementEndpoint: form.managementEndpoint.trim(),
      applicationPath: form.applicationPath.trim(),
      credentialAlias: form.credentialAlias.trim() || null,
      scriptProfile: isScriptProfileMode
        ? {
            profileId: form.scriptProfileId.trim(),
            parameters: scriptParameters,
          }
        : undefined,
      enabled: form.enabled,
    });
  }

  return (
    <Dialog
      closeLabel={editing ? "关闭编辑服务器" : "关闭新增服务器"}
      description="服务器配置只登记非生产发布目标，真正执行仍由服务端策略、工作流和 Worker 隔离控制。"
      eyebrow={`Server / ${targetEnvironment}`}
      icon={<Server size={18} />}
      onClose={onClose}
      open={open}
      size="wide"
      title={editing ? "编辑服务器" : "新增服务器"}
    >
      <form className={styles.serverForm} onSubmit={handleSubmit}>
        <div className={styles.formGrid}>
          <label className={styles.formField}>
            <span>节点 ID</span>
            <input
              aria-label="Node ID"
              onChange={(event) => updateField("nodeId", event.currentTarget.value)}
              placeholder="tomcat-dev-1"
              readOnly={editing}
              value={form.nodeId}
            />
          </label>
          <label className={styles.formField}>
            <span>目标环境</span>
            <input readOnly value={form.targetEnvironment} />
          </label>
          <label className={styles.formField}>
            <span>服务器类型</span>
            <select
              aria-label="Server type"
              onChange={(event) => updateField("serverType", event.currentTarget.value)}
              value={form.serverType}
            >
              {SERVER_TYPE_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </label>
          <label className={styles.formField}>
            <span>管理模式</span>
            <select
              aria-label="Management mode"
              onChange={(event) => updateField("managementMode", event.currentTarget.value)}
              value={form.managementMode}
            >
              {managementModeOptions(form.serverType).map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </label>
          <label className={styles.formField}>
            <span>管理端点</span>
            <input
              aria-label="Management endpoint"
              onChange={(event) => updateField("managementEndpoint", event.currentTarget.value)}
              placeholder="http://127.0.0.1:18080/manager/text"
              value={form.managementEndpoint}
            />
          </label>
          <label className={styles.formField}>
            <span>应用路径</span>
            <input
              aria-label="Application path"
              onChange={(event) => updateField("applicationPath", event.currentTarget.value)}
              placeholder="/orders"
              value={form.applicationPath}
            />
          </label>
          <label className={styles.formField}>
            <span>凭据别名</span>
            <input
              aria-label="Credential alias"
              onChange={(event) => updateField("credentialAlias", event.currentTarget.value)}
              placeholder="tomcat-dev"
              value={form.credentialAlias}
            />
          </label>
          <label className={styles.toggleField}>
            <input
              checked={form.enabled}
              onChange={(event) => updateField("enabled", event.currentTarget.checked)}
              type="checkbox"
            />
            <span>启用服务器</span>
          </label>
        </div>

        {isScriptProfileMode ? (
          <section className={styles.scriptProfilePanel} aria-label="Liberty script profile">
            <label className={styles.formField}>
              <span>Script Profile ID</span>
              <input
                onChange={(event) => updateField("scriptProfileId", event.currentTarget.value)}
                placeholder="liberty-war-deploy"
                value={form.scriptProfileId}
              />
            </label>

            <div className={styles.scriptParameterHeader}>
              <strong>Script parameters</strong>
              <Button className={styles.compactButton} onClick={addScriptParameter} type="button" variant="secondary">
                <PlusCircle aria-hidden="true" size={15} />
                Add
              </Button>
            </div>

            <div className={styles.scriptParameterList}>
              {form.scriptParameters.map((parameter, index) => (
                <div className={styles.scriptParameterRow} key={parameter.id}>
                  <label className={styles.formField}>
                    <span>{`Parameter ${index + 1} name`}</span>
                    <input
                      onChange={(event) => updateScriptParameter(parameter.id, "name", event.currentTarget.value)}
                      placeholder="serverName"
                      value={parameter.name}
                    />
                  </label>
                  <label className={styles.formField}>
                    <span>{`Parameter ${index + 1} value`}</span>
                    <input
                      onChange={(event) => updateScriptParameter(parameter.id, "value", event.currentTarget.value)}
                      placeholder="defaultServer"
                      value={parameter.value}
                    />
                  </label>
                  <Button
                    className={styles.removeParameterButton}
                    disabled={form.scriptParameters.length <= 1}
                    onClick={() => removeScriptParameter(parameter.id)}
                    type="button"
                    variant="secondary"
                  >
                    Remove
                  </Button>
                </div>
              ))}
            </div>
          </section>
        ) : null}

        {error ? <p className={styles.formError}>{readErrorMessage(error)}</p> : null}

        <div className={styles.dialogActions}>
          <Button onClick={onClose} variant="secondary">
            取消
          </Button>
          <Button aria-label="Save release server" disabled={!canSubmit} type="submit">
            保存服务器
          </Button>
        </div>
      </form>
    </Dialog>
  );
}

/**
 * @param {string} targetEnvironment
 * @returns {ReleaseServerForm}
 */
function createDefaultServerForm(targetEnvironment) {
  return {
    nodeId: "",
    targetEnvironment,
    serverType: "TOMCAT",
    managementMode: "TOMCAT_WAR_UPLOAD",
    managementEndpoint: "http://127.0.0.1:18080/manager/text",
    applicationPath: "/orders",
    credentialAlias: "",
    scriptProfileId: "liberty-war-deploy",
    scriptParameters: [
      { id: "serverName", name: "serverName", value: "defaultServer" },
      { id: "applicationName", name: "applicationName", value: "orders" },
      { id: "artifactPath", name: "artifactPath", value: "\\\\jenkins\\share\\orders\\latest\\orders.war" },
    ],
    enabled: true,
  };
}

/**
 * @param {ReleaseServer} server
 * @returns {ReleaseServerForm}
 */
function createServerFormFromServer(server) {
  return {
    nodeId: server.nodeId,
    targetEnvironment: server.targetEnvironment,
    serverType: server.serverType,
    managementMode: server.serverType === "LIBERTY" ? "LIBERTY_SCRIPT_PROFILE" : server.managementMode,
    managementEndpoint: server.managementEndpoint,
    applicationPath: server.applicationPath ?? "",
    credentialAlias: server.credentialAlias ?? "",
    scriptProfileId: server.scriptProfile?.profileId ?? "liberty-war-deploy",
    scriptParameters:
      server.scriptProfile?.parameters.map((parameter, index) => ({
        id: `${parameter.name}-${index}`,
        name: parameter.name,
        value: parameter.value,
      })) ?? createDefaultServerForm(server.targetEnvironment).scriptParameters,
    enabled: server.enabled,
  };
}

/**
 * @param {string} serverType
 * @returns {string[]}
 */
function managementModeOptions(serverType) {
  if (serverType === "LIBERTY") {
    return MANAGEMENT_MODE_OPTIONS_BY_SERVER_TYPE.LIBERTY;
  }
  return MANAGEMENT_MODE_OPTIONS_BY_SERVER_TYPE.TOMCAT;
}

/**
 * @param {ReleaseServer} server
 * @param {string} name
 */
function scriptParameterValue(server, name) {
  return server.scriptProfile?.parameters.find((parameter) => parameter.name === name)?.value ?? "";
}

/**
 * @param {ReleaseServer[]} servers
 */
function hasCompleteLibertySharedArtifactPaths(servers) {
  return servers.every((server) => {
    if (server.serverType !== "LIBERTY" || server.managementMode !== "LIBERTY_SCRIPT_PROFILE") {
      return true;
    }
    return isSharedArtifactPath(scriptParameterValue(server, "artifactPath"));
  });
}

/**
 * @param {ReleaseServer[]} servers
 */
function libertySharedArtifactPaths(servers) {
  return servers
    .map((server) => scriptParameterValue(server, "artifactPath"))
    .filter((artifactPath) => isSharedArtifactPath(artifactPath));
}

/**
 * @param {string} value
 */
function isHttpUrl(value) {
  return /^https?:\/\//i.test(value);
}

/**
 * @param {string} value
 */
function isSharedArtifactPath(value) {
  return value.trim().startsWith("\\\\");
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
 *   artifacts: ReleaseArtifact[],
 *   artifactsQuery: ReturnType<typeof useReleaseArtifacts>,
 *   servers: ReleaseServer[],
 *   serversQuery: ReturnType<typeof useReleaseServers>,
 *   targetEnvironment: string,
 * }} props
 */
function InventoryPanel({
  applications,
  applicationsQuery,
  artifacts,
  artifactsQuery,
  servers,
  serversQuery,
  targetEnvironment,
}) {
  const queryState = queryFeedback([applicationsQuery, artifactsQuery, serversQuery], "发布库存读取失败");
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
          <section aria-label="制品库存" className={styles.inventorySection}>
            <span className={styles.sectionLabel}>制品 / {targetEnvironment}</span>
            {artifacts.length === 0 ? (
              <p>暂无制品</p>
            ) : (
              artifacts.slice(0, 3).map((artifact) => (
                <div className={styles.inventoryRow} key={artifact.artifactId}>
                  <strong>{artifact.artifactId}</strong>
                  <span>{artifact.checksum}</span>
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
