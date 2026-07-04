import { useMemo, useState } from "react";
import { Eye } from "lucide-react";

import { searchSkillCandidates } from "../../api/agent-api.js";
import { ApiError } from "../../api/client.js";
import { DataTable } from "../../components/data-display/DataTable.jsx";
import { StatusPill } from "../../components/data-display/StatusPill.jsx";
import { FeedbackState } from "../../components/feedback/FeedbackState.jsx";
import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import { Dialog } from "../../components/primitives/Dialog.jsx";
import { SearchBox } from "../../components/search/SearchBox.jsx";
import { useSkills } from "./use-skills.js";
import styles from "./SkillRegistryPage.module.css";

/** @typedef {import("../../schemas/skill-schemas.js").RegisteredSkill} RegisteredSkill */
/** @typedef {import("../../schemas/agent-schemas.js").SkillRouteCandidate} SkillRouteCandidate */
/** @typedef {import("../../components/search/SearchBox.jsx").SearchRequest} SearchRequest */
/** @typedef {{status: "idle" | "loading" | "success" | "error", query: string, candidates: SkillRouteCandidate[], error: unknown}} CandidateSearchState */

const PAGE_SIZE = 5;

const defaultCandidateCriteria = {
  skillId: null,
  category: null,
  maxRiskLevel: "READ_ONLY",
  requiredParameters: [],
  requiredTags: [],
  requestContextTags: [],
  publicationStatusRequired: "VALIDATED",
};

const categoryTerms = [
  {
    value: "APPLICATION_DIAGNOSTICS",
    terms: ["应用", "服务", "日志", "依赖", "app", "application", "service", "dependency", "log"],
  },
  {
    value: "INFRASTRUCTURE_DIAGNOSTICS",
    terms: [
      "基础设施",
      "节点",
      "主机",
      "服务器",
      "证书",
      "infra",
      "infrastructure",
      "node",
      "host",
      "certificate",
    ],
  },
  {
    value: "PLATFORM_OBSERVABILITY",
    terms: ["平台", "告警", "天气", "观测", "指标", "platform", "alert", "weather", "observability", "metric"],
  },
];

const tagTerms = [
  { tag: "log", terms: ["日志", "log"] },
  { tag: "summary", terms: ["摘要", "summary"] },
  { tag: "health", terms: ["健康", "health"] },
  { tag: "dependency", terms: ["依赖", "dependency"] },
  { tag: "weather", terms: ["天气", "weather"] },
  { tag: "alert", terms: ["告警", "alert"] },
  { tag: "certificate", terms: ["证书", "certificate"] },
  { tag: "expiry", terms: ["到期", "过期", "expiry", "expiration"] },
];

const filterOptions = [
  { label: "全部", value: "ALL" },
  { label: "INFRASTRUCTURE", value: "INFRASTRUCTURE_DIAGNOSTICS" },
  { label: "APPLICATION", value: "APPLICATION_DIAGNOSTICS" },
  { label: "OBSERVABILITY", value: "PLATFORM_OBSERVABILITY" },
  { label: "READ_ONLY", value: "READ_ONLY" },
  { label: "已签名", value: "VALIDATED" },
];

export function SkillRegistryPage() {
  const skills = useSkills();
  const [activeFilter, setActiveFilter] = useState("ALL");
  const [searchRequest, setSearchRequest] = useState(
    /** @type {SearchRequest} */ ({ mode: "conditions", query: "" }),
  );
  const [candidateSearch, setCandidateSearch] = useState(
    /** @type {CandidateSearchState} */ ({
      status: "idle",
      query: "",
      candidates: [],
      error: null,
    }),
  );
  const [page, setPage] = useState(1);
  const [detailSkill, setDetailSkill] = useState(/** @type {RegisteredSkill | null} */ (null));

  const filteredSkills = useMemo(() => {
    const catalog = skills.data?.skills ?? [];
    const candidateKeys =
      searchRequest.mode === "natural" && candidateSearch.status === "success"
        ? new Set(candidateSearch.candidates.map((candidate) => skillCatalogKey(candidate.skill)))
        : null;

    return catalog.filter((skill) => {
      const descriptor = skill.descriptor;
      const filterMatched =
        activeFilter === "ALL" ||
        descriptor.category === activeFilter ||
        descriptor.riskLevel === activeFilter ||
        skill.publicationStatus === activeFilter;
      if (!filterMatched) {
        return false;
      }

      if (candidateKeys) {
        return candidateKeys.has(skillCatalogKey(skill));
      }

      return matchesSkillSearch(skill, searchRequest);
    });
  }, [activeFilter, candidateSearch, searchRequest, skills.data?.skills]);

  /**
   * @param {SearchRequest} request
   */
  async function handleSkillSearch(request) {
    setSearchRequest(request);
    setPage(1);

    const query = request.query.trim();
    if (request.mode !== "natural" || query.length === 0) {
      setCandidateSearch({
        status: "idle",
        query: "",
        candidates: [],
        error: null,
      });
      return;
    }

    setCandidateSearch({
      status: "loading",
      query,
      candidates: [],
      error: null,
    });

    try {
      const response = await searchSkillCandidates(buildRoutingCriteriaFromNaturalLanguage(query));
      setCandidateSearch({
        status: "success",
        query,
        candidates: response.candidates,
        error: null,
      });
    } catch (error) {
      setCandidateSearch({
        status: "error",
        query,
        candidates: [],
        error,
      });
    }
  }

  return (
    <WorkspacePageFrame className={styles.registryCanvas}>
      <WorkspaceStatusBar title="Skill 注册中心" />

      <main className={styles.workspaceBody}>
        <section className={styles.filters} aria-label="Skill 条件匹配">
          <SearchBox
            ariaLabel="Skill 搜索"
            className={styles.skillSearch}
            conditionLabel="Skill 条件过滤"
            conditionOptions={filterOptions}
            inputLabel="搜索 Skill ID、描述、Owner、参数或标签"
            naturalPlaceholder="例如：我想检查节点健康状态"
            onConditionChange={(value) => {
              setActiveFilter(value);
              setPage(1);
            }}
            onSearch={(request) => {
              void handleSkillSearch(request);
            }}
            placeholder="Skill ID / 描述 / Owner / 参数 / 标签"
            selectedCondition={activeFilter}
          />
          <SkillCandidateSearchState state={candidateSearch} />
        </section>

        <section className={styles.registryTable} aria-label="内置 Skill 目录">
          <div className={styles.tableHeader}>
            <div>
              <h2>内置 Skill</h2>
              <p>
                {filteredSkills.length} 个匹配项，来源于 M03 已签名发布目录。
              </p>
            </div>
          </div>
          <SkillCatalogState
            page={page}
            query={skills}
            rows={filteredSkills}
            setPage={setPage}
            showDetail={setDetailSkill}
          />
        </section>
      </main>

      <SkillDetailDialog
        onClose={() => setDetailSkill(null)}
        skill={detailSkill}
      />
    </WorkspacePageFrame>
  );
}

/**
 * @param {{state: CandidateSearchState}} props
 */
function SkillCandidateSearchState({ state }) {
  if (state.status === "idle") {
    return null;
  }

  if (state.status === "loading") {
    return (
      <p className={styles.searchState} role="status">
        正在查询候选 Skill
      </p>
    );
  }

  if (state.status === "error") {
    const isForbidden = state.error instanceof ApiError && state.error.kind === "forbidden";
    const isContract = state.error instanceof ApiError && state.error.kind === "contract";
    return (
      <FeedbackState
        message={
          isForbidden
            ? "服务端策略拒绝查询候选 Skill。"
            : "候选 Skill 响应无法被操作台安全解析。"
        }
        state="error"
        title={
          isForbidden
            ? "候选查询被拒绝"
            : isContract
              ? "候选查询契约不兼容"
              : "候选查询失败"
        }
      />
    );
  }

  if (state.candidates.length === 0) {
    return (
      <FeedbackState
        message={`没有返回与“${state.query}”匹配的已签名只读候选。`}
        state="empty"
        title="无候选 Skill"
      />
    );
  }

  return (
    <section aria-label="候选 Skill" className={styles.candidateResults}>
      <h2>候选 Skill</h2>
      <ul className={styles.candidateList}>
        {state.candidates.map((candidate) => (
          <li className={styles.candidate} key={skillCatalogKey(candidate.skill)}>
            <div className={styles.candidateHeader}>
              <strong className={styles.skillId}>{candidate.skill.descriptor.skillId}</strong>
              <small>{candidate.skill.descriptor.displayName}</small>
            </div>
            <span className={styles.candidateScore}>候选分 {candidate.score}</span>
            <p className={styles.candidateRules}>{formatValues(candidate.matchedRules)}</p>
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * @param {{
 *   page: number,
 *   query: ReturnType<typeof useSkills>,
 *   rows: RegisteredSkill[],
 *   setPage: (page: number) => void,
 *   showDetail: (skill: RegisteredSkill) => void,
 * }} props
 */
function SkillCatalogState({ page, query, rows, setPage, showDetail }) {
  if (query.isPending) {
    return (
      <FeedbackState
        message="正在读取控制面 Skill 目录。"
        state="loading"
        title="Skill 目录读取中"
      />
    );
  }

  if (query.isError) {
    const isForbidden = query.error instanceof ApiError && query.error.kind === "forbidden";
    const isContract = query.error instanceof ApiError && query.error.kind === "contract";
    return (
      <FeedbackState
        message={
          isForbidden
            ? "服务端策略拒绝读取 Skill 目录。"
            : "Skill 目录响应无法被操作台安全解析。"
        }
        state="error"
        title={
          isForbidden
            ? "Skill 目录读取被拒绝"
            : isContract
              ? "Skill 目录契约不兼容"
              : "Skill 目录读取失败"
        }
      />
    );
  }

  return (
    <DataTable
      className={styles.registryDataTable}
      ariaLabel="内置 Skill 表格"
      columns={skillColumns(showDetail)}
      emptyMessage="控制面当前没有返回符合筛选条件的 Skill。"
      emptyTitle="没有已注册 Skill"
      getRowKey={(row) => {
        const skill = /** @type {RegisteredSkill} */ (row);
        return `${skill.descriptor.skillId}:${skill.descriptor.version}`;
      }}
      minWidth="1040px"
      onRowClick={(row) => showDetail(/** @type {RegisteredSkill} */ (row))}
      pagination={{
        page,
        pageSize: PAGE_SIZE,
        total: rows.length,
        onPageChange: setPage,
      }}
      rows={rows}
    />
  );
}

/**
 * @param {(skill: RegisteredSkill) => void} showDetail
 * @returns {Array<{header: string, key: string, render: (row: unknown) => import("react").ReactNode, align?: "left" | "center" | "right", width?: string}>}
 */
function skillColumns(showDetail) {
  return [
    {
      header: "Skill ID",
      key: "skill",
      render: (row) => {
        const skill = /** @type {RegisteredSkill} */ (row);
        return (
          <span className={styles.primaryCell}>
            <strong className={styles.skillId}>{skill.descriptor.skillId}</strong>
            <small>{formatCategory(skill.descriptor.category)} · v{skill.descriptor.version}</small>
          </span>
        );
      },
      width: "24%",
    },
    {
      header: "描述",
      key: "description",
      render: (row) => {
        const skill = /** @type {RegisteredSkill} */ (row);
        return (
          <span className={styles.descriptionCell}>
            <strong>{skill.descriptor.displayName}</strong>
            <small>{skill.descriptor.description}</small>
          </span>
        );
      },
      width: "30%",
    },
    {
      header: "条件匹配",
      key: "match",
      render: (row) => {
        const skill = /** @type {RegisteredSkill} */ (row);
        return (
          <span className={styles.matchCell}>
            <span>参数: {formatParameters(skill)}</span>
            <span>标签: {formatValues(skill.descriptor.tags)}</span>
            <span>角色: {formatRequiredRoles(skill.descriptor.requiredRoles)}</span>
          </span>
        );
      },
      width: "26%",
    },
    {
      header: "风险",
      key: "risk",
      render: (row) => {
        const skill = /** @type {RegisteredSkill} */ (row);
        return (
          <StatusPill tone={skill.descriptor.readOnly ? "success" : "warning"}>
            {skill.descriptor.riskLevel}
          </StatusPill>
        );
      },
      width: "9%",
    },
    {
      header: "状态",
      key: "status",
      render: (row) => {
        const skill = /** @type {RegisteredSkill} */ (row);
        return formatPublicationStatus(skill.publicationStatus);
      },
      width: "7%",
    },
    {
      align: "right",
      header: "详情",
      key: "action",
      render: (row) => {
        const skill = /** @type {RegisteredSkill} */ (row);
        return (
          <button
            aria-label={`查看 ${skill.descriptor.displayName} 详情`}
            className={styles.detailLinkButton}
            onClick={(event) => {
              event.stopPropagation();
              showDetail(skill);
            }}
            type="button"
          >
            <Eye aria-hidden="true" size={15} strokeWidth={2.35} />
            查看
          </button>
        );
      },
      width: "4%",
    },
  ];
}

/**
 * @param {{onClose: () => void, skill: RegisteredSkill | null}} props
 */
function SkillDetailDialog({ onClose, skill }) {
  return (
    <Dialog
      closeLabel="关闭 Skill 详情"
      description={skill?.descriptor.description}
      eyebrow="Skill 详情"
      icon={<Eye size={18} strokeWidth={2.35} />}
      onClose={onClose}
      open={Boolean(skill)}
      size="wide"
      title={skill?.descriptor.displayName ?? "Skill 详情"}
    >
      {skill ? (
        <div className={styles.detailDialogBody}>
          <dl className={styles.detailGrid}>
            <DetailItem label="Skill ID" value={skill.descriptor.skillId} />
            <DetailItem label="版本" value={skill.descriptor.version} />
            <DetailItem label="Owner" value={skill.descriptor.owner} />
            <DetailItem label="Executor" value={skill.descriptor.executor} />
            <DetailItem label="输出" value={skill.descriptor.outputType} />
            <DetailItem label="状态" value={formatPublicationStatus(skill.publicationStatus)} />
            <DetailItem label="角色" value={formatRequiredRoles(skill.descriptor.requiredRoles)} />
            <DetailItem label="参数" value={formatParameters(skill)} />
            <DetailItem label="标签" value={formatValues(skill.descriptor.tags)} />
            <DetailItem label="拦截器" value={formatValues(skill.descriptor.interceptors)} />
            <DetailItem label="契约路径" value={skill.manifestPath} />
          </dl>
          <p className={styles.detailNotice}>
            P1 阶段只展示已签名只读 Skill。上传、安装、升级、卸载和写执行发布不在浏览器中开放。
          </p>
        </div>
      ) : null}
    </Dialog>
  );
}

/**
 * @param {{label: string, value: string}} props
 */
function DetailItem({ label, value }) {
  return (
    <div className={styles.detailItem}>
      <dt>{label}</dt>
      <dd>{value}</dd>
    </div>
  );
}

/**
 * @param {string} category
 */
function formatCategory(category) {
  if (category === "INFRASTRUCTURE_DIAGNOSTICS") {
    return "INFRA";
  }
  if (category === "APPLICATION_DIAGNOSTICS") {
    return "APP";
  }
  return "OBS";
}

/**
 * @param {string} status
 */
function formatPublicationStatus(status) {
  if (status === "VALIDATED") {
    return "已签名";
  }
  if (status === "DRAFT") {
    return "草稿";
  }
  return "已拒绝";
}

/**
 * @param {string[]} values
 */
function formatValues(values) {
  return values.length > 0 ? values.join(" / ") : "无";
}

/**
 * @param {string[]} values
 */
function formatRequiredRoles(values) {
  const displayValues = values.map((value) => value.replace(/^ROLE_/u, ""));
  return formatValues(displayValues);
}

/**
 * @param {RegisteredSkill} skill
 */
function formatParameters(skill) {
  const names = skill.descriptor.parameters.map((parameter) => parameter.name);
  return names.length > 0 ? names.join(" / ") : "无参数";
}

/**
 * @param {RegisteredSkill} skill
 * @param {SearchRequest} request
 */
function matchesSkillSearch(skill, request) {
  const query = request.query.trim();
  if (!query) {
    return true;
  }

  const searchText = normalizeSearchText([
    skill.descriptor.skillId,
    skill.descriptor.displayName,
    skill.descriptor.description,
    skill.descriptor.owner,
    skill.descriptor.executor,
    skill.descriptor.outputType,
    skill.descriptor.category,
    skill.descriptor.riskLevel,
    skill.publicationStatus,
    skill.manifestPath,
    ...skill.descriptor.tags,
    ...skill.descriptor.requiredRoles,
    ...skill.descriptor.interceptors,
    ...skill.descriptor.parameters.map((parameter) => parameter.name),
  ].join(" "));

  const normalizedQuery = normalizeSearchText(query);
  if (request.mode === "conditions") {
    return searchText.includes(normalizedQuery);
  }

  const naturalTokens = tokenizeNaturalQuery(query);
  if (naturalTokens.length === 0) {
    return true;
  }
  return searchText.includes(normalizedQuery) || naturalTokens.every((token) => searchText.includes(token));
}

/**
 * @param {string} value
 */
function normalizeSearchText(value) {
  return value.toLowerCase().replace(/\s+/gu, " ").trim();
}

const naturalSearchStopWords = new Set([
  "a",
  "an",
  "find",
  "for",
  "i",
  "me",
  "please",
  "search",
  "show",
  "skill",
  "the",
  "to",
  "want",
  "个",
  "帮",
  "查",
  "找",
  "看",
  "请",
  "我",
  "想",
  "要",
  "一",
  "的",
]);

/**
 * @param {string} query
 */
function tokenizeNaturalQuery(query) {
  return query
    .toLowerCase()
    .replace(/[\p{P}\p{S}]/gu, " ")
    .split(/\s+/u)
    .flatMap((token) => (/[\u4e00-\u9fff]/u.test(token) ? Array.from(token) : [token]))
    .filter((token) => token.length > 0 && !naturalSearchStopWords.has(token));
}

/**
 * @param {RegisteredSkill} skill
 */
function skillCatalogKey(skill) {
  return `${skill.descriptor.skillId}:${skill.descriptor.version}`;
}

/**
 * @param {string} query
 */
function buildRoutingCriteriaFromNaturalLanguage(query) {
  const normalizedQuery = normalizeNaturalLanguageQuery(query);
  return {
    ...defaultCandidateCriteria,
    skillId: extractSkillId(normalizedQuery),
    category: resolveCategory(normalizedQuery),
    requiredTags: resolveTags(normalizedQuery),
  };
}

/**
 * @param {string} query
 */
function normalizeNaturalLanguageQuery(query) {
  return query.normalize("NFKC").trim().toLowerCase();
}

/**
 * @param {string} normalizedQuery
 */
function extractSkillId(normalizedQuery) {
  const match = /\b[a-z][a-z0-9-]*-read\b/u.exec(normalizedQuery);
  return match ? match[0] : null;
}

/**
 * @param {string} normalizedQuery
 */
function resolveCategory(normalizedQuery) {
  const category = categoryTerms.find((option) =>
    option.terms.some((term) => normalizedQuery.includes(term)),
  );
  return category?.value ?? null;
}

/**
 * @param {string} normalizedQuery
 */
function resolveTags(normalizedQuery) {
  return tagTerms
    .filter((option) => option.terms.some((term) => normalizedQuery.includes(term)))
    .map((option) => option.tag);
}
