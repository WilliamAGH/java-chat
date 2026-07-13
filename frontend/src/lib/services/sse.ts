/**
 * Canonical SSE stream parser for chat endpoints.
 *
 * Provides unified Server-Sent Events parsing with proper buffering,
 * event type handling, and connection cleanup. Used by both main chat
 * and guided learning streams.
 */

import {
  StreamStatusSchema,
  StreamErrorSchema,
  StreamTextSchema,
  ProviderEventSchema,
  CitationsArraySchema,
  type StreamStatus,
  type StreamError,
  type ProviderEvent,
  type Citation,
} from "../validation/schemas";
import { validateWithSchema } from "../validation/validate";
import { csrfHeader, extractApiErrorMessage, fetchWithCsrfRetry } from "./csrf";

/** SSE event types emitted by streaming endpoints. */
const SSE_EVENT_STATUS = "status";
const SSE_EVENT_ERROR = "error";
const SSE_EVENT_CITATION = "citation";
const SSE_EVENT_PROVIDER = "provider";
const SSE_EVENT_TEXT = "text";
const INVALID_SSE_EVENT_MESSAGE = "Received an invalid SSE event from the server";

/** Optional request options for streaming fetch calls. */
export interface StreamSseRequestOptions {
  signal?: AbortSignal;
}

/** Callbacks for SSE stream processing. */
export interface SseCallbacks {
  onText: (streamText: string) => void;
  onStatus?: (status: StreamStatus) => void;
  onError?: (error: StreamError) => void;
  onCitations?: (citations: Citation[]) => void;
  onProvider?: (provider: ProviderEvent) => void;
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === "AbortError";
}

function throwInvalidSseEvent(callbacks: SseCallbacks): never {
  const streamError: StreamError = { message: INVALID_SSE_EVENT_MESSAGE };
  callbacks.onError?.(streamError);
  throw new Error(streamError.message);
}

/**
 * Attempts JSON parsing when an SSE payload looks like JSON.
 * Returns the parsed SSE payload or null when it is not valid JSON.
 * Logs parse errors with source context for protocol diagnostics.
 */
export function tryParseJson(rawSsePayload: string, source: string): unknown {
  const trimmedSsePayload = rawSsePayload.trim();
  if (!trimmedSsePayload.startsWith("{") && !trimmedSsePayload.startsWith("[")) {
    return null;
  }
  try {
    return JSON.parse(trimmedSsePayload);
  } catch (parseError) {
    console.warn(`[${source}] JSON parse failed for an SSE payload that looked like JSON:`, {
      preview: trimmedSsePayload.slice(0, 100),
      error: parseError instanceof Error ? parseError.message : String(parseError),
    });
  }
  return null;
}

/**
 * Processes a complete SSE event and dispatches to appropriate callback.
 *
 * @param sseEventType - The SSE event type (status, error, citation, provider, or text)
 * @param rawSseEventText - The raw event text emitted by the SSE stream
 * @param callbacks - Callbacks to invoke based on event type
 * @throws Error when an error event is received (to terminate the stream)
 */
function processEvent(
  sseEventType: string,
  rawSseEventText: string,
  callbacks: SseCallbacks,
  source: string,
): void {
  const normalizedSseEventType = sseEventType.trim().toLowerCase();

  if (normalizedSseEventType === SSE_EVENT_STATUS) {
    const parsedSseEvent = tryParseJson(rawSseEventText, source);
    const statusValidation = validateWithSchema(
      StreamStatusSchema,
      parsedSseEvent,
      `${source}:status`,
    );
    if (!statusValidation.success) {
      throwInvalidSseEvent(callbacks);
    }
    callbacks.onStatus?.(statusValidation.validated);
    return;
  }

  if (normalizedSseEventType === SSE_EVENT_ERROR) {
    const parsedSseEvent = tryParseJson(rawSseEventText, source);
    const errorValidation = validateWithSchema(
      StreamErrorSchema,
      parsedSseEvent,
      `${source}:error`,
    );
    if (!errorValidation.success) {
      throwInvalidSseEvent(callbacks);
    }
    const streamError = errorValidation.validated;
    callbacks.onError?.(streamError);
    const streamFailure: Error & { details?: string } = new Error(streamError.message);
    streamFailure.details = streamError.details ?? undefined;
    throw streamFailure;
  }

  if (normalizedSseEventType === SSE_EVENT_CITATION) {
    const parsedSseEvent = tryParseJson(rawSseEventText, source);
    const citationsValidation = validateWithSchema(
      CitationsArraySchema,
      parsedSseEvent,
      `${source}:citations`,
    );
    if (!citationsValidation.success) {
      throwInvalidSseEvent(callbacks);
    }
    callbacks.onCitations?.(citationsValidation.validated);
    return;
  }

  if (normalizedSseEventType === SSE_EVENT_PROVIDER) {
    const parsedSseEvent = tryParseJson(rawSseEventText, source);
    const providerValidation = validateWithSchema(
      ProviderEventSchema,
      parsedSseEvent,
      `${source}:provider`,
    );
    if (!providerValidation.success) {
      throwInvalidSseEvent(callbacks);
    }
    callbacks.onProvider?.(providerValidation.validated);
    return;
  }

  if (normalizedSseEventType === SSE_EVENT_TEXT) {
    const parsedSseEvent = tryParseJson(rawSseEventText, source);
    const streamTextValidation = validateWithSchema(
      StreamTextSchema,
      parsedSseEvent,
      `${source}:text`,
    );
    if (!streamTextValidation.success) {
      throwInvalidSseEvent(callbacks);
    }
    if (streamTextValidation.validated.text !== "") {
      callbacks.onText(streamTextValidation.validated.text);
    }
    return;
  }

  console.error(`[${source}] Rejected unsupported SSE event type`, {
    eventType: normalizedSseEventType || "<missing>",
  });
  throwInvalidSseEvent(callbacks);
}

/**
 * Streams SSE responses from a POST endpoint with proper buffering and cleanup.
 *
 * Handles:
 * - Multi-byte character buffering across chunks
 * - SSE event type parsing (event:, data:, blank line termination)
 * - Heartbeat comment filtering
 * - [DONE] token skipping
 * - Graceful stream cancellation on early exit
 *
 * @param url - The endpoint URL
 * @param requestBody - The request body to POST
 * @param callbacks - Callbacks for different event types
 * @param source - Source identifier for logging (e.g., 'chat.ts', 'guided.ts')
 */
export async function streamSse(
  url: string,
  requestBody: object,
  callbacks: SseCallbacks,
  source: string,
  requestOptions: StreamSseRequestOptions = {},
): Promise<void> {
  const abortSignal = requestOptions.signal;
  let httpResponse: Response;

  try {
    httpResponse = await fetchWithCsrfRetry(
      url,
      {
        method: "POST",
        headers: {
          "Content-Type": "application/json",
          ...csrfHeader(),
        },
        body: JSON.stringify(requestBody),
        signal: abortSignal,
      },
      `streamSse:${source}`,
    );
  } catch (fetchError) {
    if (abortSignal?.aborted || isAbortError(fetchError)) {
      return;
    }
    throw fetchError;
  }

  await consumeSseStream(httpResponse, callbacks, source, abortSignal);
}

/**
 * Streams SSE responses from a GET endpoint with the same parser used for POST streams.
 */
export async function streamSseGet(
  url: string,
  callbacks: SseCallbacks,
  source: string,
  requestOptions: StreamSseRequestOptions = {},
): Promise<void> {
  const abortSignal = requestOptions.signal;
  let httpResponse: Response;

  try {
    httpResponse = await fetch(url, { method: "GET", signal: abortSignal });
  } catch (fetchError) {
    if (abortSignal?.aborted || isAbortError(fetchError)) {
      return;
    }
    throw fetchError;
  }

  await consumeSseStream(httpResponse, callbacks, source, abortSignal);
}

async function consumeSseStream(
  httpResponse: Response,
  callbacks: SseCallbacks,
  source: string,
  abortSignal?: AbortSignal,
): Promise<void> {
  if (!httpResponse.ok) {
    const apiMessage = await extractApiErrorMessage(httpResponse, `streamSse:${source}`);
    const errorMessage =
      apiMessage ?? `HTTP ${httpResponse.status}: ${httpResponse.statusText || "Request failed"}`;
    const httpError = new Error(errorMessage);
    callbacks.onError?.({ message: httpError.message });
    throw httpError;
  }

  const sseReader = httpResponse.body?.getReader();
  if (!sseReader) {
    const missingStreamError = new Error("No response body");
    callbacks.onError?.({ message: missingStreamError.message });
    throw missingStreamError;
  }

  const decoder = new TextDecoder();
  let streamCompletedNormally = false;
  let unprocessedText = "";
  let sseEventBuffer = "";
  let hasBufferedSseEvent = false;
  let currentSseEventType: string | null = null;

  const flushSseEvent = () => {
    if (!hasBufferedSseEvent) {
      currentSseEventType = null;
      return;
    }

    const sseEventType = currentSseEventType ?? "";
    const rawSseEventText = sseEventBuffer;
    sseEventBuffer = "";
    hasBufferedSseEvent = false;
    currentSseEventType = null;

    processEvent(sseEventType, rawSseEventText, callbacks, source);
  };

  try {
    while (true) {
      const { done: streamEnded, value: byteSegment } = await sseReader.read();

      if (streamEnded) {
        streamCompletedNormally = true;
        // Flush any remaining bytes from the TextDecoder (handles multi-byte chars split across chunks)
        const remainingDecodedText = decoder.decode();
        if (remainingDecodedText) {
          unprocessedText += remainingDecodedText;
        }
        // Commit any remaining buffered line before flushing event data
        if (unprocessedText.length > 0) {
          sseEventBuffer = sseEventBuffer
            ? `${sseEventBuffer}\n${unprocessedText}`
            : unprocessedText;
          hasBufferedSseEvent = true;
          unprocessedText = "";
        }
        flushSseEvent();
        break;
      }

      const decodedText = decoder.decode(byteSegment, { stream: true });
      unprocessedText += decodedText;
      const receivedLines = unprocessedText.split("\n");
      unprocessedText = receivedLines[receivedLines.length - 1];

      for (let lineIndex = 0; lineIndex < receivedLines.length - 1; lineIndex++) {
        let receivedLine = receivedLines[lineIndex];
        if (receivedLine.endsWith("\r")) {
          receivedLine = receivedLine.slice(0, -1);
        }

        // Skip SSE comments (keepalive heartbeats)
        if (receivedLine.startsWith(":")) {
          continue;
        }

        // Track event type
        if (receivedLine.startsWith("event:")) {
          currentSseEventType = receivedLine.startsWith("event: ")
            ? receivedLine.slice(7)
            : receivedLine.slice(6);
          continue;
        }

        // Accumulate data within current SSE event
        if (receivedLine.startsWith("data:")) {
          // Per SSE spec, strip optional space after "data:" prefix
          const sseEventText = receivedLine.startsWith("data: ")
            ? receivedLine.slice(6)
            : receivedLine.slice(5);

          // Skip [DONE] token
          if (sseEventText === "[DONE]") {
            continue;
          }

          // Accumulate within current SSE event
          if (hasBufferedSseEvent) {
            sseEventBuffer += "\n";
          }
          sseEventBuffer += sseEventText;
          hasBufferedSseEvent = true;
        } else if (receivedLine.trim() === "") {
          // Blank line marks end of SSE event - commit accumulated data
          flushSseEvent();
        }
      }
    }
  } catch (streamError) {
    if (abortSignal?.aborted || isAbortError(streamError)) {
      return;
    }
    throw streamError;
  } finally {
    // Cancel reader on abnormal exit to prevent dangling connections
    if (!streamCompletedNormally) {
      try {
        await sseReader.cancel();
      } catch {
        // Expected: cancel() throws if stream already closed by abort signal or server.
        // Safe to ignore - we're in cleanup and the stream is already terminated.
      }
    }
    sseReader.releaseLock();
  }
}
