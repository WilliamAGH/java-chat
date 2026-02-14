/**
 * Session identifier utilities for client-side chat session management.
 */

function createSessionRandomPart(): string {
  if (typeof crypto !== "undefined") {
    if (typeof crypto.randomUUID === "function") {
      return crypto.randomUUID();
    }
    if (typeof crypto.getRandomValues === "function") {
      const randomBytes = new Uint8Array(16);
      crypto.getRandomValues(randomBytes);
      return Array.from(randomBytes, (randomByte) => randomByte.toString(16).padStart(2, "0")).join(
        "",
      );
    }
  }
  return Math.random().toString(36).slice(2, 14).padEnd(12, "0");
}

/**
 * Generates a unique session identifier with a domain-specific prefix.
 *
 * Combines timestamp for rough ordering with random suffix for uniqueness.
 * Safe for CSR; if SSR is added, call from onMount or use server-provided ID.
 *
 * @param prefix - Domain identifier (e.g., 'chat', 'guided')
 * @returns Unique session ID string in format "{prefix}-{timestamp}-{random}"
 */
export function generateSessionId(prefix: string): string {
  const randomPart = createSessionRandomPart();
  return `${prefix}-${Date.now()}-${randomPart}`;
}
