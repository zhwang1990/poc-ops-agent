import { ArrowLeft, ExternalLink, FileText } from "lucide-react";
import { useNavigate, useParams } from "react-router-dom";

import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import { findThirdPartyLicenseById } from "./third-party-licenses-data.js";
import styles from "./ThirdPartyLicensesPage.module.css";

export function ThirdPartyLicenseDetailPage() {
  const navigate = useNavigate();
  const { licenseId } = useParams();
  const declaration = findThirdPartyLicenseById(licenseId ?? "");

  function returnToWorkspace() {
    navigate("/third-party-licenses");
  }

  return (
    <WorkspacePageFrame className={styles.licenseCanvas}>
      <WorkspaceStatusBar title="第三方组件声明" />

      <main aria-label="第三方组件许可证工作区" className={styles.licenseBody}>
        {declaration ? (
          <LicenseDetail declaration={declaration} onReturn={returnToWorkspace} />
        ) : (
          <MissingLicense onReturn={returnToWorkspace} />
        )}
      </main>
    </WorkspacePageFrame>
  );
}

/**
 * @param {{
 *   declaration: import("./third-party-licenses-data.js").ThirdPartyLicenseDeclaration,
 *   onReturn: () => void,
 * }} props
 */
function LicenseDetail({ declaration, onReturn }) {
  return (
    <section aria-label={`${declaration.name} 许可证全文`} className={styles.detailPanel}>
      <div className={styles.detailToolbar}>
        <button className={styles.returnButton} onClick={onReturn} type="button">
          <ArrowLeft aria-hidden="true" size={16} strokeWidth={2.5} />
          <span>返回工作区</span>
        </button>
        <span className={styles.detailBadge}>
          <FileText aria-hidden="true" size={14} strokeWidth={2.4} />
          {declaration.license}
        </span>
      </div>

      <div className={styles.detailContentGrid}>
        <dl className={styles.detailMeta}>
          <div>
            <dt>组件</dt>
            <dd>{declaration.name}</dd>
          </div>
          <div>
            <dt>版本</dt>
            <dd>{declaration.version}</dd>
          </div>
          <div>
            <dt>版权归属</dt>
            <dd>{declaration.copyright}</dd>
          </div>
          <div>
            <dt>项目主页</dt>
            <dd>
              <a className={styles.homepageLink} href={declaration.homepage} rel="noreferrer" target="_blank">
                <span>{declaration.homepage}</span>
                <ExternalLink aria-hidden="true" size={14} strokeWidth={2.5} />
              </a>
            </dd>
          </div>
        </dl>

        <pre className={styles.licenseNotice}>{declaration.notice}</pre>
      </div>
    </section>
  );
}

/**
 * @param {{ onReturn: () => void }} props
 */
function MissingLicense({ onReturn }) {
  return (
    <section aria-label="许可证未找到" className={styles.detailPanel}>
      <div className={styles.detailToolbar}>
        <button className={styles.returnButton} onClick={onReturn} type="button">
          <ArrowLeft aria-hidden="true" size={16} strokeWidth={2.5} />
          <span>返回工作区</span>
        </button>
      </div>
      <div className={styles.missingMessage}>
        <span className={styles.eyebrow}>M09 / 法律信息</span>
        <h2>未找到许可证</h2>
        <p>当前组件不在已确认的第三方组件声明清单中。</p>
      </div>
    </section>
  );
}
