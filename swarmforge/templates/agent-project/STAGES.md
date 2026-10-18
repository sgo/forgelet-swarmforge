# Stages

This project is run by one agent. The stages are gates in tooling, not
handoffs: the next one refuses while an earlier one is stale.

The shared engineering rules are in
`swarmforge/constitution/articles/engineering.prompt`; read them before setting
up or changing the build.

The project tools live in `.swarmforge/bin`. Before running a gate manually:

```sh
export PATH="$PWD/.swarmforge/bin:$PWD/swarmforge/scripts:$PATH"
```

## Vertical slices

A task is one public behaviour through every gate, not a layer of a behaviour.
Do not put "API", "storage", "tests", "clean", "architect", "harden" or
"verify" on the backlog as separate items; they belong to the same slice.
Spec-only and bootstrap tasks need an explicit waiver in the approved spec,
not a deferred gate.

```
spec -> code -> clean -> architect -> harden -> clean-final -> qa -> release?
```

Run `bb swarmforge/scripts/stage.bb list --root .` when you think the work is
finished. The list is the reminder; `stage complete` refuses while any required
stage is todo or stale.

The template's `stages.edn` assumes a Maven reactor with an `architecture`
module and an acceptance script. Adjust the commands and evidence paths to the
project before the first `stage check`.

## Specifier workflow

The operator gives a card or chat request with the observed problem. You are
the specifier: read `mission.md` and the code, then write `tasks/<name>.md`
and one or more `features/<name>*.feature` files. Prune the Gherkin, run
`gherkin-parser` and `ir-dry-checker`, list every feature file and the task
note in `:spec`, and then run `stage propose spec <file>...`. The operator
approves the exact bundle; they do not fill a template. Ask when the request is
ambiguous; otherwise write the specification yourself and continue as the same
agent.

The template starts with no feature or task files; the specifier's first turn
creates them. The default `:spec` bundle is `features/*.feature` plus
`tasks/*.md`.

- **spec**: the approved feature bundle. `stage propose spec <file>...` prints
  the proposal; `stage approve <id> --words
  "<their words>"` records the exact answer. Approval is single-use and stale
  when any file in the bundle changes.
  The pre-commit hook refuses a commit until the current bundle is approved.
- **code**: `mvn -Pwith-unit-tests verify`.
- **clean**: `crap4java` for CRAP and `dry4java` for duplicate pairs. The
  project's CRAP ceiling is 6; DRY and mutation are deltas against the task's
  base commit.
- **architect**: green invariants plus `ARCHITECTURE.md`, which states what was
  restructured and why. This is refactoring, not a behaviour change.
- **harden**: PIT with XML output. A survivor on a touched line blocks unless
  the project records why it is equivalent.
- **clean-final**: the same clean check on the hardened tree.
- **qa**: property tests first, then the acceptance suite.
- **release**: optional; `n/a` is not a pass for a release that was skipped.

Do not create worktrees. Do not hand work to another role. The project root is
the checkout.
