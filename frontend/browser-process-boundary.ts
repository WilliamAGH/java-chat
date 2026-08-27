export function assertBrowserProcessBoundary(
  platform = process.platform,
  environment: Readonly<Record<string, string | undefined>> = process.env,
): void {
  if (platform === "darwin" && environment.CODEX_SESSION_ID) {
    throw new Error(
      "Browser launch is blocked in the Codex macOS sandbox; use the GitHub browser-test job.",
    );
  }
}
