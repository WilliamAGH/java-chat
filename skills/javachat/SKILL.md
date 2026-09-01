---
name: javachat
description: "Answer software and platform documentation questions with the JavaChat CLI and retrieved citations. Use for Java, Kotlin, Docker, Docker Swarm, Dokploy, Clerk, Spring, indexed GitHub repositories, or any question that should be checked against JavaChat's current knowledge inventory."
---

# JavaChat documentation

Use JavaChat from any repository to answer technical questions from the sources actually indexed
by the selected deployment.

## Check the CLI

The command and both help flags show the complete command surface without contacting the server:

```bash
command -v javachat
javachat
javachat --help
javachat -h
javachat --version
```

When the user asks to upgrade a published installation, use the installation-aware updater:

```bash
javachat update
```

The command updates the global npm installation or the declaring project's local dependency. It
refuses direct source and temporary `npx` invocations.

JavaChat requires Node.js 24.18.0 or newer. If the command is missing and the local checkout exists,
install independent package bytes without publishing:

```bash
package_directory="$(mktemp -d)"
npm pack --pack-destination "$package_directory" "$HOME/Developer/git/java-chat/cli"
npm install --global "$package_directory"/*.tgz
```

## Authenticate one deployment

Credentials are stored per host. Development is the default documentation target unless the user
names another deployment:

```bash
env -u JAVACHAT_API_KEY javachat --host https://dev.javachat.ai auth status
```

`auth login` creates and stores a credential. It is a human-operated command; agents must not run or
recommend credential issuance. A human can keep the callback process in the foreground and use:

```bash
env -u JAVACHAT_API_KEY javachat \
  --host https://dev.javachat.ai \
  --no-browser \
  auth login
```

`auth logout` revokes the server key and removes the stored credential. It is also human-operated;
agents must never run or recommend credential revocation:

```bash
env -u JAVACHAT_API_KEY javachat --host https://dev.javachat.ai auth logout
```

## Inspect available knowledge

Check corpus presence before asking when the required package, repository, or version matters:

```bash
env -u JAVACHAT_API_KEY javachat --host https://dev.javachat.ai list all
```

`list knowledge` is an exact alias:

```bash
env -u JAVACHAT_API_KEY javachat --host https://dev.javachat.ai list knowledge
```

Each group shows its package or repository identity, canonical documentation or GitHub URL, exact
ingested versions or commit revisions, and chunk count. An unversioned source is labeled
`unversioned`; do not infer a version from its name.

## Ask a grounded question

Start a fresh conversation, show retrieval progress, and name the version or operational decision
in the question:

```bash
env -u JAVACHAT_API_KEY javachat \
  --host https://dev.javachat.ai \
  --new \
  --verbose \
  ask "Using only retrieved official documentation or indexed official source, answer: <question>. Name the applicable version, distinguish documented facts from inference, and cite the exact sources. If the required source is unavailable, say so."
```

For command syntax, ask for the exact current command and flags. For deployment behavior, ask for
prerequisites, incompatibilities, and rollback-relevant caveats.

## Accept the answer

Treat the answer as source-grounded only when:

- the command exits successfully;
- retrieval progress appears before the answer;
- the requested package and version are addressed;
- `Sources:` contains relevant canonical URLs; and
- the cited pages support the material claims.

Corpus presence, successful retrieval, and valid citations are separate proofs. Report a missing
source or retrieval gap instead of presenting uncited general knowledge as JavaChat documentation.
