import { X } from "lucide-react";
import { useEffect, useId, useRef } from "react";
import { createPortal } from "react-dom";

import styles from "./Dialog.module.css";

/**
 * @typedef {import("react").ReactNode} ReactNode
 */

/**
 * @typedef {{
 *   children: ReactNode,
 *   className?: string,
 *   closeLabel?: string,
 *   description?: string,
 *   eyebrow?: string,
 *   icon?: ReactNode,
 *   onClose: () => void,
 *   open: boolean,
 *   size?: "compact" | "standard" | "wide" | "fullscreen",
 *   title: string,
 * }} DialogProps
 */

/**
 * @param {DialogProps} props
 */
export function Dialog({
  children,
  className = "",
  closeLabel = "关闭对话框",
  description,
  eyebrow,
  icon,
  onClose,
  open,
  size = "standard",
  title,
}) {
  const titleId = useId();
  const descriptionId = useId();
  const surfaceRef = useRef(/** @type {HTMLElement | null} */ (null));

  useEffect(() => {
    if (!open) {
      return undefined;
    }

    const previousHtmlOverflow = document.documentElement.style.overflow;
    const previousBodyOverflow = document.body.style.overflow;
    document.documentElement.style.overflow = "hidden";
    document.body.style.overflow = "hidden";

    const closeOnEscape = (/** @type {KeyboardEvent} */ event) => {
      if (event.key === "Escape") {
        onClose();
      }
    };

    window.addEventListener("keydown", closeOnEscape);
    surfaceRef.current?.focus();

    return () => {
      window.removeEventListener("keydown", closeOnEscape);
      document.documentElement.style.overflow = previousHtmlOverflow;
      document.body.style.overflow = previousBodyOverflow;
    };
  }, [onClose, open]);

  if (!open) {
    return null;
  }

  return createPortal(
    <div
      className={styles.dialogBackdrop}
      data-dialog-backdrop=""
    >
      <section
        aria-describedby={description ? descriptionId : undefined}
        aria-labelledby={titleId}
        aria-modal="true"
        className={`${styles.dialogSurface} ${className}`.trim()}
        data-dialog-size={size}
        ref={surfaceRef}
        role="dialog"
        tabIndex={-1}
      >
        <header className={styles.dialogHeader}>
          <div
            className={styles.dialogTitleCluster}
            data-has-icon={icon ? "true" : "false"}
          >
            {icon ? (
              <span
                aria-hidden="true"
                className={styles.dialogTitleIcon}
                data-dialog-title-icon=""
              >
                {icon}
              </span>
            ) : null}
            <div className={styles.dialogTitleBlock}>
              {eyebrow ? <span className={styles.dialogEyebrow}>{eyebrow}</span> : null}
              <h2 className={styles.dialogTitle} id={titleId}>
                {title}
              </h2>
              {description ? (
                <p className={styles.dialogDescription} id={descriptionId}>
                  {description}
                </p>
              ) : null}
            </div>
          </div>
          <button
            aria-label={closeLabel}
            className={styles.dialogCloseButton}
            onClick={onClose}
            type="button"
          >
            <X aria-hidden="true" size={18} strokeWidth={2.35} />
          </button>
        </header>
        <div className={styles.dialogBody}>{children}</div>
      </section>
    </div>,
    document.body,
  );
}
