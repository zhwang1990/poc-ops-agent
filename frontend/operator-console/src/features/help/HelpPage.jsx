import { CircleHelp, Search, ShieldCheck } from "lucide-react";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";

import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import { Badge } from "../../components/primitives/Badge.jsx";
import {
  getHelpSectionById,
  helpSections,
  popularHelpKeywords,
  searchHelpContent,
} from "./help-content.js";
import styles from "./HelpPage.module.css";

const firstSectionId = helpSections[0]?.sectionId ?? "";
export function HelpPage() {
  const [activeSectionId, setActiveSectionId] = useState(firstSectionId);
  const [query, setQuery] = useState("");
  const activeSection = getHelpSectionById(activeSectionId) ?? helpSections[0] ?? null;
  const trimmedQuery = query.trim();
  const searchResults = useMemo(() => searchHelpContent(trimmedQuery), [trimmedQuery]);

  /**
   * @param {import("./help-content.js").HelpSearchResult} result
   */
  function openSearchResult(result) {
    setActiveSectionId(result.sectionId);
    setQuery("");
    window.requestAnimationFrame(() => {
      const element = document.getElementById(result.anchorId);
      if (typeof element?.scrollIntoView === "function") {
        element.scrollIntoView({
          block: "start",
          behavior: "smooth",
        });
      }
    });
  }

  /**
   * @param {string} keyword
   */
  function searchKeyword(keyword) {
    setQuery(keyword);
  }

  return (
    <WorkspacePageFrame className={styles.helpCanvas}>
      <WorkspaceStatusBar title="帮助" />

      <div className={styles.helpLayout}>
        <nav aria-label="帮助章节目录" className={styles.sectionNav}>
          <div className={styles.navHeader}>
            <CircleHelp aria-hidden="true" size={18} strokeWidth={2.4} />
            <div>
              <span>线上产品手册</span>
              <strong>帮助章节目录</strong>
            </div>
          </div>
          <div className={styles.navList}>
            {helpSections.map((section) => (
              <button
                aria-label={section.title}
                aria-current={section.sectionId === activeSection?.sectionId ? "page" : undefined}
                className={styles.navItem}
                key={section.sectionId}
                onClick={() => setActiveSectionId(section.sectionId)}
                type="button"
              >
                <span>{section.title}</span>
                <small>{section.module}</small>
              </button>
            ))}
          </div>
        </nav>

        <main aria-label="帮助正文" className={styles.manualPanel}>
          <section className={styles.searchPanel}>
            <label className={styles.searchBox}>
              <Search aria-hidden="true" size={18} strokeWidth={2.3} />
              <input
                aria-label="搜索场景、页面、错误或权限问题"
                onChange={(event) => setQuery(event.target.value)}
                placeholder="搜索场景、页面、错误或权限问题"
                type="search"
                value={query}
              />
            </label>
            <div aria-label="热门关键词" className={styles.keywordRow}>
              {popularHelpKeywords.map((keyword) => (
                <button key={keyword} onClick={() => searchKeyword(keyword)} type="button">
                  {keyword}
                </button>
              ))}
            </div>
          </section>

          <section aria-label="帮助正文" className={styles.manualBody}>
            {trimmedQuery ? (
              <SearchResults
                onOpenResult={openSearchResult}
                query={trimmedQuery}
                results={searchResults}
              />
            ) : (
              <SectionContent section={activeSection} />
            )}
          </section>
        </main>

        <aside aria-label="本章速览" className={styles.overviewPanel}>
          <div className={styles.overviewHeader}>
            <ShieldCheck aria-hidden="true" size={18} strokeWidth={2.4} />
            <h2>本章速览</h2>
          </div>
          {activeSection ? (
            <>
              <QuickFact label="适用角色" values={activeSection.roleHints} />
              <QuickFact label="相关页面" values={activeSection.relatedPages} />
              <QuickFact label="相关模块" values={[activeSection.module]} />
              <div className={styles.boundaryBox}>
                <span>边界</span>
                <p>{activeSection.boundary}</p>
              </div>
            </>
          ) : null}
        </aside>
      </div>
    </WorkspacePageFrame>
  );
}

/**
 * @param {{ section: import("./help-content.js").HelpSection | null }} props
 */
function SectionContent({ section }) {
  if (!section) {
    return null;
  }

  const shouldShowPromotedScenario = !section.primaryLink;

  return (
    <article className={styles.sectionContent} id={`help-section-${section.sectionId}`}>
      <header className={styles.sectionHero}>
        <Badge tone="info">{section.module}</Badge>
        <h2>{section.title}</h2>
        <p>{section.summary}</p>
        <div className={styles.safetyBanner}>
          <ShieldCheck aria-hidden="true" size={18} strokeWidth={2.4} />
          <span>只读诊断，不执行生产写操作。</span>
        </div>
        {section.primaryLink ? (
          <Link className={styles.primaryLink} to={section.primaryLink.to}>
            {section.primaryLink.label}
          </Link>
        ) : null}
        {shouldShowPromotedScenario ? (
          <p className={styles.promotedScenario}>
            推荐场景：
            <span>用 Agent 排查服务错误</span>
          </p>
        ) : null}
      </header>

      {section.scenarios.length > 0 ? (
        <div className={styles.scenarioList}>
          {section.scenarios.map((scenario) => (
            <ScenarioCard key={scenario.id} scenario={scenario} />
          ))}
        </div>
      ) : null}

      <FaqList faqs={section.faqs} />
    </article>
  );
}

/**
 * @param {{ scenario: import("./help-content.js").HelpScenario }} props
 */
function ScenarioCard({ scenario }) {
  return (
    <article
      aria-label={scenario.title}
      className={styles.scenarioCard}
      id={`help-scenario-${scenario.id}`}
    >
      <header className={styles.scenarioHeader}>
        <span>{scenario.page}</span>
        <h3>{scenario.title}</h3>
        <p>{scenario.whenToUse}</p>
      </header>
      <div className={styles.scenarioMeta}>
        {scenario.roles.map((role) => (
          <Badge key={role} tone="neutral">
            {role}
          </Badge>
        ))}
      </div>
      <HelpList label="操作步骤" values={scenario.steps} />
      <HelpList label="结果怎么看" values={scenario.howToReadResult} />
      <HelpList label="失败或拒绝时怎么办" values={scenario.failureHandling} />
      <HelpList label="安全边界" values={scenario.safetyNotes} />
    </article>
  );
}

/**
 * @param {{
 *   onOpenResult: (result: import("./help-content.js").HelpSearchResult) => void,
 *   query: string,
 *   results: import("./help-content.js").HelpSearchResult[],
 * }} props
 */
function SearchResults({ onOpenResult, query, results }) {
  if (results.length === 0) {
    return (
      <section className={styles.emptyState} role="status">
        <h2>未找到匹配手册内容</h2>
        <p>请更换关键词，或从热门关键词进入对应章节。</p>
      </section>
    );
  }

  return (
    <section className={styles.searchResults}>
      <h2>{`搜索结果：${query}`}</h2>
      <ul aria-label="帮助搜索结果" className={styles.resultList}>
        {results.map((result) => (
          <li key={`${result.type}-${result.anchorId}`}>
            <button aria-label={result.title} onClick={() => onOpenResult(result)} type="button">
              <span>{result.sectionTitle}</span>
              <strong>{result.title}</strong>
              <small>{result.summary}</small>
            </button>
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * @param {{ label: string, values: string[] }} props
 */
function HelpList({ label, values }) {
  if (values.length === 0) {
    return null;
  }

  return (
    <section className={styles.helpList}>
      <h4>{label}</h4>
      <ol>
        {values.map((value) => (
          <li key={value}>{value}</li>
        ))}
      </ol>
    </section>
  );
}

/**
 * @param {{ faqs: import("./help-content.js").HelpFaq[] }} props
 */
function FaqList({ faqs }) {
  if (faqs.length === 0) {
    return null;
  }

  return (
    <section className={styles.faqList}>
      <h2>常见问题</h2>
      {faqs.map((faq) => (
        <article aria-label={faq.title} className={styles.faqItem} id={`help-faq-${faq.id}`} key={faq.id}>
          <h3>{faq.title}</h3>
          <p>{faq.summary}</p>
          <p>{faq.answer}</p>
        </article>
      ))}
    </section>
  );
}

/**
 * @param {{ label: string, values: string[] }} props
 */
function QuickFact({ label, values }) {
  return (
    <section className={styles.quickFact}>
      <span>{label}</span>
      <div>
        {values.map((value) => (
          <Badge key={value} tone="neutral">
            {value}
          </Badge>
        ))}
      </div>
    </section>
  );
}
