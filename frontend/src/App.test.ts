import { beforeEach, describe, expect, it, vi } from "vitest";
import { render } from "@testing-library/svelte";

const refreshCsrfTokenMock = vi.fn(async () => true);

vi.mock("./lib/services/csrf", async () => {
  const actualCsrfService =
    await vi.importActual<typeof import("./lib/services/csrf")>("./lib/services/csrf");
  return {
    ...actualCsrfService,
    refreshCsrfToken: refreshCsrfTokenMock,
  };
});

describe("App CSRF preflight", () => {
  beforeEach(() => {
    refreshCsrfTokenMock.mockClear();
  });

  it("refreshes CSRF token on app mount", async () => {
    const App = (await import("./App.svelte")).default;
    render(App);

    expect(refreshCsrfTokenMock).toHaveBeenCalledTimes(1);
  });
});
