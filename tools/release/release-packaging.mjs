import { createHash } from "node:crypto";
import { createReadStream } from "node:fs";
import {
  access,
  chmod,
  copyFile,
  mkdir,
  readdir,
  readFile,
  rm,
  stat,
  writeFile,
} from "node:fs/promises";
import { dirname, join, relative, resolve } from "node:path";
import { spawn } from "node:child_process";

const forbiddenFileExtensions = new Set([".env", ".key", ".pem", ".p12", ".pfx", ".log"]);
const forbiddenDirectoryNames = new Set([".git", "node_modules", "target", "test-results"]);

export async function assertDirectoryExists(path, label) {
  let entry;
  try {
    entry = await stat(path);
  } catch {
    throw new Error(`${label} not found: ${path}`);
  }
  if (!entry.isDirectory()) {
    throw new Error(`${label} is not a directory: ${path}`);
  }
}

export async function assertFileExists(path, label) {
  let entry;
  try {
    entry = await stat(path);
  } catch {
    throw new Error(`${label} not found: ${path}`);
  }
  if (!entry.isFile()) {
    throw new Error(`${label} is not a file: ${path}`);
  }
}

export async function findSingleJar(directory, artifactPrefix) {
  await assertDirectoryExists(directory, `${artifactPrefix} target directory`);
  const entries = await readdir(directory, { withFileTypes: true });
  const candidates = entries
    .filter((entry) => entry.isFile())
    .map((entry) => entry.name)
    .filter((name) => name.startsWith(artifactPrefix) && name.endsWith(".jar"))
    .filter((name) => !name.endsWith("-sources.jar") && !name.endsWith("-javadoc.jar"))
    .sort();

  if (candidates.length !== 1) {
    throw new Error(
      `Expected exactly one ${artifactPrefix} jar in ${directory}, found ${candidates.length}.`,
    );
  }
  return join(directory, candidates[0]);
}

export async function getSha256Hex(path) {
  await assertFileExists(path, "checksum source");
  return new Promise((resolveHash, rejectHash) => {
    const hash = createHash("sha256");
    const stream = createReadStream(path);
    stream.on("data", (chunk) => hash.update(chunk));
    stream.on("error", rejectHash);
    stream.on("end", () => resolveHash(hash.digest("hex")));
  });
}

export async function runCommand(command, args, options = {}) {
  const display = [command, ...args].join(" ");
  await new Promise((resolveCommand, rejectCommand) => {
    const child = spawn(command, args, {
      cwd: options.cwd,
      env: options.env ?? process.env,
      shell: false,
      stdio: options.stdio ?? "inherit",
      windowsHide: true,
    });
    child.on("error", (error) => rejectCommand(new Error(`${display} failed to start: ${error.message}`)));
    child.on("exit", (code) => {
      if (code === 0) {
        resolveCommand();
        return;
      }
      rejectCommand(new Error(`${display} failed with exit code ${code}.`));
    });
  });
}

export async function buildReleasePackage(options) {
  const {
    artifactRoot,
    controlPlaneJar,
    executionWorkerJar,
    frontendDist,
    gitCommit,
    now = new Date(),
    publishDirectory,
    skillPackagesDirectory,
    version,
  } = options;

  validateVersion(version);
  await assertFileExists(controlPlaneJar, "control plane jar");
  await assertFileExists(executionWorkerJar, "execution worker jar");
  await assertDirectoryExists(frontendDist, "frontend dist");
  await assertFileExists(join(frontendDist, "index.html"), "frontend index.html");
  if (skillPackagesDirectory) {
    await assertDirectoryExists(skillPackagesDirectory, "skill packages directory");
  }

  const releaseName = `ops-agent-${version}`;
  const resolvedArtifactRoot = resolve(artifactRoot);
  const releaseDirectory = join(resolvedArtifactRoot, releaseName);
  const zipPath = join(resolvedArtifactRoot, `${releaseName}.zip`);

  await rm(releaseDirectory, { recursive: true, force: true });
  await rm(zipPath, { force: true });
  await mkdir(releaseDirectory, { recursive: true });

  await copyFileInto(controlPlaneJar, join(releaseDirectory, "apps", "control-plane-bootstrap.jar"));
  await copyFileInto(executionWorkerJar, join(releaseDirectory, "apps", "execution-worker.jar"));
  await copyDirectory(frontendDist, join(releaseDirectory, "frontend", "operator-console-dist"));
  if (skillPackagesDirectory) {
    await copyDirectory(skillPackagesDirectory, join(releaseDirectory, "contracts", "skills", "packages"));
  }
  await writeStartupConfiguration(join(releaseDirectory, "config"));
  await writeStartupScripts(join(releaseDirectory, "scripts"));
  await assertNoForbiddenReleaseContent(releaseDirectory);

  const payloadFiles = await listFiles(releaseDirectory);
  const fileEntries = await Promise.all(
    payloadFiles.map(async (path) => ({
      path: toPosixRelativePath(releaseDirectory, path),
      sha256: await getSha256Hex(path),
      sizeBytes: (await stat(path)).size,
    })),
  );
  fileEntries.sort((left, right) => left.path.localeCompare(right.path));

  const manifest = {
    artifactId: "ops-agent-unified-release",
    generatedAt: now.toISOString(),
    gitCommit,
    version,
    files: fileEntries,
  };
  const manifestPath = join(releaseDirectory, "release-manifest.json");
  await writeFile(manifestPath, `${JSON.stringify(manifest, null, 2)}\n`, "utf8");

  const checksumEntries = [
    ...fileEntries,
    {
      path: "release-manifest.json",
      sha256: await getSha256Hex(manifestPath),
      sizeBytes: (await stat(manifestPath)).size,
    },
  ].sort((left, right) => left.path.localeCompare(right.path));
  const checksumPath = join(releaseDirectory, "checksums.sha256");
  await writeFile(
    checksumPath,
    checksumEntries.map((entry) => `${entry.sha256}  ${entry.path}`).join("\n") + "\n",
    "utf8",
  );

  await createZipWithJarTool(releaseDirectory, zipPath);

  if (publishDirectory) {
    await mkdir(publishDirectory, { recursive: true });
    await copyFile(zipPath, join(publishDirectory, `${releaseName}.zip`));
    await copyFile(manifestPath, join(publishDirectory, `${releaseName}-manifest.json`));
    await copyFile(checksumPath, join(publishDirectory, `${releaseName}-checksums.sha256`));
  }

  return {
    checksumPath,
    manifestPath,
    releaseDirectory,
    zipPath,
  };
}

function validateVersion(version) {
  if (!version || !/^[A-Za-z0-9][A-Za-z0-9._-]*$/.test(version)) {
    throw new Error(`Invalid release version: ${version}`);
  }
}

async function copyFileInto(source, destination) {
  await mkdir(dirname(destination), { recursive: true });
  await copyFile(source, destination);
}

async function copyDirectory(source, destination) {
  await mkdir(destination, { recursive: true });
  const entries = await readdir(source, { withFileTypes: true });
  for (const entry of entries) {
    const sourcePath = join(source, entry.name);
    const destinationPath = join(destination, entry.name);
    if (entry.isDirectory()) {
      assertAllowedPath(sourcePath, entry.name, true);
      await copyDirectory(sourcePath, destinationPath);
    } else if (entry.isFile()) {
      assertAllowedPath(sourcePath, entry.name, false);
      await copyFileInto(sourcePath, destinationPath);
    }
  }
}

async function writeStartupConfiguration(configDirectory) {
  await mkdir(configDirectory, { recursive: true });
  await writeFile(
    join(configDirectory, "start-ops-agent.cmd"),
    [
      "@rem Optional startup configuration loaded by scripts\\start-ops-agent.cmd.",
      "@rem Set one of these when java.exe is not available on PATH.",
      "@rem set \"OPS_AGENT_JAVA_HOME=C:\\Program Files\\Java\\jdk-21\"",
      "@rem set \"OPS_AGENT_JAVA_EXE=C:\\Program Files\\Java\\jdk-21\\bin\\java.exe\"",
      "if not defined OPS_AGENT_CONTROL_PLANE_ADDRESS set \"OPS_AGENT_CONTROL_PLANE_ADDRESS=0.0.0.0\"",
      "if not defined OPS_AGENT_CONTROL_PLANE_PORT set \"OPS_AGENT_CONTROL_PLANE_PORT=8080\"",
      "if not defined OPS_AGENT_WORKER_ADDRESS set \"OPS_AGENT_WORKER_ADDRESS=127.0.0.1\"",
      "if not defined OPS_AGENT_WORKER_PORT set \"OPS_AGENT_WORKER_PORT=8091\"",
      "if not defined OPS_AGENT_SPRING_PROFILES set \"OPS_AGENT_SPRING_PROFILES=demo\"",
      "@rem Leave empty to use contracts\\skills\\packages inside this release package.",
      "@rem set \"OPS_AGENT_SKILL_REGISTRY_ROOT_PATH=\"",
      "@rem set \"OPS_AGENT_CONTROL_PLANE_JAVA_OPTS=\"",
      "@rem set \"OPS_AGENT_WORKER_JAVA_OPTS=\"",
      "@rem set \"OPS_AGENT_CONTROL_PLANE_ARGS=\"",
      "@rem set \"OPS_AGENT_WORKER_ARGS=\"",
      "",
    ].join("\r\n"),
    "utf8",
  );
}

async function writeStartupScripts(scriptDirectory) {
  await mkdir(scriptDirectory, { recursive: true });
  const controlPlaneCmdPath = join(scriptDirectory, "start-control-plane.cmd");
  const controlPlaneShPath = join(scriptDirectory, "start-control-plane.sh");
  const executionWorkerCmdPath = join(scriptDirectory, "start-execution-worker.cmd");
  const executionWorkerShPath = join(scriptDirectory, "start-execution-worker.sh");
  const startAllCmdPath = join(scriptDirectory, "start-ops-agent.cmd");
  await writeFile(
    controlPlaneCmdPath,
    [
      "@echo off",
      "setlocal",
      "set APP_DIR=%~dp0\\..\\apps",
      "if defined OPS_AGENT_JAVA_EXE (",
      "  set \"JAVA_CMD=%OPS_AGENT_JAVA_EXE%\"",
      ") else if defined OPS_AGENT_JAVA_HOME (",
      "  set \"JAVA_CMD=%OPS_AGENT_JAVA_HOME%\\bin\\java.exe\"",
      ") else (",
      "  set \"JAVA_CMD=java\"",
      ")",
      '"%JAVA_CMD%" %OPS_AGENT_JAVA_OPTS% -jar "%APP_DIR%\\control-plane-bootstrap.jar" %*',
      "",
    ].join("\r\n"),
    "utf8",
  );
  await writeFile(
    controlPlaneShPath,
    [
      "#!/usr/bin/env sh",
      "set -eu",
      'SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)',
      'APP_DIR="$SCRIPT_DIR/../apps"',
      'exec java ${OPS_AGENT_JAVA_OPTS:-} -jar "$APP_DIR/control-plane-bootstrap.jar" "$@"',
      "",
    ].join("\n"),
    "utf8",
  );
  await writeFile(
    executionWorkerCmdPath,
    [
      "@echo off",
      "setlocal",
      "set APP_DIR=%~dp0\\..\\apps",
      "if defined OPS_AGENT_JAVA_EXE (",
      "  set \"JAVA_CMD=%OPS_AGENT_JAVA_EXE%\"",
      ") else if defined OPS_AGENT_JAVA_HOME (",
      "  set \"JAVA_CMD=%OPS_AGENT_JAVA_HOME%\\bin\\java.exe\"",
      ") else (",
      "  set \"JAVA_CMD=java\"",
      ")",
      '"%JAVA_CMD%" %OPS_AGENT_JAVA_OPTS% -jar "%APP_DIR%\\execution-worker.jar" %*',
      "",
    ].join("\r\n"),
    "utf8",
  );
  await writeFile(
    startAllCmdPath,
    [
      "@echo off",
      "setlocal EnableExtensions",
      "set \"SCRIPT_DIR=%~dp0\"",
      "for %%I in (\"%SCRIPT_DIR%..\") do set \"RELEASE_DIR=%%~fI\"",
      "set \"APP_DIR=%RELEASE_DIR%\\apps\"",
      "set \"LOG_DIR=%RELEASE_DIR%\\runtime-logs\"",
      "set \"CONFIG_FILE=%RELEASE_DIR%\\config\\start-ops-agent.cmd\"",
      "",
      "if exist \"%CONFIG_FILE%\" call \"%CONFIG_FILE%\"",
      "",
      "if not defined OPS_AGENT_CONTROL_PLANE_ADDRESS set \"OPS_AGENT_CONTROL_PLANE_ADDRESS=0.0.0.0\"",
      "if not defined OPS_AGENT_CONTROL_PLANE_PORT set \"OPS_AGENT_CONTROL_PLANE_PORT=8080\"",
      "if not defined OPS_AGENT_WORKER_ADDRESS set \"OPS_AGENT_WORKER_ADDRESS=127.0.0.1\"",
      "if not defined OPS_AGENT_WORKER_PORT set \"OPS_AGENT_WORKER_PORT=8091\"",
      "if not defined OPS_AGENT_SPRING_PROFILES set \"OPS_AGENT_SPRING_PROFILES=demo\"",
      "if not defined OPS_AGENT_SKILL_REGISTRY_ROOT_PATH set \"OPS_AGENT_SKILL_REGISTRY_ROOT_PATH=%RELEASE_DIR%\\contracts\\skills\\packages\"",
      "",
      "call :resolveJava || exit /b 1",
      "if not exist \"%APP_DIR%\\control-plane-bootstrap.jar\" (",
      "  echo Missing control plane jar: %APP_DIR%\\control-plane-bootstrap.jar",
      "  exit /b 1",
      ")",
      "if not exist \"%APP_DIR%\\execution-worker.jar\" (",
      "  echo Missing execution worker jar: %APP_DIR%\\execution-worker.jar",
      "  exit /b 1",
      ")",
      "if not exist \"%OPS_AGENT_SKILL_REGISTRY_ROOT_PATH%\" (",
      "  echo Missing Skill registry path: %OPS_AGENT_SKILL_REGISTRY_ROOT_PATH%",
      "  exit /b 1",
      ")",
      "if not exist \"%LOG_DIR%\" mkdir \"%LOG_DIR%\"",
      "",
      "echo Starting Ops Agent execution worker on %OPS_AGENT_WORKER_ADDRESS%:%OPS_AGENT_WORKER_PORT%",
      "start \"Ops Agent Execution Worker\" /D \"%RELEASE_DIR%\" cmd /c \"\"%OPS_AGENT_JAVA_EXE%\" %OPS_AGENT_WORKER_JAVA_OPTS% -jar \"%APP_DIR%\\execution-worker.jar\" --server.address=%OPS_AGENT_WORKER_ADDRESS% --server.port=%OPS_AGENT_WORKER_PORT% %OPS_AGENT_WORKER_ARGS% 1>>\"%LOG_DIR%\\worker.out.log\" 2>>\"%LOG_DIR%\\worker.err.log\"\"",
      "",
      "echo Starting Ops Agent control plane on %OPS_AGENT_CONTROL_PLANE_ADDRESS%:%OPS_AGENT_CONTROL_PLANE_PORT%",
      "start \"Ops Agent Control Plane\" /D \"%RELEASE_DIR%\" cmd /c \"\"%OPS_AGENT_JAVA_EXE%\" %OPS_AGENT_CONTROL_PLANE_JAVA_OPTS% -jar \"%APP_DIR%\\control-plane-bootstrap.jar\" --server.address=%OPS_AGENT_CONTROL_PLANE_ADDRESS% --server.port=%OPS_AGENT_CONTROL_PLANE_PORT% --spring.profiles.active=%OPS_AGENT_SPRING_PROFILES% --ops-agent.worker.base-url=http://127.0.0.1:%OPS_AGENT_WORKER_PORT% \"--ops-agent.skill-registry.root-path=%OPS_AGENT_SKILL_REGISTRY_ROOT_PATH%\" %OPS_AGENT_CONTROL_PLANE_ARGS% 1>>\"%LOG_DIR%\\control-plane.out.log\" 2>>\"%LOG_DIR%\\control-plane.err.log\"\"",
      "",
      "echo Logs: %LOG_DIR%",
      "echo Control plane: http://%COMPUTERNAME%:%OPS_AGENT_CONTROL_PLANE_PORT%",
      "exit /b 0",
      "",
      ":resolveJava",
      "if defined OPS_AGENT_JAVA_EXE (",
      "  if exist \"%OPS_AGENT_JAVA_EXE%\" exit /b 0",
      "  if /i \"%OPS_AGENT_JAVA_EXE%\"==\"java\" (",
      "    where java >nul 2>nul && exit /b 0",
      "  )",
      "  echo OPS_AGENT_JAVA_EXE does not exist: %OPS_AGENT_JAVA_EXE%",
      "  exit /b 1",
      ")",
      "if defined OPS_AGENT_JAVA_HOME (",
      "  if exist \"%OPS_AGENT_JAVA_HOME%\\bin\\java.exe\" (",
      "    set \"OPS_AGENT_JAVA_EXE=%OPS_AGENT_JAVA_HOME%\\bin\\java.exe\"",
      "    exit /b 0",
      "  )",
      "  echo OPS_AGENT_JAVA_HOME does not contain bin\\java.exe: %OPS_AGENT_JAVA_HOME%",
      "  exit /b 1",
      ")",
      "set \"OPS_AGENT_JAVA_EXE=java\"",
      "where java >nul 2>nul",
      "if errorlevel 1 (",
      "  echo Java was not found. Set OPS_AGENT_JAVA_HOME or OPS_AGENT_JAVA_EXE in %CONFIG_FILE%.",
      "  exit /b 1",
      ")",
      "exit /b 0",
      "",
    ].join("\r\n"),
    "utf8",
  );
  await writeFile(
    executionWorkerShPath,
    [
      "#!/usr/bin/env sh",
      "set -eu",
      'SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)',
      'APP_DIR="$SCRIPT_DIR/../apps"',
      'exec java ${OPS_AGENT_JAVA_OPTS:-} -jar "$APP_DIR/execution-worker.jar" "$@"',
      "",
    ].join("\n"),
    "utf8",
  );
  await chmod(controlPlaneShPath, 0o755);
  await chmod(executionWorkerShPath, 0o755);
}

async function assertNoForbiddenReleaseContent(root) {
  const files = await listFiles(root);
  for (const file of files) {
    const relativePath = toPosixRelativePath(root, file);
    const segments = relativePath.split("/");
    for (const segment of segments.slice(0, -1)) {
      if (forbiddenDirectoryNames.has(segment)) {
        throw new Error(`Forbidden directory in release package: ${relativePath}`);
      }
    }
    assertAllowedPath(file, segments.at(-1), false);
  }
}

function assertAllowedPath(path, name, isDirectory) {
  if (isDirectory && forbiddenDirectoryNames.has(name)) {
    throw new Error(`Forbidden directory in release package: ${path}`);
  }
  if (!isDirectory) {
    if (name === ".env" || name.startsWith(".env.")) {
      throw new Error(`Forbidden environment file in release package: ${path}`);
    }
    for (const extension of forbiddenFileExtensions) {
      if (name.endsWith(extension)) {
        throw new Error(`Forbidden file type in release package: ${path}`);
      }
    }
  }
}

async function listFiles(root) {
  const result = [];
  async function visit(directory) {
    const entries = await readdir(directory, { withFileTypes: true });
    for (const entry of entries) {
      const path = join(directory, entry.name);
      if (entry.isDirectory()) {
        await visit(path);
      } else if (entry.isFile()) {
        result.push(path);
      }
    }
  }
  await visit(root);
  return result;
}

async function createZipWithJarTool(sourceDirectory, zipPath) {
  await mkdir(dirname(zipPath), { recursive: true });
  await access(sourceDirectory);
  await runCommand("jar", ["--create", "--file", zipPath, "-C", sourceDirectory, "."], {
    stdio: "pipe",
  });
}

function toPosixRelativePath(root, path) {
  return relative(root, path).replaceAll("\\", "/");
}
