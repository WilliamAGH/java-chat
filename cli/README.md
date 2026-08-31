# JavaChat CLI

Ask JavaChat from a terminal with the same retrieval and citations as the web application.

## Install

```bash
npm install --global @williamcallahan/javachat-cli
javachat login
javachat "How do Java records work?"
```

`javachat login` opens JavaChat in a browser. After sign-in and approval, the CLI stores the
personal Clerk API key in `$XDG_CONFIG_HOME/javachat/credentials.json` (or
`~/.config/javachat/credentials.json`) with POSIX owner-only permissions. The package supports
macOS and Linux.

Use `JAVACHAT_API_KEY` for non-interactive environments and `JAVACHAT_HOST` to target another
JavaChat deployment.

## Commands

- `javachat "question"` — ask a question with the same retrieval and citations as the website.
- `javachat login` / `javachat logout` — authorize or remove this machine's credential.
- `javachat whoami` — show who the stored key belongs to.
- `javachat knowledge` — list the document groups ingested in the knowledge base
  (documentation sets, books, articles, PDFs, and indexed GitHub repositories, with chunk counts).

Run `javachat --help` for options.
