import assert from "node:assert/strict";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

import {
  assertDirectoryExists,
  buildReleasePackage,
  findSingleJar,
  getSha256Hex,
} from "./release-packaging.mjs";

const root = await mkdtemp(join(tmpdir(), "ops-agent-release-test-"));

try {
  const dist = join(root, "dist");
  const controlTarget = join(root, "target", "control");
  const workerTarget = join(root, "target", "worker");
  await mkdir(dist, { recursive: true });
  await mkdir(controlTarget, { recursive: true });
  await mkdir(workerTarget, { recursive: true });
  await writeFile(join(dist, "index.html"), "<div>operator console</div>");
  await writeFile(join(controlTarget, "control-plane-bootstrap.jar"), "control");
  await writeFile(join(workerTarget, "execution-worker.jar"), "worker");

  await assert.rejects(
    () => assertDirectoryExists(join(root, "missing"), "missing dist"),
    /missing dist/,
  );

  assert.equal(
    await findSingleJar(controlTarget, "control-plane-bootstrap"),
    join(controlTarget, "control-plane-bootstrap.jar"),
  );

  const result = await buildReleasePackage({
    artifactRoot: join(root, "artifacts"),
    controlPlaneJar: join(controlTarget, "control-plane-bootstrap.jar"),
    executionWorkerJar: join(workerTarget, "execution-worker.jar"),
    frontendDist: dist,
    gitCommit: "test-commit",
    now: new Date("2026-07-03T00:00:00.000Z"),
    version: "0.1.0-test",
  });

  const manifest = JSON.parse(await readFile(result.manifestPath, "utf8"));
  assert.equal(manifest.version, "0.1.0-test");
  assert.equal(manifest.gitCommit, "test-commit");
  assert.ok(manifest.files.some((file) => file.path === "apps/control-plane-bootstrap.jar"));
  assert.ok(manifest.files.some((file) => file.path === "apps/execution-worker.jar"));
  assert.ok(manifest.files.some((file) => file.path === "frontend/operator-console-dist/index.html"));
  assert.ok(manifest.files.some((file) => file.path === "scripts/start-control-plane.cmd"));
  assert.ok(manifest.files.some((file) => file.path === "scripts/start-execution-worker.cmd"));
  assert.ok(manifest.files.some((file) => file.path === "scripts/start-control-plane.sh"));
  assert.ok(manifest.files.some((file) => file.path === "scripts/start-execution-worker.sh"));

  const checksums = await readFile(result.checksumPath, "utf8");
  assert.match(checksums, /apps\/control-plane-bootstrap\.jar/);
  assert.match(checksums, /frontend\/operator-console-dist\/index\.html/);
  assert.match(await getSha256Hex(join(controlTarget, "control-plane-bootstrap.jar")), /^[a-f0-9]{64}$/);
  assert.match(result.zipPath, /ops-agent-0\.1\.0-test\.zip$/);

  const zipBytes = await readFile(result.zipPath);
  assert.ok(zipBytes.length > 0);

  console.log("Release packaging tests passed.");
} finally {
  await rm(root, { recursive: true, force: true });
}
