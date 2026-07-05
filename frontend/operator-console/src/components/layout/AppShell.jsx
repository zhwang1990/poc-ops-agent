import {
  AudioLines,
  Bot,
  Boxes,
  CircleDot,
  CircleHelp,
  FileClock,
  Network,
  Rocket,
  Scale,
  SearchCheck,
  Settings2,
  SlidersHorizontal,
  Wrench,
  Workflow,
} from "lucide-react";
import { useMemo, useState } from "react";
import { NavLink } from "react-router-dom";

import styles from "./AppShell.module.css";
import { WorkspaceLayoutContext } from "./WorkspaceLayoutContext.jsx";

const navigation = [
  {
    icon: CircleDot,
    label: "总览",
    tone: "accent",
    to: "/overview",
  },
  { icon: Bot, label: "Agent 工作区", tone: "info", to: "/agent" },
  { icon: Network, label: "RAG 问答", tone: "teal", to: "/rag" },
  { icon: SearchCheck, label: "SQL 工作区", tone: "deep", to: "/sql" },
  { icon: Wrench, label: "工具中心", tone: "quick", to: "/tools" },
  { icon: Settings2, label: "API Caller 设置", tone: "quick", to: "/tools/api-caller-settings" },
  { icon: SlidersHorizontal, label: "模型设置", tone: "model", to: "/model-settings" },
  { icon: Rocket, label: "发布中心", tone: "release", to: "/release" },
  { icon: Boxes, label: "Skill 注册中心", tone: "warning", to: "/skills" },
  { icon: AudioLines, label: "会议录制纪要", tone: "meeting", to: "/meeting-notes" },
  { icon: Workflow, label: "工作流事件", tone: "green", to: "/workflow-events" },
  { icon: FileClock, label: "审计记录", tone: "slate", to: "/audit" },
  { icon: CircleHelp, label: "帮助", tone: "quick", to: "/help" },
];

/**
 * @typedef {object} AppShellProps
 * @property {import("react").ReactNode} children
 * @property {unknown} [session]
 */

/**
 * @param {AppShellProps} props
 */
export function AppShell({ children }) {
  const [showMenuLabels, setShowMenuLabels] = useState(true);
  const [isWorkspaceExpanded, setWorkspaceExpanded] = useState(false);
  const isIconOnly = !showMenuLabels || isWorkspaceExpanded;
  const layoutState = useMemo(
    () => ({
      showMenuLabels,
      setShowMenuLabels,
      isWorkspaceExpanded,
      setWorkspaceExpanded,
      toggleMenuLabels: () => setShowMenuLabels((current) => !current),
      toggleWorkspaceExpanded: () => setWorkspaceExpanded((current) => !current),
    }),
    [isWorkspaceExpanded, showMenuLabels],
  );

  return (
    <WorkspaceLayoutContext.Provider value={layoutState}>
      <div className={`${styles.shell} ${isIconOnly ? styles.iconOnlyShell : ""}`}>
        <aside className={styles.sidebar}>
          <nav aria-label="主导航" className={styles.nav}>
            {navigation.map((item) => {
              const Icon = item.icon;

              return (
                <NavLink
                  className={({ isActive }) =>
                    `${styles.navLink} ${styles[`navTone${item.tone}`]} ${isActive ? styles.active : ""}`
                  }
                  aria-label={item.label}
                  key={item.to}
                  title={isIconOnly ? item.label : undefined}
                  to={item.to}
                >
                  <span aria-hidden="true" className={styles.navIcon}>
                    <span className={styles.navSymbol} />
                    <Icon className={styles.navGlyph} strokeWidth={2} />
                  </span>
                  <span aria-hidden={isIconOnly} className={styles.navLabel} hidden={isIconOnly}>
                    {item.label}
                  </span>
                  <span aria-hidden="true" className={styles.navPulse} hidden={isIconOnly} />
                </NavLink>
              );
            })}
          </nav>
          <footer className={styles.sidebarFooter}>
            <NavLink
              aria-label="法律信息"
              className={({ isActive }) =>
                `${styles.legalLink} ${isActive ? styles.activeLegalLink : ""}`
              }
              title={isIconOnly ? "法律信息" : undefined}
              to="/third-party-licenses"
            >
              <span aria-hidden="true" className={styles.legalIcon}>
                <Scale size={17} strokeWidth={2.35} />
              </span>
              <span aria-hidden={isIconOnly} className={styles.legalLabel} hidden={isIconOnly}>
                法律信息
              </span>
            </NavLink>
          </footer>
        </aside>
        <main className={styles.content}>{children}</main>
      </div>
    </WorkspaceLayoutContext.Provider>
  );
}
