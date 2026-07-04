import {
  Bot,
  BookOpenCheck,
  SendHorizontal,
  ShieldCheck,
  SlidersHorizontal,
  UserRound,
} from "lucide-react";
import { useState } from "react";

import { NaturalLanguageDialog } from "../../components/conversation/NaturalLanguageDialog.jsx";
import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import { Badge } from "../../components/primitives/Badge.jsx";
import styles from "./RagQuestionPage.module.css";

const evidenceItems = [
  {
    category: "Runbook",
    detail: "payment-api 错误率排查，第 3 节",
  },
  {
    category: "工单复盘",
    detail: "INC-2841 订单库慢查询关联",
  },
  {
    category: "发布记录",
    detail: "release-2026-06 前置检查",
  },
];

const knowledgeSources = [
  { label: "Runbook / 运维手册", status: "在线", tone: "blue" },
  { label: "故障复盘 / 工单", status: "在线", tone: "green" },
  { label: "ADR / 架构决策", status: "在线", tone: "gold" },
  { label: "发布记录 / 变更说明", status: "在线", tone: "red" },
];

const citationMetrics = [
  { label: "最高相似度", value: "0.86", tone: "green" },
  { label: "引用片段", value: "3 条", tone: "blue" },
  { label: "缺口提示", value: "1 项", tone: "gold" },
];

const retrievalParameters = [
  { label: "TopK", value: "5", tone: "blue" },
  { label: "重排模型", value: "开启", tone: "green" },
  { label: "无引用回答", value: "禁止", tone: "red" },
];

export function RagQuestionPage() {
  const [question, setQuestion] = useState(
    "输入问题，例如：根据最近故障复盘，总结 payment-api 超时的常见原因，并列出引用来源。",
  );

  return (
    <WorkspacePageFrame className={styles.ragCanvas}>
      <WorkspaceStatusBar title="RAG 问答" />

      <div className={styles.ragLayout}>
        <section aria-label="RAG 问答窗口" className={styles.ragWindow}>
          <div className={styles.ragHead}>
            <div>
              <h2>知识库问答</h2>
            </div>
            <Badge className={styles.citationBadge} tone="success">
              引用已开启
            </Badge>
          </div>

          <div className={styles.ragBody}>
            <Message author="操作员" tone="operator">
              payment-api 最近 7 天错误率升高时，应该先查哪些 Runbook 和历史工单？
            </Message>
            <Message author="Agent" tone="agent">
              <p>
                建议先从服务依赖、发布窗口和数据库慢查询三个方向排查。当前知识库中可直接引用的材料包括
                payment-api 故障 Runbook、订单库慢查询复盘和 6 月发布检查记录。未检索到生产写操作授权文档，因此回答只给出排查路径。
              </p>
              <div className={styles.evidenceGrid}>
                {evidenceItems.map((item) => (
                  <article className={styles.evidenceCard} key={item.detail}>
                    <strong>{item.category}</strong>
                    <span>{item.detail}</span>
                  </article>
                ))}
              </div>
            </Message>
          </div>

          <NaturalLanguageDialog
            ariaLabel="RAG 问题输入区"
            className={styles.ragComposer}
            inputClassName={styles.ragInput}
            inputLabel="RAG 问题"
            onChange={setQuestion}
            onSubmit={() => {}}
            placeholder="输入问题，例如：根据最近故障复盘，总结 payment-api 超时的常见原因，并列出引用来源。"
            submitAriaLabel="提交 RAG 问题"
            submitClassName={styles.ragSend}
            submitDisabled
            submitIcon={<SendHorizontal aria-hidden="true" size={19} strokeWidth={2.4} />}
            value={question}
          />
        </section>

        <aside className={styles.ragSide} aria-label="RAG 检索上下文">
          <SidePanel icon={BookOpenCheck} title="知识源">
            {knowledgeSources.map((item) => (
              <StatusRow item={item} key={item.label} />
            ))}
          </SidePanel>
          <SidePanel icon={ShieldCheck} title="引用与可信度">
            {citationMetrics.map((item) => (
              <StatusRow item={item} key={item.label} />
            ))}
          </SidePanel>
          <SidePanel icon={SlidersHorizontal} title="检索参数">
            {retrievalParameters.map((item) => (
              <StatusRow item={item} key={item.label} />
            ))}
          </SidePanel>
        </aside>
      </div>
    </WorkspacePageFrame>
  );
}

/**
 * @param {{
 *   author: string,
 *   children: import("react").ReactNode,
 *   tone: "agent" | "operator",
 * }} props
 */
function Message({ author, children, tone }) {
  const Icon = tone === "operator" ? UserRound : Bot;

  return (
    <article className={`${styles.message} ${styles[tone]}`}>
      <div className={styles.messageHead}>
        <span className={styles.messageRole}>
          <span aria-hidden="true" className={styles.messageIcon}>
            <Icon size={15} strokeWidth={2.6} />
          </span>
          {author}
        </span>
      </div>
      <div className={styles.messageContent}>{children}</div>
    </article>
  );
}

/**
 * @param {{
 *   children: import("react").ReactNode,
 *   icon: import("lucide-react").LucideIcon,
 *   title: string,
 * }} props
 */
function SidePanel({ children, icon: Icon, title }) {
  return (
    <section className={styles.sidePanel}>
      <h3>
        <span aria-hidden="true" className={styles.sidePanelIcon}>
          <Icon size={16} strokeWidth={2.4} />
        </span>
        {title}
      </h3>
      <div className={styles.sideRows}>{children}</div>
    </section>
  );
}

/**
 * @param {{item: {label: string, status?: string, tone: string, value?: string}}} props
 */
function StatusRow({ item }) {
  return (
    <div className={`${styles.statusRow} ${styles[item.tone]}`}>
      <span aria-hidden="true" className={styles.statusDot} />
      <span>{item.label}</span>
      <strong>{item.status ?? item.value}</strong>
    </div>
  );
}
