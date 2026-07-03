import assert from "node:assert/strict";

import {
  createFrontendBuildSteps,
  parseReleasePackageArgs,
} from "./package-release-options.mjs";

const defaults = parseReleasePackageArgs([]);
assert.equal(defaults.skipFrontendTests, false);
assert.deepEqual(createFrontendBuildSteps(defaults), [
  { command: "npm", args: ["run", "build"] },
]);

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
]);
assert.equal(parsed.skipTests, true);
assert.equal(parsed.skipFrontendInstall, true);
assert.equal(parsed.skipFrontendTests, true);
assert.equal(parsed.version, "0.1.0-test");
assert.equal(parsed.artifactRoot, "tmp/release");
assert.equal(parsed.publishDirectory, "tmp/publish");
assert.deepEqual(createFrontendBuildSteps(parsed), [
  { command: "npm", args: ["run", "check"] },
  { command: "npm", args: ["run", "lint"] },
  { command: "npm", args: ["exec", "vite", "--", "build"] },
]);

assert.throws(
  () => parseReleasePackageArgs(["--skip-frontend-test"]),
  /Unknown argument: --skip-frontend-test/,
);

console.log("Release package option tests passed.");
