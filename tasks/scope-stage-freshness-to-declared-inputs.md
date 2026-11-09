# scope-stage-freshness-to-declared-inputs

# Scope stage freshness to declared inputs

## Operator-observed problem

While `forgelet-host` was running its `harden` stage, routing a second card
added `tasks/<next-card>.md` to the project. The active stage then became stale
even though the active card's source, tests, features, build configuration and
mutation inputs were unchanged. This forced the spec bundle to be reproposed
and the gates rerun against a stable queued tree.

`stage.bb` currently records one `worktree-tree` hash per stage. Its `blocked-by`
and `record-stage` logic compares that global tree, so the `:stamp` lists in
`stages.edn` are printed and stored but do not actually determine freshness.
An unrelated queued task note therefore invalidates evidence it never affected.

## Desired behaviour

- Compute a separate stable content hash from each stage's resolved `:stamp`
  inputs and use it for freshness and blocking.
- Keep the full worktree identity in stage records as diagnostic metadata and
  continue requiring a clean committed tree for completion.
- Scope the spec stage to the exact approved spec bundle.
- Let an unrelated queued task note leave the active card's `code`, `clean`,
  `architect`, `harden`, `qa`, `docs` and deploy stages current.
- Continue invalidating the spec and dependent stages when the active task note
  or feature changes.
- Continue invalidating the stages that consume a source, test, script, build
  file or tool when that input changes.
- Widen unknown or malformed stamp inputs to the whole tree with a visible
  warning, as the existing contract requires.
- Treat records without the per-stage input hash as stale once; never fabricate
  current evidence during migration.

## Testing

- Add an unrelated `tasks/queued-next-card.md` after harden and prove harden
  remains current.
- Change the active task note/feature and prove spec and dependent stages
  become stale.
- Change a stamped source, test, harness or build file and prove exactly the
  consuming stages become stale.
- Cover file deletion, unknown-input widening, clean-versus-dirty worktrees,
  committed no-op checks, malformed legacy records and `stage complete`
  refusal.
- Run the layer's code and clean gates and verify the queued-card scenario in a
  fixture without invoking real mutation tools.

## Boundaries

- Work in `forgelet-swarmforge` on `forgelet`.
- Do not weaken any stage, skip required evidence or change project-owned
  `stages.edn` semantics.
- Do not restart the active forge or compose unreleased layer code.

## Verification boundary

The implementation is complete when the fixture proves unrelated queued input
does not stale active evidence and relevant input changes still do. Deployment
requires a separately approved composition after the layer card is pushed.
