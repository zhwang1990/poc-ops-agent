import { Download, Upload } from "lucide-react";

import { SqlComparePanel } from "./SqlComparePanel.jsx";
import { SqlCodeEditor } from "./SqlCodeEditor.jsx";
import { SqlNaturalLanguagePanel } from "./SqlNaturalLanguagePanel.jsx";
import {
  findSqlEditorStatements,
  isLikelyControlledDmlSql,
} from "./sql-workbench-utils.js";
import styles from "./SqlWorkbenchPage.module.css";

const SESSION_MODE_OPTIONS = [
  { label: "SQL", value: "sql" },
  { label: "自然语言", value: "natural-language" },
  { label: "Compare", value: "compare" },
];

/**
 * @typedef {import("./sql-workbench-utils.js").SqlSessionMode} SqlSessionMode
 * @typedef {import("./sql-workbench-utils.js").SqlWorkbenchSession} SqlWorkbenchSession
 * @typedef {import("./sql-workbench-utils.js").SqlCompareState} SqlCompareState
 * @typedef {import("./sql-workbench-utils.js").SqlNaturalLanguageState} SqlNaturalLanguageState
 */

/**
 * @param {{
 *   activeSchema: string,
 *   canRunDmlStatements: boolean,
 *   canRunReadOnlyStatements: boolean,
 *   comparePending: boolean,
 *   naturalLanguagePending: boolean,
 *   onExportSql: () => void,
 *   onGenerateNaturalLanguageSql: () => void,
 *   onImportSqlFile: (file: File) => void | Promise<void>,
 *   onNaturalLanguageChange: (patch: Partial<SqlNaturalLanguageState>) => void,
 *   onCompareChange: (patch: Partial<SqlCompareState>) => void,
 *   onModeChange: (mode: SqlSessionMode) => void,
 *   onRunCompare: () => void,
 *   onRunStatement: (sqlText: string) => void,
 *   onSqlChange: (sqlText: string) => void,
 *   dmlRunDisabledReason: string,
 *   session: SqlWorkbenchSession,
 * }} props
 */
export function SqlEditorPanel({
  activeSchema,
  canRunDmlStatements,
  canRunReadOnlyStatements,
  comparePending,
  naturalLanguagePending,
  onExportSql,
  onGenerateNaturalLanguageSql,
  onImportSqlFile,
  onNaturalLanguageChange,
  onCompareChange,
  onModeChange,
  onRunCompare,
  onRunStatement,
  onSqlChange,
  dmlRunDisabledReason,
  session,
}) {
  const currentMode = session.mode ?? "sql";
  const hasControlledWriteStatement =
    currentMode === "sql" &&
    findSqlEditorStatements(session.sql).some((statement) =>
      isLikelyControlledDmlSql(statement.sql),
    );
  const writeRunDisabledReason =
    hasControlledWriteStatement && !canRunDmlStatements
      ? dmlRunDisabledReason
      : null;
  /**
   * @param {import("react").ChangeEvent<HTMLInputElement>} event
   */
  function handleImportChange(event) {
    const file = event.currentTarget.files?.[0];
    event.currentTarget.value = "";
    if (file) {
      void onImportSqlFile(file);
    }
  }

  return (
    <section className={styles.editorCard}>
      <div className={styles.editorHeader}>
        <div
          aria-label="SQL 会话模式"
          className={styles.sessionModeTabs}
          role="tablist"
        >
          {SESSION_MODE_OPTIONS.map((mode) => (
            <button
              aria-selected={currentMode === mode.value}
              className={currentMode === mode.value ? styles.activeModeTab : ""}
              key={mode.value}
              onClick={() => onModeChange(/** @type {SqlSessionMode} */ (mode.value))}
              role="tab"
              type="button"
            >
              {mode.label}
            </button>
          ))}
        </div>

        {currentMode === "sql" ? (
          <div className={styles.editorToolbar}>
            <div aria-label="SQL 文件操作" className={styles.editorFileActions} role="group">
              <label className={styles.secondaryButton}>
                <Upload aria-hidden="true" size={15} />
                导入 .sql
                <input
                  accept=".sql,text/plain,application/sql"
                  aria-label="导入 .sql"
                  className={styles.fileInput}
                  onChange={handleImportChange}
                  type="file"
                />
              </label>
              <button
                className={styles.secondaryButton}
                disabled={session.sql.trim().length === 0}
                onClick={onExportSql}
                type="button"
              >
                <Download aria-hidden="true" size={15} />
                导出 .sql
              </button>
              <button disabled type="button">
                停止
              </button>
            </div>
          </div>
        ) : null}
      </div>

      {currentMode === "sql" ? (
        <section
          aria-describedby={writeRunDisabledReason ? "sql-write-run-status" : undefined}
          aria-label={`${session.label}.sql`}
          className={`${styles.sqlEditor} ${
            writeRunDisabledReason ? styles.sqlEditorWithRunStatus : ""
          }`}
        >
          <span>{session.label}.sql</span>
          {writeRunDisabledReason ? (
            <p className={styles.sqlWriteRunStatus} id="sql-write-run-status" role="status">
              {writeRunDisabledReason}
            </p>
          ) : null}
          <SqlCodeEditor
            canRunDmlStatements={canRunDmlStatements}
            canRunReadOnlyStatements={canRunReadOnlyStatements}
            dmlRunDisabledReason={dmlRunDisabledReason}
            onChange={onSqlChange}
            onRunStatement={onRunStatement}
            value={session.sql}
          />
        </section>
      ) : null}

      {currentMode === "natural-language" ? (
        <SqlNaturalLanguagePanel
          activeSchema={activeSchema}
          isPending={naturalLanguagePending}
          onChange={onNaturalLanguageChange}
          onGenerate={onGenerateNaturalLanguageSql}
          state={session.naturalLanguage}
        />
      ) : null}

      {currentMode === "compare" ? (
        <SqlComparePanel
          activeSchema={activeSchema}
          isPending={comparePending}
          onChange={onCompareChange}
          onRunCompare={onRunCompare}
          state={session.compare}
        />
      ) : null}
    </section>
  );
}
