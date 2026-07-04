import styles from "./WorkspacePageFrame.module.css";
import { useWorkspaceLayout } from "./WorkspaceLayoutContext.jsx";

/**
 * @typedef {object} WorkspacePageFrameProps
 * @property {import("react").ReactNode} children
 * @property {string} [className]
 */

/**
 * @param {WorkspacePageFrameProps} props
 */
export function WorkspacePageFrame({ children, className = "" }) {
  const { isWorkspaceExpanded } = useWorkspaceLayout();

  return (
    <div
      className={`${styles.workspaceFrame} ${
        isWorkspaceExpanded ? styles.workspaceFrameExpanded : ""
      } ${className}`.trim()}
      data-workspace-expanded={isWorkspaceExpanded ? "true" : "false"}
      data-workspace-frame="true"
    >
      {children}
    </div>
  );
}
