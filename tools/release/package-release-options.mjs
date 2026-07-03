export function parseReleasePackageArgs(args) {
  const parsed = {
    artifactRoot: undefined,
    publishDirectory: undefined,
    skipFrontendInstall: false,
    skipFrontendTests: false,
    skipTests: false,
    version: undefined,
  };

  for (let index = 0; index < args.length; index += 1) {
    const arg = args[index];
    if (arg === "--help" || arg === "-h") {
      parsed.help = true;
      continue;
    }
    if (arg === "--skip-frontend-install") {
      parsed.skipFrontendInstall = true;
      continue;
    }
    if (arg === "--skip-frontend-tests") {
      parsed.skipFrontendTests = true;
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

export function createFrontendBuildSteps(options) {
  if (!options.skipFrontendTests) {
    return [{ command: "npm", args: ["run", "build"] }];
  }

  return [{ command: "npm", args: ["exec", "vite", "--", "build"] }];
}

function readValue(args, index, name) {
  const value = args[index + 1];
  if (!value || value.startsWith("--")) {
    throw new Error(`${name} requires a value.`);
  }
  return value;
}
