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
import { spawn } from "node:child_process";
import { randomUUID, randomBytes } from "node:crypto";
import { hostname, homedir, platform } from "node:os";
import { mkdir, readFile, writeFile, chmod, rm } from "node:fs/promises";
import { dirname, join } from "node:path";
import { stdout, stderr, argv, exit, env } from "node:process";
import { stripVTControlCharacters } from "node:util";

const DEFAULT_HOST = "https://javachat.ai";
const CREDENTIALS_MODE = 0o600;
const CREDENTIALS_DIRECTORY_MODE = 0o700;
const LOGIN_TIMEOUT_MS = 5 * 60 * 1000;
const CITATION_DISPLAY_LIMIT = 5;

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
  const parsed = new URL(candidate);
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
  const clientLabel = hostname().trim() || "javachat-cli";

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
  const storedHosts = await readCredentials();
  const existing = env.JAVACHAT_API_KEY?.trim() || storedHosts[host]?.apiKey;
  if (existing) {
    stderr.write(`Already signed in to ${host}. Run "javachat logout" before replacing the key.\n`);
    return 0;
  }
  const apiKey = await authorizeThroughBrowser(host, !options.noBrowser);
  const identity = await fetchIdentity(host, apiKey);

  const allHosts = await readCredentials();
  allHosts[host] = { apiKey, sessionId: `chat-${randomUUID()}` };
  await writeCredentials(allHosts);
  stdout.write(
    `Signed in to ${host} as ${identity}.\nKey stored in ${credentialsPath()} (owner-only).\n`,
  );
  return 0;
}

async function commandLogout(host) {
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

async function commandWhoami(host) {
  const apiKey = await storedKeyFor(host);
  if (!apiKey) {
    stderr.write(`Not signed in to ${host}. Run "javachat login".\n`);
    return 1;
  }
  stdout.write(`${await fetchIdentity(host, apiKey)} at ${host}\n`);
  return 0;
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
    throw new Error(`Not signed in to ${host}. Run "javachat login" first.`);
  }
  await fetchIdentity(host, apiKey);
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
  javachat "How do Java records work?"     Ask a question
  javachat login                           Authorize this machine in your browser
  javachat logout                          Remove the local credential
  javachat whoami                          Show who the stored key belongs to

Options
  --host <url>    Target a different deployment (default ${DEFAULT_HOST})
  --new           Start a fresh conversation instead of continuing the last one
  --verbose       Show retrieval progress on stderr
  --no-browser    Print the approval URL instead of opening a browser

Environment
  JAVACHAT_API_KEY   Use this key instead of the stored one (for CI)
  JAVACHAT_HOST      Default host when --host is absent
`;

function parseArguments(rawArguments) {
  const options = { verbose: false, newSession: false, noBrowser: false };
  let host = env.JAVACHAT_HOST?.trim() || DEFAULT_HOST;
  const positional = [];
  for (let index = 0; index < rawArguments.length; index += 1) {
    const argument = rawArguments[index];
    if (argument === "--host") host = rawArguments[++index] ?? host;
    else if (argument === "--verbose" || argument === "-v") options.verbose = true;
    else if (argument === "--new") options.newSession = true;
    else if (argument === "--no-browser") options.noBrowser = true;
    else positional.push(argument);
  }
  return { host: normalizeHost(host), options, positional };
}

async function main() {
  const { host, options, positional } = parseArguments(argv.slice(2));
  const [first, ...rest] = positional;

  if (!first || first === "--help" || first === "-h" || first === "help") {
    stdout.write(USAGE);
    return first ? 0 : 1;
  }
  if (first === "login") return await commandLogin(host, options);
  if (first === "logout") return await commandLogout(host);
  if (first === "whoami") return await commandWhoami(host);

  const question = (first === "ask" ? rest : positional).join(" ").trim();
  if (!question) {
    stderr.write('Nothing to ask. Try: javachat "How do Java records work?"\n');
    return 1;
  }
  return await commandAsk(host, question, options);
}

main()
  .then((exitCode) => exit(exitCode))
  .catch((failure) => {
    stderr.write(`javachat: ${stripVTControlCharacters(String(failure.message ?? failure))}\n`);
    exit(1);
  });
