import { describe, it, expect, vi, beforeEach } from 'vitest'
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

  it('keeps the guided assistant message DOM node stable when the stream completes', async () => {
    fetchTocMock.mockResolvedValue([
      { slug: 'intro', title: 'Test Lesson', summary: 'Lesson summary', keywords: [] }
    ])

    fetchLessonContentMock.mockResolvedValue({ markdown: '# Lesson', cached: false })
    fetchGuidedLessonCitationsMock.mockResolvedValue({ success: true, citations: [] })

    let completeStream: (() => void) | null = null
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

    if (!completeStream) {
      throw new Error('Expected guided stream completion callback to be set')
    }
    completeStream()
    await tick()

    const assistantTextElementAfter = await findByText('Hello')
    const assistantMessageElementAfter = assistantTextElementAfter.closest('.chat-panel--desktop .message.assistant')

    expect(assistantMessageElementAfter).toBe(assistantMessageElement)
    expect(container.querySelector('.chat-panel--desktop .message.assistant .cursor.visible')).toBeNull()
  })
})
