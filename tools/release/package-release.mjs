#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

import {
  createBackendBuildCommand,
  createFrontendBuildSteps,
  parseReleasePackageArgs,
} from "./package-release-options.mjs";
import {
  assertDirectoryExists,
  buildReleasePackage,
  findSingleJar,
  runCommand,
} from "./release-packaging.mjs";

const scriptDirectory = dirname(fileURLToPath(import.meta.url));
const repositoryRoot = resolve(scriptDirectory, "../..");
const backendRoot = join(repositoryRoot, "backend");
const frontendRoot = join(repositoryRoot, "frontend", "operator-console");

const options = parseReleasePackageArgs(process.argv.slice(2));
if (options.help) {
  printHelp();
  process.exit(0);
}
const version = options.version ?? await readMavenProjectVersion(join(backendRoot, "pom.xml"));
const artifactRoot = resolve(repositoryRoot, options.artifactRoot ?? join("artifacts", "release"));
const publishDirectory = options.publishDirectory
  ? resolve(repositoryRoot, options.publishDirectory)
  : undefined;
const frontendDist = join(frontendRoot, "dist");
const skillPackagesDirectory = join(backendRoot, "contracts", "skills", "packages");

if (!options.skipFrontendInstall) {
  await runPortableCommand("npm", ["ci"], { cwd: frontendRoot });
}

for (const step of createFrontendBuildSteps(options)) {
  await runPortableCommand(step.command, step.args, { cwd: frontendRoot });
}
await assertDirectoryExists(frontendDist, "frontend dist");

const backendBuild = createBackendBuildCommand(options, frontendDist);
await runPortableCommand(backendBuild.command, backendBuild.args, { cwd: backendRoot });

const controlPlaneJar = await findSingleJar(
  join(backendRoot, "control-plane", "bootstrap", "target"),
  "control-plane-bootstrap",
);
const executionWorkerJar = await findSingleJar(
  join(backendRoot, "execution-worker", "target"),
  "execution-worker",
);

const result = await buildReleasePackage({
  artifactRoot,
  controlPlaneJar,
  executionWorkerJar,
  frontendDist,
  gitCommit: readGitCommit(),
  publishDirectory,
  skillPackagesDirectory,
  version,
});

console.log(`Release directory: ${result.releaseDirectory}`);
console.log(`Release zip: ${result.zipPath}`);
console.log(`Release manifest: ${result.manifestPath}`);
console.log(`Release checksums: ${result.checksumPath}`);

function printHelp() {
  console.log(`Usage: node tools/release/package-release.mjs [options]

Options:
  --version <value>             Override release version. Defaults to backend/pom.xml version.
  --artifact-root <path>        Output root. Defaults to artifacts/release.
  --publish-dir <path>          Copy zip, manifest, and checksum files to an external directory.
  --maven-command <command>     Maven executable. Defaults to system mvn on PATH.
  --skip-tests                  Use Maven package with -DskipTests for local packaging verification.
  --skip-frontend-install       Skip npm ci when node_modules is already prepared.
  --skip-frontend-tests         Run only Vite build for the frontend.
  -h, --help                    Show this help.
`);
}

async function readMavenProjectVersion(pomPath) {
  const pom = await readFile(pomPath, "utf8");
  const match = pom.match(/<project[\s\S]*?<version>([^<]+)<\/version>/);
  if (!match) {
    throw new Error(`Cannot read Maven project version from ${pomPath}`);
  }
  return match[1].trim();
}

async function runPortableCommand(command, args, options) {
  if (process.platform !== "win32") {
    await runCommand(command, args, options);
    return;
  }

  const windowsCommand = command.endsWith(".cmd") ? command : `${command}.cmd`;
  await runCommand("cmd.exe", ["/d", "/s", "/c", windowsCommand, ...args], options);
}

function readGitCommit() {
  const result = spawnSync("git", ["rev-parse", "--short", "HEAD"], {
    cwd: repositoryRoot,
    encoding: "utf8",
    windowsHide: true,
  });
  if (result.status !== 0) {
    return process.env.GITHUB_SHA ?? "local";
  }
  return result.stdout.trim();
}
