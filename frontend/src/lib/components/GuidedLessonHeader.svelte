<script lang="ts">
  const ALL_LESSONS_LABEL = "All Lessons";

  let {
    lessonTitle,
    onReturnToLessons,
  }: {
    lessonTitle: string;
    onReturnToLessons: () => void;
  } = $props();
</script>

<div class="lesson-header">
  <div class="lesson-header-inner">
    <button
      type="button"
      class="back-button"
      aria-label={ALL_LESSONS_LABEL}
      onclick={onReturnToLessons}
    >
      <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
        <path
          fill-rule="evenodd"
          d="M17 10a.75.75 0 0 1-.75.75H5.612l4.158 3.96a.75.75 0 1 1-1.04 1.08l-5.5-5.25a.75.75 0 0 1 0-1.08l5.5-5.25a.75.75 0 1 1 1.04 1.08L5.612 9.25H16.25A.75.75 0 0 1 17 10Z"
          clip-rule="evenodd"
        />
      </svg>
      <span>{ALL_LESSONS_LABEL}</span>
    </button>
    <h1 class="lesson-title">{lessonTitle}</h1>
  </div>
</div>

<style>
  .lesson-header {
    flex-shrink: 0;
    padding: var(--space-4) var(--space-6);
    border-bottom: 1px solid var(--color-border-subtle);
    background: var(--color-bg-secondary);
  }

  .lesson-header-inner {
    display: flex;
    align-items: center;
    gap: var(--space-4);
    max-width: 1400px;
    margin: 0 auto;
    width: 100%;
  }

  .back-button {
    flex-shrink: 0;
    display: flex;
    align-items: center;
    gap: var(--space-2);
    padding: var(--space-2) var(--space-3);
    background: transparent;
    border: 1px solid var(--color-border-default);
    border-radius: var(--radius-md);
    font-size: var(--text-sm);
    line-height: 1;
    color: var(--color-text-secondary);
    cursor: pointer;
    transition: all var(--duration-fast) var(--ease-out);
  }

  .back-button:hover {
    background: var(--color-bg-tertiary);
    color: var(--color-text-primary);
  }

  .back-button svg {
    width: 16px;
    height: 16px;
  }

  .lesson-title {
    font-family: var(--font-serif);
    font-size: var(--text-xl);
    font-weight: 500;
    color: var(--color-text-primary);
    letter-spacing: var(--tracking-tight);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
    line-height: 1;
    margin: 0;
    padding-bottom: 2px;
  }

  @media (max-width: 768px) {
    .lesson-header {
      padding: var(--space-3) var(--space-4);
    }

    .lesson-title {
      font-size: var(--text-lg);
    }
  }

  @media (max-width: 640px) {
    .back-button span {
      display: none;
    }
  }
</style>
