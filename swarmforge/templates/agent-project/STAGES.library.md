# Stages — library profile

This is a library project. Its Gherkin describes the public API's behaviour;
there is no separate operator-facing QA gate, so those scenarios are executed
by the explicit **verify** stage. This is a deliberate profile, not an `n/a`
that hides a skipped gate.

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
spec -> code -> clean -> architect -> harden -> clean-final -> verify -> release?
```

Run `bb swarmforge/scripts/stage.bb list --root .` when you think the work is
finished. `stage complete` refuses while any required stage is todo or stale.

The template assumes a Maven reactor with an `architecture` module and the
acceptance script under `scripts/`. Adjust the commands and evidence paths to
the library before the first `stage check`.

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

Every approved step must be executable through the public API. The template
ships `acceptance/src` with a runner and an empty handler list; add one
handler per step shape and keep `scripts/run-acceptance-tests.sh` green. A
step that can only be checked by reading source is not a scenario.

- **spec**: the approved feature bundle. `stage propose spec <file>...` prints
  it; `stage approve <id> --words "<their
  words>"` records the exact answer. Approval is single-use and stale when the
  bundle changes.
  The pre-commit hook refuses a commit until the current bundle is approved.
  The proposal is also posted to the project dashboard and the phone's
  approvals room; `stage approve` or `stage reject` clears it. Only the
  operator's words approve a spec.
- **code**: `mvn -Pwith-unit-tests verify`.
- **clean**: CRAP at or below 6 and DRY deltas against the task's base commit.
- **architect**: invariant tests plus `ARCHITECTURE.md`; refactoring only.
- **harden**: PIT with XML output; a survivor on a touched line blocks unless
  the project records why it is equivalent.
- **clean-final**: the same clean check on the hardened tree.
- **verify**: property tests and the Gherkin acceptance suite.
  `scripts/run-acceptance-tests.sh` parses every feature and dispatches every
  step to a handler under `acceptance/src`; the handlers call the public API.
  A missing handler, an ambiguous handler, or a failed assertion fails the
  gate. If the library publishes an API, also add the compatibility check
  (for example japicmp or revapi) to this stage, so a binary or source
  incompatibility cannot pass unnoticed.
- **release**: optional; `n/a` is not a pass for a release that was skipped.

Each `stage check` moves the project's card on `.swarmforge/board/tasks.tsv`,
which is what the phone's activity room reads. `stage complete` moves it to
`done`.

Do not create worktrees or hand work to another role. The project root is the
checkout.
