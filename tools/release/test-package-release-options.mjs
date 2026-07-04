import assert from "node:assert/strict";

import {
  createBackendBuildCommand,
  createFrontendBuildSteps,
  parseReleasePackageArgs,
} from "./package-release-options.mjs";

const defaults = parseReleasePackageArgs([]);
assert.equal(defaults.skipFrontendTests, false);
assert.deepEqual(createFrontendBuildSteps(defaults), [
  { command: "npm", args: ["run", "build"] },
]);
assert.equal(defaults.mavenCommand, "mvn");
assert.deepEqual(
  createBackendBuildCommand(defaults, "C:\\repo\\frontend\\operator-console\\dist"),
  {
    command: "mvn",
    args: [
      "-f",
      "pom.xml",
      "-B",
      "-ntp",
      "-Dops-agent.include-operator-console=true",
      "-Dops-agent.operator-console.dist=C:\\repo\\frontend\\operator-console\\dist",
      "verify",
    ],
  },
);

const parsed = parseReleasePackageArgs([
  "--skip-tests",
  "--skip-frontend-install",
  "--skip-frontend-tests",
  "--version",
  "0.1.0-test",
  "--artifact-root",
  "tmp/release",
  "--publish-dir",
  "tmp/publish",
  "--maven-command",
  "C:\\tools\\apache-maven\\bin\\mvn.cmd",
]);
assert.equal(parsed.skipTests, true);
assert.equal(parsed.skipFrontendInstall, true);
assert.equal(parsed.skipFrontendTests, true);
assert.equal(parsed.version, "0.1.0-test");
assert.equal(parsed.artifactRoot, "tmp/release");
assert.equal(parsed.publishDirectory, "tmp/publish");
assert.equal(parsed.mavenCommand, "C:\\tools\\apache-maven\\bin\\mvn.cmd");
assert.deepEqual(createFrontendBuildSteps(parsed), [
  { command: "npm", args: ["exec", "vite", "--", "build"] },
]);
assert.deepEqual(
  createBackendBuildCommand(parsed, "C:\\repo\\frontend\\operator-console\\dist"),
  {
    command: "C:\\tools\\apache-maven\\bin\\mvn.cmd",
    args: [
      "-f",
      "pom.xml",
      "-B",
      "-ntp",
      "-Dops-agent.include-operator-console=true",
      "-Dops-agent.operator-console.dist=C:\\repo\\frontend\\operator-console\\dist",
      "-DskipTests",
      "package",
    ],
  },
);

assert.throws(
  () => parseReleasePackageArgs(["--skip-frontend-test"]),
  /Unknown argument: --skip-frontend-test/,
);

console.log("Release package option tests passed.");
