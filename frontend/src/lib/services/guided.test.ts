import { beforeEach, describe, expect, it, vi } from 'vitest'

const { streamSseMock } = vi.hoisted(() => {
  return { streamSseMock: vi.fn() }
})

vi.mock('./sse', () => {
  return { streamSse: streamSseMock }
})

import { streamGuidedChat } from './guided'

describe('streamGuidedChat recovery', () => {
  beforeEach(() => {
    streamSseMock.mockReset()
  })

  it('retries once for recoverable invalid stream errors before any chunk', async () => {
    streamSseMock.mockRejectedValueOnce(new Error('OverflowException: malformed response frame'))
    streamSseMock.mockImplementationOnce(async (_url, _body, callbacks) => {
      callbacks.onText('Recovered guided response')
    })

    const onChunk = vi.fn()
    const onStatus = vi.fn()
    const onError = vi.fn()
    const onCitations = vi.fn()

    await expect(
      streamGuidedChat('guided-session-1', 'intro', 'Teach me streams', {
        onChunk,
        onStatus,
        onError,
        onCitations
      })
    ).resolves.toBeUndefined()

    expect(streamSseMock).toHaveBeenCalledTimes(2)
    expect(onStatus).toHaveBeenCalledWith(
      expect.objectContaining({
        message: 'Temporary stream issue detected'
      })
    )
    expect(onStatus).toHaveBeenCalledWith(
      expect.objectContaining({
        message: 'Streaming recovered'
      })
    )
    expect(onChunk).toHaveBeenCalledWith('Recovered guided response')
    expect(onError).not.toHaveBeenCalled()
  })

  it('does not retry for non-recoverable rate-limit errors', async () => {
    streamSseMock.mockRejectedValueOnce(new Error('429 rate limit exceeded'))

    const onChunk = vi.fn()
    const onStatus = vi.fn()
    const onError = vi.fn()
    const onCitations = vi.fn()

    await expect(
      streamGuidedChat('guided-session-2', 'intro', 'Teach me streams', {
        onChunk,
        onStatus,
        onError,
        onCitations
      })
    ).rejects.toThrow('429 rate limit exceeded')

    expect(streamSseMock).toHaveBeenCalledTimes(1)
    expect(onStatus).not.toHaveBeenCalledWith(
      expect.objectContaining({
        message: 'Temporary stream issue detected'
      })
    )
    const [firstOnErrorCall] = onError.mock.calls
    expect(firstOnErrorCall).toBeDefined()
    expect(firstOnErrorCall[0]).toEqual({ message: '429 rate limit exceeded' })
  })
})
