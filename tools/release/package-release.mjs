#!/usr/bin/env node
import { readFile } from "node:fs/promises";
import { spawnSync } from "node:child_process";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

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

const options = await parseArgs(process.argv.slice(2));
const version = options.version ?? await readMavenProjectVersion(join(backendRoot, "pom.xml"));
const artifactRoot = resolve(repositoryRoot, options.artifactRoot ?? join("artifacts", "release"));
const publishDirectory = options.publishDirectory
  ? resolve(repositoryRoot, options.publishDirectory)
  : undefined;
const frontendDist = join(frontendRoot, "dist");

if (!options.skipFrontendInstall) {
  await runPortableCommand("npm", ["ci"], { cwd: frontendRoot });
}

await runPortableCommand("npm", ["run", "build"], { cwd: frontendRoot });
await assertDirectoryExists(frontendDist, "frontend dist");

const mavenGoal = options.skipTests ? "package" : "verify";
const mavenArgs = [
  "-f",
  "pom.xml",
  "-B",
  "-ntp",
  "-Dops-agent.include-operator-console=true",
  `-Dops-agent.operator-console.dist=${frontendDist}`,
];
if (options.skipTests) {
  mavenArgs.push("-DskipTests");
}
mavenArgs.push(mavenGoal);
await runMaven(mavenArgs);

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
  version,
});

console.log(`Release directory: ${result.releaseDirectory}`);
console.log(`Release zip: ${result.zipPath}`);
console.log(`Release manifest: ${result.manifestPath}`);
console.log(`Release checksums: ${result.checksumPath}`);

async function parseArgs(args) {
  const parsed = {
    artifactRoot: undefined,
    publishDirectory: undefined,
    skipFrontendInstall: false,
    skipTests: false,
    version: undefined,
  };

  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === "--help" || arg === "-h") {
      printHelp();
      process.exit(0);
    }
    if (arg === "--skip-frontend-install") {
      parsed.skipFrontendInstall = true;
      continue;
    }
    if (arg === "--skip-tests") {
      parsed.skipTests = true;
      continue;
    }
    if (arg === "--version") {
      parsed.version = readValue(args, index, arg);
      index += 1;
      continue;
    }
    if (arg === "--artifact-root") {
      parsed.artifactRoot = readValue(args, index, arg);
      index += 1;
      continue;
    }
    if (arg === "--publish-dir") {
      parsed.publishDirectory = readValue(args, index, arg);
      index += 1;
      continue;
    }
    throw new Error(`Unknown argument: ${arg}`);
  }

  return parsed;
}

function readValue(args, index, name) {
  const value = args[index + 1];
  if (!value || value.startsWith("--")) {
    throw new Error(`${name} requires a value.`);
  }
  return value;
}

function printHelp() {
  console.log(`Usage: node tools/release/package-release.mjs [options]

Options:
  --version <value>             Override release version. Defaults to backend/pom.xml version.
  --artifact-root <path>        Output root. Defaults to artifacts/release.
  --publish-dir <path>          Copy zip, manifest, and checksum files to an external directory.
  --skip-tests                  Use Maven package with -DskipTests for local packaging verification.
  --skip-frontend-install       Skip npm ci when node_modules is already prepared.
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

async function runMaven(args) {
  const wrapper = process.platform === "win32" ? "mvnw.cmd" : "./mvnw";
  await runPortableCommand(wrapper, args, { cwd: backendRoot });
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
