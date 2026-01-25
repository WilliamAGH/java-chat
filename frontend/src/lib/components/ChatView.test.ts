import { describe, it, expect, vi, beforeEach } from 'vitest'
import { render, fireEvent } from '@testing-library/svelte'
import { tick } from 'svelte'

const streamChatMock = vi.fn()

vi.mock('../services/chat', async () => {
  const actualChatService = await vi.importActual<typeof import('../services/chat')>('../services/chat')
  return {
    ...actualChatService,
    streamChat: streamChatMock
  }
})

async function renderChatView() {
  const ChatViewComponent = (await import('./ChatView.svelte')).default
  return render(ChatViewComponent)
}

describe('ChatView streaming stability', () => {
  beforeEach(() => {
    streamChatMock.mockReset()
  })

  it('keeps the assistant message DOM node stable when the stream completes', async () => {
    let completeStream: () => void = () => {
      throw new Error('Expected stream completion callback to be set')
    }

    streamChatMock.mockImplementation(async (_sessionId, _message, onChunk, options) => {
      options?.onStatus?.({ message: 'Searching', details: 'Loading sources' })

      await Promise.resolve()
      onChunk('Hello')

      await Promise.resolve()
      options?.onCitations?.([{ url: 'https://example.com', title: 'Example' }])

      return new Promise<void>((resolve) => {
        completeStream = resolve
      })
    })

    const { getByLabelText, getByRole, container, findByText } = await renderChatView()

    const inputElement = getByLabelText('Message input') as HTMLTextAreaElement
    await fireEvent.input(inputElement, { target: { value: 'Hi' } })

    const sendButton = getByRole('button', { name: 'Send message' })
    await fireEvent.click(sendButton)

    const assistantTextElement = await findByText('Hello')
    await tick()

    const assistantMessageElement = assistantTextElement.closest('.message.assistant')
    expect(assistantMessageElement).not.toBeNull()

    expect(container.querySelector('.message.assistant .cursor.visible')).not.toBeNull()

    completeStream()
    await tick()

    const assistantTextElementAfter = await findByText('Hello')
    const assistantMessageElementAfter = assistantTextElementAfter.closest('.message.assistant')

    expect(assistantMessageElementAfter).toBe(assistantMessageElement)
    expect(container.querySelector('.message.assistant .cursor.visible')).toBeNull()
  })
})
