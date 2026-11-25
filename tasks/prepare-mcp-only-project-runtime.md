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

A fresh disposable project runs with no local stage executable, MCP registration
is the only normal stage path, the pre-commit spec check fails closed without
the shared interface, and no existing project is changed by this preparation
card.
