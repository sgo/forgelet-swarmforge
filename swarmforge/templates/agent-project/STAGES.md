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
spec -> code -> clean -> architect -> harden -> clean-final -> qa -> e2e -> docs -> deploy -> release?
```

Run `bb swarmforge/scripts/stage.bb list --root .` when you think the work is
finished. The list is the reminder; `stage complete` refuses while any required
stage is todo or stale.

The template's `stages.edn` assumes a Maven reactor with an `architecture`
module and the acceptance script under `scripts/`. Adjust the commands and
evidence paths to the project before the first `stage check`.

<!-- forgelet:single-agent-commit-policy:begin -->
## Commit working checkpoints

After the current spec is approved, commit the approved spec before
implementation and commit coherent working checkpoints as they are completed.
Do not wait for every gate or for deployment to make your first commit, and do
not defer the whole card to one final pre-deploy commit. Keep each checkpoint
reviewable and free of unrelated or generated files.

The pre-commit hook checks only that the current spec bundle is approved. Stage
evidence is tied to the Git tree, so committing unchanged contents does not
invalidate a stage that has passed. Before deployment, commit and push the final
reviewed tree.
<!-- forgelet:single-agent-commit-policy:end -->

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

Every approved step must be executable through the public API. The template
ships `acceptance/src` with a runner and an empty handler list; add one
handler per step shape and keep `scripts/run-acceptance-tests.sh` green. A
step the harness cannot execute is not a scenario, only prose.

A bootstrap feature is waived only by naming it, with a reason, in
`acceptance/waived-features.txt`. The runner prints the waiver, so a skipped
feature is visible in the gate log rather than silently ignored.

`harden` runs PIT and then `scripts/run-acceptance-mutation.sh`, which mutates
the example values in every non-waived feature through the same runner. A
survivor or an infrastructure error fails the gate. The mutator works on
copies under `tmp/`, so it never rewrites the approved feature files.
`gherkin-mutator` mutates example cells only: value-driven scenarios must be
Scenario Outlines, and a non-waived feature that reports zero mutations must be
parameterized or have its exemption recorded in the task note.

- **spec**: the approved feature bundle. `stage propose spec <file>...` prints
  the proposal; `stage approve <id> --words
  "<their words>"` records the exact answer. Approval is single-use and stale
  when any file in the bundle changes.
  The pre-commit hook refuses a commit until the current bundle is approved.
  The proposal is also posted to the project dashboard and the phone's
  approvals room; `stage approve` or `stage reject` clears it. Only the
  operator's words approve a spec.
  While a proposal is pending, a new proposal is refused; decide it, or pass
  `--replace` to supersede it explicitly. The old proposal is recorded as
  superseded and the bridge reports that ending.
- **code**: `mvn -Pwith-unit-tests verify`.
- **clean**: `crap4java` for CRAP and `dry4java` for duplicate pairs. The
  project's CRAP ceiling is 6; DRY and mutation are deltas against the task's
  base commit.
- **architect**: green invariants plus `ARCHITECTURE.md`, which states what was
  restructured and why. This is refactoring, not a behaviour change.
- **harden**: PIT with XML output, then Gherkin acceptance mutation. A PIT
  survivor on a touched line blocks unless the project records why it is
  equivalent; a Gherkin survivor or error blocks.
- **clean-final**: the same clean check on the hardened tree.
- **qa**: property tests first, then the acceptance suite.
  `scripts/run-acceptance-tests.sh` parses every feature and dispatches every
  step to a handler under `acceptance/src`. A step with no handler or two
  matching handlers fails the gate.
- **e2e**: required for every standard-profile card unless its approved task
  note contains `## E2E waiver` with a reason. Register the card in
  `qa/e2e.edn` with its procedure, script and required environment-variable
  names. The procedure is `qa/<card>.md`; the script exercises the same
  end-to-end path through the user interface. Missing inputs fail, never skip.
  Input values are not recorded or printed; captured output and
  `target/e2e/check.log` replace them with `<redacted:NAME>`. Library-profile
  projects use their public-API `verify` gate instead.
- **docs**: required for every card. Update `README.md`, `docs/manual.md` and
  `ARCHITECTURE.md` where their audiences require it. `docs/manual-contract.edn`
  links each document section to the public APIs it explains and to executable
  examples whose output checks the prose. `scripts/check-documentation.sh`
  verifies required documents, headings, API ownership, examples and local
  links. The task report records what changed or why a surface was unchanged.
- **deploy**: required for every completed card. After every earlier gate is
  current, commit and push the card, then run `stage check deploy`. The stage
  executes `scripts/publish-snapshot.sh`, which requires a `-SNAPSHOT` version,
  uses `GITHUB_PACKAGES_TOKEN` through the project helper, activates
  `with-release-tests`, and deploys to the target named by
  `swarmforge/deploy.conf`. `stage complete` refuses while this stage is absent,
  optional or stale. No GitHub Actions workflow is involved.
- **release**: optional; `n/a` is not a pass for a release that was skipped.

Each `stage check` moves the project's card on `.swarmforge/board/tasks.tsv`,
which is what the phone's activity room reads. `stage complete` moves it to
`done`.

<!-- forgelet:single-agent-correction-policy:begin -->
## Spec corrections after the first approval

The first spec proposal for each card requires the operator's approval. After
that approval, corrections to the task note or Gherkin for the same unfinished
card are approved automatically by the stage tool; do not create another
approval request. Completing the card resets this policy for the next card.
<!-- forgelet:single-agent-correction-policy:end -->

`stage propose spec` binds one active board card to the proposal and keeps its
name and `task_id`. Every later stage moves that same row. A project-named row
created by older stage tooling is removed when the routed card is bound; it is
not a second card. If several routed cards are active and the task note cannot
identify the intended one, the stage tool refuses rather than guessing.

Do not create worktrees. Do not hand work to another role. The project root is
the checkout.
