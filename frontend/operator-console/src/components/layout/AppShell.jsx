import {
  AudioLines,
  Bot,
  Boxes,
  CircleDot,
  CircleHelp,
  DatabaseZap,
  FileClock,
  Network,
  Rocket,
  SearchCheck,
  SlidersHorizontal,
  Workflow,
} from "lucide-react";
import { Link, useLocation } from "react-router-dom";

import styles from "./AppShell.module.css";

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
  { icon: SlidersHorizontal, label: "模型设置", tone: "model", to: "/model-settings" },
  { icon: Rocket, label: "发布中心", tone: "release", to: "/release" },
  { icon: Boxes, label: "Skill 注册中心", tone: "warning", to: "/skills" },
  { icon: AudioLines, label: "会议录制纪要", tone: "meeting", to: "/meeting-notes" },
  { icon: DatabaseZap, label: "AS400对象管理", tone: "as400", to: "/as400-ddl" },
  { icon: Workflow, label: "工作流事件", tone: "green", to: "/workflow-events" },
  { icon: FileClock, label: "审计记录", tone: "slate", to: "/audit" },
  { icon: CircleHelp, label: "帮助", tone: "quick", to: "/help" },
];

/**
 * @param {(typeof navigation)[number]} item
 * @param {ReturnType<typeof useLocation>} location
 */
function isNavigationItemActive(item, location) {
  return location.pathname === item.to;
}

/**
 * @typedef {object} AppShellProps
 * @property {import("react").ReactNode} children
 * @property {unknown} [session]
 */

/**
 * @param {AppShellProps} props
 */
export function AppShell({ children }) {
  const location = useLocation();

  return (
    <div className={styles.shell}>
      <aside className={styles.sidebar}>
        <nav aria-label="主导航" className={styles.nav}>
          {navigation.map((item) => {
            const Icon = item.icon;
            const isActive = isNavigationItemActive(item, location);

            return (
              <Link
                className={`${styles.navLink} ${styles[`navTone${item.tone}`]} ${
                  isActive ? styles.active : ""
                }`}
                key={item.to}
                to={item.to}
              >
                <span aria-hidden="true" className={styles.navIcon}>
                  <span className={styles.navSymbol} />
                  <Icon className={styles.navGlyph} strokeWidth={2.25} />
                </span>
                <span className={styles.navLabel}>{item.label}</span>
                <span aria-hidden="true" className={styles.navPulse} />
              </Link>
            );
          })}
        </nav>

        <div className={styles.sidebarFooter}>
          <div aria-hidden="true" className={styles.sidebarPreview}>
            <span className={styles.sidebarPreviewOrbit}>
              {navigation.map((item) => {
                const isPreviewActive = isNavigationItemActive(item, location);

                return (
                  <span
                    className={`${styles.sidebarPreviewMenuNode} ${
                      styles[`navTone${item.tone}`]
                    } ${isPreviewActive ? styles.sidebarPreviewMenuNodeActive : ""}`}
                    key={`preview-${item.to}`}
                  />
                );
              })}
            </span>
            <span className={styles.sidebarPreviewCore} />
          </div>
        </div>
      </aside>
      <main className={styles.content}>{children}</main>
    </div>
  );
}
