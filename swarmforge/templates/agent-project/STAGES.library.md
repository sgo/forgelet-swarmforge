# Stages — library profile

This is a library project. It has no operator-facing acceptance scenarios, so
the QA gate is replaced by an explicit **verify** stage. This is a deliberate
profile, not an `n/a` that hides a skipped gate.

The shared engineering rules are in
`swarmforge/constitution/articles/engineering.prompt`; read them before setting
up or changing the build.

```
spec -> code -> clean -> architect -> harden -> clean-final -> verify -> release?
```

Run `bb swarmforge/scripts/stage.bb list --root .` when you think the work is
finished. `stage complete` refuses while any required stage is todo or stale.

The template assumes a Maven reactor with an `architecture` module. Adjust the
commands and evidence paths to the library before the first `stage check`.

## Specifier workflow

The operator gives a card or chat request with the observed problem. You are
the specifier: read `mission.md` and the public API, then write
`tasks/<name>.md` and one or more `features/<name>*.feature` files. The Gherkin
describes the library's public behaviour, not a user interface. Prune it, run
`gherkin-parser` and `ir-dry-checker`, list every feature file and the task
note in `:spec`, and then run `stage propose spec <file>...`. The operator
approves the exact bundle; they do not fill a template. Ask when the request is
ambiguous; otherwise write the specification yourself and continue as the same
agent.

The template starts with no feature or task files; the specifier's first turn
creates them. The default `:spec` bundle is `features/*.feature` plus
`tasks/*.md`.

- **spec**: the approved feature bundle. `stage propose spec <file>...` prints
  it; `stage approve <id> --words "<their
  words>"` records the exact answer. Approval is single-use and stale when the
  bundle changes.
- **code**: `mvn -Pwith-unit-tests verify`.
- **clean**: CRAP at or below 6 and DRY deltas against the task's base commit.
- **architect**: invariant tests plus `ARCHITECTURE.md`; refactoring only.
- **harden**: PIT with XML output; a survivor on a touched line blocks unless
  the project records why it is equivalent.
- **clean-final**: the same clean check on the hardened tree.
- **verify**: property tests. If the library publishes an API, add the
  compatibility check (for example japicmp or revapi) to this stage, so a
  binary or source incompatibility cannot pass as "no acceptance scenarios".
- **release**: optional; `n/a` is not a pass for a release that was skipped.

Do not create worktrees or hand work to another role. The project root is the
checkout.
