import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { createServer } from "node:http";
import { once } from "node:events";
import { test } from "node:test";
import { fileURLToPath } from "node:url";
import { mkdtemp, rm } from "node:fs/promises";
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
  const apiServer = createServer((request, response) => {
    if (request.url === "/api/me") {
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
  const child = spawn(process.execPath, [CLI_ENTRYPOINT, "login", "--no-browser"], {
    env: { ...process.env, XDG_CONFIG_HOME: configurationHome },
    stdio: ["ignore", "pipe", "pipe"],
  });
  let standardError = "";
  child.stderr.setEncoding("utf8").on("data", (errorChunk) => {
    standardError += errorChunk;
  });
  await waitForCliState(() => standardError.includes("Waiting for approval..."));
  const approvalUrlMatch = standardError.match(/https:\/\/javachat\.ai\/cli\/authorize\?[^\s]+/);
  assert.notEqual(approvalUrlMatch, null);
  const approvalUrl = new URL(approvalUrlMatch[0]);
  const callbackPort = approvalUrl.searchParams.get("port");
  assert.notEqual(callbackPort, null);

  const callbackResponse = await fetch(`http://127.0.0.1:${callbackPort}/complete`, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: "null",
  });
  assert.equal(callbackResponse.status, 400);
  const [exitCode] = await once(child, "close");

  assert.equal(exitCode, 1);
  assert.match(standardError, /Authorization callback was malformed/);
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
