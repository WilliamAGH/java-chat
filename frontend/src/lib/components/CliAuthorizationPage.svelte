<script lang="ts">
  import {
    clerkAuthentication,
    createCliApiKey,
    openSignIn,
  } from '../composables/clerkAuthentication.svelte'
  import { CliAuthorizationQuerySchema } from '../validation/schemas'
  import { validateWithSchema } from '../validation/validate'

  const authorizationQueryValidation = validateWithSchema(
    CliAuthorizationQuerySchema,
    Object.fromEntries(new URLSearchParams(globalThis.location.search)),
    globalThis.location.href,
  )

  let authorizationFailure = $state<string | null>(null)
  let authorizationPending = $state(false)

  async function authorizeCli(): Promise<void> {
    if (!authorizationQueryValidation.success || authorizationPending) {
      return
    }
    authorizationPending = true
    authorizationFailure = null
    try {
      const apiKeySecret = await createCliApiKey(authorizationQueryValidation.validated.label)
      const callbackUrl = new URL(
        `http://127.0.0.1:${authorizationQueryValidation.validated.port}/callback`,
      )
      callbackUrl.hash = new URLSearchParams({
        state: authorizationQueryValidation.validated.state,
        key: apiKeySecret,
      }).toString()
      globalThis.location.assign(callbackUrl)
    } catch (authorizationError) {
      authorizationPending = false
      authorizationFailure =
        authorizationError instanceof Error
          ? authorizationError.message
          : 'JavaChat CLI authorization failed.'
    }
  }
</script>

<svelte:head>
  <meta name="robots" content="noindex,nofollow" />
</svelte:head>

<main class="authorization-page">
  <section class="authorization-card" aria-labelledby="cli-authorization-heading">
    <div class="authorization-kicker">Command line</div>
    <h1 id="cli-authorization-heading">Authorize JavaChat CLI</h1>

    {#if !authorizationQueryValidation.success}
      <p role="alert">
        This authorization request is invalid. Return to your terminal and run
        <code>javachat login</code> again.
      </p>
    {:else if clerkAuthentication.phase === 'disabled' || clerkAuthentication.phase === 'failed'}
      <p role="alert">
        Account security is unavailable in this build or browser. Return to your terminal and
        target a Clerk-enabled JavaChat deployment.
      </p>
    {:else if clerkAuthentication.phase === 'loading'}
      <p>Loading account security…</p>
    {:else if !clerkAuthentication.signedInUser}
      <p>Sign in to choose the account that will own this API key.</p>
      <button type="button" onclick={openSignIn}>Sign in</button>
    {:else}
      <p>
        Approve <strong>{authorizationQueryValidation.validated.label}</strong> to ask JavaChat
        from this terminal. The new key can be revoked from your Clerk account.
      </p>
      <button type="button" onclick={authorizeCli} disabled={authorizationPending}>
        {authorizationPending ? 'Authorizing…' : 'Authorize terminal'}
      </button>
    {/if}

    {#if authorizationFailure}
      <p role="alert">{authorizationFailure}</p>
    {/if}
  </section>
</main>

<style>
  .authorization-page {
    flex: 1;
    display: grid;
    place-items: center;
    padding: var(--space-6);
    background: var(--color-bg-primary);
  }

  .authorization-card {
    width: min(100%, 34rem);
    padding: var(--space-8);
    border: 1px solid var(--color-border-default);
    border-radius: var(--radius-lg);
    background: var(--color-bg-secondary);
    box-shadow: var(--shadow-lg);
  }

  .authorization-kicker {
    margin-bottom: var(--space-2);
    color: var(--color-accent);
    font-size: var(--text-sm);
    font-weight: 700;
    letter-spacing: 0.08em;
    text-transform: uppercase;
  }

  h1 {
    margin: 0 0 var(--space-4);
  }

  p {
    color: var(--color-text-secondary);
    line-height: 1.6;
  }

  button {
    margin-top: var(--space-4);
    padding: var(--space-3) var(--space-5);
    border: 0;
    border-radius: var(--radius-md);
    background: var(--color-accent);
    color: var(--color-accent-foreground);
    font: inherit;
    font-weight: 700;
    cursor: pointer;
  }

  button:disabled {
    cursor: wait;
    opacity: 0.7;
  }
</style>
