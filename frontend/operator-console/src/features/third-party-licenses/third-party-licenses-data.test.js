import { describe, expect, test } from "vitest";

import packageLock from "../../../package-lock.json";
import {
  findThirdPartyLicenseById,
  thirdPartyLicenses,
  thirdPartyLicensesSummary,
} from "./third-party-licenses-data.js";

/** @typedef {"id" | "name" | "version" | "license" | "copyright" | "notice" | "usage" | "homepage" | "deliveryUnit" | "packageName" | "licenseUrl"} RequiredThirdPartyLicenseField */

/** @type {readonly RequiredThirdPartyLicenseField[]} */
const requiredFields = Object.freeze([
  "id",
  "name",
  "version",
  "license",
  "copyright",
  "notice",
  "usage",
  "homepage",
  "deliveryUnit",
  "packageName",
  "licenseUrl",
]);

const expectedFrontendComponents = Object.freeze([
  {
    id: "react",
    name: "React",
    packageName: "react",
  },
  {
    id: "lucide-react",
    name: "Lucide React",
    packageName: "lucide-react",
  },
  {
    id: "tanstack-react-query",
    name: "TanStack React Query",
    packageName: "@tanstack/react-query",
  },
]);

const expectedBackendComponents = Object.freeze([
  {
    id: "spring-boot-starter-webflux",
    name: "Spring Boot WebFlux Starter",
    version: "3.4.13",
  },
  {
    id: "agentscope-java",
    name: "AgentScope Java",
    version: "2.0.0-RC4",
  },
  {
    id: "jt400",
    name: "JT400",
    version: "21.0.6",
  },
]);

/** @type {Record<string, { version: string }>} */
const packageLockPackages = /** @type {Record<string, { version: string }>} */ (
  /** @type {unknown} */ (packageLock.packages)
);

describe("third party license data", () => {
  test("keeps the required frontend and backend component declarations", () => {
    expect(thirdPartyLicenses.length).toBeGreaterThanOrEqual(20);

    for (const expected of expectedFrontendComponents) {
      expect(thirdPartyLicenses).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            deliveryUnit: "frontend",
            id: expected.id,
            name: expected.name,
            packageName: expected.packageName,
          }),
        ]),
      );
    }

    for (const expected of expectedBackendComponents) {
      expect(thirdPartyLicenses).toEqual(
        expect.arrayContaining([
          expect.objectContaining({
            deliveryUnit: "backend",
            id: expected.id,
            name: expected.name,
            version: expected.version,
          }),
        ]),
      );
    }
  });

  test("keeps every declaration complete with a license notice", () => {
    for (const declaration of thirdPartyLicenses) {
      for (const field of requiredFields) {
        expect(declaration[field], `${declaration.id}.${field}`).toEqual(expect.any(String));
        expect(declaration[field].trim().length, `${declaration.id}.${field}`).toBeGreaterThan(0);
      }

      expect(["frontend", "backend"]).toContain(declaration.deliveryUnit);
      expect(declaration.notice, `${declaration.id}.notice`).toContain(declaration.license.split(" / ")[0]);
      expect(declaration.notice, `${declaration.id}.notice`).toContain(declaration.copyright);
    }
  });

  test("keeps frontend versions aligned with locked direct runtime dependencies", () => {
    for (const expected of expectedFrontendComponents) {
      expect(findThirdPartyLicenseById(expected.id)?.version).toBe(lockedPackageVersion(expected.packageName));
    }
  });

  test("summarizes component count, license types, and delivery units", () => {
    expect(thirdPartyLicensesSummary.componentCount).toBe(thirdPartyLicenses.length);
    expect(thirdPartyLicensesSummary.licenseTypes).toEqual([
      "Apache License 2.0",
      "IBM Public License 1.0",
      "ISC License",
      "MIT License",
      "MPL 2.0 / EPL 1.0",
    ]);
    expect(thirdPartyLicensesSummary.deliveryUnits).toEqual([
      { count: 14, id: "frontend", label: "前端操作台" },
      { count: 16, id: "backend", label: "后端服务" },
    ]);
  });

  test("finds a declaration by id and returns undefined for unknown ids", () => {
    expect(findThirdPartyLicenseById("react")).toMatchObject({
      id: "react",
      name: "React",
    });
    expect(findThirdPartyLicenseById("missing")).toBeUndefined();
  });

  test("does not declare queFork before it is confirmed as integrated", () => {
    const serializedDeclarations = JSON.stringify(thirdPartyLicenses).toLowerCase();

    expect(serializedDeclarations).not.toContain("quefork");
    expect(thirdPartyLicenses.map(({ id }) => id)).not.toContain("queFork");
  });
});

/**
 * @param {string} packageName
 * @returns {string}
 */
function lockedPackageVersion(packageName) {
  return packageLockPackages[`node_modules/${packageName}`].version;
}
