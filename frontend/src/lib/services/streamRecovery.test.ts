import { describe, expect, it } from 'vitest'
import {
  buildStreamRecoverySucceededStatus,
  buildStreamRetryStatus,
  resolveStreamRecoveryRetryCount,
  shouldRetryStreamRequest,
  toStreamError,
  toStreamFailureException
} from './streamRecovery'

describe('streamRecovery', () => {
  it('allows one retry for overflow failures before any chunk is rendered', () => {
    const retryDecision = shouldRetryStreamRequest(
      new Error('OverflowException while decoding response'),
      null,
      false,
      0,
      1
    )

    expect(retryDecision).toBe(true)
  })

  it('refuses retry after content has started streaming', () => {
    const retryDecision = shouldRetryStreamRequest(
      new Error('OverflowException while decoding response'),
      null,
      true,
      0,
      1
    )

    expect(retryDecision).toBe(false)
  })

  it('refuses retry for non-recoverable quota errors', () => {
    const retryDecision = shouldRetryStreamRequest(
      new Error('HTTP 429 rate limit exceeded'),
      null,
      false,
      0,
      1
    )

    expect(retryDecision).toBe(false)
  })

  it('builds a user-visible retry status message', () => {
    const retryStatus = buildStreamRetryStatus(1, 1)

    expect(retryStatus.message).toBe('Temporary stream issue detected')
    expect(retryStatus.details).toContain('Retrying your request (1/1)')
  })

  it('builds a user-visible recovery status message after retry succeeds', () => {
    const recoveryStatus = buildStreamRecoverySucceededStatus(1)

    expect(recoveryStatus.message).toBe('Streaming recovered')
    expect(recoveryStatus.details).toContain('Recovered after retry (1)')
  })

  it('maps thrown errors into StreamError shape', () => {
    const mappedStreamError = toStreamError(new Error('OverflowException'), null)

    expect(mappedStreamError).toEqual({ message: 'OverflowException' })
  })

  it('retries once for recoverable network failures before any chunk is rendered', () => {
    const retryDecision = shouldRetryStreamRequest(new Error('TypeError: Failed to fetch'), null, false, 0, 1)

    expect(retryDecision).toBe(true)
  })

  it('preserves stream error details in thrown stream failure exception', () => {
    const streamFailureException = toStreamFailureException(new Error('Transport failed'), {
      message: 'OverflowException',
      details: 'Malformed response frame at byte 512'
    })

    expect(streamFailureException.message).toBe('OverflowException')
    expect((streamFailureException as Error & { details?: string }).details).toBe(
      'Malformed response frame at byte 512'
    )
  })

  it('uses default retry count for missing config', () => {
    expect(resolveStreamRecoveryRetryCount(undefined)).toBe(1)
  })

  it('clamps configured retry count into safe range', () => {
    expect(resolveStreamRecoveryRetryCount(-1)).toBe(0)
    expect(resolveStreamRecoveryRetryCount(9)).toBe(3)
  })

  it('falls back to default retry count for non-numeric config', () => {
    expect(resolveStreamRecoveryRetryCount('abc')).toBe(1)
    expect(resolveStreamRecoveryRetryCount('')).toBe(1)
  })
})
