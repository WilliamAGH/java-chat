<script lang="ts">
  import { toasts, dismissToast } from '../stores/toastStore'

  const severityLabel: Record<string, string> = {
    error: 'Error',
    info: 'Notice'
  }
</script>

<div class="toast-container" aria-live="polite" aria-label="Notifications">
  {#each $toasts as notice (notice.id)}
    <div class="toast toast--{notice.severity}" role="status">
      <div class="toast__content">
        <p class="toast__message">
          {notice.message}
          {#if notice.action}
            <span class="toast__divider"> — </span>
            <a class="toast__action" href={notice.action.href}>{notice.action.label}</a>
          {/if}
        </p>
        {#if notice.detail}
          <p class="toast__detail">{notice.detail}</p>
        {/if}
      </div>
      <button
        class="toast__dismiss"
        type="button"
        aria-label={`Dismiss ${severityLabel[notice.severity] ?? 'notice'}`}
        on:click={() => dismissToast(notice.id)}
      >
        ✕
      </button>
    </div>
  {/each}
</div>

<style>
  .toast-container {
    position: fixed;
    right: var(--space-6);
    bottom: var(--space-6);
    display: flex;
    flex-direction: column;
    gap: var(--space-3);
    z-index: 40;
    pointer-events: none;
  }

  .toast {
    pointer-events: auto;
    display: flex;
    align-items: flex-start;
    justify-content: space-between;
    gap: var(--space-3);
    min-width: min(320px, 90vw);
    max-width: 420px;
    padding: var(--space-3) var(--space-4);
    border-radius: 16px;
    background: var(--color-bg-secondary);
    border: 1px solid var(--color-border-default);
    box-shadow: 0 12px 32px rgba(0, 0, 0, 0.35);
    color: var(--color-text-primary);
  }

  .toast--error {
    border-left: 4px solid var(--color-error);
  }

  .toast--info {
    border-left: 4px solid var(--color-info);
  }

  .toast__content {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }

  .toast__message {
    margin: 0;
    font-size: 0.95rem;
    font-weight: 600;
  }

  .toast__divider {
    color: var(--color-text-tertiary);
  }

  .toast__action {
    color: var(--color-accent);
    font-weight: 600;
    text-decoration: underline;
  }

  .toast__action:hover {
    color: var(--color-accent-hover);
  }

  .toast__detail {
    margin: 0;
    color: var(--color-text-secondary);
    font-size: 0.85rem;
  }

  .toast__dismiss {
    border: none;
    background: transparent;
    color: var(--color-text-tertiary);
    font-size: 0.95rem;
    cursor: pointer;
    padding: 0;
    line-height: 1;
  }

  .toast__dismiss:hover {
    color: var(--color-text-primary);
  }

  @media (max-width: 640px) {
    .toast-container {
      left: var(--space-4);
      right: var(--space-4);
      bottom: var(--space-4);
    }

    .toast {
      min-width: auto;
      width: 100%;
    }
  }
</style>
