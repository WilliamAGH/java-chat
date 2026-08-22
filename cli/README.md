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
