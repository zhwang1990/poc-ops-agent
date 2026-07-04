import {
  Activity,
  LogOut,
  Maximize2,
  Minimize2,
  PanelLeftClose,
  PanelLeftOpen,
  TimerReset,
  UserRound,
} from "lucide-react";
import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";

import { getBrowserSession, logout } from "../../api/auth-api.js";
import styles from "./WorkspaceStatusBar.module.css";
import { useWorkspaceLayout } from "./WorkspaceLayoutContext.jsx";

const OFF_WORK_HOUR = 18;
const WORKDAY_START_HOUR = 9;

/**
 * @typedef {object} WorkspaceStatusBarProps
 * @property {string} title
 */

/**
 * @param {WorkspaceStatusBarProps} props
 */
export function WorkspaceStatusBar({
  title,
}) {
  const {
    isWorkspaceExpanded,
    showMenuLabels,
    toggleMenuLabels,
    toggleWorkspaceExpanded,
  } = useWorkspaceLayout();
  const navigate = useNavigate();
  const queryClient = useQueryClient();
  const sessionQuery = useQuery({
    queryKey: ["browser-session"],
    queryFn: getBrowserSession,
  });
  const logoutMutation = useMutation({
    mutationFn: logout,
    onSuccess: () => {
      queryClient.removeQueries({ queryKey: ["browser-session"] });
      navigate("/login", { replace: true });
    },
  });
  const session = sessionQuery.data;
  const isAuthenticated = session?.authenticated === true;
  const username = isAuthenticated ? (session.username ?? "未登录") : "未登录";
  const operatorId = isAuthenticated ? (session.subject ?? "unavailable") : "unavailable";

  return (
    <section aria-label="当前工作台" className={styles.appCapsule}>
      <div className={styles.brandPlate}>
        <span aria-hidden="true" className={styles.logoMark}>
          <span>EA</span>
        </span>
        <span className={styles.brandCopy}>
          <span className={styles.brandName}>企业智能 Agent</span>
          <h1 className={styles.capsuleHeading}>{title}</h1>
        </span>
      </div>
      <section aria-label="工作台状态" className={styles.workspaceContext}>
        <span aria-hidden="true" className={styles.contextIcon}>
          <Activity size={16} strokeWidth={2.4} />
        </span>
        <span className={styles.contextCopy}>
          <span>运行状态</span>
          <strong>受控操作会话</strong>
        </span>
        <span className={styles.statusPills}>
          <span className={styles.statusPill}>P1 只读控制台</span>
          <span className={`${styles.statusPill} ${styles.statusPillLive}`}>会话在线</span>
        </span>
        <span aria-hidden="true" className={styles.signalRail}>
          <i />
          <i />
          <i />
        </span>
      </section>
      <OperatorDock
        isLogoutPending={logoutMutation.isPending}
        onLogout={() => logoutMutation.mutate()}
        operatorId={operatorId}
        username={username}
      />
      <WorkspaceToolbar
        isWorkspaceExpanded={isWorkspaceExpanded}
        onToggleMenuLabels={toggleMenuLabels}
        onToggleWorkspace={toggleWorkspaceExpanded}
        showMenuLabels={showMenuLabels}
      />
    </section>
  );
}

/**
 * @param {{
 *   isWorkspaceExpanded: boolean,
 *   onToggleMenuLabels: () => void,
 *   onToggleWorkspace: () => void,
 *   showMenuLabels: boolean,
 * }} props
 */
function WorkspaceToolbar({
  isWorkspaceExpanded,
  onToggleMenuLabels,
  onToggleWorkspace,
  showMenuLabels,
}) {
  const menuLabel = showMenuLabels ? "关闭菜单名称" : "展示菜单名称";
  const workspaceLabel = isWorkspaceExpanded ? "退出展开" : "展开工作区";

  return (
    <section aria-label="工作区工具栏" className={styles.workspaceToolbar} role="toolbar">
      <button
        aria-label={menuLabel}
        aria-pressed={showMenuLabels}
        className={`${styles.toolbarIconToggle} ${
          showMenuLabels ? styles.toolbarIconTogglePressed : ""
        }`}
        onClick={onToggleMenuLabels}
        title={menuLabel}
        type="button"
      >
        <span aria-hidden="true" className={styles.toolbarButtonIcon}>
          {showMenuLabels ? (
            <PanelLeftClose size={15} strokeWidth={2.4} />
          ) : (
            <PanelLeftOpen size={15} strokeWidth={2.4} />
          )}
        </span>
      </button>
      <button
        aria-label={workspaceLabel}
        aria-pressed={isWorkspaceExpanded}
        className={`${styles.toolbarButton} ${
          isWorkspaceExpanded ? styles.toolbarButtonPressed : ""
        }`}
        onClick={onToggleWorkspace}
        title={workspaceLabel}
        type="button"
      >
        <span aria-hidden="true" className={styles.toolbarButtonIcon}>
          {isWorkspaceExpanded ? (
            <Minimize2 size={15} strokeWidth={2.4} />
          ) : (
            <Maximize2 size={15} strokeWidth={2.4} />
          )}
        </span>
      </button>
    </section>
  );
}

/**
 * @param {{
 *   isLogoutPending: boolean,
 *   onLogout: () => void,
 *   operatorId: string,
 *   username: string,
 * }} props
 */
function OperatorDock({ isLogoutPending, onLogout, operatorId, username }) {
  const operatorIdLabel = `ID ${operatorId}`;

  return (
    <section aria-label="当前登录人" className={styles.operatorDock}>
      <WorkdayCountdown />
      <div className={styles.operatorProfile} data-operator-profile="">
        <span aria-hidden="true" className={styles.operatorAvatar}>
          <UserRound size={17} strokeWidth={2.4} />
        </span>
        <span className={styles.operatorIdentity}>
          <strong title={username}>{username}</strong>
          <small aria-label={operatorIdLabel} title={operatorIdLabel}>
            {operatorIdLabel}
          </small>
        </span>
      </div>
      <button
        aria-label="登出当前账号"
        className={styles.logoutButton}
        disabled={isLogoutPending}
        onClick={onLogout}
        type="button"
      >
        <span aria-hidden="true" className={styles.logoutIconBadge}>
          <LogOut size={14} strokeWidth={2.6} />
        </span>
        <span>登出</span>
      </button>
    </section>
  );
}

/**
 * @param {Date} now
 */
function getOffWorkCountdown(now) {
  const start = new Date(now);
  start.setHours(WORKDAY_START_HOUR, 0, 0, 0);

  const target = new Date(now);
  target.setHours(OFF_WORK_HOUR, 0, 0, 0);

  const remainingMs = target.getTime() - now.getTime();
  const workdayMs = target.getTime() - start.getTime();
  const elapsedMs = Math.min(Math.max(now.getTime() - start.getTime(), 0), workdayMs);
  const progress = Math.round((elapsedMs / workdayMs) * 100);

  if (remainingMs <= 0) {
    return {
      label: "下班倒计时",
      meta: "今日已收工",
      progress: 100,
      timeText: "00:00:00",
    };
  }

  const hours = Math.floor(remainingMs / 3_600_000);
  const minutes = Math.floor((remainingMs % 3_600_000) / 60_000);
  const seconds = Math.floor((remainingMs % 60_000) / 1_000);

  return {
    label: "下班倒计时",
    meta: "18:00 下班",
    progress,
    timeText: [hours, minutes, seconds].map((part) => String(part).padStart(2, "0")).join(":"),
  };
}

/**
 * @param {{compact?: boolean}} props
 */
export function WorkdayCountdown({ compact = false }) {
  const [now, setNow] = useState(() => new Date());
  const countdown = getOffWorkCountdown(now);

  useEffect(() => {
    const timer = window.setInterval(() => setNow(new Date()), 1_000);

    return () => window.clearInterval(timer);
  }, []);

  return (
    <div
      aria-label={`下班倒计时：${countdown.timeText}`}
      className={`${styles.workdayCountdown} ${compact ? styles.workdayCountdownCompact : ""}`}
      data-creative-timer="workday-countdown"
      role="timer"
    >
      <span aria-hidden="true" className={styles.countdownGlyph}>
        <TimerReset size={14} strokeWidth={2.5} />
      </span>
      <span className={styles.countdownContent}>
        <span>{countdown.label}</span>
        <strong>{countdown.timeText}</strong>
      </span>
    </div>
  );
}
