# Architecture

## Decision

The project is language-neutral until its first card chooses a language and
replaces the placeholder lifecycle gates.

## Invariants

- The project owns its language and build tool.
- Every non-spec gate fails closed until the first card establishes a real,
  executable gate.
- Shared project, messaging and stage-runtime behavior is independent of the
  chosen implementation language.
