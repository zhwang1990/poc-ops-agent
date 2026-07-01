import { describe, expect, test } from "vitest";

import {
  getHelpSectionById,
  helpSections,
  popularHelpKeywords,
  searchHelpContent,
} from "./help-content.js";

describe("help manual content", () => {
  test("keeps the required section id and title order", () => {
    expect(helpSections.map(({ sectionId, title }) => [sectionId, title])).toEqual([
      ["quick-start", "快速开始"],
      ["agent-workspace", "Agent 工作区"],
      ["rag-question", "RAG 问答"],
      ["sql-workbench", "SQL 工作区"],
      ["model-settings", "模型设置"],
      ["release-center", "发布中心"],
      ["skill-registry", "Skill 注册中心"],
      ["workflow-events", "工作流事件"],
      ["audit-records", "审计记录"],
      ["permissions-security", "权限与安全边界"],
      ["faq", "常见问题"],
    ]);
  });

  test("provides required guidance fields for every section", () => {
    for (const section of helpSections) {
      expect(section.summary).toEqual(expect.any(String));
      expect(section.summary.length).toBeGreaterThan(0);
      expect(section.boundary).toEqual(expect.any(String));
      expect(section.boundary.length).toBeGreaterThan(0);
      expect(section.keywords).toEqual(expect.arrayContaining([expect.any(String)]));
      expect(section.roleHints).toEqual(expect.arrayContaining([expect.any(String)]));
    }
  });

  test("finds a section by id and returns null for missing ids", () => {
    expect(getHelpSectionById("agent-workspace")?.title).toBe("Agent 工作区");
    expect(getHelpSectionById("missing")).toBeNull();
  });

  test("returns permission refusal scenarios before sections and faqs", () => {
    const results = searchHelpContent("权限拒绝");

    expect(results[0]).toMatchObject({
      type: "scenario",
      sectionId: "permissions-security",
      title: "解释按钮不可用",
    });
    expect(results.some((result) => result.type === "faq")).toBe(true);
  });

  test("finds SQL validation guidance without generated results", () => {
    const results = searchHelpContent("SQL 校验");

    expect(results.some((result) => result.title === "校验 SQL 是否只读")).toBe(true);
    expect(results.every((result) => result.type !== "generated")).toBe(true);
  });

  test("returns an empty result list for blank and unmatched queries", () => {
    expect(searchHelpContent("")).toEqual([]);
    expect(searchHelpContent("不存在的帮助关键字")).toEqual([]);
  });

  test("exports popular help keywords", () => {
    expect(popularHelpKeywords).toEqual(
      expect.arrayContaining(["Agent", "权限拒绝", "SQL 校验", "发布失败"]),
    );
  });
});
