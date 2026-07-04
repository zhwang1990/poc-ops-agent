import { ChevronLeft, ChevronRight, ExternalLink, FileText } from "lucide-react";
import { useMemo, useState } from "react";
import { Link } from "react-router-dom";

import { WorkspacePageFrame } from "../../components/layout/WorkspacePageFrame.jsx";
import { WorkspaceStatusBar } from "../../components/layout/WorkspaceStatusBar.jsx";
import {
  thirdPartyLicenses,
  thirdPartyLicensesSummary,
} from "./third-party-licenses-data.js";
import styles from "./ThirdPartyLicensesPage.module.css";

const LICENSES_PAGE_SIZE = 10;

export function ThirdPartyLicensesPage() {
  const [currentPage, setCurrentPage] = useState(1);
  const totalPages = Math.ceil(thirdPartyLicenses.length / LICENSES_PAGE_SIZE);
  const pageStartIndex = (currentPage - 1) * LICENSES_PAGE_SIZE;
  const pageEndIndex = Math.min(pageStartIndex + LICENSES_PAGE_SIZE, thirdPartyLicenses.length);
  const visibleLicenses = useMemo(
    () => thirdPartyLicenses.slice(pageStartIndex, pageEndIndex),
    [pageEndIndex, pageStartIndex],
  );

  /**
   * @param {number} page
   */
  function goToPage(page) {
    setCurrentPage(Math.min(Math.max(page, 1), totalPages));
  }

  return (
    <WorkspacePageFrame className={styles.licenseCanvas}>
      <WorkspaceStatusBar title="第三方组件声明" />

      <main aria-label="第三方组件声明工作区" className={styles.licenseBody}>
        <header className={styles.pageHeader}>
          <div className={styles.titleBlock}>
            <span className={styles.eyebrow}>M09 / 法律信息</span>
          </div>
          <div aria-label="声明摘要" className={styles.summaryPills}>
            {thirdPartyLicensesSummary.deliveryUnits.map((unit) => (
              <span key={unit.id}>
                {unit.label} {unit.count} 项
              </span>
            ))}
            <span>{thirdPartyLicensesSummary.licenseTypes.length} 种许可证</span>
          </div>
        </header>

        <section aria-label="第三方组件清单" className={styles.componentPanel}>
          <div className={styles.componentTableWrap}>
            <table className={styles.componentTable}>
              <thead>
                <tr>
                  <th scope="col">交付单元</th>
                  <th scope="col">组件</th>
                  <th scope="col">版本</th>
                  <th scope="col">许可证</th>
                  <th scope="col">版权归属</th>
                  <th scope="col">操作</th>
                </tr>
              </thead>
              <tbody>
                {visibleLicenses.map((declaration) => (
                  <tr aria-label={`${declaration.name} ${declaration.version}`} key={declaration.id}>
                    <td>
                      <span className={styles.unitBadge}>{declaration.deliveryUnitLabel}</span>
                    </td>
                    <td>
                      <strong>{declaration.name}</strong>
                    </td>
                    <td>{declaration.version}</td>
                    <td>{declaration.license}</td>
                    <td>
                      <span className={styles.copyrightCell}>{declaration.copyright}</span>
                    </td>
                    <td>
                      <div className={styles.rowActions}>
                        <Link
                          aria-label={`${declaration.name} 许可证`}
                          className={styles.licenseLink}
                          title="许可证"
                          to={`/third-party-licenses/${declaration.id}`}
                        >
                          <FileText aria-hidden="true" size={14} strokeWidth={2.5} />
                        </Link>
                        <a
                          aria-label={`${declaration.name} 项目主页`}
                          className={styles.homepageIconLink}
                          href={declaration.homepage}
                          rel="noreferrer"
                          target="_blank"
                          title="项目主页"
                        >
                          <ExternalLink aria-hidden="true" size={14} strokeWidth={2.5} />
                        </a>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <nav aria-label="第三方组件分页" className={styles.paginationBar}>
            <span className={styles.paginationStatus}>
              第 {currentPage} / {totalPages} 页
              <small>
                {pageStartIndex + 1}-{pageEndIndex} / {thirdPartyLicensesSummary.componentCount} 项
              </small>
            </span>
            <div className={styles.paginationControls}>
              <button
                aria-label="上一页"
                className={styles.paginationButton}
                disabled={currentPage === 1}
                onClick={() => goToPage(currentPage - 1)}
                type="button"
              >
                <ChevronLeft aria-hidden="true" size={15} strokeWidth={2.5} />
                <span>上一页</span>
              </button>
              <div aria-label="页码" className={styles.pageNumbers}>
                {Array.from({ length: totalPages }, (_, index) => index + 1).map((page) => (
                  <button
                    aria-current={page === currentPage ? "page" : undefined}
                    aria-label={`第 ${page} 页`}
                    className={styles.pageNumberButton}
                    key={page}
                    onClick={() => goToPage(page)}
                    type="button"
                  >
                    {page}
                  </button>
                ))}
              </div>
              <button
                aria-label="下一页"
                className={styles.paginationButton}
                disabled={currentPage === totalPages}
                onClick={() => goToPage(currentPage + 1)}
                type="button"
              >
                <span>下一页</span>
                <ChevronRight aria-hidden="true" size={15} strokeWidth={2.5} />
              </button>
            </div>
          </nav>
        </section>
      </main>
    </WorkspacePageFrame>
  );
}
