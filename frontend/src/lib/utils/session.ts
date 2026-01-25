/**
 * Session identifier utilities for client-side chat session management.
 */

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
  return `${prefix}-${Date.now()}-${Math.random().toString(36).slice(2, 15)}`
}
