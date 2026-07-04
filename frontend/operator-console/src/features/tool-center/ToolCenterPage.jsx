import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";

export function ToolCenterPage() {
  return (
    <WorkspacePageFrame>
      <WorkspaceStatusBar title="工具中心" />
      <main aria-label="工具中心工作区">
        <h2>工具目录</h2>
      </main>
    </WorkspacePageFrame>
  );
}
