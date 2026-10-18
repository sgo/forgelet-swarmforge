# Stages

This project is run by one agent. The stages are gates in tooling, not
handoffs: the next one refuses while an earlier one is stale.

```
spec -> code -> clean -> architect -> harden -> clean-final -> qa -> release?
```

Run `bb swarmforge/scripts/stage.bb list --root .` when you think the work is
finished. The list is the reminder; `stage complete` refuses while any required
stage is todo or stale.

The template's `stages.edn` assumes a Maven reactor with an `architecture`
module and an acceptance script. Adjust the commands and evidence paths to the
project before the first `stage check`.

- **spec**: `spec.md` needs the operator's approval. `stage propose spec
  spec.md` prints the proposal; `stage approve <id> --words "<their words>"`
  records the exact answer. Approval is single-use and stale when the spec
  changes.
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
