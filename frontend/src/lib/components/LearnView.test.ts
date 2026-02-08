import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, fireEvent } from '@testing-library/svelte'
import { tick } from 'svelte'

const fetchTocMock = vi.fn()
const fetchLessonContentMock = vi.fn()
const fetchGuidedLessonCitationsMock = vi.fn()
const streamGuidedChatMock = vi.fn()

vi.mock('../services/guided', async () => {
  const actualGuidedService = await vi.importActual<typeof import('../services/guided')>('../services/guided')
  return {
    ...actualGuidedService,
    fetchTOC: fetchTocMock,
    fetchLessonContent: fetchLessonContentMock,
    fetchGuidedLessonCitations: fetchGuidedLessonCitationsMock,
    streamGuidedChat: streamGuidedChatMock
  }
})

async function renderLearnView() {
  const LearnViewComponent = (await import('./LearnView.svelte')).default
  return render(LearnViewComponent)
}

describe('LearnView guided chat streaming stability', () => {
  beforeEach(() => {
    fetchTocMock.mockReset()
    fetchLessonContentMock.mockReset()
    fetchGuidedLessonCitationsMock.mockReset()
    streamGuidedChatMock.mockReset()
  })

  afterEach(() => {
    vi.unstubAllGlobals()
  })

  it('keeps the guided assistant message DOM node stable when the stream completes', async () => {
    fetchTocMock.mockResolvedValue([{ slug: 'intro', title: 'Test Lesson', summary: 'Lesson summary', keywords: [] }])

    fetchLessonContentMock.mockResolvedValue({ markdown: '# Lesson', cached: false })
    fetchGuidedLessonCitationsMock.mockResolvedValue({ success: true, citations: [] })

    let completeStream: () => void = () => {
      throw new Error('Expected guided stream completion callback to be set')
    }
    streamGuidedChatMock.mockImplementation(async (_sessionId, _slug, _message, callbacks) => {
      callbacks.onStatus?.({ message: 'Searching', details: 'Loading sources' })

      await Promise.resolve()
      callbacks.onChunk('Hello')

      await Promise.resolve()
      callbacks.onCitations?.([{ url: 'https://example.com', title: 'Example' }])

      return new Promise<void>((resolve) => {
        completeStream = resolve
      })
    })

    const { findByRole, getByLabelText, getByRole, container, findByText } = await renderLearnView()

    const lessonButton = await findByRole('button', { name: /test lesson/i })
    await fireEvent.click(lessonButton)

    const inputElement = getByLabelText('Message input') as HTMLTextAreaElement
    await fireEvent.input(inputElement, { target: { value: 'Hi' } })

    const sendButton = getByRole('button', { name: 'Send message' })
    await fireEvent.click(sendButton)

    const assistantTextElement = await findByText('Hello')
    await tick()

    const assistantMessageElement = assistantTextElement.closest('.chat-panel--desktop .message.assistant')
    expect(assistantMessageElement).not.toBeNull()

    expect(container.querySelector('.chat-panel--desktop .message.assistant .cursor.visible')).not.toBeNull()

    completeStream()
    await tick()

    const assistantTextElementAfter = await findByText('Hello')
    const assistantMessageElementAfter = assistantTextElementAfter.closest('.chat-panel--desktop .message.assistant')

    expect(assistantMessageElementAfter).toBe(assistantMessageElement)
    expect(container.querySelector('.chat-panel--desktop .message.assistant .cursor.visible')).toBeNull()
  })

  it('cancels the guided stream and clears messages without late writes after clear chat', async () => {
    fetchTocMock.mockResolvedValue([{ slug: 'intro', title: 'Test Lesson', summary: 'Lesson summary', keywords: [] }])

    fetchLessonContentMock.mockResolvedValue({ markdown: '# Lesson', cached: false })
    fetchGuidedLessonCitationsMock.mockResolvedValue({ success: true, citations: [] })

    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      status: 200,
      statusText: 'OK',
      text: async () => ''
    })
    vi.stubGlobal('fetch', fetchMock)

    const guidedSessionIds: string[] = []
    const abortSignalsByStream: Array<AbortSignal | undefined> = []
    let hasIssuedClear = false

    streamGuidedChatMock.mockImplementation(async (sessionId, _slug, _message, callbacks) => {
      guidedSessionIds.push(sessionId)
      abortSignalsByStream.push(callbacks.signal)
      callbacks.onChunk(hasIssuedClear ? 'Hello again' : 'Hello')

      if (hasIssuedClear) {
        return
      }

      const streamAbortSignal = callbacks.signal
      if (!streamAbortSignal) {
        throw new Error('Expected LearnView to pass an AbortSignal for guided streaming')
      }

      return new Promise<void>((resolve) => {
        streamAbortSignal.addEventListener(
          'abort',
          () => {
            // Simulate a late chunk arriving after Clear Chat.
            Promise.resolve().then(() => callbacks.onChunk('Late chunk'))
            resolve()
          },
          { once: true }
        )
      })
    })

    const { findByRole, getByLabelText, getByRole, findByText, queryByText } = await renderLearnView()

    const lessonButton = await findByRole('button', { name: /test lesson/i })
    await fireEvent.click(lessonButton)

    const inputElement = getByLabelText('Message input') as HTMLTextAreaElement
    await fireEvent.input(inputElement, { target: { value: 'Hi' } })

    const sendButton = getByRole('button', { name: 'Send message' })
    await fireEvent.click(sendButton)

    await findByText('Hello')

    const clearChatButton = getByRole('button', { name: 'Clear chat' })
    await fireEvent.click(clearChatButton)
    hasIssuedClear = true
    await tick()

    expect(queryByText('Hello')).toBeNull()
    expect(queryByText('Late chunk')).toBeNull()

    const inputElementAfterClear = getByLabelText('Message input') as HTMLTextAreaElement
    await fireEvent.input(inputElementAfterClear, { target: { value: 'Hi again' } })

    const sendButtonAfterClear = getByRole('button', { name: 'Send message' })
    await fireEvent.click(sendButtonAfterClear)

    await findByText('Hello again')

    expect(guidedSessionIds).toHaveLength(2)
    expect(guidedSessionIds[1]).not.toBe(guidedSessionIds[0])

    expect(fetchMock).toHaveBeenCalledWith(
      `/api/chat/clear?sessionId=${encodeURIComponent(guidedSessionIds[0])}`,
      expect.objectContaining({ method: 'POST' })
    )
    expect(abortSignalsByStream[0]?.aborted ?? false).toBe(true)
  })
})
