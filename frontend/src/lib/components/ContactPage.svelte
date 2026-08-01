<script lang="ts">
  import {
    CONTACT_MESSAGE_MAX_LENGTH,
    CONTACT_NAME_MAX_LENGTH,
    ContactSubmissionSchema,
  } from '../validation/schemas'
  import { submitContactMessage, type ContactSubmitFailure } from '../services/contact'

  /**
   * Contact page at the /contact route.
   *
   * Carries the same spam-guard contract as the retired dialog: the honeypot
   * `website` field and the `renderedAt` timestamp are captured when the page
   * mounts so the backend can reject submissions faster than a human can type.
   */
  interface Props {
    /** SPA navigation for internal links; falls back to a full page load. */
    onInternalNavigate?: (path: string) => void
  }

  let { onInternalNavigate }: Props = $props()

  type ContactSubmitPhase = 'editing' | 'submitting' | 'succeeded'

  interface ContactFieldErrors {
    name?: string
    email?: string
    message?: string
  }

  let contactName = $state('')
  let contactEmail = $state('')
  let contactMessage = $state('')
  let honeypotWebsite = $state('')
  const renderedAtEpochMs = Date.now()

  let submitPhase = $state<ContactSubmitPhase>('editing')
  let fieldErrors = $state<ContactFieldErrors>({})
  let submissionFailure = $state<ContactSubmitFailure | null>(null)

  function fieldErrorsFromIssues(issues: { path: PropertyKey[]; message: string }[]): ContactFieldErrors {
    const nextFieldErrors: ContactFieldErrors = {}
    for (const issue of issues) {
      const issueField = issue.path[0]
      if (issueField === 'name' || issueField === 'email' || issueField === 'message') {
        nextFieldErrors[issueField] ??= issue.message
      } else {
        console.error('[ContactPage] Submission blocked on a hidden field:', issue)
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

  function composeAnotherMessage(): void {
    contactName = ''
    contactEmail = ''
    contactMessage = ''
    honeypotWebsite = ''
    submitPhase = 'editing'
    fieldErrors = {}
    submissionFailure = null
  }

  function navigateToPrivacy(clickEvent: MouseEvent): void {
    if (!onInternalNavigate) {
      return
    }
    clickEvent.preventDefault()
    onInternalNavigate('/privacy')
  }
</script>

<svelte:head>
  <meta name="robots" content="index,follow" />
</svelte:head>

<article class="contact-page">
  <div class="contact-shell">
    <nav class="breadcrumb" aria-label="Breadcrumb">
      <a href="/">Home</a>
      <span aria-hidden="true">/</span>
      <span>Contact</span>
    </nav>

    <header class="contact-heading">
      <div class="kicker">Support</div>
      <h1>Contact</h1>
      <p class="deck">
        Questions, feedback, or privacy requests — send a message and it lands
        directly with the team.
      </p>
    </header>

    <div class="contact-layout">
      <div class="contact-body">
        {#if submitPhase === 'succeeded'}
          <div class="contact-success" role="status">
            <svg class="contact-success-icon" viewBox="0 0 20 20" fill="currentColor" aria-hidden="true">
              <path fill-rule="evenodd" d="M10 18a8 8 0 1 0 0-16 8 8 0 0 0 0 16Zm3.857-9.809a.75.75 0 0 0-1.214-.882l-3.483 4.79-1.88-1.88a.75.75 0 1 0-1.06 1.061l2.5 2.5a.75.75 0 0 0 1.137-.089l4-5.5Z" clip-rule="evenodd"/>
            </svg>
            <h2 class="contact-success-heading">Message sent</h2>
            <p class="contact-success-text">
              Thanks for reaching out — we'll get back to you at {contactEmail}.
            </p>
            <button type="button" class="submit-btn" onclick={composeAnotherMessage}>
              Send another message
            </button>
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
                rows="7"
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
      </div>

      <aside class="contact-aside" aria-label="Other ways to reach us">
        <h2>Privacy requests</h2>
        <p>
          Access, correction, or deletion of your personal information is covered
          by the <a href="/privacy" onclick={navigateToPrivacy}>Privacy Policy</a>,
          or email
          <a href="mailto:privacy@javachat.ai">privacy@javachat.ai</a> from the
          address associated with your account.
        </p>
        <h2>What to include</h2>
        <p>
          A short description of what you were doing and what went wrong is
          enough — no logs needed. We reply to the address you provide.
        </p>
      </aside>
    </div>
  </div>
</article>

<style>
  .contact-page {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    background:
      radial-gradient(circle at 82% 8%, var(--color-accent-subtle), transparent 28rem),
      var(--color-bg-primary);
  }

  .contact-shell {
    width: min(1120px, calc(100% - 3rem));
    margin: 0 auto;
    padding: var(--space-8) 0 var(--space-20);
  }

  .breadcrumb {
    display: flex;
    align-items: center;
    gap: var(--space-2);
    color: var(--color-text-tertiary);
    font-size: var(--text-sm);
  }

  .breadcrumb a,
  .contact-aside a {
    color: var(--color-accent-hover);
    text-decoration-thickness: 1px;
    text-underline-offset: 0.2em;
  }

  .contact-heading {
    max-width: 760px;
    padding: var(--space-16) 0 var(--space-12);
  }

  .kicker {
    margin-bottom: var(--space-4);
    color: var(--color-accent-hover);
    font-size: var(--text-xs);
    font-weight: 600;
    letter-spacing: var(--tracking-wider);
    text-transform: uppercase;
  }

  h1 {
    font-family: var(--font-serif);
    font-size: clamp(var(--text-3xl), 7vw, 4.5rem);
    font-weight: 500;
    letter-spacing: var(--tracking-tight);
    line-height: 1.05;
  }

  .deck {
    max-width: 680px;
    margin-top: var(--space-5);
    color: var(--color-text-secondary);
    font-family: var(--font-serif);
    font-size: var(--text-xl);
    line-height: var(--leading-snug);
  }

  .contact-layout {
    display: grid;
    grid-template-columns: minmax(0, 720px) minmax(220px, 1fr);
    gap: var(--space-16);
    align-items: start;
  }

  .contact-form {
    display: flex;
    flex-direction: column;
    gap: var(--space-4);
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
    transition:
      border-color var(--duration-fast) var(--ease-out),
      box-shadow var(--duration-fast) var(--ease-out);
  }

  .form-field textarea {
    resize: vertical;
    min-height: 140px;
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
    align-self: flex-start;
    padding: var(--space-3) var(--space-5);
    font-size: var(--text-sm);
    font-weight: 500;
    color: white;
    background: var(--color-accent);
    border: none;
    border-radius: var(--radius-md);
    cursor: pointer;
    transition:
      background-color var(--duration-fast) var(--ease-out),
      transform var(--duration-fast) var(--ease-out);
  }

  .submit-btn:hover:not(:disabled) {
    background: var(--color-accent-hover);
  }

  .submit-btn:active:not(:disabled) {
    transform: scale(0.96);
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

  @media (prefers-reduced-motion: reduce) {
    .spinner {
      animation: none;
    }
  }

  .contact-success {
    display: flex;
    flex-direction: column;
    align-items: flex-start;
    gap: var(--space-3);
  }

  .contact-success-icon {
    width: 40px;
    height: 40px;
    color: var(--color-success);
  }

  .contact-success-heading {
    font-family: var(--font-serif);
    font-size: var(--text-2xl);
    font-weight: 500;
    color: var(--color-text-primary);
  }

  .contact-success-text {
    margin-bottom: var(--space-2);
    font-size: var(--text-base);
    color: var(--color-text-secondary);
  }

  .contact-aside {
    position: sticky;
    top: var(--space-8);
    padding: var(--space-6);
    border: 1px solid var(--color-border-default);
    border-radius: var(--radius-xl);
    background: var(--color-surface-muted);
    box-shadow: var(--shadow-md);
  }

  .contact-aside h2 {
    margin-bottom: var(--space-3);
    font-family: var(--font-serif);
    font-size: var(--text-xl);
    font-weight: 500;
    color: var(--color-text-primary);
  }

  .contact-aside h2 + p {
    margin-bottom: var(--space-6);
  }

  .contact-aside h2:not(:first-child) {
    margin-top: var(--space-6);
  }

  .contact-aside p {
    color: var(--color-text-secondary);
    font-size: var(--text-sm);
    line-height: var(--leading-relaxed);
  }

  .contact-aside p:last-child {
    margin-bottom: 0;
  }

  .contact-aside a {
    overflow-wrap: anywhere;
  }

  @media (max-width: 820px) {
    .contact-layout {
      grid-template-columns: 1fr;
      gap: var(--space-10);
    }

    .contact-aside {
      position: static;
      grid-row: 1;
    }
  }

  @media (max-width: 640px) {
    .contact-shell {
      width: min(100% - 2rem, 720px);
      padding-top: var(--space-6);
    }

    .contact-heading {
      padding: var(--space-10) 0 var(--space-8);
    }

    .deck {
      font-size: var(--text-lg);
    }

    .form-field input,
    .form-field textarea {
      font-size: 16px; /* Prevents iOS zoom on focus */
    }
  }
</style>
