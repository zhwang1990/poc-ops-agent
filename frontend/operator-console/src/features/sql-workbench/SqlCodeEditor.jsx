import { sql } from "@codemirror/lang-sql";
import { HighlightStyle, syntaxHighlighting } from "@codemirror/language";
import { Compartment, EditorState, RangeSetBuilder } from "@codemirror/state";
import { EditorView, GutterMarker, gutter } from "@codemirror/view";
import { tags } from "@lezer/highlight";
import { basicSetup } from "codemirror";
import { useEffect, useRef } from "react";

import {
  findSqlEditorStatements,
  isLikelyControlledDmlSql,
  isLikelyReadOnlySql,
} from "./sql-workbench-utils.js";
import styles from "./SqlWorkbenchPage.module.css";

/**
 * @typedef {import("./sql-workbench-utils.js").SqlEditorStatement} SqlEditorStatement
 */

const sqlCommentHighlightStyle = HighlightStyle.define([
  { tag: [tags.comment, tags.lineComment, tags.blockComment], class: "cm-sql-comment" },
]);

const sqlEditorTheme = EditorView.theme({
  "&": {
    height: "100%",
    background: "transparent",
    color: "var(--sql-ink)",
  },
  ".cm-scroller": {
    fontFamily: 'Consolas, "SFMono-Regular", monospace',
    lineHeight: "22px",
  },
  ".cm-content": {
    minHeight: "100%",
    padding: "12px 14px",
    caretColor: "var(--sql-ink)",
    fontFamily: 'Consolas, "SFMono-Regular", monospace',
    fontSize: "12px",
    fontWeight: "760",
    lineHeight: "22px",
  },
  ".cm-line": {
    padding: "0",
  },
  ".cm-gutters": {
    border: "0",
    background: "transparent",
    color: "var(--sql-muted)",
  },
  ".cm-lineNumbers, .cm-foldGutter": {
    display: "none",
  },
  ".cm-sql-run-gutter": {
    minWidth: "30px",
    paddingLeft: "6px",
    background: "transparent",
  },
  ".cm-focused": {
    outline: "none",
  },
  ".cm-cursor": {
    borderLeftColor: "var(--sql-ink)",
  },
  ".cm-selectionBackground": {
    background: "rgba(34, 126, 166, 0.18) !important",
  },
  ".cm-activeLine": {
    backgroundColor: "transparent",
  },
});

class SqlRunGutterSpacerMarker extends GutterMarker {
  /**
   * @param {GutterMarker} other
   */
  eq(other) {
    return other instanceof SqlRunGutterSpacerMarker;
  }

  toDOM() {
    const spacer = document.createElement("span");
    spacer.className = "cm-sql-run-spacer";
    spacer.setAttribute("aria-hidden", "true");
    spacer.textContent = "▶";
    return spacer;
  }
}

const sqlRunGutterSpacerMarker = new SqlRunGutterSpacerMarker();

/**
 * @param {string} sqlText
 * @param {boolean} canRunReadOnlyStatements
 * @param {boolean} canRunDmlStatements
 * @param {string} dmlRunDisabledReason
 */
function resolveSqlRunState(
  sqlText,
  canRunReadOnlyStatements,
  canRunDmlStatements,
  dmlRunDisabledReason,
) {
  if (isLikelyReadOnlySql(sqlText)) {
    return {
      canRun: canRunReadOnlyStatements,
      title: canRunReadOnlyStatements ? "运行此 SQL" : "当前连接不允许执行只读查询",
    };
  }
  if (isLikelyControlledDmlSql(sqlText)) {
    return {
      canRun: canRunDmlStatements,
      title: canRunDmlStatements ? "运行此 SQL" : dmlRunDisabledReason,
    };
  }
  return { canRun: false, title: "仅支持 SELECT、INSERT、UPDATE 或 DELETE" };
}

class SqlRunGutterMarker extends GutterMarker {
  /**
   * @param {SqlEditorStatement} statement
   * @param {{canRun: boolean, title: string}} runState
   * @param {{current: (sqlText: string) => void}} onRunStatementRef
   */
  constructor(statement, runState, onRunStatementRef) {
    super();
    this.statement = statement;
    this.runState = runState;
    this.onRunStatementRef = onRunStatementRef;
  }

  /**
   * @param {GutterMarker} other
   */
  eq(other) {
    return (
      other instanceof SqlRunGutterMarker &&
      other.statement.from === this.statement.from &&
      other.statement.to === this.statement.to &&
      other.statement.sql === this.statement.sql &&
      other.runState.canRun === this.runState.canRun &&
      other.runState.title === this.runState.title
    );
  }

  toDOM() {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "cm-sql-run-button";
    button.setAttribute("aria-label", "运行此 SQL");
    button.title = this.runState.title;
    button.disabled = !this.runState.canRun;
    button.textContent = "▶";
    button.addEventListener("mousedown", (event) => {
      event.preventDefault();
    });
    button.addEventListener("click", (event) => {
      event.preventDefault();
      event.stopPropagation();
      if (!this.runState.canRun) {
        return;
      }
      this.onRunStatementRef.current(this.statement.sql);
    });
    return button;
  }
}

/**
 * @param {boolean} canRunReadOnlyStatements
 * @param {boolean} canRunDmlStatements
 * @param {string} dmlRunDisabledReason
 * @param {{current: (sqlText: string) => void}} onRunStatementRef
 */
function createSqlRunGutterExtension(
  canRunReadOnlyStatements,
  canRunDmlStatements,
  dmlRunDisabledReason,
  onRunStatementRef,
) {
  return gutter({
    class: "cm-sql-run-gutter",
    initialSpacer: () => sqlRunGutterSpacerMarker,
    markers(view) {
      const markers =
        /** @type {RangeSetBuilder<GutterMarker>} */ (new RangeSetBuilder());
      for (const statement of findSqlEditorStatements(view.state.doc.toString())) {
        const line = view.state.doc.lineAt(statement.from);
        markers.add(
          line.from,
          line.from,
          new SqlRunGutterMarker(
            statement,
            resolveSqlRunState(
              statement.sql,
              canRunReadOnlyStatements,
              canRunDmlStatements,
              dmlRunDisabledReason,
            ),
            onRunStatementRef,
          ),
        );
      }
      return markers.finish();
    },
  });
}

/**
 * CodeMirror hides gutters from the accessibility tree by default because they
 * usually contain line numbers. This gutter contains real execution controls.
 *
 * @param {EditorView} view
 */
function exposeSqlRunGutter(view) {
  view.dom.querySelector(".cm-gutters")?.removeAttribute("aria-hidden");
}

/**
 * @param {{
 *   canRunDmlStatements: boolean,
 *   canRunReadOnlyStatements: boolean,
 *   dmlRunDisabledReason: string,
 *   onChange: (sqlText: string) => void,
 *   onRunStatement: (sqlText: string) => void,
 *   value: string,
 * }} props
 */
export function SqlCodeEditor({
  canRunDmlStatements,
  canRunReadOnlyStatements,
  dmlRunDisabledReason,
  onChange,
  onRunStatement,
  value,
}) {
  const rootRef = useRef(/** @type {HTMLDivElement | null} */ (null));
  const viewRef = useRef(/** @type {EditorView | null} */ (null));
  const onChangeRef = useRef(onChange);
  const onRunStatementRef = useRef(onRunStatement);
  const runGutterCompartmentRef = useRef(new Compartment());
  const initialValueRef = useRef(value);
  const initialCanRunDmlStatementsRef = useRef(canRunDmlStatements);
  const initialCanRunReadOnlyStatementsRef = useRef(canRunReadOnlyStatements);
  const initialDmlRunDisabledReasonRef = useRef(dmlRunDisabledReason);
  const isApplyingExternalValueRef = useRef(false);

  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  useEffect(() => {
    onRunStatementRef.current = onRunStatement;
  }, [onRunStatement]);

  useEffect(() => {
    if (!rootRef.current) {
      return undefined;
    }

    const view = new EditorView({
      parent: rootRef.current,
      state: EditorState.create({
        doc: initialValueRef.current,
        extensions: [
          basicSetup,
          runGutterCompartmentRef.current.of(
            createSqlRunGutterExtension(
              initialCanRunReadOnlyStatementsRef.current,
              initialCanRunDmlStatementsRef.current,
              initialDmlRunDisabledReasonRef.current,
              onRunStatementRef,
            ),
          ),
          sql(),
          sqlEditorTheme,
          syntaxHighlighting(sqlCommentHighlightStyle),
          EditorView.lineWrapping,
          EditorView.contentAttributes.of({
            "aria-label": "SQL 文本",
            "aria-multiline": "true",
            autocapitalize: "off",
            spellcheck: "false",
          }),
          EditorView.updateListener.of((update) => {
            exposeSqlRunGutter(update.view);
            if (!update.docChanged || isApplyingExternalValueRef.current) {
              return;
            }
            onChangeRef.current(update.state.doc.toString());
          }),
        ],
      }),
    });

    viewRef.current = view;
    exposeSqlRunGutter(view);
    return () => {
      view.destroy();
      viewRef.current = null;
    };
  }, []);

  useEffect(() => {
    const view = viewRef.current;
    if (!view) {
      return;
    }

    view.dispatch({
      effects: runGutterCompartmentRef.current.reconfigure(
        createSqlRunGutterExtension(
          canRunReadOnlyStatements,
          canRunDmlStatements,
          dmlRunDisabledReason,
          onRunStatementRef,
        ),
      ),
    });
  }, [canRunDmlStatements, canRunReadOnlyStatements, dmlRunDisabledReason]);

  useEffect(() => {
    const view = viewRef.current;
    if (!view) {
      return;
    }

    const currentValue = view.state.doc.toString();
    if (currentValue === value) {
      return;
    }

    isApplyingExternalValueRef.current = true;
    try {
      view.dispatch({
        changes: {
          from: 0,
          insert: value,
          to: currentValue.length,
        },
      });
    } finally {
      isApplyingExternalValueRef.current = false;
    }
  }, [value]);

  return <div className={styles.sqlCodeEditor} ref={rootRef} />;
}
