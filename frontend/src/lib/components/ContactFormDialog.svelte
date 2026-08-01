<script lang="ts">
  import {
    CONTACT_MESSAGE_MAX_LENGTH,
    CONTACT_NAME_MAX_LENGTH,
    ContactSubmissionSchema,
  } from '../validation/schemas'
  import { submitContactMessage, type ContactSubmitFailure } from '../services/contact'

  /**
   * Contact/support form dialog.
   *
   * Mirrors the MobileChatDrawer modal pattern: native <dialog> driven with
   * showModal()/close(), backdrop click and Escape dismissal, and focus
   * returned to the invoking control on close. The honeypot `website` field
   * and the `renderedAt` timestamp implement the spam-guard contract for
   * POST /api/contact; `renderedAt` is captured when the dialog opens so the
   * backend can reject submissions faster than a human can type.
   */
  interface Props {
    isOpen: boolean
    onClose: () => void
  }

  let { isOpen, onClose }: Props = $props()

  type ContactSubmitPhase = 'editing' | 'submitting' | 'succeeded'

  interface ContactFieldErrors {
    name?: string
    email?: string
    message?: string
  }

  let contactDialog: HTMLDialogElement | null = $state(null)
  let previouslyFocusedElement: HTMLElement | null = $state(null)

  let contactName = $state('')
  let contactEmail = $state('')
  let contactMessage = $state('')
  let honeypotWebsite = $state('')
  let renderedAtEpochMs = $state(0)

  let submitPhase = $state<ContactSubmitPhase>('editing')
  let fieldErrors = $state<ContactFieldErrors>({})
  let submissionFailure = $state<ContactSubmitFailure | null>(null)

  $effect(() => {
    if (!contactDialog) {
      return
    }

    if (!isOpen) {
      if (contactDialog.open) {
        contactDialog.close()
      }
      return
    }

    if (contactDialog.open) {
      return
    }

    previouslyFocusedElement =
      document.activeElement instanceof HTMLElement ? document.activeElement : null
    resetContactForm()
    contactDialog.showModal()
  })

  function resetContactForm(): void {
    contactName = ''
    contactEmail = ''
    contactMessage = ''
    honeypotWebsite = ''
    renderedAtEpochMs = Date.now()
    submitPhase = 'editing'
    fieldErrors = {}
    submissionFailure = null
  }

  function fieldErrorsFromIssues(issues: { path: PropertyKey[]; message: string }[]): ContactFieldErrors {
    const nextFieldErrors: ContactFieldErrors = {}
    for (const issue of issues) {
      const issueField = issue.path[0]
      if (issueField === 'name' || issueField === 'email' || issueField === 'message') {
        nextFieldErrors[issueField] ??= issue.message
      } else {
        console.error('[ContactFormDialog] Submission blocked on a hidden field:', issue)
      }
    }
    return nextFieldErrors
  }

  async function handleContactSubmit(submitEvent: SubmitEvent): Promise<void> {
    submitEvent.preventDefault()
    if (submitPhase === 'submitting') {
      return
    }

    const submissionValidation = ContactSubmissionSchema.safeParse({
      name: contactName,
      email: contactEmail,
      message: contactMessage,
      website: honeypotWebsite,
      renderedAt: renderedAtEpochMs,
    })

    if (!submissionValidation.success) {
      fieldErrors = fieldErrorsFromIssues(submissionValidation.error.issues)
      return
    }

    fieldErrors = {}
    submissionFailure = null
    submitPhase = 'submitting'

    const submitOutcome = await submitContactMessage(submissionValidation.data)

    if (submitOutcome.success) {
      submitPhase = 'succeeded'
      return
    }

    submitPhase = 'editing'
    submissionFailure = submitOutcome.error
  }

  function clearFieldError(fieldName: keyof ContactFieldErrors): void {
    if (fieldErrors[fieldName]) {
      fieldErrors = { ...fieldErrors, [fieldName]: undefined }
    }
  }

  function closeDialog(): void {
    contactDialog?.close()
  }

  function handleDialogCancel(cancelEvent: Event): void {
    cancelEvent.preventDefault()
    closeDialog()
  }

  function handleDialogClose(): void {
    onClose()
    previouslyFocusedElement?.focus()
  }

  function handleDialogBackdropClick(mouseEvent: MouseEvent): void {
    if (mouseEvent.target === contactDialog) {
      closeDialog()
    }
  }
</script>

<dialog
  bind:this={contactDialog}
  class="contact-dialog"
  aria-label="Contact support"
  aria-modal="true"
  oncancel={handleDialogCancel}
  onclose={handleDialogClose}
  onclick={handleDialogBackdropClick}
>
  {#if isOpen}
    <div class="contact-dialog-header">
      <div class="contact-dialog-title">
        <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
          <path d="M3 4a2 2 0 0 0-2 2v1.161l8.441 4.221a1.25 1.25 0 0 0 1.118 0L19 7.162V6a2 2 0 0 0-2-2H3Z"/>
          <path d="M19 8.839l-7.77 3.885a2.75 2.75 0 0 1-2.46 0L1 8.839V14a2 2 0 0 0 2 2h14a2 2 0 0 0 2-2V8.839Z"/>
        </svg>
        <span>Contact support</span>
      </div>
      <button
        type="button"
        class="dialog-close-btn"
        onclick={closeDialog}
        aria-label="Close contact form"
      >
        <svg viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
          <path d="M6.28 5.22a.75.75 0 0 0-1.06 1.06L8.94 10l-3.72 3.72a.75.75 0 1 0 1.06 1.06L10 11.06l3.72 3.72a.75.75 0 1 0 1.06-1.06L11.06 10l3.72-3.72a.75.75 0 0 0-1.06-1.06L10 8.94 6.28 5.22Z"/>
        </svg>
      </button>
    </div>

    {#if submitPhase === 'succeeded'}
      <div class="contact-success" role="status">
        <svg class="contact-success-icon" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
          <path fill-rule="evenodd" d="M10 18a8 8 0 1 0 0-16 8 8 0 0 0 0 16Zm3.857-9.809a.75.75 0 0 0-1.214-.882l-3.483 4.79-1.88-1.88a.75.75 0 1 0-1.06 1.061l2.5 2.5a.75.75 0 0 0 1.137-.089l4-5.5Z" clip-rule="evenodd"/>
        </svg>
        <h3 class="contact-success-heading">Message sent</h3>
        <p class="contact-success-text">
          Thanks for reaching out — we'll get back to you at {contactEmail}.
        </p>
        <button type="button" class="submit-btn" onclick={closeDialog}>Done</button>
      </div>
    {:else}
      <form class="contact-form" onsubmit={handleContactSubmit} novalidate>
        <div class="honeypot-field" aria-hidden="true">
          <label for="contact-website">Website</label>
          <input
            id="contact-website"
            type="text"
            name="website"
            bind:value={honeypotWebsite}
            tabindex="-1"
            autocomplete="off"
          />
        </div>

        <div class="form-field">
          <label for="contact-name">Name</label>
          <input
            id="contact-name"
            type="text"
            name="name"
            bind:value={contactName}
            oninput={() => clearFieldError('name')}
            autocomplete="name"
            maxlength={CONTACT_NAME_MAX_LENGTH}
            disabled={submitPhase === 'submitting'}
            aria-invalid={fieldErrors.name ? 'true' : undefined}
            aria-describedby={fieldErrors.name ? 'contact-name-error' : undefined}
          />
          {#if fieldErrors.name}
            <p class="field-error" id="contact-name-error">{fieldErrors.name}</p>
          {/if}
        </div>

        <div class="form-field">
          <label for="contact-email">Email</label>
          <input
            id="contact-email"
            type="email"
            name="email"
            bind:value={contactEmail}
            oninput={() => clearFieldError('email')}
            autocomplete="email"
            disabled={submitPhase === 'submitting'}
            aria-invalid={fieldErrors.email ? 'true' : undefined}
            aria-describedby={fieldErrors.email ? 'contact-email-error' : undefined}
          />
          {#if fieldErrors.email}
            <p class="field-error" id="contact-email-error">{fieldErrors.email}</p>
          {/if}
        </div>

        <div class="form-field">
          <label for="contact-message">Message</label>
          <textarea
            id="contact-message"
            name="message"
            bind:value={contactMessage}
            oninput={() => clearFieldError('message')}
            rows="5"
            maxlength={CONTACT_MESSAGE_MAX_LENGTH}
            disabled={submitPhase === 'submitting'}
            aria-invalid={fieldErrors.message ? 'true' : undefined}
            aria-describedby={fieldErrors.message ? 'contact-message-error' : undefined}
          ></textarea>
          {#if fieldErrors.message}
            <p class="field-error" id="contact-message-error">{fieldErrors.message}</p>
          {/if}
        </div>

        {#if submissionFailure?.kind === 'rate-limited'}
          <p class="form-notice" role="status">
            Too many messages — please try again later.
          </p>
        {:else if submissionFailure?.kind === 'rejected'}
          <p class="form-notice form-notice-error" role="alert">{submissionFailure.message}</p>
        {:else if submissionFailure}
          <p class="form-notice form-notice-error" role="alert">
            We couldn't send your message. Please try again.
          </p>
        {/if}

        <button type="submit" class="submit-btn" disabled={submitPhase === 'submitting'}>
          {#if submitPhase === 'submitting'}
            <svg class="spinner" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
              <circle cx="12" cy="12" r="10" stroke-dasharray="60" stroke-dashoffset="20" />
            </svg>
            <span>Sending…</span>
          {:else}
            <span>Send message</span>
          {/if}
        </button>
      </form>
    {/if}
  {/if}
</dialog>

<style>
  .contact-dialog::backdrop {
    background: rgba(0, 0, 0, 0.5);
    animation: fade-in var(--duration-fast) var(--ease-out);
  }

  .contact-dialog {
    width: min(480px, calc(100vw - 2 * var(--space-4)));
    max-height: 85vh;
    max-height: 85dvh;
    margin: auto;
    padding: 0;
    background: var(--color-bg-primary);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-xl);
    box-shadow: var(--shadow-xl);
    animation: fade-in-up var(--duration-normal) var(--ease-out);
  }

  .contact-dialog-header {
    display: flex;
    align-items: center;
    justify-content: space-between;
    padding: var(--space-4);
    border-bottom: 1px solid var(--color-border-subtle);
  }

  .contact-dialog-title {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    font-size: var(--text-base);
    font-weight: 500;
    color: var(--color-text-primary);
  }

  .contact-dialog-title svg {
    width: 20px;
    height: 20px;
    color: var(--color-accent);
  }

  .dialog-close-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    width: 44px;
    height: 44px;
    padding: 0;
    background: transparent;
    border: none;
    border-radius: var(--radius-md);
    color: var(--color-text-secondary);
    cursor: pointer;
    transition: all var(--duration-fast) var(--ease-out);
  }

  .dialog-close-btn:hover {
    background: var(--color-bg-tertiary);
    color: var(--color-text-primary);
  }

  .dialog-close-btn svg {
    width: 20px;
    height: 20px;
  }

  .contact-form {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
    padding: var(--space-4);
    overflow-y: auto;
  }

  /* Off-screen rather than display:none: bots detect and skip hidden fields. */
  .honeypot-field {
    position: absolute;
    left: -100vw;
    top: 0;
    width: 1px;
    height: 1px;
    overflow: hidden;
  }

  .form-field {
    display: flex;
    flex-direction: column;
    gap: var(--space-1);
  }

  .form-field label {
    font-size: var(--text-sm);
    font-weight: 500;
    color: var(--color-text-secondary);
  }

  .form-field input,
  .form-field textarea {
    padding: var(--space-2) var(--space-3);
    font-family: var(--font-sans);
    font-size: var(--text-base);
    line-height: var(--leading-normal);
    color: var(--color-text-primary);
    background: var(--color-bg-secondary);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-md);
    outline: none;
    transition: all var(--duration-fast) var(--ease-out);
  }

  .form-field textarea {
    resize: vertical;
    min-height: 120px;
  }

  .form-field input:focus,
  .form-field textarea:focus {
    border-color: var(--color-accent);
    box-shadow: 0 0 0 3px var(--color-accent-subtle);
  }

  .form-field input[aria-invalid='true'],
  .form-field textarea[aria-invalid='true'] {
    border-color: var(--color-error);
  }

  .form-field input:disabled,
  .form-field textarea:disabled {
    opacity: 0.6;
    cursor: not-allowed;
  }

  .field-error {
    font-size: var(--text-xs);
    color: var(--color-error);
  }

  .form-notice {
    padding: var(--space-3);
    font-size: var(--text-sm);
    color: var(--color-text-secondary);
    background: var(--color-surface-muted);
    border: 1px solid var(--color-border-subtle);
    border-radius: var(--radius-md);
  }

  .form-notice-error {
    color: var(--color-error);
    background: var(--color-accent-subtle);
    border-color: var(--color-error);
  }

  .submit-btn {
    display: flex;
    align-items: center;
    justify-content: center;
    gap: var(--space-2);
    padding: var(--space-3) var(--space-4);
    font-size: var(--text-sm);
    font-weight: 500;
    color: white;
    background: var(--color-accent);
    border: none;
    border-radius: var(--radius-md);
    cursor: pointer;
    transition: all var(--duration-fast) var(--ease-out);
  }

  .submit-btn:hover:not(:disabled) {
    background: var(--color-accent-hover);
  }

  .submit-btn:disabled {
    opacity: 0.7;
    cursor: not-allowed;
  }

  .spinner {
    width: 16px;
    height: 16px;
    animation: spin 1s linear infinite;
  }

  @keyframes spin {
    from { transform: rotate(0deg); }
    to { transform: rotate(360deg); }
  }

  .contact-success {
    display: flex;
    flex-direction: column;
    align-items: center;
    gap: var(--space-3);
    padding: var(--space-8) var(--space-4);
    text-align: center;
  }

  .contact-success-icon {
    width: 40px;
    height: 40px;
    color: var(--color-success);
  }

  .contact-success-heading {
    font-size: var(--text-lg);
    font-weight: 500;
    color: var(--color-text-primary);
  }

  .contact-success-text {
    font-size: var(--text-sm);
    color: var(--color-text-secondary);
  }

  /* Mobile */
  @media (max-width: 640px) {
    .contact-dialog {
      max-height: 90vh;
      max-height: 90dvh;
    }

    .form-field input,
    .form-field textarea {
      font-size: 16px; /* Prevents iOS zoom on focus */
    }
  }
</style>
