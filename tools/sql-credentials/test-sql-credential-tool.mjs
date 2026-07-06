import assert from "node:assert/strict";
import { execFileSync, spawnSync } from "node:child_process";
import { mkdtempSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import path from "node:path";

const repoRoot = path.resolve(import.meta.dirname, "..", "..");
const toolScript = path.join(repoRoot, "tools", "sql-credentials", "put-sql-credential.cmd");
const tempDir = mkdtempSync(path.join(tmpdir(), "ops-agent-sql-credential-tool-"));

try {
  const keyStorePath = path.join(tempDir, "sql-credentials.jceks");
  const commandLine = [
    toolScript,
    "put",
    "--store",
    keyStorePath,
    "--alias",
    "as400-dev-readonly",
    "--store-password-env",
    "OPS_AGENT_SQL_KEYSTORE_PASSWORD",
    "--secret-stdin",
  ].join(" ");
  const result = spawnSync(
    "cmd.exe",
    ["/d", "/c", commandLine],
    {
      cwd: repoRoot,
      env: {
        ...process.env,
        OPS_AGENT_SQL_KEYSTORE_PASSWORD: "store-password",
      },
      input: "database-password\n",
      encoding: "utf8",
    },
  );

  assert.equal(result.status, 0, result.stderr);
  assert.match(result.stdout, /SQL credential alias written: as400-dev-readonly/);
  assert.match(result.stdout, /KeyStore:/);
  assert.doesNotMatch(result.stdout, /database-password/);
  assert.doesNotMatch(result.stderr, /database-password/);

  const verifierSource = path.join(tempDir, "ReadJceksSecret.java");
  writeFileSync(
    verifierSource,
    `
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import javax.crypto.SecretKey;

public final class ReadJceksSecret {
  public static void main(String[] args) throws Exception {
    KeyStore keyStore = KeyStore.getInstance("JCEKS");
    char[] storePassword = args[1].toCharArray();
    try (var input = Files.newInputStream(Path.of(args[0]))) {
      keyStore.load(input, storePassword);
    }
    KeyStore.Entry entry = keyStore.getEntry(args[2], new KeyStore.PasswordProtection(storePassword));
    SecretKey secretKey = ((KeyStore.SecretKeyEntry) entry).getSecretKey();
    System.out.print(new String(secretKey.getEncoded(), StandardCharsets.UTF_8));
  }
}
`,
  );
  execFileSync("javac", [verifierSource], { cwd: tempDir, stdio: "pipe" });
  const secret = execFileSync(
    "java",
    ["-cp", tempDir, "ReadJceksSecret", keyStorePath, "store-password", "as400-dev-readonly"],
    { encoding: "utf8" },
  );

  assert.equal(secret, "database-password");
  console.log("SQL credential cmd tool tests passed.");
} finally {
  rmSync(tempDir, { recursive: true, force: true });
}
