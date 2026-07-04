import {
  CheckCircle2,
  ClipboardCheck,
  DatabaseZap,
  FileClock,
  FileSpreadsheet,
  Plus,
  SearchCheck,
  ShieldCheck,
  Trash2,
  Upload,
  Workflow,
} from "lucide-react";
import { useMemo, useState } from "react";

import toolbarStyles from "../../components/layout/PageToolbar.module.css";
import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { useWorkspaceLayout } from "../../components/layout/WorkspaceLayoutContext.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import { Badge } from "../../components/primitives/Badge.jsx";
import { Button } from "../../components/primitives/Button.jsx";
import styles from "./As400ObjectManagementPage.module.css";

const tableDraft = {
  schemaName: "ORDERS",
  tableName: "CUSORD",
  tableFullName: "客户订单主表",
  targetEnvironment: "development",
  owner: "order-ops",
  changeType: "新建表",
};

const mockImportFile = {
  fileName: "AS400_客户订单表_导入模板.xlsx",
  sheetName: "字段设计",
  parsedRows: 8,
  status: "已解析 mock 数据",
  updatedAt: "2026-06-28 21:40",
};

const physicalTypeOptions = [
  "CHAR",
  "VARCHAR",
  "GRAPHIC",
  "VARGRAPHIC",
  "DECIMAL",
  "INTEGER",
  "BIGINT",
  "DATE",
  "TIME",
  "TIMESTAMP",
];

const importMappingRows = [
  { source: "业务含义", target: "businessMeaning", required: true, status: "已匹配" },
  { source: "是否可空", target: "nullable", required: false, status: "已匹配" },
  { source: "是否主键", target: "primaryKey", required: false, status: "已匹配" },
  { source: "是否支持中文", target: "supportsChinese", required: false, status: "已匹配" },
  { source: "字段全称", target: "fullName", required: false, status: "可选" },
];

const initialFieldRows = [
  {
    id: "orderNo",
    businessMeaning: "订单编号",
    fullName: "客户订单唯一编号",
    physicalType: "CHAR",
    length: "12",
    scale: "0",
    nullable: false,
    primaryKey: true,
    supportsChinese: false,
    selectedAbbreviation: "ORDNO",
    candidates: ["ORDNO", "ORDID", "ORDNUM", "CUSTORD", "ORDKEY", "ORDCD", "ORDERID", "ORNO", "ORDSN", "ODRID"],
  },
  {
    id: "customerName",
    businessMeaning: "客户名称",
    fullName: "下单客户中文名称",
    physicalType: "VARGRAPHIC",
    length: "80",
    scale: "0",
    nullable: false,
    primaryKey: false,
    supportsChinese: true,
    selectedAbbreviation: "CUSTNM",
    candidates: ["CUSTNM", "CUSNAM", "CUSTOMER", "CNAME", "BUYERNM", "CLTNM", "CSTNM", "CUSNAME", "CNM", "BCUST"],
  },
  {
    id: "orderAmount",
    businessMeaning: "订单金额",
    fullName: "订单含税总金额",
    physicalType: "DECIMAL",
    length: "15",
    scale: "2",
    nullable: false,
    primaryKey: false,
    supportsChinese: false,
    selectedAbbreviation: "ORDAMT",
    candidates: ["ORDAMT", "AMOUNT", "TOTALAMT", "ORDVAL", "TAXAMT", "SUMAMT", "ORDA", "OTAMT", "OAMT", "ORDSUM"],
  },
  {
    id: "orderStatus",
    businessMeaning: "订单状态",
    fullName: "订单当前处理状态",
    physicalType: "CHAR",
    length: "2",
    scale: "0",
    nullable: true,
    primaryKey: false,
    supportsChinese: false,
    selectedAbbreviation: "ORDST",
    candidates: ["ORDST", "STATUS", "OSTAT", "ORDSTS", "STATE", "STCD", "ORDS", "PROCST", "OSCD", "STUS"],
  },
];

/** @type {Array<{ label: string, value: string, tone: "ok" | "info" | "warning" | "danger" }>} */
const validationItems = [
  { label: "必填字段", value: "业务含义", tone: "ok" },
  { label: "主键策略", value: "支持联合主键", tone: "info" },
  { label: "中文字段", value: "使用图形字符建议", tone: "info" },
  { label: "执行状态", value: "仅 UI 草稿", tone: "warning" },
];

const flowSteps = [
  { label: "Excel / 在线草稿", value: "mock-ready", tone: "info" },
  { label: "字段校验", value: "本地预览", tone: "ok" },
  { label: "大模型缩写", value: "10 个候选", tone: "info" },
  { label: "审批接入", value: "待接入 M05", tone: "warning" },
  { label: "执行链路", value: "禁用", tone: "danger" },
];

/**
 * @typedef {object} FieldRow
 * @property {string} id
 * @property {string} businessMeaning
 * @property {string} fullName
 * @property {string} physicalType
 * @property {string} length
 * @property {string} scale
 * @property {boolean} nullable
 * @property {boolean} primaryKey
 * @property {boolean} supportsChinese
 * @property {string} selectedAbbreviation
 * @property {string[]} candidates
 */

export function As400ObjectManagementPage() {
  const [activeMode, setActiveMode] = useState(/** @type {"excel" | "manual"} */ ("manual"));
  const [fieldRows, setFieldRows] = useState(/** @type {FieldRow[]} */ (initialFieldRows));
  const [importFile, setImportFile] = useState(mockImportFile);
  const { isWorkspaceExpanded } = useWorkspaceLayout();

  const primaryKeyCount = fieldRows.filter((row) => row.primaryKey).length;
  const chineseFieldCount = fieldRows.filter((row) => row.supportsChinese).length;
  const nullableFieldCount = fieldRows.filter((row) => row.nullable).length;
  const requiredReadyCount = fieldRows.filter((row) => row.businessMeaning.trim()).length;
  const selectedCandidates = useMemo(
    () => fieldRows.map((row) => row.selectedAbbreviation).join(", ") || "无",
    [fieldRows],
  );

  /**
   * @param {string} id
   * @param {Partial<FieldRow>} patch
   */
  function updateFieldRow(id, patch) {
    setFieldRows((rows) =>
      rows.map((row) => (row.id === id ? { ...row, ...patch } : row)),
    );
  }

  /**
   * @param {string} id
   */
  function deleteFieldRow(id) {
    setFieldRows((rows) => rows.filter((row) => row.id !== id));
  }

  function addDraftRow() {
    const nextIndex = fieldRows.length + 1;
    setFieldRows((rows) => [
      ...rows,
      {
        id: `mockField${nextIndex}`,
        businessMeaning: "新字段",
        fullName: "待补充字段全称",
        physicalType: "CHAR",
        length: "12",
        scale: "0",
        nullable: true,
        primaryKey: false,
        supportsChinese: false,
        selectedAbbreviation: "NEWFLD",
        candidates: ["NEWFLD", "NFLD", "FIELD", "NEWCOL", "BUSFLD", "COLID", "ASFLD", "DRAFT", "FLDNO", "COLNM"],
      },
    ]);
  }

  /**
   * @param {import("react").ChangeEvent<HTMLInputElement>} event
   */
  function handleFileChange(event) {
    const fileName = event.currentTarget.files?.[0]?.name;
    if (!fileName) {
      return;
    }
    setImportFile({
      fileName,
      sheetName: "字段设计",
      parsedRows: fieldRows.length,
      status: "等待映射确认",
      updatedAt: "本地上传",
    });
  }

  return (
    <WorkspacePageFrame
      className={`${styles.as400Canvas} ${isWorkspaceExpanded ? styles.workspaceExpanded : ""}`}
    >
      <WorkspaceStatusBar title="AS400对象管理" />

      <section aria-label="AS400 数据对象管理工作区" className={styles.objectWorkspace}>
        <ObjectToolbar
          activeMode={activeMode}
          onModeChange={setActiveMode}
        />

        <div className={styles.objectLayout}>
          <main className={styles.workSurface}>
            <header className={styles.surfaceHeader}>
              <div className={styles.surfaceTitleLine}>
                <span aria-hidden="true" className={styles.surfaceTitleIcon}>
                  <DatabaseZap size={15} strokeWidth={2.4} />
                </span>
                <h2>{tableDraft.tableFullName}</h2>
                <span className={styles.objectCodePill}>
                  {tableDraft.schemaName}.{tableDraft.tableName}
                </span>
              </div>
              <Badge className={styles.statusBadge} tone="warning">
                UI 草稿
              </Badge>
            </header>

            <section aria-label="表级 mock 信息" className={styles.tableMetaGrid}>
              <MetaTile label="目标环境" value={tableDraft.targetEnvironment} />
              <MetaTile label="对象模式" value={tableDraft.schemaName} />
              <MetaTile label="表名" value={tableDraft.tableName} />
              <MetaTile label="变更类型" value={tableDraft.changeType} />
            </section>

            {activeMode === "excel" ? (
              <ExcelImportPanel
                importFile={importFile}
                onFileChange={handleFileChange}
                onLoadMock={() => setImportFile(mockImportFile)}
              />
            ) : (
              <ManualDesignPanel onAddRow={addDraftRow} />
            )}

            <FieldDraftTable
              fieldRows={fieldRows}
              onDeleteFieldRow={deleteFieldRow}
              onUpdateFieldRow={updateFieldRow}
            />
          </main>

          {!isWorkspaceExpanded ? (
            <aside aria-label="AS400 对象草稿状态" className={styles.sideRail}>
              <SummaryPanel
                chineseFieldCount={chineseFieldCount}
                fieldCount={fieldRows.length}
                nullableFieldCount={nullableFieldCount}
                primaryKeyCount={primaryKeyCount}
                requiredReadyCount={requiredReadyCount}
                selectedCandidates={selectedCandidates}
              />
              <ValidationPanel />
              <FlowPanel />
            </aside>
          ) : null}
        </div>
      </section>
    </WorkspacePageFrame>
  );
}

/**
 * @param {{
 *   activeMode: "excel" | "manual",
 *   onModeChange: (mode: "excel" | "manual") => void,
 * }} props
 */
function ObjectToolbar({ activeMode, onModeChange }) {
  return (
    <section
      aria-label="对象管理模式"
      className={`${styles.modeToolbar} ${toolbarStyles.surface}`}
    >
      <span className={styles.modeIcon}>
        <DatabaseZap size={17} strokeWidth={2.4} />
      </span>
      <div className={styles.modeCopy}>
        <strong>表结构草稿</strong>
        <small>建表可编辑，改表入口保留为后续受控变更</small>
      </div>
      <div className={`${styles.modeTabs} ${toolbarStyles.actionGroup}`} role="tablist">
        <button
          aria-selected={activeMode === "excel"}
          className={`${toolbarStyles.button} ${
            activeMode === "excel" ? toolbarStyles.selected : toolbarStyles.neutral
          }`}
          onClick={() => onModeChange("excel")}
          role="tab"
          type="button"
        >
          <FileSpreadsheet size={15} strokeWidth={2.4} />
          上传 Excel
        </button>
        <button
          aria-selected={activeMode === "manual"}
          className={`${toolbarStyles.button} ${
            activeMode === "manual" ? toolbarStyles.selected : toolbarStyles.neutral
          }`}
          onClick={() => onModeChange("manual")}
          role="tab"
          type="button"
        >
          <SearchCheck size={15} strokeWidth={2.4} />
          在线设计
        </button>
      </div>
      <div className={`${styles.modeActions} ${toolbarStyles.actionGroup}`}>
        <button
          aria-disabled="true"
          className={`${toolbarStyles.button} ${toolbarStyles.neutral}`}
          type="button"
        >
          改表草稿
        </button>
        <button className={`${toolbarStyles.button} ${toolbarStyles.primary}`} disabled type="button">
          提交审批
        </button>
      </div>
    </section>
  );
}

/**
 * @param {{
 *   importFile: typeof mockImportFile,
 *   onFileChange: (event: import("react").ChangeEvent<HTMLInputElement>) => void,
 *   onLoadMock: () => void,
 * }} props
 */
function ExcelImportPanel({ importFile, onFileChange, onLoadMock }) {
  return (
    <section aria-label="Excel 导入模式" className={styles.importPanel}>
      <div className={styles.uploadBox}>
        <span aria-hidden="true" className={styles.uploadIcon}>
          <Upload size={20} strokeWidth={2.3} />
        </span>
        <div>
          <strong>{importFile.fileName}</strong>
          <small>
            {importFile.sheetName} / {importFile.parsedRows} 行 / {importFile.status}
          </small>
        </div>
        <label className={styles.fileButton}>
          选择文件
          <input accept=".xlsx,.xls" onChange={onFileChange} type="file" />
        </label>
        <Button onClick={onLoadMock} variant="secondary">
          加载示例 Excel
        </Button>
      </div>
      <div className={styles.mappingTable} role="table" aria-label="Excel 字段映射">
        <div className={styles.mappingHead} role="row">
          <span role="columnheader">Excel 列</span>
          <span role="columnheader">草稿字段</span>
          <span role="columnheader">要求</span>
          <span role="columnheader">状态</span>
        </div>
        {importMappingRows.map((row) => (
          <div className={styles.mappingRow} key={row.source} role="row">
            <span role="cell">{row.source}</span>
            <span role="cell">{row.target}</span>
            <span role="cell">{row.required ? "必填" : "可选"}</span>
            <strong role="cell">{row.status}</strong>
          </div>
        ))}
      </div>
    </section>
  );
}

/**
 * @param {{ onAddRow: () => void }} props
 */
function ManualDesignPanel({ onAddRow }) {
  return (
    <section aria-label="在线设计模式" className={styles.manualPanel}>
      <Button className={styles.addRowButton} onClick={onAddRow} variant="secondary">
        <Plus size={15} strokeWidth={2.6} />
        新增字段
      </Button>
    </section>
  );
}

/**
 * @param {{
 *   fieldRows: FieldRow[],
 *   onDeleteFieldRow: (id: string) => void,
 *   onUpdateFieldRow: (id: string, patch: Partial<FieldRow>) => void,
 * }} props
 */
function FieldDraftTable({
  fieldRows,
  onDeleteFieldRow,
  onUpdateFieldRow,
}) {
  return (
    <section aria-label="字段草稿表格" className={styles.fieldTableSection}>
      <div className={styles.fieldTableWrap}>
        <table className={styles.fieldTable}>
          <thead>
            <tr>
              <th>序号</th>
              <th>业务含义*</th>
              <th>字段全称</th>
              <th>物理类型</th>
              <th>长度</th>
              <th>小数</th>
              <th>是否可空</th>
              <th>是否主键</th>
              <th>是否支持中文</th>
              <th>缩写候选</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            {fieldRows.map((row, index) => (
              <FieldDraftRow
                index={index}
                key={row.id}
                onDeleteFieldRow={() => onDeleteFieldRow(row.id)}
                onUpdateFieldRow={(patch) => onUpdateFieldRow(row.id, patch)}
                row={row}
              />
            ))}
          </tbody>
        </table>
      </div>
    </section>
  );
}

/**
 * @param {{
 *   index: number,
 *   onDeleteFieldRow: () => void,
 *   onUpdateFieldRow: (patch: Partial<FieldRow>) => void,
 *   row: FieldRow,
 * }} props
 */
function FieldDraftRow({
  index,
  onDeleteFieldRow,
  onUpdateFieldRow,
  row,
}) {
  return (
    <tr>
      <td>{index + 1}</td>
      <td>
        <input
          aria-label={`第 ${index + 1} 行业务含义`}
          className={styles.textInput}
          onChange={(event) => onUpdateFieldRow({ businessMeaning: event.target.value })}
          value={row.businessMeaning}
        />
      </td>
      <td>
        <input
          aria-label={`第 ${index + 1} 行字段全称`}
          className={styles.textInput}
          onChange={(event) => onUpdateFieldRow({ fullName: event.target.value })}
          value={row.fullName}
        />
      </td>
      <td>
        <select
          aria-label={`第 ${index + 1} 行物理类型`}
          className={`${styles.shortInput} ${styles.physicalInput} ${styles.physicalSelect}`}
          onChange={(event) => onUpdateFieldRow({ physicalType: event.target.value })}
          value={row.physicalType}
        >
          {physicalTypeOptions.map((physicalType) => (
            <option key={physicalType} value={physicalType}>
              {physicalType}
            </option>
          ))}
        </select>
      </td>
      <td>
        <input
          aria-label={`第 ${index + 1} 行长度`}
          className={styles.numberInput}
          onChange={(event) => onUpdateFieldRow({ length: event.target.value })}
          value={row.length}
        />
      </td>
      <td>
        <input
          aria-label={`第 ${index + 1} 行小数位`}
          className={`${styles.numberInput} ${styles.decimalInput}`}
          onChange={(event) => onUpdateFieldRow({ scale: event.target.value })}
          value={row.scale}
        />
      </td>
      <td>
        <ToggleCell
          checked={row.nullable}
          label={`第 ${index + 1} 行是否可空`}
          onChange={(checked) => onUpdateFieldRow({ nullable: checked })}
        />
      </td>
      <td>
        <ToggleCell
          checked={row.primaryKey}
          label={`第 ${index + 1} 行是否主键`}
          onChange={(checked) => onUpdateFieldRow({ primaryKey: checked })}
        />
      </td>
      <td>
        <ToggleCell
          checked={row.supportsChinese}
          label={`第 ${index + 1} 行是否支持中文`}
          onChange={(checked) => onUpdateFieldRow({ supportsChinese: checked })}
        />
      </td>
      <td>
        <select
          aria-label={`第 ${index + 1} 行缩写候选`}
          className={styles.candidateSelect}
          onChange={(event) => onUpdateFieldRow({ selectedAbbreviation: event.target.value })}
          value={row.selectedAbbreviation}
        >
          {row.candidates.map((candidate) => (
            <option key={candidate} value={candidate}>
              {candidate}
            </option>
          ))}
        </select>
      </td>
      <td className={styles.deleteCell}>
        <button
          aria-label={`删除第 ${index + 1} 行字段`}
          className={styles.deleteRowButton}
          onClick={onDeleteFieldRow}
          type="button"
        >
          <Trash2 size={14} strokeWidth={2.4} />
        </button>
      </td>
    </tr>
  );
}

/**
 * @param {{
 *   checked: boolean,
 *   label: string,
 *   onChange: (checked: boolean) => void,
 * }} props
 */
function ToggleCell({ checked, label, onChange }) {
  return (
    <label className={styles.toggleCell}>
      <input
        aria-label={label}
        checked={checked}
        onChange={(event) => onChange(event.target.checked)}
        type="checkbox"
      />
      <span>{checked ? "是" : "否"}</span>
    </label>
  );
}

/**
 * @param {{ label: string, value: string }} props
 */
function MetaTile({ label, value }) {
  return (
    <article className={styles.metaTile}>
      <span>{label}</span>
      <strong>{value}</strong>
    </article>
  );
}

/**
 * @param {{
 *   chineseFieldCount: number,
 *   fieldCount: number,
 *   nullableFieldCount: number,
 *   primaryKeyCount: number,
 *   requiredReadyCount: number,
 *   selectedCandidates: string,
 * }} props
 */
function SummaryPanel({
  chineseFieldCount,
  fieldCount,
  nullableFieldCount,
  primaryKeyCount,
  requiredReadyCount,
  selectedCandidates,
}) {
  return (
    <section className={styles.sidePanel}>
      <h3>
        <span aria-hidden="true" className={`${styles.panelIcon} ${styles.panelIconTask}`}>
          <ClipboardCheck size={15} strokeWidth={2.6} />
        </span>
        草稿摘要
      </h3>
      <MiniRow label="字段数" tone="info" value={`${fieldCount}`} />
      <MiniRow label="必填完成" tone="ok" value={`${requiredReadyCount}/${fieldCount}`} />
      <MiniRow label="主键字段" tone={primaryKeyCount > 0 ? "ok" : "danger"} value={`${primaryKeyCount}`} />
      <MiniRow label="可空字段" tone="info" value={`${nullableFieldCount}`} />
      <MiniRow label="中文字段" tone="info" value={`${chineseFieldCount}`} />
      <div className={styles.panelNote}>
        <CheckCircle2 aria-hidden="true" size={16} />
        <span>已选缩写：{selectedCandidates}</span>
      </div>
    </section>
  );
}

function ValidationPanel() {
  return (
    <section className={styles.sidePanel}>
      <h3>
        <span aria-hidden="true" className={`${styles.panelIcon} ${styles.panelIconSkill}`}>
          <ShieldCheck size={15} strokeWidth={2.6} />
        </span>
        校验与边界
      </h3>
      {validationItems.map((item) => (
        <MiniRow itemKey={item.label} key={item.label} label={item.label} tone={item.tone} value={item.value} />
      ))}
      <div className={`${styles.panelNote} ${styles.warningNote}`}>
        <FileClock aria-hidden="true" size={16} />
        <span>当前只准备 UI 草稿，不生成可执行 DDL，不提交 Worker。</span>
      </div>
    </section>
  );
}

function FlowPanel() {
  return (
    <section className={`${styles.sidePanel} ${styles.scanLine}`}>
      <h3>
        <span aria-hidden="true" className={`${styles.panelIcon} ${styles.panelIconSession}`}>
          <Workflow size={15} strokeWidth={2.6} />
        </span>
        后续链路
      </h3>
      <div className={styles.flowList}>
        {flowSteps.map((step, index) => (
          <div className={styles.flowStep} key={step.label}>
            <span>{index + 1}</span>
            <strong data-tone={step.tone}>{step.label}</strong>
            <small>{step.value}</small>
          </div>
        ))}
      </div>
    </section>
  );
}

/**
 * @param {{
 *   itemKey?: string,
 *   label: string,
 *   tone?: "default" | "info" | "ok" | "warning" | "danger",
 *   value: string,
 * }} props
 */
function MiniRow({ label, tone = "default", value }) {
  return (
    <div className={styles.miniRow}>
      <span>{label}</span>
      <strong data-tone={tone}>{value}</strong>
    </div>
  );
}
