import { readFileSync } from "node:fs";

import { render, screen } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import { describe, expect, test } from "vitest";

import { AppProviders } from "../../app/providers.jsx";
import { RagQuestionPage } from "./RagQuestionPage.jsx";

const ragQuestionCss = readFileSync(
  "src/features/rag-question/RagQuestionPage.module.css",
  "utf8",
);

describe("RagQuestionPage", () => {
  test("renders RAG input through the shared natural-language dialog", () => {
    render(
      <AppProviders Router={MemoryRouter} routerProps={{ initialEntries: ["/rag"] }}>
        <RagQuestionPage />
      </AppProviders>,
    );

    expect(screen.getByRole("search", { name: "RAG 问题输入区" })).toBeInTheDocument();
    expect(screen.getByRole("textbox", { name: "RAG 问题" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "提交 RAG 问题" })).toBeDisabled();
  });

  test("keeps the question title compact without the range subtitle", () => {
    render(
      <AppProviders Router={MemoryRouter} routerProps={{ initialEntries: ["/rag"] }}>
        <RagQuestionPage />
      </AppProviders>,
    );

    const ragHeadRule = ragQuestionCss.match(/[.]ragHead\s*[{][^}]+[}]/u)?.[0] ?? "";
    const ragHeadTitleRule = ragQuestionCss.match(/[.]ragHead h2\s*[{][^}]+[}]/u)?.[0] ?? "";

    expect(screen.getByRole("heading", { name: "知识库问答" })).toBeInTheDocument();
    expect(
      screen.queryByText("当前范围：Runbook、ADR、工单复盘、发布记录"),
    ).not.toBeInTheDocument();
    expect(ragHeadRule).toContain("padding: 10px 22px");
    expect(ragHeadRule).toContain("min-height: 48px");
    expect(ragHeadTitleRule).toContain("font-size: 18px");
  });
});
