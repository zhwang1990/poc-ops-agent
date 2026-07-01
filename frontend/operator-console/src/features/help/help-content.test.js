import { describe, expect, test } from "vitest";

import {
  getHelpSectionById,
  helpSections,
  popularHelpKeywords,
  searchHelpContent,
} from "./help-content.js";

const allowedSearchResultTypes = ["faq", "scenario", "section"];

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

  test("keeps scenario and faq records complete with stable unique anchors", () => {
    const anchorIds = new Set();

    for (const section of helpSections) {
      const sectionAnchorId = `help-section-${section.sectionId}`;
      expect(anchorIds.has(sectionAnchorId)).toBe(false);
      anchorIds.add(sectionAnchorId);

      for (const scenario of section.scenarios) {
        expect(scenario.id).toMatch(/^[a-z0-9]+(?:-[a-z0-9]+)*$/u);
        expect(scenario.title.length).toBeGreaterThan(0);
        expect(scenario.page.length).toBeGreaterThan(0);
        expect(scenario.whenToUse.length).toBeGreaterThan(0);
        expect(scenario.roles).toEqual(expect.arrayContaining([expect.any(String)]));
        expect(scenario.prerequisites).toEqual(expect.arrayContaining([expect.any(String)]));
        expect(scenario.steps).toEqual(expect.arrayContaining([expect.any(String)]));
        expect(scenario.howToReadResult).toEqual(expect.arrayContaining([expect.any(String)]));
        expect(scenario.failureHandling).toEqual(expect.arrayContaining([expect.any(String)]));
        expect(scenario.safetyNotes).toEqual(expect.arrayContaining([expect.any(String)]));
        expect(scenario.keywords).toEqual(expect.arrayContaining([expect.any(String)]));

        const scenarioAnchorId = `help-scenario-${scenario.id}`;
        expect(anchorIds.has(scenarioAnchorId)).toBe(false);
        anchorIds.add(scenarioAnchorId);
      }

      for (const faq of section.faqs) {
        expect(faq.id).toMatch(/^[a-z0-9]+(?:-[a-z0-9]+)*$/u);
        expect(faq.title.length).toBeGreaterThan(0);
        expect(faq.summary.length).toBeGreaterThan(0);
        expect(faq.answer.length).toBeGreaterThan(0);
        expect(faq.keywords).toEqual(expect.arrayContaining([expect.any(String)]));

        const faqAnchorId = `help-faq-${faq.id}`;
        expect(anchorIds.has(faqAnchorId)).toBe(false);
        anchorIds.add(faqAnchorId);
      }
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
      anchorId: "help-scenario-explain-disabled-button",
    });
    expect(results.some((result) => result.type === "faq")).toBe(true);
  });

  test("finds SQL validation guidance without generated result types", () => {
    const results = searchHelpContent("SQL 校验");
    const resultTypes = new Set(results.map((result) => result.type));

    expect(results.some((result) => result.title === "校验 SQL 是否只读")).toBe(true);
    expect([...resultTypes].every((type) => allowedSearchResultTypes.includes(type))).toBe(true);
  });

  test("returns DOM-ready anchor ids for section, scenario, and faq results", () => {
    const sectionResults = searchHelpContent("SQL 工作区").filter((result) => result.type === "section");
    const scenarioResults = searchHelpContent("SQL 校验").filter((result) => result.type === "scenario");
    const faqResults = searchHelpContent("为什么模型不能直接执行操作").filter(
      (result) => result.type === "faq",
    );

    expect(sectionResults).toEqual(
      expect.arrayContaining([expect.objectContaining({ anchorId: "help-section-sql-workbench" })]),
    );
    expect(scenarioResults).toEqual(
      expect.arrayContaining([expect.objectContaining({ anchorId: "help-scenario-validate-read-only-sql" })]),
    );
    expect(faqResults.length).toBeGreaterThan(0);
    expect(faqResults.every((result) => result.anchorId.startsWith("help-faq-"))).toBe(true);
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
