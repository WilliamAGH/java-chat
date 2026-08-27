import { describe, expect, it } from "vitest";
import { assertBrowserProcessBoundary } from "../browser-process-boundary";

describe("browser process boundary", () => {
  it("rejects a macOS Codex browser launch", () => {
    expect(() =>
      assertBrowserProcessBoundary("darwin", { CODEX_SESSION_ID: "test-session" }),
    ).toThrow("Browser launch is blocked in the Codex macOS sandbox");
  });

  it("permits CI and non-Codex hosts", () => {
    expect(() => assertBrowserProcessBoundary("linux", { CODEX_SESSION_ID: "test" })).not.toThrow();
    expect(() => assertBrowserProcessBoundary("darwin", {})).not.toThrow();
  });
});
