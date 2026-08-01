<script lang="ts">
  /**
   * Privacy policy at the /privacy route.
   *
   * Every location that names the privacy mailbox also links the contact form
   * so readers can act without leaving the app.
   */
  interface Props {
    /** SPA navigation for internal links; falls back to a full page load. */
    onInternalNavigate?: (path: string) => void
  }

  let { onInternalNavigate }: Props = $props()

  function navigateToContact(clickEvent: MouseEvent): void {
    if (!onInternalNavigate) {
      return
    }
    clickEvent.preventDefault()
    onInternalNavigate('/contact')
  }
</script>

<svelte:head>
  <meta name="robots" content="index,follow" />
</svelte:head>

<article class="privacy-page">
  <div class="privacy-shell">
    <nav class="breadcrumb" aria-label="Breadcrumb">
      <a href="/">Home</a>
      <span aria-hidden="true">/</span>
      <span>Privacy Policy</span>
    </nav>

    <header class="privacy-heading">
      <div class="kicker">Legal</div>
      <h1>Privacy Policy</h1>
      <p class="deck">
        How Java Chat collects, uses, discloses, retains, and protects personal information.
      </p>
      <time datetime="2026-07-31">Effective July 31, 2026</time>
    </header>

    <div class="privacy-layout">
      <div class="policy">
        <p>
          Java Chat (“Java Chat,” “we,” “us,” or “our”) is operated by a California sole
          proprietorship. This Privacy Policy explains how we collect, use, disclose, retain, and
          protect information when you use Java Chat’s websites, mobile or desktop applications,
          and related services (the “Service”).
        </p>
        <p>
          If you have a privacy question or want to exercise a privacy right, contact us at
          <a href="mailto:privacy@javachat.ai">privacy@javachat.ai</a> or through the
          <a href="/contact" onclick={navigateToContact}>contact form</a>.
        </p>

        <h2>Information We Collect</h2>
        <p>We collect information needed to provide, secure, and improve the Service.</p>
        <ul>
          <li>
            <strong>Account information.</strong> If you choose to create an account, our
            authentication provider, Clerk, processes the account, contact, profile, session, and
            verification information you provide. Java Chat’s backend receives the verified Clerk
            user identifier needed to recognize an authenticated request.
          </li>
          <li>
            <strong>Chat and learning content.</strong> We collect the Java questions, guided-lesson
            messages, session identifiers, generated answers, citations, and feedback you provide
            while using the Service.
          </li>
          <li>
            <strong>Technical and usage information.</strong> We and our service providers may
            collect IP address, browser and device information, operating system, referring page,
            pages and features used, timestamps, online identifiers, security events, diagnostic
            details, AI trace information, and similar information used to operate, secure, observe,
            and measure the Service.
          </li>
          <li>
            <strong>Cookies and local application storage.</strong> The Service uses a security
            cookie for cross-site request forgery protection. Clerk may use cookies or browser
            storage to maintain authentication. The native applications use a web view and the
            system authentication session to access the same Service.
          </li>
        </ul>

        <h2>How We Collect Information</h2>
        <p>
          We collect information directly from you when you ask a question, use a guided lesson,
          create or use an account, or contact us. We collect technical information automatically
          when you use the Service. We may also receive authentication and session information from
          Clerk.
        </p>

        <h2>How We Use Information</h2>
        <p>We use information to:</p>
        <ul>
          <li>provide and secure chat, guided lessons, citations, and account features;</li>
          <li>maintain conversation continuity during an active server session;</li>
          <li>retrieve relevant Java documentation and generate AI-assisted answers;</li>
          <li>authenticate users and protect requests against abuse;</li>
          <li>measure usage, diagnose failures, observe AI performance, and improve reliability; and</li>
          <li>comply with law, enforce our agreements, and protect Java Chat and its users.</li>
        </ul>

        <h2>When We Disclose Information</h2>
        <p>
          We disclose information needed to operate the Service to the following categories of
          service providers:
        </p>
        <ul>
          <li>
            <strong>Authentication providers,</strong> including Clerk, for account and session
            management.
          </li>
          <li>
            <strong>AI providers and gateways.</strong> Questions, recent conversation context, and
            relevant Java-documentation excerpts are sent to the configured OpenAI-compatible
            gateway and model provider to generate answers. Search queries may also be processed by
            the configured embedding model.
          </li>
          <li>
            <strong>Search and application infrastructure.</strong> Java Chat uses Qdrant-backed
            vector search to retrieve relevant Java documentation and uses hosting and security
            infrastructure to deliver the Service.
          </li>
          <li>
            <strong>Analytics providers,</strong> including Simple Analytics, Clicky, and PostHog, to
            measure visits and use of the Service.
          </li>
          <li>
            <strong>Error-monitoring and AI-observability providers,</strong> including Sentry and
            Langfuse. They may receive technical, diagnostic, and AI trace information, including
            request or response content associated with an error or trace.
          </li>
          <li>
            <strong>Authorities and professional advisers</strong> when reasonably necessary to
            comply with law, respond to a lawful request, or protect rights and safety.
          </li>
        </ul>
        <p>
          The categories above describe the kinds of providers we use. Named providers are current
          examples, not a permanent or exhaustive list. We may add, replace, or discontinue
          providers as the Service evolves. If a provider change materially affects how we collect,
          use, or disclose personal information, we will update this Policy and provide any notice
          or choice required by applicable law.
        </p>
        <p>
          We disclose only the information reasonably needed for the provider’s function. Providers
          process information under their applicable terms, privacy practices, and agreements with
          us.
        </p>

        <h2>Advertising, Sale, and Sharing</h2>
        <p>
          Java Chat does not sell personal information for money and does not intentionally use
          personal information for cross-context behavioral advertising. We disclose information to
          the service providers described above so they can operate, secure, and measure the Service.
          If these practices change, we will update this Policy and provide any notice or choice
          required by applicable law.
        </p>

        <h2>Data Retention and Deletion</h2>
        <p>
          Java Chat keeps completed chat exchanges in server memory under a randomly generated
          session identifier so the conversation can continue. This chat history is not stored in a
          durable Java Chat database and is removed when you clear the chat, when the server process
          restarts, or when it is otherwise no longer needed for the active session. The browser’s
          visible transcript is held for the current page session and is removed when you clear it or
          reload the application.
        </p>
        <p>
          Clerk retains account and authentication information according to its settings and privacy
          practices. Analytics, AI-observability, diagnostic, security, and server records are
          retained according to the applicable provider settings and our operational, security, and
          legal needs. Backup or provider copies may persist temporarily through ordinary retention
          cycles.
        </p>
        <p>
          To request deletion of your Java Chat account or other personal information, email
          <a href="mailto:privacy@javachat.ai">privacy@javachat.ai</a> from the address associated
          with your account, or start the request through the
          <a href="/contact" onclick={navigateToContact}>contact form</a>. We may need to verify
          your identity and may retain information that we are legally permitted or required to
          retain.
        </p>

        <h2>Your Choices and Privacy Rights</h2>
        <p>
          You may clear an active chat through the Service and contact us to request access to,
          correction of, or deletion of personal information. You can also use browser settings to
          manage cookies and site storage, although blocking required security or authentication
          storage may prevent parts of the Service from working.
        </p>

        <h3>California Residents</h3>
        <p>
          The California Consumer Privacy Act (“CCPA”) applies only when its statutory requirements
          are met. If the CCPA applies to Java Chat and to your information, California residents may
          have rights to know or access, correct, delete, opt out of certain sale or sharing, limit
          certain uses of sensitive personal information, and not be discriminated against for
          exercising applicable rights.
        </p>
        <p>
          To submit a request, email
          <a href="mailto:privacy@javachat.ai">privacy@javachat.ai</a> with “Privacy Request” in the
          subject line, or send the same request through the
          <a href="/contact" onclick={navigateToContact}>contact form</a>. We may verify your
          request before responding. An authorized agent may submit a request where permitted by
          law.
        </p>

        <h2>Do Not Track and Opt-Out Preference Signals</h2>
        <p>
          Java Chat does not currently change its practices in response to a browser Do Not Track
          signal. Our analytics and authentication providers may respond to browser signals under
          their own privacy practices. Where applicable law requires Java Chat to honor a valid
          opt-out preference signal, such as Global Privacy Control, we will process that signal as
          required.
        </p>

        <h2>Children’s Privacy</h2>
        <p>
          Java Chat is not directed to children under 13, and we do not knowingly collect personal
          information from children under 13. If you believe a child has provided personal
          information, contact <a href="mailto:privacy@javachat.ai">privacy@javachat.ai</a> or use
          the <a href="/contact" onclick={navigateToContact}>contact form</a> so we can investigate
          and take appropriate action.
        </p>

        <h2>Security</h2>
        <p>
          We use reasonable administrative, technical, and organizational measures designed to
          protect information. No system is completely secure, and we cannot guarantee absolute
          security.
        </p>

        <h2>Changes to This Policy</h2>
        <p>
          We may update this Policy when our practices or legal obligations change. We will post the
          updated Policy with a new effective date. If a change is material, we will provide
          additional notice through the Service, by email, or through another appropriate method
          when required by law.
        </p>

        <h2>Contact</h2>
        <p>
          Java Chat<br />
          California sole proprietorship<br />
          <a href="mailto:privacy@javachat.ai">privacy@javachat.ai</a><br />
          <a href="/contact" onclick={navigateToContact}>Contact form</a>
        </p>
      </div>

      <aside class="privacy-contact" aria-label="Privacy contact">
        <h2>Privacy requests</h2>
        <p>Access, correction, deletion, and other privacy questions can be sent to:</p>
        <a href="mailto:privacy@javachat.ai">privacy@javachat.ai</a>
        <p>
          No email client handy? Use the
          <a href="/contact" onclick={navigateToContact}>contact form</a> instead.
        </p>
      </aside>
    </div>
  </div>
</article>

<style>
  .privacy-page {
    flex: 1;
    min-height: 0;
    overflow-y: auto;
    background:
      radial-gradient(circle at 82% 8%, var(--color-accent-subtle), transparent 28rem),
      var(--color-bg-primary);
  }

  .privacy-shell {
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
  .policy a,
  .privacy-contact a {
    color: var(--color-accent-hover);
    text-decoration-thickness: 1px;
    text-underline-offset: 0.2em;
  }

  .privacy-heading {
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

  time {
    display: block;
    margin-top: var(--space-5);
    color: var(--color-text-tertiary);
    font-size: var(--text-sm);
  }

  .privacy-layout {
    display: grid;
    grid-template-columns: minmax(0, 720px) minmax(220px, 1fr);
    gap: var(--space-16);
    align-items: start;
  }

  .policy {
    color: var(--color-text-secondary);
    line-height: var(--leading-relaxed);
  }

  .policy :global(p),
  .policy :global(ul) {
    margin-bottom: var(--space-5);
  }

  .policy :global(ul) {
    padding-left: var(--space-6);
  }

  .policy :global(li) {
    margin-bottom: var(--space-3);
    padding-left: var(--space-1);
  }

  .policy :global(strong) {
    color: var(--color-text-primary);
    font-weight: 600;
  }

  .policy :global(h2),
  .policy :global(h3) {
    color: var(--color-text-primary);
    font-family: var(--font-serif);
    font-weight: 500;
    line-height: var(--leading-tight);
  }

  .policy :global(h2) {
    margin: var(--space-12) 0 var(--space-4);
    font-size: var(--text-2xl);
  }

  .policy :global(h3) {
    margin: var(--space-8) 0 var(--space-3);
    font-size: var(--text-xl);
  }

  .privacy-contact {
    position: sticky;
    top: var(--space-8);
    padding: var(--space-6);
    border: 1px solid var(--color-border-default);
    border-radius: var(--radius-xl);
    background: var(--color-surface-muted);
    box-shadow: var(--shadow-md);
  }

  .privacy-contact h2 {
    margin-bottom: var(--space-3);
    font-family: var(--font-serif);
    font-size: var(--text-xl);
    font-weight: 500;
  }

  .privacy-contact p {
    margin-bottom: var(--space-4);
    color: var(--color-text-secondary);
    font-size: var(--text-sm);
    line-height: var(--leading-relaxed);
  }

  .privacy-contact a {
    overflow-wrap: anywhere;
    font-size: var(--text-sm);
  }

  @media (max-width: 820px) {
    .privacy-layout {
      grid-template-columns: 1fr;
      gap: var(--space-10);
    }

    .privacy-contact {
      position: static;
      grid-row: 1;
    }
  }

  @media (max-width: 640px) {
    .privacy-shell {
      width: min(100% - 2rem, 720px);
      padding-top: var(--space-6);
    }

    .privacy-heading {
      padding: var(--space-10) 0 var(--space-8);
    }

    .deck {
      font-size: var(--text-lg);
    }

    .policy :global(h2) {
      margin-top: var(--space-10);
    }
  }
</style>
