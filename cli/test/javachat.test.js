import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { once } from "node:events";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { mkdtemp, readFile, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";

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
    [CLI_ENTRYPOINT, "login", "--host", host, "--no-browser"],
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

test("prints usage without loading credentials", async () => {
  const cliExecution = await runCli(["--help"]);

  assert.equal(cliExecution.exitCode, 0);
  assert.match(cliExecution.standardOutput, /javachat login/);
  assert.equal(cliExecution.standardError, "");
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
    ["--host", `http://127.0.0.1:${address.port}`, "How do records work?"],
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
    ["--host", `http://127.0.0.1:${address.port}`, "What is a sealed interface?"],
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
    ["--host", `http://127.0.0.1:${address.port}`, "Anything"],
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
    ["--host", `http://127.0.0.1:${address.port}`, "How do records work?"],
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
