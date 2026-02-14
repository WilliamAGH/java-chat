/**
 * Chat service for streaming conversations with the backend.
 *
 * @see {@link ./sse.ts} for SSE stream parsing implementation
 * @see {@link ../validation/schemas.ts} for type definitions
 */

import {
  CitationsArraySchema,
  type StreamStatus,
  type StreamError,
  type Citation,
} from "../validation/schemas";
import { validateFetchJson } from "../validation/validate";
import { csrfHeader, extractApiErrorMessage, fetchWithCsrfRetry } from "./csrf";
import { streamWithRetry } from "./streamRecovery";

export type { StreamStatus, StreamError, Citation };

export interface ChatMessage {
  /** Stable client-side identifier for rendering and list keying. */
  messageId: string;
  role: "user" | "assistant";
  messageText: string;
  timestamp: number;
  isError?: boolean;
}

export interface StreamChatOptions {
  onStatus?: (status: StreamStatus) => void;
  onError?: (error: StreamError) => void;
  onCitations?: (citations: Citation[]) => void;
  signal?: AbortSignal;
}

/** Result type for citation fetches - distinguishes empty results from errors. */
export type CitationFetchResult =
  | { success: true; citations: Citation[] }
  | { success: false; error: string };

/**
 * Stream chat response from the backend using Server-Sent Events.
 *
 * @param sessionId - Unique session identifier
 * @param message - User's message
 * @param onChunk - Callback for each streamed text chunk
 * @param options - Optional callbacks for status, error, and citation events
 */
export async function streamChat(
  sessionId: string,
  message: string,
  onChunk: (chunk: string) => void,
  options: StreamChatOptions = {},
): Promise<void> {
  return streamWithRetry(
    "/api/chat/stream",
    { sessionId, latest: message },
    {
      onChunk,
      onStatus: options.onStatus,
      onError: options.onError,
      onCitations: options.onCitations,
      signal: options.signal,
    },
    "chat.ts",
  );
}

/**
 * Clears the server-side chat memory for a session.
 *
 * @param sessionId - Session identifier to clear on the backend.
 */
export async function clearChatSession(sessionId: string): Promise<void> {
  const normalizedSessionId = sessionId.trim();
  if (!normalizedSessionId) {
    throw new Error("Session ID is required");
  }

  const clearSessionResponse = await fetchWithCsrfRetry(
    `/api/chat/clear?sessionId=${encodeURIComponent(normalizedSessionId)}`,
    {
      method: "POST",
      headers: {
        ...csrfHeader(),
      },
    },
    "clearChatSession",
  );

  if (!clearSessionResponse.ok) {
    const apiMessage = await extractApiErrorMessage(clearSessionResponse, "clearChatSession");
    const httpStatusLabel = `HTTP ${clearSessionResponse.status}`;
    const suffix = apiMessage ? `: ${apiMessage}` : `: ${httpStatusLabel}`;
    throw new Error(`Failed to clear chat session${suffix}`);
  }
}

/**
 * Fetches and validates citations from any citation endpoint.
 * Shared implementation for both chat and guided learning citation fetches.
 *
 * @param citationUrl - Full URL to fetch citations from
 * @param logLabel - Label for validation and error logging context
 */
export async function fetchCitationsByEndpoint(
  citationUrl: string,
  logLabel: string,
): Promise<CitationFetchResult> {
  try {
    const citationsResponse = await fetch(citationUrl);
    const citationsValidation = await validateFetchJson(
      citationsResponse,
      CitationsArraySchema,
      logLabel,
    );

    if (!citationsValidation.success) {
      return { success: false, error: citationsValidation.error };
    }

    return { success: true, citations: citationsValidation.validated };
  } catch (error) {
    const errorMessage =
      error instanceof Error ? error.message : "Network error fetching citations";
    console.error(`[${logLabel}] Unexpected error:`, error);
    return { success: false, error: errorMessage };
  }
}

/**
 * Fetch citations for a query.
 * Used by LearnView to fetch lesson-level citations separately from the chat stream.
 * Returns a Result type to distinguish between empty results and fetch failures.
 */
export async function fetchCitations(query: string): Promise<CitationFetchResult> {
  return fetchCitationsByEndpoint(
    `/api/chat/citations?q=${encodeURIComponent(query)}`,
    `fetchCitations [query=${query}]`,
  );
}
