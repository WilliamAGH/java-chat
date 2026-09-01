# JavaChat CLI

Ask JavaChat from a terminal with the same retrieval and citations as the web application.

## Install

```bash
npm install --global @wcallahan/javachat-cli
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
  (documentation sets, books, articles, PDFs, and indexed GitHub repositories, with per-group and
  total chunk counts).

Run `javachat --help` for options.

## Develop locally

The CLI has no runtime dependencies. Node.js 24.18 or newer and npm are sufficient:

```bash
cd cli
npm install
npm run dev -- --version
npm run dev -- --help
```

Everything after `--` is passed to the CLI. It targets `https://javachat.ai` by default. Use
`--host` for another deployment:

```bash
npm run dev -- --host http://localhost:8085 --help
npm run dev -- --host https://dev.javachat.ai --help
npm run dev -- --host https://javachat.ai --help
```

Credentials are stored separately for each host. Sign in to the host you intend to test:

```bash
npm run dev -- --host https://javachat.ai login
npm run dev -- --host https://javachat.ai whoami
```

For headless environments, set `JAVACHAT_API_KEY` instead of running `login`. Do not put API keys
in command arguments, package scripts, or committed files.

## Dogfood the CLI

Exercise the inventory and a fresh, citation-backed answer against the target deployment:

```bash
npm run dev -- --host https://javachat.ai knowledge
npm run dev -- --host https://javachat.ai --new --verbose \
  "Compare HikariCP 7.0.5 with Spring AI 1.1.5 and name every indexed version used as evidence."
```

Then verify the package that npm would install, not only the source entrypoint:

```bash
npm test
npm run pack:check
npm pack
npm install --global ./wcallahan-javachat-cli-0.0.1.tgz
javachat --version
javachat --host https://javachat.ai whoami
javachat --host https://javachat.ai knowledge
```

The tarball-installed `javachat` command uses the same per-host credential file as the source
entrypoint.

## Publish the first npm release

Package versions on npm are immutable. Confirm the prepared version and packed contents before
publishing from `cli/`:

```bash
npm test
npm run pack:check
npm whoami
npm publish --access public
npm view @wcallahan/javachat-cli@0.0.1 version
```

Publishing a scoped public package requires control of the `wcallahan` npm scope and npm's current
two-factor authentication or granular access requirements. If `npm whoami` is not `wcallahan`, sign
in with `npm login` before publishing. Do not publish `0.0.1` until every source and tarball dogfood
check above passes; any correction after publication must use a new version.
