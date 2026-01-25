# Contributing to Java Chat

Feedback and contributions are welcome. If you find a bug or want a feature, please open an issue in this repository.

## Getting started

1) Fork the repository
2) Clone your fork
3) Create a feature branch
4) Make your changes
5) Run checks locally:

```bash
make test
make build
make lint
```

6) Commit with a descriptive message
7) Push your branch and open a Pull Request

## Guidelines

- Keep PRs focused (one change per PR when possible).
- Add tests for new behavior.
- Update docs when you change workflows or endpoints.
- Don’t commit secrets (use `.env`, and keep `.env.example` up to date).

## Reporting issues

When reporting an issue, please include:

- Clear description of the problem
- Steps to reproduce
- Expected vs actual behavior
- OS, Java version, and how you’re running the app (`make dev`, `make run`, etc.)

