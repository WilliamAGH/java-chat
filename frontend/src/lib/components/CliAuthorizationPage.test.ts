import { cleanup, render } from "@testing-library/svelte";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import CliAuthorizationPage from "./CliAuthorizationPage.svelte";
import { CliAuthorizationQuerySchema } from "../validation/schemas";

const { clerkAuthenticationMock, openSignInMock } = vi.hoisted(() => ({
  clerkAuthenticationMock: {
    signedInUser: null,
    phase: "ready" as const,
  },
  openSignInMock: vi.fn(),
}));

vi.mock("../composables/clerkAuthentication.svelte", () => ({
  clerkAuthentication: clerkAuthenticationMock,
  createCliApiKey: vi.fn(),
  openSignIn: openSignInMock,
}));

beforeEach(() => {
  clerkAuthenticationMock.phase = "ready";
  clerkAuthenticationMock.signedInUser = null;
  openSignInMock.mockClear();
});

afterEach(() => {
  cleanup();
  globalThis.history.replaceState({}, "", "/");
});

describe("CLI authorization request", () => {
  it("preserves the label's 64 UTF-16 code-unit limit", () => {
    const authorizationQuery = {
      port: "49152",
      state: "0123456789abcdefghijklmnopqrstuvwxyz_AB",
    };

    expect(CliAuthorizationQuerySchema.safeParse({
      ...authorizationQuery,
      label: "😀".repeat(32),
    }).success).toBe(true);
    expect(CliAuthorizationQuerySchema.safeParse({
      ...authorizationQuery,
      label: "😀".repeat(33),
    }).success).toBe(false);
  });

  it("rejects malformed loopback parameters", async () => {
    globalThis.history.replaceState({}, "", "/cli/authorize?port=70000&state=bad&label=terminal");
    const authorizationPage = render(CliAuthorizationPage);

    expect(authorizationPage.getByRole("alert")).toHaveTextContent(
      "This authorization request is invalid. Return to your terminal and run javachat auth login again.",
    );
    expect(
      authorizationPage.queryByRole("button", { name: "Authorize terminal" }),
    ).not.toBeInTheDocument();
  });

  it("requires sign-in before offering approval", async () => {
    globalThis.history.replaceState(
      {},
      "",
      "/cli/authorize?port=49152&state=0123456789abcdefghijklmnopqrstuvwxyz_AB&label=workstation",
    );
    const authorizationPage = render(CliAuthorizationPage);

    expect(authorizationPage.getByText(/Sign in to choose the account/)).toBeInTheDocument();
    authorizationPage.getByRole("button", { name: "Sign in" }).click();
    expect(openSignInMock).toHaveBeenCalledTimes(1);
  });
});
