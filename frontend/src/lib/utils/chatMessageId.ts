/**
 * Chat message identifier utilities.
 *
 * Provides stable, collision-resistant IDs for message list keying across
 * chat and guided chat rendering paths.
 */

type MessageContext = 'chat' | 'guided'

let sequenceNumber = 0

function nextSequenceNumber(): number {
  sequenceNumber = (sequenceNumber + 1) % 1_000_000
  return sequenceNumber
}

function createRandomSuffix(): string {
  if (typeof crypto !== 'undefined' && typeof crypto.randomUUID === 'function') {
    return crypto.randomUUID()
  }
  return Math.random().toString(36).slice(2, 12)
}

/**
 * Creates a stable identifier for a chat message.
 *
 * @param context - Message origin (main chat vs guided chat)
 * @param sessionId - Stable per-view session identifier
 * @returns A stable unique message identifier
 */
export function createChatMessageId(context: MessageContext, sessionId: string): string {
  const timestampMs = Date.now()
  const sequence = nextSequenceNumber()
  const randomSuffix = createRandomSuffix()
  return `msg-${context}-${sessionId}-${timestampMs}-${sequence}-${randomSuffix}`
}

