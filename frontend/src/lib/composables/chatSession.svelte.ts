/**
 * Owns the main chat transcript and session identity at module level.
 *
 * ChatView is destroyed and recreated whenever the user switches between the
 * Chat and Learn tabs; component-local state made every tab switch silently
 * discard the whole conversation (and the server-side session id that gives
 * it continuity). Keeping both here makes view switches lossless.
 */

import type { ChatMessage, Citation } from "../services/chat";
import { generateSessionId } from "../utils/session";

/** Chat transcript entry with the inline citations attached by the stream. */
export interface ChatSessionMessage extends ChatMessage {
  /** Citations received inline from the SSE stream (eliminates separate API call). */
  citations?: Citation[];
}

interface ChatSessionState {
  /** Backend chat-memory identity; stable for the lifetime of the page. */
  sessionId: string;
  messages: ChatSessionMessage[];
}

export const chatSession: ChatSessionState = $state({
  sessionId: generateSessionId("chat"),
  messages: [],
});

/** Clears the transcript; primarily for tests, which share module state. */
export function resetChatSession(): void {
  chatSession.sessionId = generateSessionId("chat");
  chatSession.messages = [];
}
