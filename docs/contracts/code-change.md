---
title: "Code change policy contract"
usage: "Use whenever creating/modifying files: where to put code, when to create new types, and how to stay SRP/DDD compliant"
description: "Evergreen contract for change decisions (new file vs edit), repository structure/naming, and domain model hierarchy; references rule IDs in `AGENTS.md`"
---

# Code Change Policy Contract

See `AGENTS.md` ([FS1a-k], [MO1a-g], [AR1a-f], [ND1a-c], [CC1a-d]).

## Non-negotiables (applies to every change)

- **SRP/DDD only**: each new type/method has one reason to change ([MO1d], [CC1a]).
- **New feature → new file**; do not grow monoliths ([MO1b], [FS1b]).
- **No edits to >500 LOC files**; first split/retrofit ([FS1f]).
- **Domain is framework-free**; dependencies point inward ([CC1d]).
- **No DTOs in domain**; domain records/interfaces are the API response types.
- **No map payloads** (`Map<String,Object>`, raw types); use typed records ([ZA1c], [TY1d]).

## Decision matrix: create new file vs edit existing

Use this as a hard rule, not a suggestion.

| Situation | MUST do | MUST NOT do |
|----------|---------|-------------|
| New user-facing behavior (new endpoint, new domain capability) | Add a new, narrowly scoped type in the correct layer/package ([MO1b], [AR1a]) | “Just add a method” to an unrelated class ([MO1a], [MO1d]) |
| Bug fix (existing behavior wrong) | Edit the smallest correct owner; add/adjust tests to lock behavior ([RC1c]) | Create a parallel/shadow implementation ([RC1a]) |
| Logic change in stable code | Extract/replace via composition; keep stable code stable ([MO1g]) | Add flags, shims, or “compat” paths to hide uncertainty ([RC1b]) |
| Touching a large/overloaded file | Extract at least one seam (new type + typed contract) ([FS1f], [MO1b]) | Grow the file further ([MO1a]) |
| Reuse needed across features | Add a domain value object / explicit port / explicit service with intent-revealing name ([AB1b]) | Add `*Utils/*Helper/*Common/*Base*` grab bags ([FS1e]) |

### When adding a method is allowed

Adding to an existing type is allowed only when all are true:

- It is the **same responsibility** as the type’s existing purpose ([MO1d]).
- The method’s inputs belong together (avoid data clumps/long parameter lists; extract a parameter record when needed) ([CS1b], [CS1c]).
- The method does not pull in a new dependency direction (dependencies still point inward) ([CC1d]).

If any bullet fails, create a new type and inject it explicitly.

## Create-new-type checklist (before you write code)

1. **Scout & Verify**: Use `context7`/`perplexity` for docs and check local sources (`~/.m2`) to prove your API assumptions are true ([ZA1a], [ZA1b]).
2. **Search/reuse first**: confirm a type/pattern doesn’t already exist ([FS1a], [ZA1a]).
3. **Pick the correct layer** (web → use case → domain → adapters/out) ([AR1a]).
4. **Pick the correct feature package** (feature-first, lowercase, singular nouns).
5. **Name by role** (ban generic names; suffix declares meaning) ([ND1a-b]).
6. **Keep the file small** (stay comfortably under 500 LOC; split by concept early) ([FS1f]).
7. **Add/adjust tests** using existing patterns/utilities ([VR1b]).
8. **Verify** with repo-standard commands (`make lint`, `make test`) ([VR1a-c]).

## Repository structure and naming (placement is part of the contract)

### Canonical roots (Java)

Only these root packages are allowed ([AR1a]):

- `com.example.javachat.boot`
- `com.example.javachat.application`
- `com.example.javachat.domain`
- `com.example.javachat.adapters`
- `com.example.javachat.support`

### Feature-first package rule

All layers organize by **feature first**, then by role.

Examples:
- `domain/model/chat/...`
- `application/usecase/chat/...`
- `adapters/in/web/chat/...`
- `adapters/out/persistence/chat/...`

### No mixed packages

A package contains either:
- Direct classes only, or
- Subpackages only (plus optional `package-info.java`).

If you need both, insert one more nesting level.

## Domain model hierarchy

- Domain records are immutable value objects ([FS1g]).
- Validation happens in constructors.
- Domain → API mapping happens once at the boundary.

## Layer responsibility contract

### Controllers (`adapters/in/web/**`)

Allowed:
- Bind/validate inputs.
- Delegate to exactly one use case per endpoint ([AR1a]).
- Return `ResponseEntity`.

Prohibited:
- Business logic.
- Repository calls.

### Use cases (`application/usecase/**`)

Allowed:
- One use case class = one method = one responsibility ([AR1b]).
- Orchestrate ports, transactions.

Prohibited:
- Web concerns (Spring MVC).

### Persistence adapters (`adapters/out/persistence/**`)

Allowed:
- Repository implementations.
- Mapping to domain types.

Prohibited:
- Business rules.

## Verification gates (do not skip)

- Build/test/lint: `make build`, `make test`, `make lint` ([VR1a-c]).
