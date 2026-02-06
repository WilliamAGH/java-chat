import { beforeEach, describe, expect, it, vi } from 'vitest'

const { streamSseMock } = vi.hoisted(() => {
  return { streamSseMock: vi.fn() }
})

vi.mock('./sse', () => {
  return { streamSse: streamSseMock }
})

import { streamChat } from './chat'

describe('streamChat recovery', () => {
  beforeEach(() => {
    streamSseMock.mockReset()
  })

  it('retries once for recoverable overflow failure before any streamed chunk', async () => {
    streamSseMock.mockRejectedValueOnce(new Error('OverflowException: malformed response frame'))
    streamSseMock.mockImplementationOnce(async (_url, _body, callbacks) => {
      callbacks.onText('Recovered response')
    })

    const onChunk = vi.fn()
    const onStatus = vi.fn()
    const onError = vi.fn()

    await expect(
      streamChat('session-1', 'What is new in Java 25?', onChunk, { onStatus, onError })
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
    expect(onChunk).toHaveBeenCalledWith('Recovered response')
    expect(onError).not.toHaveBeenCalled()
  })

  it('does not retry when a chunk already streamed to the UI', async () => {
    streamSseMock.mockImplementationOnce(async (_url, _body, callbacks) => {
      callbacks.onText('Partial answer')
      throw new Error('OverflowException: malformed response frame')
    })

    const onChunk = vi.fn()
    const onStatus = vi.fn()
    const onError = vi.fn()

    await expect(
      streamChat('session-2', 'Explain records', onChunk, { onStatus, onError })
    ).rejects.toThrow('OverflowException: malformed response frame')

    expect(streamSseMock).toHaveBeenCalledTimes(1)
    expect(onChunk).toHaveBeenCalledWith('Partial answer')
    expect(onStatus).not.toHaveBeenCalledWith(
      expect.objectContaining({
        message: 'Temporary stream issue detected'
      })
    )
    expect(onError).toHaveBeenCalledWith({
      message: 'OverflowException: malformed response frame'
    })
  })
})
