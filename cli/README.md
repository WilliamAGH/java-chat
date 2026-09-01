# JavaChat CLI

JavaChat is a hosted research and learning assistant at [javachat.ai](https://javachat.ai) for
software developers. It searches version-aware technical documentation before generating an
answer. Use the `javachat` CLI to learn an API, compare documented behavior across releases, or
find the commands, configuration, and migration details needed to move development work forward
without leaving your terminal.

## Requirements

- Node.js 24.18 or newer
- macOS or Linux
- A JavaChat account for interactive use, or an existing API key for automation

The package has no runtime dependencies.

## Install

```bash
npm install --global @wcallahan/javachat-cli
javachat --version
```

Authorize the default production deployment and ask a question:

```bash
javachat auth login
javachat auth status
javachat ask "How do Java records work?"
```

Answers stream to standard output. When retrieval supplies supporting sources, the answer ends with
a `Sources:` section containing the cited documentation or source-repository URLs. Claims without
retrieved support may be preceded by `Source unavailable:`; treat that material as uncited and
verify it independently.

## What it helps you do

- Learn unfamiliar languages, libraries, SDKs, and platforms from retrieved documentation.
- Find APIs, commands, configuration rules, prerequisites, migration details, and operational
  caveats while implementing, debugging, upgrading, or deploying software.
- Compare indexed releases without silently treating one version's behavior as another's.
- Inspect the cited pages when retrieval supplies supporting sources.

## Questions to try

- “What does `Thread.ofVirtual()` return in Java 25, and what is the shortest documented example?”
- “When should I use `MULTISET` instead of a flat join in jOOQ 3.21.7?”
- “How do I add a 30-second delay between tasks in a Docker Swarm rolling update?”
- “What defaults can I configure with Spring AI 1.1.2 `ChatClient.Builder`?”
- “How does a Clerk session token differ from a backend Secret Key?”

Representative coverage in the shared JavaChat knowledge index includes:

- **Languages:** Java SE 21, 25, and 26; Kotlin 2.4.10; Groovy 5.0.7; Scala 3; and Python 3.14.7.
- **Libraries:** Spring Framework 7.0.7; Spring AI 1.1.x; four indexed Jackson 2.x/3.x releases;
  jOOQ 3.21.7; HikariCP 7.x; and Lombok 1.18.46.
- **Platforms:** PostgreSQL 17/18, Docker, Cloudflare, Clerk, Dokploy, Traefik, Doppler, and Infisical.
- **Repository snapshots:** OpenAI Java, Anthropic SDKs, Langfuse, Dokploy, Infisical, and Traefik.

Use `javachat list all` for the complete current inventory. Chat retrieves supporting material from
the documentation collections.

## Commands

```text
javachat ask "question"       Ask a research question
javachat auth login           Authorize this machine in a browser
javachat auth logout          Revoke and remove the stored credential
javachat auth status          Show the account for the selected deployment
javachat list all             List every indexed source, URL, and version
javachat list knowledge       Alias for list all
```

Run `javachat`, `javachat --help`, or `javachat -h` for the complete command and option reference.

### Inspect the available sources

Check the inventory before asking about an exact package or version:

```bash
javachat list all
```

Each group includes its canonical documentation or GitHub URL, exact ingested versions or commit
revisions, and indexed chunk count. `javachat list knowledge` produces the same output.

### Start a fresh, verbose question

```bash
javachat --new --verbose ask \
  "According to the Java 25 Thread API, what does Thread.ofVirtual() return?"
```

`--new` starts a new conversation. `--verbose` writes retrieval progress to standard error while
the answer continues on standard output. Without `--new`, later questions made with a stored
interactive credential resume the most recent conversation for that host. Headless requests using
`JAVACHAT_API_KEY` always start a new session.

## Authentication and deployments

Credentials are stored separately for each JavaChat host. Interactive login writes the personal
credential to `$XDG_CONFIG_HOME/javachat/credentials.json`, or
`~/.config/javachat/credentials.json` when `XDG_CONFIG_HOME` is unset. On POSIX systems, the file
uses owner-only permissions.

Browser-approved API keys expire after 30 days. On a machine with a browser available separately,
print the approval URL instead:

```bash
javachat --no-browser auth login
```

The approving browser must run on the same machine as the CLI because the callback listener uses
that machine's loopback interface. On a remote shell, use an existing `JAVACHAT_API_KEY` instead of
starting browser login.

Use `--host` to select another deployment:

```bash
javachat --host https://dev.javachat.ai auth login
javachat --host https://dev.javachat.ai auth status
javachat --host https://dev.javachat.ai list all
```

For CI or another non-interactive environment, provide an existing credential through
`JAVACHAT_API_KEY`. `JAVACHAT_HOST` changes the default deployment.

```bash
JAVACHAT_HOST=https://javachat.ai \
JAVACHAT_API_KEY="$EXISTING_JAVACHAT_API_KEY" \
javachat --new ask "What does Thread.ofVirtual() return?"
```

Keep credentials out of command arguments, package scripts, logs, and committed files.

## Develop the package

From the repository's `cli/` directory:

```bash
npm install
npm run dev -- --version
npm run dev -- --help
npm test
```

Everything after `npm run dev --` is passed to the CLI. To test against a locally running JavaChat
server, authenticate that host first:

```bash
npm run dev -- --host http://localhost:8085 auth login
npm run dev -- --host http://localhost:8085 ask "How do Java records work?"
```

## Verify the package artifact

Test the tarball rather than relying only on the source entrypoint:

```bash
npm test
npm run pack:check
npm pack
npm install --global ./wcallahan-javachat-cli-0.0.2.tgz
javachat --version
javachat auth status
javachat list knowledge
```

The tarball-installed command uses the same host-scoped credential file as the source entrypoint.

## License

See [LICENSE.md](LICENSE.md).
