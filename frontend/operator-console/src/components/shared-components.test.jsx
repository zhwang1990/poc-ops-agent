import { readFileSync } from "node:fs";

import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it } from "vitest";

import { DisabledFeature } from "./feedback/DisabledFeature.jsx";
import { FeedbackState } from "./feedback/FeedbackState.jsx";
import { Badge } from "./primitives/Badge.jsx";
import { Button } from "./primitives/Button.jsx";
import { Card } from "./primitives/Card.jsx";
import { Dialog } from "./primitives/Dialog.jsx";

const dialogCss = readFileSync("src/components/primitives/Dialog.module.css", "utf8");

describe("shared primitives", () => {
  it("applies the button variant and forwards native button attributes", () => {
    render(
      <Button
        aria-describedby="deployment-note"
        className="deployment-action"
        data-testid="deployment-button"
        disabled
        name="deployment"
        variant="danger"
      >
        部署
      </Button>,
    );

    const button = screen.getByRole("button", { name: "部署" });
    expect(button).toBeDisabled();
    expect(button).toHaveAttribute("aria-describedby", "deployment-note");
    expect(button).toHaveAttribute("name", "deployment");
    expect(button).toHaveClass("deployment-action");
    expect(button.className).toContain("danger");
  });

  it("forwards native attributes and custom classes from card and badge", () => {
    render(
      <Card aria-labelledby="summary-title" className="summary-card" id="summary">
        <h2 id="summary-title">摘要</h2>
        <Badge className="readonly-badge" data-testid="risk-badge" tone="success">
          只读
        </Badge>
      </Card>,
    );

    expect(screen.getByRole("region", { name: "摘要" })).toHaveClass(
      "card",
      "summary-card",
    );
    expect(screen.getByTestId("risk-badge")).toHaveClass(
      "badge",
      "badge--success",
      "readonly-badge",
    );
  });

  it("renders the shared dialog standard and keeps backdrop clicks from dismissing it", async () => {
    const user = userEvent.setup();
    /** @type {string[]} */
    const closeCalls = [];

    const { rerender } = render(
      <Dialog
        closeLabel="关闭标准弹窗"
        description="统一遮罩、标题、说明、内容滚动和关闭交互。"
        eyebrow="M09 对话框标准"
        onClose={() => closeCalls.push("close")}
        open={false}
        size="wide"
        title="公共对话框"
      >
        <p>弹窗内容</p>
      </Dialog>,
    );

    expect(screen.queryByRole("dialog", { name: "公共对话框" })).not.toBeInTheDocument();

    rerender(
      <Dialog
        closeLabel="关闭标准弹窗"
        description="统一遮罩、标题、说明、内容滚动和关闭交互。"
        eyebrow="M09 对话框标准"
        icon={<span data-testid="dialog-title-icon">D</span>}
        onClose={() => closeCalls.push("close")}
        open
        size="wide"
        title="公共对话框"
      >
        <p>弹窗内容</p>
      </Dialog>,
    );

    const dialog = screen.getByRole("dialog", { name: "公共对话框" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(dialog).toHaveAttribute("data-dialog-size", "wide");
    expect(dialog.className).toContain("dialogSurface");
    expect(dialog.parentElement).toHaveAttribute("data-dialog-backdrop", "");
    expect(screen.getByTestId("dialog-title-icon")).toBeInTheDocument();
    expect(screen.getByTestId("dialog-title-icon").closest("[data-dialog-title-icon]"))
      .toHaveAttribute("aria-hidden", "true");
    const backdrop = dialog.parentElement;
    if (!backdrop) {
      throw new Error("Dialog backdrop is missing");
    }
    expect(screen.getByText("M09 对话框标准")).toBeInTheDocument();
    expect(screen.getByText("统一遮罩、标题、说明、内容滚动和关闭交互。")).toBeInTheDocument();
    expect(screen.getByText("弹窗内容")).toBeInTheDocument();

    await user.keyboard("{Escape}");
    expect(closeCalls).toEqual(["close"]);

    await user.click(screen.getByRole("button", { name: "关闭标准弹窗" }));
    expect(closeCalls).toEqual(["close", "close"]);

    await user.click(backdrop);
    expect(closeCalls).toEqual(["close", "close"]);
  });

  it("keeps the dialog shell visually layered instead of a monotone panel", () => {
    const surfaceRule =
      dialogCss.match(/[.]dialogSurface\s*[{][^}]+[}]/u)?.[0] ?? "";
    const surfaceBeforeRule =
      dialogCss.match(/[.]dialogSurface::before\s*[{][^}]+[}]/u)?.[0] ?? "";
    const surfaceAfterRule =
      dialogCss.match(/[.]dialogSurface::after\s*[{][^}]+[}]/u)?.[0] ?? "";
    const headerRule =
      dialogCss.match(/[.]dialogHeader\s*[{][^}]+[}]/u)?.[0] ?? "";
    const titleClusterRule =
      dialogCss.match(/[.]dialogTitleCluster\s*[{][^}]+[}]/u)?.[0] ?? "";
    const titleIconRule =
      dialogCss.match(/[.]dialogTitleIcon\s*[{][^}]+[}]/u)?.[0] ?? "";
    const bodyRule =
      dialogCss.match(/[.]dialogBody\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(surfaceRule).toContain("radial-gradient(circle at 11% 4%");
    expect(surfaceRule).toContain("radial-gradient(circle at 92% 0%");
    expect(surfaceBeforeRule).toContain("radial-gradient(ellipse at 18% 18%");
    expect(surfaceBeforeRule).toContain("mask-image: linear-gradient");
    expect(surfaceAfterRule).toContain("height: 3px");
    expect(surfaceAfterRule).toContain("var(--dialog-accent-blue)");
    expect(headerRule).toContain("radial-gradient(circle at 0% 0%");
    expect(titleClusterRule).toContain("grid-template-columns: 38px minmax(0, 1fr)");
    expect(titleIconRule).toContain("width: 38px");
    expect(titleIconRule).toContain("height: 38px");
    expect(titleIconRule).toContain("var(--dialog-accent-blue)");
    expect(bodyRule).toContain("radial-gradient(circle at 8% 0%");
    expect(bodyRule).not.toContain("background: oklch");
  });
});

describe("shared feedback", () => {
  it("explains why a feature is disabled and renders a disabled action", () => {
    render(
      <DisabledFeature
        actionLabel="开始执行"
        reason="当前阶段仅允许只读诊断。"
        title="生产执行"
      />,
    );

    expect(screen.getByText("当前阶段仅允许只读诊断。")).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "开始执行" }),
    ).toBeDisabled();
  });

  it("uses status semantics for non-error feedback", () => {
    render(<FeedbackState state="loading" title="正在加载" />);

    expect(screen.getByRole("status", { name: "正在加载" })).toHaveAttribute(
      "aria-live",
      "polite",
    );
  });

  it("uses alert semantics for error feedback", () => {
    render(
      <FeedbackState
        message="请稍后重试。"
        state="error"
        title="加载失败"
      />,
    );

    expect(screen.getByRole("alert", { name: "加载失败" })).toHaveAttribute(
      "aria-live",
      "assertive",
    );
  });
});
