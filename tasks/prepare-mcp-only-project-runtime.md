# prepare-mcp-only-project-runtime

# Prepare projects for MCP-only stage execution

## Operator-observed need

The shared stage helper and MCP now carry structured success/error outcomes,
project-scoped grants and explicit reconciliation. The next migration is to
stop installing project-local `stage.sh`/`stage.bb`, leaving `stages.edn`,
project scripts and history intact.

The pre-commit hook currently exits successfully when local `stage.bb` is
missing, which would silently bypass the spec-approval gate.

## Desired outcome

Update the project template, composition/upgrade lifecycle and agent guidance
for MCP-only stage execution while keeping the behavior disabled until the
dedicated real-harden acceptance passes. New projects and migrated projects
must retain a fail-closed spec-approval check without a local stage helper.

## Plan

1. Make template composition able to omit project-local `stage.sh` and
   `stage.bb` while preserving `stages.edn`, project scripts and stage history.
2. Replace the pre-commit helper lookup with a canonical shared/helper
   approval-state interface. If that helper is unavailable or returns invalid
   data, refuse the commit.
3. Generate MCP-first stage guidance only when the project has an enabled
   MCP stage grant; retain a clearly marked fallback for projects not migrated.
4. Add a feature flag/allow-list for initial migration so this source change
   does not remove helpers from any live project.
5. Exercise a disposable fresh project with no local stage executable:
   pre-commit approval still works, MCP runs one bounded stage, and local
   project source/evidence remains intact.

## Boundaries

- Do not migrate existing projects in this card.
- Do not weaken or bypass spec approval.
- Do not remove `stages.edn`, stage evidence, task notes or project scripts.
- Do not require active forges to restart.
- Do not hard-code a shared helper path into project source when runtime
  configuration can provide it.
- Do not claim MCP success until the separate real-harden acceptance passes.

## Testing and evidence

- Fresh-project template produces no local stage executable when the migration
  flag is enabled.
- Pre-commit rejects unapproved specs and succeeds for approved specs through
  the shared interface, including missing/invalid helper failure cases.
- MCP-registered project agent receives the six stage tools and selects
  `stage_job_launch`.
- Existing projects retain their helpers while outside the rollout allow-list.
- Composition/upgrade preserves project-owned `stages.edn`, scripts and
  evidence.
- Bounded E2E uses a one-second stage, not the production mutation workload.

## Verification

The allowlist defaults to a non-existent forge file, so the preparation is
inactive until an operator names projects explicitly. Fresh allowlisted
projects omit only `stage.sh` and `stage.bb`; explicit upgrades do the same,
while projects outside the list keep both helpers. Reopening a migrated project
refreshes shared runtime files without restoring either stage executable, and
the project-owned `stages.edn` remains byte-identical.

`stage approval-status` is the shared approval interface. The pre-commit hook
uses a legacy local helper when present, otherwise `SWARMFORGE_STAGE_HELPER`,
otherwise the owner-only MCP stage runtime configuration's `stageHome`. A
fresh project without either helper refused an unapproved commit, an invalid
helper refused the commit, and an approved spec committed successfully with no
local stage executable.

The MCP stage E2E passed with one-second detached stages: Codex and Claude
registrations used the unchanged project grant schema, the six stage tools were
available, `stage_job_launch` was selected, the completion wake was reconciled,
and cancellation, retry and project isolation all passed. This prepares the
path but does not claim the separate real-harden acceptance.

The full `bb test` gate passed 537 tests with 4,318 assertions, all 20 dashboard
browser tests, and the bounded-projection E2E; the detached lifecycle E2E
completed in 1,569 ms. The `bb crap` clean gate passed 24 tests with 132
assertions and wrote `target/coverage/lcov.info`. The documentation contract
passed 3 documents and 5 sections.

The active forge has no migration allowlist entry from this card, so no existing
project was migrated or restarted.

## Operator acceptance

None.
