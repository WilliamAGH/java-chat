import { readFile } from "node:fs/promises";
import { dirname, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const frontendRoot = resolve(dirname(fileURLToPath(import.meta.url)), "..");
const repositoryRoot = resolve(frontendRoot, "..");

const frontendPackage = JSON.parse(await readFile(resolve(frontendRoot, "package.json"), "utf8"));
const frontendLockfile = JSON.parse(
  await readFile(resolve(frontendRoot, "package-lock.json"), "utf8"),
);
const canonicalNodeVersion = frontendPackage.engines?.node;

if (typeof canonicalNodeVersion !== "string" || canonicalNodeVersion.length === 0) {
  throw new Error("frontend/package.json must define engines.node");
}

const nodeVersionFile = (await readFile(resolve(frontendRoot, ".nvmrc"), "utf8")).trim();
const dockerfile = await readFile(resolve(repositoryRoot, "Dockerfile"), "utf8");
const gettingStartedGuide = await readFile(
  resolve(repositoryRoot, "docs/getting-started.md"),
  "utf8",
);

const expectedNodeVersionFile = `v${canonicalNodeVersion}`;
const projectedLockfileVersion = frontendLockfile.packages?.[""]?.engines?.node;
const expectedDockerImage = `public.ecr.aws/docker/library/node:${canonicalNodeVersion}-bookworm-slim`;
const expectedDocumentationVersion = `Node.js ${canonicalNodeVersion}`;

if (nodeVersionFile !== expectedNodeVersionFile) {
  throw new Error(`frontend/.nvmrc must contain ${expectedNodeVersionFile}`);
}
if (projectedLockfileVersion !== canonicalNodeVersion) {
  throw new Error("frontend/package-lock.json must project package.json engines.node");
}
if (!dockerfile.includes(expectedDockerImage)) {
  throw new Error(`Dockerfile must use ${expectedDockerImage}`);
}
if (!gettingStartedGuide.includes(expectedDocumentationVersion)) {
  throw new Error(`docs/getting-started.md must name ${expectedDocumentationVersion}`);
}

console.log(`Node ${canonicalNodeVersion} projections are synchronized.`);
