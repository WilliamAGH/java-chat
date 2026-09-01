import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { once } from "node:events";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import packageMetadata from "../package.json" with { type: "json" };

const CLI_ENTRYPOINT = fileURLToPath(new URL("../bin/javachat.js", import.meta.url));
const TEST_API_KEY = "ak_secret_0123456789abcdef0123456789abcdef";

function runCli(argumentsList, environmentVariables = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [CLI_ENTRYPOINT, ...argumentsList], {
      env: { ...process.env, ...environmentVariables },
      stdio: ["ignore", "pipe", "pipe"],
    });
    let standardOutput = "";
    let standardError = "";
    child.stdout.setEncoding("utf8").on("data", (outputChunk) => {
      standardOutput += outputChunk;
    });
    child.stderr.setEncoding("utf8").on("data", (errorChunk) => {
      standardError += errorChunk;
    });
    child.once("error", reject);
    child.once("close", (exitCode) => resolve({ exitCode, standardOutput, standardError }));
  });
}

async function startBrowserLogin(host, configurationHome) {
  const child = spawn(
    process.execPath,
    [CLI_ENTRYPOINT, "auth", "login", "--host", host, "--no-browser"],
    {
      env: { ...process.env, XDG_CONFIG_HOME: configurationHome },
      stdio: ["ignore", "pipe", "pipe"],
    },
  );
  let standardOutput = "";
  let standardError = "";
  child.stdout.setEncoding("utf8").on("data", (outputChunk) => {
    standardOutput += outputChunk;
  });
  child.stderr.setEncoding("utf8").on("data", (errorChunk) => {
    standardError += errorChunk;
  });
  await waitForCliState(() => standardError.includes("Waiting for approval..."));
  const approvalUrlMatch = standardError.match(/https?:\/\/[^\s]+\/cli\/authorize\?[^\s]+/);
  assert.notEqual(approvalUrlMatch, null);
  return {
    child,
    approvalUrl: new URL(approvalUrlMatch[0]),
    standardOutput: () => standardOutput,
    standardError: () => standardError,
  };
}

async function completeBrowserLogin(approvalUrl, apiKey = TEST_API_KEY) {
  const callbackPort = approvalUrl.searchParams.get("port");
  const expectedState = approvalUrl.searchParams.get("state");
  assert.notEqual(callbackPort, null);
  assert.notEqual(expectedState, null);
  return fetch(`http://127.0.0.1:${callbackPort}/complete`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify({ state: expectedState, key: apiKey }),
  });
}

async function writeStoredCredential(configurationHome, host, apiKey) {
  const credentialDirectory = join(configurationHome, "javachat");
  const credentialFile = join(credentialDirectory, "credentials.json");
  await mkdir(credentialDirectory, { recursive: true });
  await writeFile(
    credentialFile,
    JSON.stringify({ [host]: { apiKey, sessionId: "chat-stored" } }),
  );
  return credentialFile;
}

test("prints usage without loading credentials", async () => {
  const cliExecution = await runCli(["--help"]);

  assert.equal(cliExecution.exitCode, 0);
  assert.match(cliExecution.standardOutput, /javachat ask/);
  assert.match(cliExecution.standardOutput, /javachat auth login/);
  assert.match(cliExecution.standardOutput, /javachat auth logout/);
  assert.match(cliExecution.standardOutput, /javachat auth status/);
  assert.equal(cliExecution.standardError, "");
});

test("prints the installed version without loading credentials", async () => {
  const cliExecution = await runCli(["--version"], {
    XDG_CONFIG_HOME: CLI_ENTRYPOINT,
    JAVACHAT_API_KEY: "",
    JAVACHAT_HOST: "not a URL",
  });

  assert.equal(cliExecution.exitCode, 0);
  assert.equal(cliExecution.standardOutput, `${packageMetadata.version}\n`);
  assert.equal(cliExecution.standardError, "");
});

test("treats --version after -- as question text", async (testContext) => {
  const configurationHome = await mkdtemp(join(tmpdir(), "javachat-cli-test-"));
  testContext.after(() => rm(configurationHome, { recursive: true, force: true }));

  const cliExecution = await runCli(["ask", "--", "--version"], {
    JAVACHAT_API_KEY: "",
    XDG_CONFIG_HOME: configurationHome,
  });

  assert.equal(cliExecution.exitCode, 1);
  assert.equal(cliExecution.standardOutput, "");
  assert.match(cliExecution.standardError, /Not signed in/);
});

test("does not let a later --version bypass an unknown option", async () => {
  const cliExecution = await runCli(["--bogus", "--version"]);

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /Unknown option: --bogus/);
});

test("does not let help bypass an unknown option", async () => {
  const cliExecution = await runCli(["help", "--bogus"]);

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /Unknown option: --bogus/);
});

test("rejects non-HTTP deployment hosts", async () => {
  const cliExecution = await runCli(["--host", "file:///etc/passwd", "--help"]);

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /host must use HTTP or HTTPS/);
});

test("rejects cleartext remote deployment hosts", async () => {
  const cliExecution = await runCli(["--host", "http://javachat.example", "--help"]);

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /Remote JavaChat hosts must use HTTPS/);
});

test("streams text and citations through the public API", async (testContext) => {
  let identityRequestCount = 0;
  const apiServer = createServer((request, response) => {
    if (request.url === "/api/me") {
      identityRequestCount += 1;
      response.writeHead(200, { "content-type": "application/json" });
      response.end('{"userId":"user_cli"}');
      return;
    }
    assert.equal(request.url, "/api/chat/stream");
    assert.equal(request.headers.authorization, `Bearer ${TEST_API_KEY}`);
    response.writeHead(200, { "content-type": "text/event-stream" });
    response.end(
      'event: text\ndata: {"text":"Records are \\u001b]52;c;c3Bvb2Y=\\u0007concise."}\n\n' +
        'event: citation\ndata: [{"title":"Record Classes","url":"https://docs.example/records"}]\n\n',
    );
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const address = apiServer.address();
  assert.notEqual(address, null);
  assert.equal(typeof address, "object");

  const cliExecution = await runCli(
    ["--host", `http://127.0.0.1:${address.port}`, "ask", "How do records work?"],
    { JAVACHAT_API_KEY: TEST_API_KEY },
  );

  assert.equal(cliExecution.exitCode, 0);
  assert.match(cliExecution.standardOutput, /Records are concise\./);
  assert.doesNotMatch(cliExecution.standardOutput, /\u001b|\u0007/);
  assert.match(cliExecution.standardOutput, /Record Classes/);
  assert.match(cliExecution.standardOutput, /https:\/\/docs\.example\/records/);
  assert.equal(cliExecution.standardError, "");
  assert.equal(identityRequestCount, 0);
});

test("renders enrichment markers split across stream chunks", async (testContext) => {
  const apiServer = createServer((request, response) => {
    if (request.url === "/api/me") {
      response.writeHead(200, { "content-type": "application/json" });
      response.end('{"userId":"user_cli"}');
      return;
    }
    response.writeHead(200, { "content-type": "text/event-stream" });
    // The marker straddles three events, which is how the model actually emits
    // it; a renderer that only inspects one chunk leaks the raw token.
    response.end(
      'event: text\ndata: {"text":"Sealed interfaces restrict implementors. {{remin"}\n\n' +
        'event: text\ndata: {"text":"der: Permitted types must be final, sealed, or non-"}\n\n' +
        'event: text\ndata: {"text":"sealed.}} Use them for closed hierarchies. {{unknown:kept}}"}\n\n',
    );
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const address = apiServer.address();

  const cliExecution = await runCli(
    ["--host", `http://127.0.0.1:${address.port}`, "ask", "What is a sealed interface?"],
    { JAVACHAT_API_KEY: TEST_API_KEY },
  );

  assert.equal(cliExecution.exitCode, 0);
  assert.match(cliExecution.standardOutput, /Important Reminders: Permitted types must be final/);
  assert.doesNotMatch(cliExecution.standardOutput, /\{\{reminder|\{\{remin/);
  // Prose on both sides of the marker must survive the buffering.
  assert.match(cliExecution.standardOutput, /Sealed interfaces restrict implementors\./);
  assert.match(cliExecution.standardOutput, /Use them for closed hierarchies\./);
  // An unrecognised token stays verbatim rather than gaining an invented heading.
  assert.match(cliExecution.standardOutput, /\{\{unknown:kept\}\}/);
});

test("releases an unterminated enrichment marker instead of swallowing it", async (testContext) => {
  const apiServer = createServer((request, response) => {
    if (request.url === "/api/me") {
      response.writeHead(200, { "content-type": "application/json" });
      response.end('{"userId":"user_cli"}');
      return;
    }
    response.writeHead(200, { "content-type": "text/event-stream" });
    response.end('event: text\ndata: {"text":"Answer text. {{reminder: never closed"}\n\n');
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const address = apiServer.address();

  const cliExecution = await runCli(
    ["--host", `http://127.0.0.1:${address.port}`, "ask", "Anything"],
    { JAVACHAT_API_KEY: TEST_API_KEY },
  );

  assert.equal(cliExecution.exitCode, 0);
  assert.match(cliExecution.standardOutput, /Answer text\./);
  assert.match(cliExecution.standardOutput, /never closed/);
});

test("rejects a successful non-SSE response", async (testContext) => {
  const apiServer = createServer((request, response) => {
    if (request.url === "/api/me") {
      response.writeHead(200, { "content-type": "application/json" });
      response.end('{"userId":"user_cli"}');
      return;
    }
    response.writeHead(200, { "content-type": "text/html" });
    response.end("<html>sign in</html>");
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const address = apiServer.address();
  assert.notEqual(address, null);
  assert.equal(typeof address, "object");

  const cliExecution = await runCli(
    ["--host", `http://127.0.0.1:${address.port}`, "ask", "How do records work?"],
    { JAVACHAT_API_KEY: TEST_API_KEY },
  );

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /Expected text\/event-stream/);
});

test("rejects a null browser callback without crashing", async (testContext) => {
  const configurationHome = await mkdtemp(join(tmpdir(), "javachat-cli-test-"));
  testContext.after(() => rm(configurationHome, { recursive: true, force: true }));
  const login = await startBrowserLogin("https://javachat.ai", configurationHome);
  const callbackPort = login.approvalUrl.searchParams.get("port");
  assert.notEqual(callbackPort, null);

  const callbackResponse = await fetch(`http://127.0.0.1:${callbackPort}/complete`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: "null",
  });
  assert.equal(callbackResponse.status, 400);
  const [exitCode] = await once(login.child, "close");

  assert.equal(exitCode, 1);
  assert.match(login.standardError(), /Authorization callback was malformed/);
});

test("stores and verifies a browser-created key on successful login", async (testContext) => {
  const configurationHome = await mkdtemp(join(tmpdir(), "javachat-cli-test-"));
  testContext.after(() => rm(configurationHome, { recursive: true, force: true }));
  const apiServer = createServer((request, response) => {
    assert.equal(request.url, "/api/me");
    assert.equal(request.headers.authorization, `Bearer ${TEST_API_KEY}`);
    response.writeHead(200, { "content-type": "application/json" });
    response.end('{"userId":"user_cli"}');
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const apiAddress = apiServer.address();
  assert.notEqual(apiAddress, null);
  assert.equal(typeof apiAddress, "object");

  const host = `http://127.0.0.1:${apiAddress.port}`;
  const login = await startBrowserLogin(host, configurationHome);
  const callbackResponse = await completeBrowserLogin(login.approvalUrl);
  assert.equal(callbackResponse.status, 204);
  const [exitCode] = await once(login.child, "close");

  assert.equal(exitCode, 0);
  assert.match(login.standardOutput(), new RegExp(`Signed in to ${host} as user_cli`));
  const storedCredentials = JSON.parse(
    await readFile(join(configurationHome, "javachat", "credentials.json"), "utf8"),
  );
  assert.equal(storedCredentials[host].apiKey, TEST_API_KEY);
  assert.equal(typeof storedCredentials[host].sessionId, "string");
});

test("preserves a browser-created key when identity verification is unavailable", async (testContext) => {
  const configurationHome = await mkdtemp(join(tmpdir(), "javachat-cli-test-"));
  testContext.after(() => rm(configurationHome, { recursive: true, force: true }));
  const apiServer = createServer((request, response) => {
    assert.equal(request.url, "/api/me");
    response.writeHead(503).end();
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const apiAddress = apiServer.address();
  assert.notEqual(apiAddress, null);
  assert.equal(typeof apiAddress, "object");

  const host = `http://127.0.0.1:${apiAddress.port}`;
  const login = await startBrowserLogin(host, configurationHome);
  const callbackResponse = await completeBrowserLogin(login.approvalUrl);
  assert.equal(callbackResponse.status, 204);
  const [exitCode] = await once(login.child, "close");
  assert.equal(exitCode, 1);
  assert.match(login.standardError(), /Key verification failed: HTTP 503/);

  const storedCredentials = JSON.parse(
    await readFile(join(configurationHome, "javachat", "credentials.json"), "utf8"),
  );
  assert.equal(storedCredentials[host].apiKey, TEST_API_KEY);
  assert.equal(typeof storedCredentials[host].sessionId, "string");
});

test("rejects an unknown option instead of treating it as a question", async () => {
  const cliExecution = await runCli(["--hots", "https://dev.javachat.ai", "whoami"]);

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /Unknown option: --hots/);
});

test("requires the ask command before question text", async () => {
  const cliExecution = await runCli(["How do records work?"]);

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /Unknown command: How do records work\?/);
});

test("rejects removed flat authentication commands", async () => {
  for (const flatCommand of ["login", "logout", "whoami"]) {
    const cliExecution = await runCli([flatCommand]);
    assert.equal(cliExecution.exitCode, 1);
    assert.match(cliExecution.standardError, new RegExp(`Unknown command: ${flatCommand}`));
  }
});

test("requires an authentication command", async () => {
  const cliExecution = await runCli(["auth"]);

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /auth requires a command: login, logout, or status/);
});

test("rejects unknown authentication commands", async () => {
  const cliExecution = await runCli(["auth", "whoami"]);

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /Unknown auth command: whoami/);
});

test("allows Java option tokens after question text begins", async (testContext) => {
  let receivedQuestion = "";
  const apiServer = createServer((request, response) => {
    let requestBody = "";
    request.setEncoding("utf8");
    request.on("data", (chunk) => {
      requestBody += chunk;
    });
    request.on("end", () => {
      receivedQuestion = JSON.parse(requestBody).latest;
      response.writeHead(200, { "content-type": "text/event-stream" });
      response.end('event: text\ndata: {"text":"Use -Xmx to set the maximum heap."}\n\n');
    });
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const apiAddress = apiServer.address();
  assert.equal(typeof apiAddress, "object");

  const cliExecution = await runCli(
    ["ask", "What", "does", "-Xmx", "do?", "--host", "literal-question-text"],
    {
      JAVACHAT_API_KEY: TEST_API_KEY,
      JAVACHAT_HOST: `http://127.0.0.1:${apiAddress.port}`,
    },
  );

  assert.equal(cliExecution.exitCode, 0);
  assert.equal(receivedQuestion, "What does -Xmx do? --host literal-question-text");
});

test("rejects --host without a value", async () => {
  const cliExecution = await runCli(["auth", "status", "--host"]);

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /--host requires a value/);
});

test("describes the offending host value when it is not a URL", async () => {
  const cliExecution = await runCli(["--host", "not a url", "--help"]);

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /Invalid JavaChat host "not a url"/);
});

test("rejects stray arguments on subcommands", async () => {
  const cliExecution = await runCli(["auth", "status", "extra"]);

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /javachat auth status takes no arguments/);
});

test("auth status shows the authenticated identity", async (testContext) => {
  const apiServer = createServer((request, response) => {
    assert.equal(request.url, "/api/me");
    assert.equal(request.headers.authorization, `Bearer ${TEST_API_KEY}`);
    response.writeHead(200, { "content-type": "application/json" });
    response.end('{"userId":"user_cli"}');
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const apiAddress = apiServer.address();
  assert.equal(typeof apiAddress, "object");
  const host = `http://127.0.0.1:${apiAddress.port}`;

  const cliExecution = await runCli(["auth", "--host", host, "status"], {
    JAVACHAT_API_KEY: TEST_API_KEY,
  });

  assert.equal(cliExecution.exitCode, 0);
  assert.equal(cliExecution.standardOutput, `user_cli at ${host}\n`);
  assert.equal(cliExecution.standardError, "");
});

test("login points at JAVACHAT_API_KEY when it provides the authentication", async (testContext) => {
  const configurationHome = await mkdtemp(join(tmpdir(), "javachat-cli-test-"));
  testContext.after(() => rm(configurationHome, { recursive: true, force: true }));

  const cliExecution = await runCli(["auth", "login", "--host", "https://javachat.ai"], {
    JAVACHAT_API_KEY: TEST_API_KEY,
    XDG_CONFIG_HOME: configurationHome,
  });

  assert.equal(cliExecution.exitCode, 0);
  assert.match(cliExecution.standardError, /Unset it to sign in with the browser instead/);
  assert.doesNotMatch(cliExecution.standardError, /logout/);
});

test("auth logout revokes and removes the stored credential", async (testContext) => {
  const configurationHome = await mkdtemp(join(tmpdir(), "javachat-cli-test-"));
  testContext.after(() => rm(configurationHome, { recursive: true, force: true }));
  const apiServer = createServer((request, response) => {
    assert.equal(request.method, "DELETE");
    assert.equal(request.url, "/api/me/api-key");
    assert.equal(request.headers.authorization, `Bearer ${TEST_API_KEY}`);
    response.writeHead(204).end();
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const apiAddress = apiServer.address();
  assert.equal(typeof apiAddress, "object");
  const host = `http://127.0.0.1:${apiAddress.port}`;
  const credentialFile = await writeStoredCredential(configurationHome, host, TEST_API_KEY);

  const cliExecution = await runCli(["auth", "logout", "--host", host], {
    JAVACHAT_API_KEY: "",
    XDG_CONFIG_HOME: configurationHome,
  });

  assert.equal(cliExecution.exitCode, 0);
  assert.match(cliExecution.standardOutput, /Revoked and removed the local credential/);
  assert.equal(cliExecution.standardError, "");
  await assert.rejects(readFile(credentialFile, "utf8"), { code: "ENOENT" });
});

test("logout explains JAVACHAT_API_KEY when no credential is stored", async (testContext) => {
  const configurationHome = await mkdtemp(join(tmpdir(), "javachat-cli-test-"));
  testContext.after(() => rm(configurationHome, { recursive: true, force: true }));

  const cliExecution = await runCli(["auth", "logout", "--host", "https://javachat.ai"], {
    JAVACHAT_API_KEY: TEST_API_KEY,
    XDG_CONFIG_HOME: configurationHome,
  });

  assert.equal(cliExecution.exitCode, 0);
  assert.match(cliExecution.standardError, /authentication.*JAVACHAT_API_KEY/i);
  assert.match(cliExecution.standardError, /run "javachat auth logout" again/i);
});

test("environment authentication takes precedence over a stored logout credential", async (testContext) => {
  const configurationHome = await mkdtemp(join(tmpdir(), "javachat-cli-test-"));
  testContext.after(() => rm(configurationHome, { recursive: true, force: true }));
  const credentialFile = await writeStoredCredential(
    configurationHome,
    "https://javachat.ai",
    "ak_secret_stored",
  );

  const cliExecution = await runCli(["auth", "logout", "--host", "https://javachat.ai"], {
    JAVACHAT_API_KEY: TEST_API_KEY,
    XDG_CONFIG_HOME: configurationHome,
  });

  assert.equal(cliExecution.exitCode, 0);
  assert.match(cliExecution.standardError, /authentication.*JAVACHAT_API_KEY/i);
  assert.match(cliExecution.standardError, /stored fallback credential/i);
  assert.doesNotMatch(cliExecution.standardOutput, /Revoked/);
  const storedCredentials = JSON.parse(await readFile(credentialFile, "utf8"));
  assert.equal(storedCredentials["https://javachat.ai"].apiKey, "ak_secret_stored");
});

test("knowledge requires a credential before calling the API", async (testContext) => {
  const configurationHome = await mkdtemp(join(tmpdir(), "javachat-cli-test-"));
  testContext.after(() => rm(configurationHome, { recursive: true, force: true }));

  const cliExecution = await runCli(["knowledge", "--host", "https://javachat.ai"], {
    JAVACHAT_API_KEY: "",
    XDG_CONFIG_HOME: configurationHome,
  });

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /Not signed in to https:\/\/javachat\.ai/);
});

test("knowledge lists the ingested document groups", async (testContext) => {
  const apiServer = createServer((request, response) => {
    assert.equal(request.url, "/api/knowledge/groups");
    assert.equal(request.headers.authorization, `Bearer ${TEST_API_KEY}`);
    response.writeHead(200, { "content-type": "application/json" });
    response.end(
      JSON.stringify({
        groups: [
          { collection: "chat-docs", kind: "DOCS", name: "oracle/javase/25/api", chunks: 10 },
          { collection: "chat-docs", kind: "DOCS", name: "jetbrains/idea/2025/09", chunks: 1 },
          { collection: "chat-github-repo", kind: "GITHUB", name: "https://github.com/acme/repo", chunks: 7 },
        ],
        totalChunks: 18,
      }),
    );
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const apiAddress = apiServer.address();
  assert.notEqual(apiAddress, null);
  assert.equal(typeof apiAddress, "object");

  const cliExecution = await runCli(["knowledge", "--host", `http://127.0.0.1:${apiAddress.port}`], {
    JAVACHAT_API_KEY: TEST_API_KEY,
  });

  assert.equal(cliExecution.exitCode, 0);
  assert.match(cliExecution.standardOutput, /Knowledge base at http:\/\/127\.0\.0\.1:/);
  assert.match(cliExecution.standardOutput, /DOCS — chat-docs/);
  assert.match(cliExecution.standardOutput, /oracle\/javase\/25\/api \(10 chunks\)/);
  assert.match(cliExecution.standardOutput, /jetbrains\/idea\/2025\/09 \(1 chunk\)/);
  assert.match(cliExecution.standardOutput, /GITHUB — chat-github-repo/);
  assert.match(cliExecution.standardOutput, /18 chunks across 3 groups in 2 collections\./);
  assert.equal(cliExecution.standardError, "");
});

test("knowledge reports an empty knowledge base", async (testContext) => {
  const apiServer = createServer((request, response) => {
    response.writeHead(200, { "content-type": "application/json" });
    response.end('{"groups":[],"totalChunks":0}');
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const apiAddress = apiServer.address();
  assert.notEqual(apiAddress, null);
  assert.equal(typeof apiAddress, "object");

  const cliExecution = await runCli(["knowledge", "--host", `http://127.0.0.1:${apiAddress.port}`], {
    JAVACHAT_API_KEY: TEST_API_KEY,
  });

  assert.equal(cliExecution.exitCode, 0);
  assert.match(cliExecution.standardOutput, /No document groups are ingested/);
});

test("knowledge rejects a malformed group list", async (testContext) => {
  const apiServer = createServer((request, response) => {
    response.writeHead(200, { "content-type": "application/json" });
    response.end('{"groups":[{"collection":"chat-docs","kind":"DOCS"}],"totalChunks":0}');
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const apiAddress = apiServer.address();
  assert.notEqual(apiAddress, null);
  assert.equal(typeof apiAddress, "object");

  const cliExecution = await runCli(["knowledge", "--host", `http://127.0.0.1:${apiAddress.port}`], {
    JAVACHAT_API_KEY: TEST_API_KEY,
  });

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /malformed knowledge group/);
});

test("knowledge rejects unsafe chunk totals", async (testContext) => {
  const apiServer = createServer((request, response) => {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(
      '{"groups":[{"collection":"docs","kind":"DOCS","name":"java","chunks":9007199254740993}],"totalChunks":9007199254740993}',
    );
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const apiAddress = apiServer.address();
  assert.equal(typeof apiAddress, "object");
  const cliExecution = await runCli(["knowledge", "--host", `http://127.0.0.1:${apiAddress.port}`], {
    JAVACHAT_API_KEY: TEST_API_KEY,
  });

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /invalid knowledge inventory/);
});

test("knowledge rejects inconsistent chunk totals", async (testContext) => {
  const apiServer = createServer((request, response) => {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(
      '{"groups":[{"collection":"docs","kind":"DOCS","name":"java","chunks":3}],"totalChunks":4}',
    );
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const apiAddress = apiServer.address();
  assert.equal(typeof apiAddress, "object");

  const cliExecution = await runCli(["knowledge", "--host", `http://127.0.0.1:${apiAddress.port}`], {
    JAVACHAT_API_KEY: TEST_API_KEY,
  });

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /inconsistent knowledge inventory/);
});

test("knowledge strips terminal control characters from server strings", async (testContext) => {
  const apiServer = createServer((request, response) => {
    response.writeHead(200, { "content-type": "application/json" });
    response.end(
      JSON.stringify({
        groups: [
          {
            collection: "chat-docs",
            kind: "DOCS",
            name: "docs/\u001b]52;c;c3Bvb2Y=\u0007evil",
            chunks: 3,
          },
        ],
        totalChunks: 3,
      }),
    );
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const apiAddress = apiServer.address();
  assert.notEqual(apiAddress, null);
  assert.equal(typeof apiAddress, "object");

  const cliExecution = await runCli(["knowledge", "--host", `http://127.0.0.1:${apiAddress.port}`], {
    JAVACHAT_API_KEY: TEST_API_KEY,
  });

  assert.equal(cliExecution.exitCode, 0);
  assert.doesNotMatch(cliExecution.standardOutput, /\u001b|\u0007/);
  assert.match(cliExecution.standardOutput, /docs\/evil \(3 chunks\)/);
});

test("knowledge includes the server error body on failure", async (testContext) => {
  const apiServer = createServer((request, response) => {
    response.writeHead(401, { "content-type": "application/json" });
    response.end('{"message":"API key is invalid, revoked, or expired."}');
  });
  apiServer.listen(0, "127.0.0.1");
  await once(apiServer, "listening");
  testContext.after(() => apiServer.close());
  const apiAddress = apiServer.address();
  assert.notEqual(apiAddress, null);
  assert.equal(typeof apiAddress, "object");

  const cliExecution = await runCli(["knowledge", "--host", `http://127.0.0.1:${apiAddress.port}`], {
    JAVACHAT_API_KEY: TEST_API_KEY,
  });

  assert.equal(cliExecution.exitCode, 1);
  assert.match(cliExecution.standardError, /HTTP 401/);
  assert.match(cliExecution.standardError, /API key is invalid, revoked, or expired\./);
});

async function waitForCliState(assertion, timeoutMilliseconds = 2000) {
  const deadline = Date.now() + timeoutMilliseconds;
  while (!assertion()) {
    if (Date.now() >= deadline) {
      throw new Error("Timed out waiting for CLI state");
    }
    await new Promise((resolve) => setTimeout(resolve, 10));
  }
}
