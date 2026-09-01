#!/usr/bin/env node
/**
 * Java Chat command-line client.
 *
 * Talks to the same public HTTP API the website uses, so a terminal answer is
 * the website's answer: identical retrieval, identical citations, identical
 * model. Authentication is a personal Clerk API key obtained through the
 * browser once and stored locally.
 */

import { createServer } from "node:http";
import { spawn, spawnSync } from "node:child_process";
import { randomUUID, randomBytes } from "node:crypto";
import { existsSync, realpathSync } from "node:fs";
import { hostname, homedir, platform } from "node:os";
import { mkdir, readFile, writeFile, chmod, rm } from "node:fs/promises";
import { basename, dirname, join, resolve, sep } from "node:path";
import { stdout, stderr, argv, exit, env } from "node:process";
import { stripVTControlCharacters } from "node:util";
import packageMetadata from "../package.json" with { type: "json" };

const DEFAULT_HOST = "https://javachat.ai";
const CREDENTIALS_MODE = 0o600;
const CREDENTIALS_DIRECTORY_MODE = 0o700;
const LOGIN_TIMEOUT_MS = 5 * 60 * 1000;
const CITATION_DISPLAY_LIMIT = 5;
const CLIENT_LABEL_MAX_LENGTH = 64;
const CLI_PACKAGE = packageMetadata.name;

// The assistant is instructed to emit enrichment markers (SystemPromptConfig's
// MARKER_USAGE_PROMPT); the web client renders each as a titled callout. Titles
// are restated here rather than shared because this is the terminal's own
// presentation boundary, and a terminal has no icons or panels to reuse.
const ENRICHMENT_MARKER_TITLES = new Map([
  ["hint", "Helpful Hints"],
  ["background", "Background Context"],
  ["reminder", "Important Reminders"],
  ["warning", "Warning"],
  ["example", "Example"],
]);
const MARKER_OPEN = "{{";
const MARKER_CLOSE = "}}";
// A marker that never closes must not buffer the answer indefinitely; past this
// length the held text is released verbatim and treated as ordinary prose.
const MARKER_MAX_LENGTH = 4096;

/** Resolves the credential file honouring XDG, so a home directory stays tidy. */
function credentialsPath() {
  const configHome = env.XDG_CONFIG_HOME?.trim() || join(homedir(), ".config");
  return join(configHome, "javachat", "credentials.json");
}

/** Normalizes a host so "javachat.ai" and a trailing slash both resolve alike. */
function normalizeHost(host) {
  const candidate = host.includes("://") ? host : `https://${host}`;
  let parsed;
  try {
    parsed = new URL(candidate);
  } catch {
    throw new Error(`Invalid JavaChat host "${host}": expected a URL like https://javachat.ai`);
  }
  if (parsed.protocol !== "https:" && parsed.protocol !== "http:") {
    throw new Error("JavaChat host must use HTTP or HTTPS.");
  }
  const loopbackHost =
    parsed.hostname === "localhost" ||
    parsed.hostname === "127.0.0.1" ||
    parsed.hostname === "[::1]";
  if (parsed.protocol === "http:" && !loopbackHost) {
    throw new Error("Remote JavaChat hosts must use HTTPS.");
  }
  return parsed.origin;
}

async function readCredentials() {
  try {
    const stored = await readFile(credentialsPath(), "utf8");
    const parsed = JSON.parse(stored);
    if (!parsed || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error("credential document must be a JSON object");
    }
    for (const [storedHost, storedCredential] of Object.entries(parsed)) {
      if (
        !storedCredential ||
        typeof storedCredential !== "object" ||
        typeof storedCredential.apiKey !== "string" ||
        !storedCredential.apiKey.startsWith("ak_") ||
        typeof storedCredential.sessionId !== "string"
      ) {
        throw new Error(`credential entry for ${storedHost} is malformed`);
      }
    }
    return parsed;
  } catch (readFailure) {
    if (readFailure.code === "ENOENT") return {};
    throw new Error(`Could not read ${credentialsPath()}: ${readFailure.message}`);
  }
}

/** Writes credentials owner-only; the file holds a live API key. */
async function writeCredentials(allHosts) {
  const path = credentialsPath();
  const credentialsDirectory = dirname(path);
  await mkdir(credentialsDirectory, { recursive: true, mode: CREDENTIALS_DIRECTORY_MODE });
  await chmod(credentialsDirectory, CREDENTIALS_DIRECTORY_MODE);
  await writeFile(path, `${JSON.stringify(allHosts, null, 2)}\n`, { mode: CREDENTIALS_MODE });
  await chmod(path, CREDENTIALS_MODE);
}

async function storedKeyFor(host) {
  const envKey = env.JAVACHAT_API_KEY?.trim();
  if (envKey) return envKey;
  const allHosts = await readCredentials();
  return allHosts[host]?.apiKey ?? null;
}

/** Opens a URL in the desktop browser without failing the flow when none exists. */
function openBrowser(url) {
  const opener = platform() === "darwin" ? "open" : "xdg-open";
  const openerArguments = [url];
  const child = spawn(opener, openerArguments, { stdio: "ignore", detached: true });
  child.on("error", (browserOpenFailure) => {
    stderr.write(
      `Could not open the browser automatically: ${stripVTControlCharacters(browserOpenFailure.message)}\n`,
    );
  });
  child.unref();
}

const CALLBACK_PAGE = `<!doctype html>
<meta charset="utf-8"><title>Java Chat CLI</title>
<style>body{font:16px/1.5 system-ui,sans-serif;margin:15vh auto;max-width:32rem;padding:0 1.5rem}
h1{font-size:1.25rem;margin:0 0 .5rem}p{color:#555;margin:0}</style>
<h1 id="heading">Completing authorization…</h1><p id="detail">Keep this tab open.</p>
<script>
const approval = new URLSearchParams(location.hash.slice(1));
history.replaceState(null, "", location.pathname);
fetch("/complete", {
  method: "POST",
  headers: {"content-type": "application/json"},
  body: JSON.stringify({state: approval.get("state"), key: approval.get("key")})
}).then(response => {
  if (!response.ok) throw new Error();
  document.querySelector("#heading").textContent = "Java Chat CLI is authorized";
  document.querySelector("#detail").textContent = "You can close this tab and return to your terminal.";
}).catch(() => {
  document.querySelector("#heading").textContent = "Authorization rejected";
  document.querySelector("#detail").textContent = "Return to your terminal and try again.";
});
</script>`;

/**
 * Runs the browser authorization round trip.
 *
 * The loopback listener is the credential's only delivery path: it binds
 * 127.0.0.1 on an ephemeral port, accepts exactly one matching callback, and
 * closes. A mismatched or missing state is rejected so another local page
 * cannot hand this process a key it did not request.
 */
async function authorizeThroughBrowser(host, shouldOpenBrowser) {
  const expectedState = randomBytes(24).toString("base64url");
  const clientLabel = (hostname().trim() || "javachat-cli").slice(0, CLIENT_LABEL_MAX_LENGTH);

  return await new Promise((resolve, reject) => {
    const listener = createServer((request, response) => {
      const requested = new URL(request.url, "http://127.0.0.1");
      if (requested.pathname === "/callback" && request.method === "GET") {
        response.writeHead(200, { "content-type": "text/html; charset=utf-8" });
        response.end(CALLBACK_PAGE);
        return;
      }
      if (requested.pathname !== "/complete" || request.method !== "POST") {
        response.writeHead(404).end();
        return;
      }
      let requestBody = "";
      request.setEncoding("utf8");
      request.on("data", (bodyChunk) => {
        requestBody += bodyChunk;
        if (requestBody.length > 1024) request.destroy();
      });
      request.on("end", () => {
        let callback;
        try {
          callback = JSON.parse(requestBody);
        } catch {
          response.writeHead(400).end();
          finishFailure(new Error("Authorization callback was malformed."));
          return;
        }
        if (!callback || typeof callback !== "object" || Array.isArray(callback)) {
          response.writeHead(400).end();
          finishFailure(new Error("Authorization callback was malformed."));
          return;
        }
        if (
          callback.state !== expectedState ||
          typeof callback.key !== "string" ||
          !callback.key.startsWith("ak_") ||
          callback.key.length > 512
        ) {
          response.writeHead(400).end();
          finishFailure(new Error("Callback did not match this login attempt."));
          return;
        }
        response.writeHead(204).end();
        finishSuccess(callback.key);
      });
    });

    const timer = setTimeout(
      () => finishFailure(new Error("Timed out waiting for browser approval.")),
      LOGIN_TIMEOUT_MS,
    );

    let settled = false;
    function settle(settlement) {
      if (settled) return;
      settled = true;
      clearTimeout(timer);
      listener.close();
      settlement();
    }

    function finishFailure(error) {
      settle(() => reject(error));
    }

    function finishSuccess(apiKey) {
      settle(() => resolve(apiKey));
    }

    listener.on("error", finishFailure);
    listener.listen(0, "127.0.0.1", () => {
      const { port } = listener.address();
      const approvalUrl = new URL("/cli/authorize", host);
      approvalUrl.searchParams.set("port", String(port));
      approvalUrl.searchParams.set("state", expectedState);
      approvalUrl.searchParams.set("label", clientLabel);

      if (shouldOpenBrowser) openBrowser(approvalUrl.href);
      stderr.write(
        `Open this URL to approve this terminal as "${clientLabel}":\n\n  ${approvalUrl.href}\n\n`,
      );
      stderr.write("Waiting for approval...\n");
    });
  });
}

async function commandLogin(host, options) {
  if (env.JAVACHAT_API_KEY?.trim()) {
    stderr.write(
      `JAVACHAT_API_KEY is set and already authenticates requests to ${host}. Unset it to sign in with the browser instead.\n`,
    );
    return 0;
  }
  const storedHosts = await readCredentials();
  if (storedHosts[host]?.apiKey) {
    stderr.write(
      `Already signed in to ${host}. Run "javachat auth logout" before replacing the key.\n`,
    );
    return 0;
  }
  const apiKey = await authorizeThroughBrowser(host, !options.noBrowser);
  const allHosts = await readCredentials();
  allHosts[host] = { apiKey, sessionId: `chat-${randomUUID()}` };
  await writeCredentials(allHosts);
  const identity = await fetchIdentity(host, apiKey);
  stdout.write(
    `Signed in to ${host} as ${identity}.\nKey stored in ${credentialsPath()} (owner-only).\n`,
  );
  return 0;
}

async function commandLogout(host) {
  if (env.JAVACHAT_API_KEY?.trim()) {
    stderr.write(
      `Authentication for ${host} comes from JAVACHAT_API_KEY. Unset it, then run "javachat auth logout" again to remove any stored fallback credential.\n`,
    );
    return 0;
  }
  const allHosts = await readCredentials();
  if (!allHosts[host]) {
    stderr.write(`No stored credential for ${host}.\n`);
    return 0;
  }
  await revokeApiKey(host, allHosts[host].apiKey);
  delete allHosts[host];
  Object.keys(allHosts).length === 0
    ? await rm(credentialsPath(), { force: true })
    : await writeCredentials(allHosts);
  stdout.write(`Revoked and removed the local credential for ${host}.\n`);
  return 0;
}

async function revokeApiKey(host, apiKey) {
  const revocationResponse = await fetch(new URL("/api/me/api-key", host), {
    method: "DELETE",
    headers: { authorization: `Bearer ${apiKey}` },
  });
  if (!revocationResponse.ok && revocationResponse.status !== 401) {
    throw new Error(`Key revocation failed: HTTP ${revocationResponse.status}`);
  }
}

/** Confirms a key authenticates, returning the Clerk subject it belongs to. */
async function fetchIdentity(host, apiKey) {
  const identityResponse = await fetch(new URL("/api/me", host), {
    headers: { authorization: `Bearer ${apiKey}` },
  });
  if (!identityResponse.ok) {
    throw new Error(`Key verification failed: HTTP ${identityResponse.status}`);
  }
  const identity = await identityResponse.json();
  if (
    !identity ||
    typeof identity !== "object" ||
    Array.isArray(identity) ||
    typeof identity.userId !== "string" ||
    !identity.userId
  ) {
    throw new Error("JavaChat returned an invalid authenticated identity.");
  }
  return identity.userId;
}

async function commandStatus(host) {
  const apiKey = await storedKeyFor(host);
  if (!apiKey) {
    stderr.write(`Not signed in to ${host}. Run "javachat auth login".\n`);
    return 1;
  }
  stdout.write(`${await fetchIdentity(host, apiKey)} at ${host}\n`);
  return 0;
}

/** Updates the npm installation that owns the invoked javachat command. */
async function commandUpdate() {
  const installTarget = await resolveNpmInstallTarget();
  stderr.write(`Updating ${CLI_PACKAGE} with npm...\n`);
  const updateResult = spawnSync("npm", installTarget.npmArguments, {
    cwd: installTarget.workingDirectory,
    stdio: "inherit",
  });
  if (updateResult.error) throw new Error(`Could not start npm: ${updateResult.error.message}`);
  if (updateResult.signal) {
    throw new Error(`npm install stopped after receiving ${updateResult.signal}.`);
  }
  const updateExitCode = updateResult.status ?? 1;
  if (updateExitCode === 0) stdout.write("JavaChat CLI update complete.\n");
  return updateExitCode;
}

/** Resolves a project-local or global npm install without guessing from the package scope. */
async function resolveNpmInstallTarget() {
  const invokedEntrypoint = argv[1] ? resolve(argv[1]) : "";
  if (basename(invokedEntrypoint) !== "javachat") {
    throw new Error(
      '"javachat update" must run through an npm-installed javachat command, not the source entrypoint.',
    );
  }

  let packageRoot;
  try {
    packageRoot = dirname(dirname(realpathSync(invokedEntrypoint)));
  } catch (entrypointFailure) {
    throw new Error(`Could not resolve the installed javachat command: ${entrypointFailure.message}`);
  }
  if (existsSync(join(packageRoot, "package-lock.json")) && existsSync(join(packageRoot, "test"))) {
    throw new Error(
      '"javachat update" does not replace the repository checkout. Use npm install in cli/ for local development.',
    );
  }

  const invokedDirectory = dirname(invokedEntrypoint);
  if (basename(invokedDirectory) === ".bin" && basename(dirname(invokedDirectory)) === "node_modules") {
    const projectRoot = dirname(dirname(invokedDirectory));
    if (projectRoot.split(sep).includes("_npx")) {
      throw new Error('"javachat update" cannot update an ephemeral npx installation.');
    }
    const projectManifest = await readProjectManifest(projectRoot);
    const declaresJavaChat = [
      projectManifest.dependencies,
      projectManifest.devDependencies,
      projectManifest.optionalDependencies,
    ].some((dependencyGroup) => Object.hasOwn(dependencyGroup ?? {}, CLI_PACKAGE));
    if (!declaresJavaChat) {
      throw new Error(
        `The project at ${projectRoot} does not declare ${CLI_PACKAGE}; npm will not be allowed to modify it.`,
      );
    }
    return {
      npmArguments: ["install", `${CLI_PACKAGE}@latest`],
      workingDirectory: projectRoot,
    };
  }

  const prefixResult = spawnSync("npm", ["prefix", "--global"], { encoding: "utf8" });
  if (prefixResult.error) {
    throw new Error(`Could not ask npm for its global prefix: ${prefixResult.error.message}`);
  }
  const globalPrefixOutput = prefixResult.stdout?.trim();
  if (prefixResult.status !== 0 || !globalPrefixOutput) {
    throw new Error("npm could not identify its global installation prefix.");
  }
  const globalPrefix = resolve(globalPrefixOutput);
  if (resolve(invokedDirectory) !== join(globalPrefix, "bin")) {
    throw new Error(
      '"javachat update" could not identify this command as a project-local or global npm installation.',
    );
  }
  return {
    npmArguments: ["install", "--global", `${CLI_PACKAGE}@latest`],
    workingDirectory: globalPrefix,
  };
}

async function readProjectManifest(projectRoot) {
  try {
    const projectManifest = JSON.parse(await readFile(join(projectRoot, "package.json"), "utf8"));
    if (!projectManifest || typeof projectManifest !== "object" || Array.isArray(projectManifest)) {
      throw new Error("package.json must contain an object");
    }
    return projectManifest;
  } catch (manifestFailure) {
    throw new Error(`Could not read ${join(projectRoot, "package.json")}: ${manifestFailure.message}`);
  }
}

/**
 * Lists the document groups ingested in the deployment's knowledge base.
 *
 * The server owns the grouping (documentation-set tokens for the core
 * collections, repository URLs for GitHub collections); the terminal's job is
 * only to validate and print that inventory.
 */
async function commandList(host) {
  const apiKey = await storedKeyFor(host);
  if (!apiKey) {
    stderr.write(`Not signed in to ${host}. Run "javachat auth login".\n`);
    return 1;
  }
  const groupsResponse = await fetch(new URL("/api/knowledge/groups", host), {
    headers: { accept: "application/json", authorization: `Bearer ${apiKey}` },
  });
  if (!groupsResponse.ok) {
    const failureText = await groupsResponse.text().catch(() => "");
    throw new Error(
      `HTTP ${groupsResponse.status} from ${host}${failureText ? `: ${failureText}` : ""}`,
    );
  }
  const knowledgeInventory = parseKnowledgeInventory(await groupsResponse.json(), host);
  const knowledgeGroups = knowledgeInventory.groups;
  if (knowledgeGroups.length === 0) {
    stdout.write(`No document groups are ingested in the knowledge base at ${host}.\n`);
    return 0;
  }
  stdout.write(`Knowledge base at ${host}:\n`);
  let currentCollection = null;
  for (const knowledgeGroup of knowledgeGroups) {
    if (knowledgeGroup.collection !== currentCollection) {
      currentCollection = knowledgeGroup.collection;
      stdout.write(
        `\n${stripVTControlCharacters(knowledgeGroup.kind)} — ${stripVTControlCharacters(knowledgeGroup.collection)}\n`,
      );
    }
    const chunkNoun = knowledgeGroup.chunks === 1 ? "chunk" : "chunks";
    stdout.write(
      `  ${stripVTControlCharacters(knowledgeGroup.name)} (${knowledgeGroup.chunks} ${chunkNoun})\n`,
    );
    for (const canonicalUrl of knowledgeGroup.canonicalUrls) {
      stdout.write(`    URL: ${stripVTControlCharacters(canonicalUrl)}\n`);
    }
    stdout.write(
      `    Versions/revisions: ${knowledgeGroup.ingestedVersions.length > 0 ? knowledgeGroup.ingestedVersions.map(stripVTControlCharacters).join(", ") : "unversioned"}\n`,
    );
  }
  const collectionCount = new Set(knowledgeGroups.map((knowledgeGroup) => knowledgeGroup.collection))
    .size;
  const totalChunkNoun = knowledgeInventory.totalChunks === 1 ? "chunk" : "chunks";
  stdout.write(
    `\n${knowledgeInventory.totalChunks} ${totalChunkNoun} across ${knowledgeGroups.length} groups in ${collectionCount} collections.\n`,
  );
  return 0;
}

/** Validates the server's inventory; external data is untrusted until checked. */
function parseKnowledgeInventory(rawKnowledgeInventory, host) {
  if (
    !rawKnowledgeInventory ||
    typeof rawKnowledgeInventory !== "object" ||
    Array.isArray(rawKnowledgeInventory) ||
    !Array.isArray(rawKnowledgeInventory.groups) ||
    !Number.isSafeInteger(rawKnowledgeInventory.totalChunks) ||
    rawKnowledgeInventory.totalChunks < 0
  ) {
    throw new Error(`JavaChat returned an invalid knowledge inventory from ${host}.`);
  }
  const groups = rawKnowledgeInventory.groups.map((rawKnowledgeGroup) => {
    if (
      !rawKnowledgeGroup ||
      typeof rawKnowledgeGroup !== "object" ||
      Array.isArray(rawKnowledgeGroup) ||
      typeof rawKnowledgeGroup.collection !== "string" ||
      !rawKnowledgeGroup.collection ||
      typeof rawKnowledgeGroup.kind !== "string" ||
      !rawKnowledgeGroup.kind ||
      typeof rawKnowledgeGroup.name !== "string" ||
      !rawKnowledgeGroup.name ||
      !Array.isArray(rawKnowledgeGroup.canonicalUrls) ||
      rawKnowledgeGroup.canonicalUrls.length === 0 ||
      !Array.isArray(rawKnowledgeGroup.ingestedVersions) ||
      rawKnowledgeGroup.ingestedVersions.some(
        (ingestedVersion) => typeof ingestedVersion !== "string" || !ingestedVersion,
      ) ||
      typeof rawKnowledgeGroup.chunks !== "number" ||
      !Number.isSafeInteger(rawKnowledgeGroup.chunks) ||
      rawKnowledgeGroup.chunks < 0
    ) {
      throw new Error(`JavaChat returned a malformed knowledge group from ${host}.`);
    }
    const canonicalUrls = rawKnowledgeGroup.canonicalUrls.map((canonicalUrl) => {
      if (typeof canonicalUrl !== "string" || !canonicalUrl) {
        throw new Error(`JavaChat returned a malformed knowledge group from ${host}.`);
      }
      const parsedUrl = URL.parse(canonicalUrl, host);
      if (!parsedUrl || (parsedUrl.protocol !== "http:" && parsedUrl.protocol !== "https:")) {
        throw new Error(`JavaChat returned a malformed knowledge group from ${host}.`);
      }
      return parsedUrl.href;
    });
    return {
      collection: rawKnowledgeGroup.collection,
      kind: rawKnowledgeGroup.kind,
      name: rawKnowledgeGroup.name,
      canonicalUrls,
      ingestedVersions: rawKnowledgeGroup.ingestedVersions,
      chunks: rawKnowledgeGroup.chunks,
    };
  });
  const groupChunkTotal = groups.reduce((total, group) => total + group.chunks, 0);
  if (!Number.isSafeInteger(groupChunkTotal) || groupChunkTotal !== rawKnowledgeInventory.totalChunks) {
    throw new Error(`JavaChat returned an inconsistent knowledge inventory from ${host}.`);
  }
  return { groups, totalChunks: rawKnowledgeInventory.totalChunks };
}

/**
 * Streams one answer, printing text as it arrives and citations at the end.
 *
 * Parses SSE by hand rather than pulling a dependency: the server emits a small
 * fixed set of event names and this keeps the CLI installable with no tree.
 */
/**
 * Renders one enrichment marker as a titled terminal block.
 *
 * An unrecognised token is returned untouched: inventing a heading for markup
 * this client does not understand would misrepresent the answer.
 */
function renderEnrichmentMarker(markerBody) {
  const separatorIndex = markerBody.indexOf(":");
  const token =
    separatorIndex === -1 ? "" : markerBody.slice(0, separatorIndex).trim().toLowerCase();
  const title = ENRICHMENT_MARKER_TITLES.get(token);
  if (!title) {
    return `${MARKER_OPEN}${markerBody}${MARKER_CLOSE}`;
  }
  return `\n${title}: ${markerBody.slice(separatorIndex + 1).trim()}\n`;
}

/**
 * Converts enrichment markers to terminal blocks while text is still streaming.
 *
 * Markers routinely straddle two SSE chunks, so text from an opening `{{` is
 * held until its close arrives. Only that fragment is delayed; everything before
 * it prints immediately, which keeps the answer streaming.
 */
function createEnrichmentMarkerRenderer() {
  let heldText = "";
  return {
    /** Returns the text that is safe to print now. */
    push(streamedText) {
      heldText += streamedText;
      let printable = "";
      for (;;) {
        const openIndex = heldText.indexOf(MARKER_OPEN);
        if (openIndex === -1) {
          // A lone trailing brace may become a marker once the next chunk lands.
          const heldBraceLength = heldText.endsWith("{") ? 1 : 0;
          printable += heldText.slice(0, heldText.length - heldBraceLength);
          heldText = heldText.slice(heldText.length - heldBraceLength);
          return printable;
        }
        printable += heldText.slice(0, openIndex);
        heldText = heldText.slice(openIndex);
        const closeIndex = heldText.indexOf(MARKER_CLOSE, MARKER_OPEN.length);
        if (closeIndex === -1) {
          if (heldText.length > MARKER_MAX_LENGTH) {
            printable += heldText;
            heldText = "";
            continue;
          }
          return printable;
        }
        printable += renderEnrichmentMarker(heldText.slice(MARKER_OPEN.length, closeIndex));
        heldText = heldText.slice(closeIndex + MARKER_CLOSE.length);
      }
    },
    /** Releases an unterminated marker verbatim so no answer text is lost. */
    flush() {
      const unterminatedText = heldText;
      heldText = "";
      return unterminatedText;
    },
  };
}

async function commandAsk(host, question, options) {
  const environmentApiKey = env.JAVACHAT_API_KEY?.trim();
  const allHosts = environmentApiKey ? {} : await readCredentials();
  const apiKey = environmentApiKey || allHosts[host]?.apiKey;
  if (!apiKey) {
    throw new Error(`Not signed in to ${host}. Run "javachat auth login" first.`);
  }
  const storedSessionId = allHosts[host]?.sessionId;
  const sessionId =
    options.newSession || environmentApiKey
      ? `chat-${randomUUID()}`
      : (storedSessionId ?? `chat-${randomUUID()}`);

  // The key is the CLI's only credential: it authenticates the caller and, because
  // it is a bearer rather than a cookie, exempts the request from the browser CSRF
  // exchange the website performs.
  const headers = {
    "content-type": "application/json",
    accept: "text/event-stream",
    authorization: `Bearer ${apiKey}`,
  };

  const streamResponse = await fetch(new URL("/api/chat/stream", host), {
    method: "POST",
    headers,
    body: JSON.stringify({ sessionId, latest: question }),
  });

  if (!streamResponse.ok || !streamResponse.body) {
    const failureText = await streamResponse.text().catch(() => "");
    throw new Error(
      `HTTP ${streamResponse.status} from ${host}${failureText ? `: ${failureText}` : ""}`,
    );
  }
  const streamContentType = streamResponse.headers.get("content-type") ?? "";
  if (!streamContentType.toLowerCase().startsWith("text/event-stream")) {
    throw new Error(
      `Expected text/event-stream from ${host}, received ${streamContentType || "no content type"}`,
    );
  }

  const citations = [];
  const markerRenderer = createEnrichmentMarkerRenderer();
  let sawText = false;
  let streamFailure = null;

  for await (const sseEvent of readServerSentEvents(streamResponse.body)) {
    if (sseEvent.event === "text") {
      stdout.write(
        stripVTControlCharacters(markerRenderer.push(JSON.parse(sseEvent.data).text ?? "")),
      );
      sawText = true;
    } else if (sseEvent.event === "citation") {
      citations.push(
        ...JSON.parse(sseEvent.data).slice(0, CITATION_DISPLAY_LIMIT - citations.length),
      );
    } else if (sseEvent.event === "status" && options.verbose) {
      stderr.write(
        `  · ${stripVTControlCharacters(String(JSON.parse(sseEvent.data).message ?? ""))}\n`,
      );
    } else if (sseEvent.event === "error") {
      streamFailure = JSON.parse(sseEvent.data);
    }
  }

  stdout.write(stripVTControlCharacters(markerRenderer.flush()));
  if (sawText) stdout.write("\n");
  if (streamFailure) {
    stderr.write(`\n${stripVTControlCharacters(String(streamFailure.message ?? ""))}\n`);
    if (streamFailure.details) {
      stderr.write(`${stripVTControlCharacters(String(streamFailure.details))}\n`);
    }
    return 1;
  }
  if (!sawText) {
    throw new Error("JavaChat stream ended without an answer.");
  }
  if (citations.length > 0) {
    stdout.write("\nSources:\n");
    for (const citation of citations.slice(0, CITATION_DISPLAY_LIMIT)) {
      stdout.write(
        `  ${stripVTControlCharacters(String(citation.title || citation.url || ""))}\n  ${stripVTControlCharacters(String(citation.url ?? ""))}\n`,
      );
    }
  }

  if (!options.newSession && !environmentApiKey && !storedSessionId) {
    allHosts[host] = { ...(allHosts[host] ?? {}), sessionId };
    await writeCredentials(allHosts);
  }
  return 0;
}

/** Yields decoded {event, data} frames, tolerating heartbeat comment lines. */
async function* readServerSentEvents(body) {
  const decoder = new TextDecoder();
  let buffered = "";
  for await (const chunk of body) {
    buffered += decoder.decode(chunk, { stream: true });
    let boundary = buffered.indexOf("\n\n");
    while (boundary !== -1) {
      const frame = buffered.slice(0, boundary);
      buffered = buffered.slice(boundary + 2);
      const decoded = decodeFrame(frame);
      if (decoded) yield decoded;
      boundary = buffered.indexOf("\n\n");
    }
  }
}

function decodeFrame(frame) {
  let eventName = "message";
  const dataLines = [];
  for (const line of frame.split("\n")) {
    if (line.startsWith(":")) continue;
    if (line.startsWith("event:")) eventName = line.slice("event:".length).trim();
    else if (line.startsWith("data:")) dataLines.push(line.slice("data:".length).replace(/^ /, ""));
  }
  return dataLines.length > 0 ? { event: eventName, data: dataLines.join("\n") } : null;
}

const USAGE = `javachat — ask Java Chat from your terminal

Usage
  javachat ask "How do records work?"       Ask a question
  javachat auth login                       Authorize this machine in your browser
  javachat auth logout                      Remove the local credential
  javachat auth status                      Show who the stored key belongs to
  javachat update                           Update this npm installation
  javachat list all                        List every ingested source, URL, and version
  javachat list knowledge                  Alias for list all

Options
  --host <url>    Target a different deployment (default ${DEFAULT_HOST})
  --new           Start a fresh conversation instead of continuing the last one
  --verbose       Show retrieval progress on stderr
  --no-browser    Print the approval URL instead of opening a browser
  --help, -h      Show this complete command reference
  --version       Show the installed CLI version
  --              Treat everything after it as question text (for questions starting with -)

Environment
  JAVACHAT_API_KEY   Use this key instead of the stored one (for CI)
  JAVACHAT_HOST      Default host when --host is absent
`;

const AUTH_COMMANDS = new Map([
  ["login", commandLogin],
  ["logout", commandLogout],
  ["status", commandStatus],
]);

function parseArguments(rawArguments) {
  const options = {
    verbose: false,
    newSession: false,
    noBrowser: false,
    help: false,
    version: false,
  };
  let host = env.JAVACHAT_HOST?.trim() || DEFAULT_HOST;
  let hostOptionProvided = false;
  const positional = [];
  for (let index = 0; index < rawArguments.length; index += 1) {
    const argument = rawArguments[index];
    if (argument === "--") {
      positional.push(...rawArguments.slice(index + 1));
      break;
    }
    if (argument === "--host") {
      const hostValue = rawArguments[index + 1];
      if (!hostValue || hostValue.startsWith("-")) {
        throw new Error("--host requires a value, e.g. javachat --host https://javachat.ai");
      }
      host = hostValue;
      hostOptionProvided = true;
      index += 1;
    } else if (argument === "--verbose" || argument === "-v") options.verbose = true;
    else if (argument === "--new") options.newSession = true;
    else if (argument === "--no-browser") options.noBrowser = true;
    else if (argument === "--help" || argument === "-h") options.help = true;
    else if (argument === "--version") options.version = true;
    else if (argument.startsWith("-")) {
      throw new Error(`Unknown option: ${argument}. Run "javachat --help" for usage.`);
    } else {
      positional.push(argument);
      const questionStarted = positional[0] === "ask" && positional.length > 1;
      if (questionStarted) {
        positional.push(...rawArguments.slice(index + 1));
        break;
      }
    }
  }
  return { host, hostOptionProvided, options, positional };
}

async function main() {
  const rawArguments = argv.slice(2);
  const { host, hostOptionProvided, options, positional } = parseArguments(rawArguments);
  const [first, ...rest] = positional;

  if (options.version) {
    if (positional.length > 0) throw new Error("javachat --version takes no arguments.");
    stdout.write(`${packageMetadata.version}\n`);
    return 0;
  }

  if (options.help || first === "help" || !first) {
    if (hostOptionProvided) normalizeHost(host);
    stdout.write(USAGE);
    return 0;
  }
  if (first === "update") {
    if (rest.length > 0) throw new Error("javachat update takes no arguments.");
    return await commandUpdate();
  }
  const normalizedHost = normalizeHost(host);
  if (first === "auth") {
    const [authCommandName, ...authArguments] = rest;
    if (!authCommandName) {
      throw new Error("javachat auth requires a command: login, logout, or status.");
    }
    const authCommand = AUTH_COMMANDS.get(authCommandName);
    if (!authCommand) {
      throw new Error(
        `Unknown auth command: ${authCommandName}. Run "javachat --help" for usage.`,
      );
    }
    if (authArguments.length > 0) {
      throw new Error(`javachat auth ${authCommandName} takes no arguments.`);
    }
    return await authCommand(normalizedHost, options);
  }
  if (first === "list") {
    const [listTarget, ...listArguments] = rest;
    if (!listTarget) {
      throw new Error("javachat list requires a target: all or knowledge.");
    }
    if (listTarget !== "all" && listTarget !== "knowledge") {
      throw new Error(`Unknown list target: ${listTarget}. Run "javachat --help" for usage.`);
    }
    if (listArguments.length > 0) {
      throw new Error(`javachat list ${listTarget} takes no arguments.`);
    }
    return await commandList(normalizedHost);
  }
  if (first !== "ask") {
    throw new Error(`Unknown command: ${first}. Run "javachat --help" for usage.`);
  }
  const question = rest.join(" ").trim();
  if (!question) {
    stderr.write('Nothing to ask. Try: javachat ask "How do Java records work?"\n');
    return 1;
  }
  return await commandAsk(normalizedHost, question, options);
}

main()
  .then((exitCode) => exit(exitCode))
  .catch((failure) => {
    stderr.write(`javachat: ${stripVTControlCharacters(String(failure.message ?? failure))}\n`);
    exit(1);
  });
