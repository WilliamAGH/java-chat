import { describe, it, expect } from 'vitest'
import { render } from '@testing-library/svelte'
import MessageBubble from './MessageBubble.svelte'

describe('MessageBubble', () => {
  it('does not render copy action for user messages', () => {
    const { container } = render(MessageBubble, {
      props: {
        message: { messageId: 'msg-test-user', role: 'user', content: 'Hello', timestamp: 1 },
        index: 0
      }
    })

    expect(container.querySelector('.bubble-actions')).toBeNull()
  })

  it('renders copy action for assistant messages', () => {
    const { container, getByRole } = render(MessageBubble, {
      props: {
        message: { messageId: 'msg-test-assistant', role: 'assistant', content: 'Hello', timestamp: 1 },
        index: 0
      }
    })

    expect(container.querySelector('.bubble-actions')).not.toBeNull()
    expect(getByRole('button', { name: /copy message/i, hidden: true })).toBeInTheDocument()
  })
})
