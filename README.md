# JavaChat

[![JavaChat application](src/main/resources/static/images/java-chat-app.png)](https://javachat.ai)

JavaChat is a web application and command-line research assistant for software developers. It
searches a version-aware library of technical documentation before generating an answer. Use it to
learn an unfamiliar API, compare documented behavior across releases, or find the commands,
configuration, and migration details needed to move development work forward. Answers can include
links to the retrieved pages, and JavaChat is designed to make missing source coverage visible.

Use JavaChat in whichever form fits your workflow:

- **Web:** open [javachat.ai](https://javachat.ai) for chat and guided lessons.
- **Terminal:** install the [JavaChat CLI](cli/README.md) to research from any directory and receive
  source links when retrieval finds supporting documentation.
- **Self-hosted development:** run the Svelte and Spring Boot application locally and connect it to
  Qdrant and an OpenAI-compatible gateway.

## What JavaChat helps you do

- **Learn APIs and platforms:** request a focused explanation, short example, applicable version,
  and source pages you can inspect.
- **Research technical decisions:** investigate releases, migration paths, commands, configuration
  rules, prerequisites, and operational caveats.
- **Move real work forward:** keep the CLI beside your code while implementing an integration,
  debugging a library, planning an upgrade, or configuring a deployment.
- **See the source boundary:** JavaChat can retrieve the exact requested release or nearby releases
  of the same technology when they are indexed, and can report when the requested evidence is absent.

## Example queries possible with JavaChat

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

Run `javachat list all` for the complete current inventory. Chat retrieves supporting material from
the documentation collections.

## Install the CLI

The CLI supports macOS and Linux and requires Node.js 24.18 or newer.

```bash
npm install --global @wcallahan/javachat-cli
javachat auth login
javachat ask "How do Java records work?"
```

Upgrade an npm-installed copy with `javachat update`. The command updates the project-local package
that owns the invoked binary, or the active global npm installation.

Use `javachat list all` to see the documentation packages, source repositories, versions, and
revisions available on the selected deployment. See the [CLI README](cli/README.md) for all
commands, non-interactive authentication, local development, and package verification.

## How it works

1. JavaChat searches the documentation index for material relevant to the question and requested
   version.
2. The strongest matches are supplied as context for the answer.
3. The answer streams to the web app or CLI, with source links when retrieval returns citations.
   When matching source documents are absent, JavaChat still answers from the model's general
   knowledge and labels that limitation in the answer.
4. Guided lessons keep their own conversations, while ordinary chat can continue a prior session.

The repository also contains the pipeline that fetches, chunks, embeds, deduplicates, and indexes
the source material. See [Architecture](docs/architecture.md) for Qdrant, dense and sparse search,
reciprocal-rank fusion, and streaming details.

## Run the application locally

### Prerequisites

- BellSoft Liberica JDK 25
- Node.js 24.18
- Docker, when running the local Qdrant service
- `mise` or another Java version manager for the pinned development toolchain

Install the pinned Java version:

```bash
mise install
```

Install and select the frontend's pinned Node.js version with `nvm`:

```bash
nvm install 24.18.0
nvm use 24.18.0
```

Then configure and start the full application:

```bash
cp .env.example .env
# Set OPENAI_BASE_URL and OPENAI_API_KEY in .env.
make compose-up
make dev
```

Open [http://localhost:8085](http://localhost:8085). The local commands create missing Qdrant
schemas only for loopback connections; remote Qdrant deployments remain fail-closed.

For a fuller explanation of the environment variables, local collection setup, and optional
documentation ingestion, read [Getting started](docs/getting-started.md).

## Useful development commands

```bash
make help       # list repository-owned commands
make build      # build the frontend and backend
make test       # run shell contracts and JVM tests
make lint       # run frontend and JVM static analysis
make health     # check the running application
```

To work only on the npm package:

```bash
cd cli
npm install
npm run dev -- --help
npm test
```

## Index documentation and repositories

Documentation ingestion is optional for ordinary application development. Run it only after the
gateway and Qdrant preflight checks pass.

```bash
make full-pipeline
REPO_PATH=/absolute/path/to/repository make process-github-repo
REPO_URL=https://github.com/owner/repository make process-github-repo
SYNC_EXISTING=1 make process-github-repo
```

See [Pipeline commands](docs/pipeline-commands.md) for source selection and ingestion controls, and
[GitHub repository ingestion](docs/github-repository-ingestion.md) for repository synchronization.

## Documentation

- [Documentation index](docs/README.md)
- [Getting started](docs/getting-started.md)
- [Architecture](docs/architecture.md)
- [HTTP and streaming API](docs/api.md)
- [Contributing](CONTRIBUTING.md)

## License

See [LICENSE.md](LICENSE.md).
