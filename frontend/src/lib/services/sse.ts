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
  TextChunkSchema,
  ProviderEventSchema,
  CitationsArraySchema,
  type StreamStatus,
  type StreamError,
  type ProviderEvent,
  type Citation,
  type BrowserSearchFailure,
} from "../validation/schemas";
import { validateWithSchema } from "../validation/validate";
import { csrfHeader, readApiError, fetchWithCsrfRetry } from "./csrf";
import { reportSearchFailure } from "./searchDiagnostics";

/** SSE event types emitted by streaming endpoints. */
const SSE_EVENT_STATUS = "status";
const SSE_EVENT_ERROR = "error";
const SSE_EVENT_CITATION = "citation";
const SSE_EVENT_PROVIDER = "provider";
const SSE_EVENT_TEXT = "text";
const INVALID_SSE_EVENT_MESSAGE = "Received an invalid SSE event from the server";
const DIAGNOSTIC_PAGE_PATH_MAX_LENGTH = 160;

/** Optional request options for streaming fetch calls. */
export interface StreamSseRequestOptions {
  signal?: AbortSignal;
}

/**
 * Terminal failure thrown when the server emits an SSE error event.
 * Carries the full validated payload so the UI can render details and
 * honor the server's retryable flag; a plain Error would drop them.
 */
export class StreamFailureError extends Error {
  readonly details?: string;
  readonly code?: string;
  readonly retryable?: boolean;
  readonly stage?: string;
  httpStatus?: number;
  requestId?: string;
  retryAfterSeconds?: number;

  constructor(streamError: StreamError, options?: ErrorOptions) {
    super(streamError.message, options);
    this.name = "StreamFailureError";
    this.details = streamError.details ?? undefined;
    this.code = streamError.code ?? undefined;
    this.retryable = streamError.retryable ?? undefined;
    this.stage = streamError.stage ?? undefined;
  }
}

/** Callbacks for SSE stream processing. */
export interface SseCallbacks {
  onText: (streamText: string) => void;
  onStatus?: (status: StreamStatus) => void;
  onError?: (error: StreamError) => void;
  onCitations?: (citations: Citation[]) => void;
  onProvider?: (provider: ProviderEvent) => void;
}

/** User-facing summary when a stream request never reaches the server or drops mid-stream. */
const NETWORK_FAILURE_MESSAGE = "Couldn't reach the server";

/** Recovery guidance shown with the network failure summary. */
const NETWORK_FAILURE_DETAILS = "Check your connection and try again.";

/** HTTP status for rate limiting, which always warrants a retry after backoff. */
const HTTP_TOO_MANY_REQUESTS_STATUS = 429;

/** First HTTP status in the server-error range; 5xx failures are transient. */
const HTTP_SERVER_ERROR_STATUS_THRESHOLD = 500;

/** Reports whether an HTTP failure status is likely transient and worth retrying. */
function isRetryableHttpStatus(httpStatus: number): boolean {
  return (
    httpStatus === HTTP_TOO_MANY_REQUESTS_STATUS || httpStatus >= HTTP_SERVER_ERROR_STATUS_THRESHOLD
  );
}

function isAbortError(error: unknown): boolean {
  return error instanceof Error && error.name === "AbortError";
}

/**
 * Reports and throws the canonical network-layer stream failure.
 *
 * <p>fetch rejections and mid-stream read failures are transport problems, not server answers:
 * surfacing the browser's raw TypeError text ("Failed to fetch") leaks jargon and carries no
 * recovery path. The wrapped StreamFailureError gives views the same friendly details and
 * retryable flag that server-sent error events already provide. The original transport error is
 * logged and attached as `cause` so network diagnostics (DNS, TLS, connection reset) survive.</p>
 */
function throwNetworkStreamFailure(
  callbacks: SseCallbacks,
  source: string,
  transportError: unknown,
): never {
  const networkFailure: StreamError = {
    message: NETWORK_FAILURE_MESSAGE,
    details: NETWORK_FAILURE_DETAILS,
    retryable: true,
  };
  console.error(`[${source}] Stream transport failure:`, transportError);
  callbacks.onError?.(networkFailure);
  throw new StreamFailureError(networkFailure, { cause: transportError });
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
    throw new StreamFailureError(streamError);
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
    const textChunkValidation = validateWithSchema(
      TextChunkSchema,
      parsedSseEvent,
      `${source}:text`,
    );
    if (!textChunkValidation.success) {
      throwInvalidSseEvent(callbacks);
    }
    if (textChunkValidation.validated.text !== "") {
      callbacks.onText(textChunkValidation.validated.text);
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
  const startedAt = performance.now();
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
    reportStreamFailure(url, startedAt, fetchError);
    throwNetworkStreamFailure(callbacks, source, fetchError);
  }

  try {
    await consumeSseStream(httpResponse, callbacks, source, abortSignal);
  } catch (streamFailure) {
    reportStreamFailure(url, startedAt, streamFailure, httpResponse);
    throw streamFailure;
  }
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
  const startedAt = performance.now();
  let httpResponse: Response;

  try {
    httpResponse = await fetch(url, { method: "GET", signal: abortSignal });
  } catch (fetchError) {
    if (abortSignal?.aborted || isAbortError(fetchError)) {
      return;
    }
    reportStreamFailure(url, startedAt, fetchError);
    throwNetworkStreamFailure(callbacks, source, fetchError);
  }

  try {
    await consumeSseStream(httpResponse, callbacks, source, abortSignal);
  } catch (streamFailure) {
    reportStreamFailure(url, startedAt, streamFailure, httpResponse);
    throw streamFailure;
  }
}

function reportStreamFailure(
  url: string,
  startedAt: number,
  failure: unknown,
  httpResponse?: Response,
): void {
  const streamFailure = failure instanceof StreamFailureError ? failure : undefined;
  const frontendScript = document.querySelector<HTMLScriptElement>('script[type="module"][src]');
  let failureKind: BrowserSearchFailure["kind"] = "PROTOCOL";
  if (!httpResponse) {
    failureKind = "NETWORK";
  } else if (!httpResponse.ok) {
    failureKind = "HTTP";
  } else if (streamFailure?.cause) {
    failureKind = "NETWORK";
  } else if (streamFailure && httpResponse.body !== null) {
    failureKind = "SSE";
  }
  void reportSearchFailure({
    path: new URL(url, window.location.origin).pathname,
    kind: failureKind,
    httpStatus: httpResponse?.status,
    requestId: httpResponse?.headers.get("X-Request-ID") ?? undefined,
    code: streamFailure?.code,
    stage: streamFailure?.stage,
    retryable: streamFailure?.retryable,
    retryAfterSeconds: streamFailure?.retryAfterSeconds,
    pagePath: window.location.pathname.slice(0, DIAGNOSTIC_PAGE_PATH_MAX_LENGTH),
    frontendAsset: frontendScript ? new URL(frontendScript.src).pathname : undefined,
    elapsedMs: Math.round(performance.now() - startedAt),
  });
}

async function consumeSseStream(
  httpResponse: Response,
  callbacks: SseCallbacks,
  source: string,
  abortSignal?: AbortSignal,
): Promise<void> {
  if (!httpResponse.ok) {
    const apiError = await readApiError(httpResponse, `streamSse:${source}`);
    const apiMessage = apiError?.message.trim();
    const statusSummary = `HTTP ${httpResponse.status}: ${httpResponse.statusText || "Request failed"}`;
    const httpFailure: StreamError = {
      message: apiMessage || statusSummary,
      retryable: isRetryableHttpStatus(httpResponse.status),
    };
    if (apiMessage) {
      httpFailure.details = apiError?.details || statusSummary;
    }
    callbacks.onError?.(httpFailure);
    const refusal = new StreamFailureError(httpFailure);
    refusal.httpStatus = httpResponse.status;
    refusal.requestId = httpResponse.headers.get("X-Request-ID") ?? undefined;
    const retryAfter = httpResponse.headers.get("Retry-After");
    if (retryAfter) {
      const retrySeconds = Number(retryAfter);
      const retryDate = Date.parse(retryAfter);
      if (Number.isFinite(retrySeconds) && retrySeconds >= 0) {
        refusal.retryAfterSeconds = retrySeconds;
      } else if (Number.isFinite(retryDate)) {
        refusal.retryAfterSeconds = Math.max(0, Math.ceil((retryDate - Date.now()) / 1000));
      }
    }
    throw refusal;
  }

  const sseReader = httpResponse.body?.getReader();
  if (!sseReader) {
    const missingBodyFailure: StreamError = {
      message: "No response body",
      retryable: true,
    };
    callbacks.onError?.(missingBodyFailure);
    throw new StreamFailureError(missingBodyFailure);
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

  const processSseLine = (decodedSseLine: string) => {
    const receivedLine = decodedSseLine.endsWith("\r")
      ? decodedSseLine.slice(0, -1)
      : decodedSseLine;

    if (receivedLine.startsWith(":")) {
      return;
    }

    if (receivedLine.startsWith("event:")) {
      currentSseEventType = receivedLine.startsWith("event: ")
        ? receivedLine.slice(7)
        : receivedLine.slice(6);
      return;
    }

    if (receivedLine.startsWith("data:")) {
      const sseEventText = receivedLine.startsWith("data: ")
        ? receivedLine.slice(6)
        : receivedLine.slice(5);
      if (sseEventText === "[DONE]") {
        return;
      }
      if (hasBufferedSseEvent) {
        sseEventBuffer += "\n";
      }
      sseEventBuffer += sseEventText;
      hasBufferedSseEvent = true;
      return;
    }

    if (receivedLine.trim() === "") {
      flushSseEvent();
    }
  };

  try {
    while (true) {
      const { done: streamEnded, value: byteSegment } = await sseReader
        .read()
        .catch((streamReadFailure: unknown): never => {
          if (abortSignal?.aborted || isAbortError(streamReadFailure)) {
            throw streamReadFailure;
          }
          throwNetworkStreamFailure(callbacks, source, streamReadFailure);
        });

      if (streamEnded) {
        streamCompletedNormally = true;
        // Flush any remaining bytes from the TextDecoder (handles multi-byte chars split across chunks)
        const remainingDecodedText = decoder.decode();
        if (remainingDecodedText) {
          unprocessedText += remainingDecodedText;
        }
        if (unprocessedText.length > 0) {
          processSseLine(unprocessedText);
          unprocessedText = "";
        }
        if (hasBufferedSseEvent) {
          throwInvalidSseEvent(callbacks);
        }
        break;
      }

      const decodedText = decoder.decode(byteSegment, { stream: true });
      unprocessedText += decodedText;
      const receivedLines = unprocessedText.split("\n");
      unprocessedText = receivedLines[receivedLines.length - 1];

      for (let lineIndex = 0; lineIndex < receivedLines.length - 1; lineIndex++) {
        processSseLine(receivedLines[lineIndex]);
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
