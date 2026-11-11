# add-dependent-proposal-backlog-records

# Add dependency-aware proposal backlog records

## Operator-observed need

The operator wants to review and approve the active backlog from the app rather
than maintaining a separate Markdown plan. A proposal must be able to depend on
another proposal. The dependent remains blocked after its prerequisite is
approved, while the prerequisite is visibly in progress. Only verified
completed-card cleanup releases the dependent for approval.

The first slice establishes the shared authority so the host and app do not
invent different dependency rules.

## Contract

- Extend route-proposal records with one optional stable prerequisite proposal
  id. Use a singular dependency for this slice so the app can render a clear
  tree; reserve future schema evolution for multiple prerequisites.
- `route_card.sh propose` accepts `--depends-on <proposal-id>`.
- At proposal time, the prerequisite must exist, must not be superseded, must
  belong to the same forge, and must not create a cycle.
- `route_card.sh commit` refuses to approve a dependent while its prerequisite
  lacks a verified completion marker. The refusal names the blocking proposal
  and its linked card.
- A pending or merely consumed/approved prerequisite is still blocking. Being
  approved is not sufficient for dependency release.
- Keep proposal records after completion with a completion timestamp and task
  identity. Completion means verified and cleaned by the existing
  completed-card report path; deleting a done board row directly does not
  satisfy a dependency.
- Extend `completed_card_report.sh remove` so that, after validating all
  verified done snapshots, it stamps the linked prerequisite proposal complete
  before removing its board row. Retry/recovery must be idempotent and must not
  release a dependency when verification or removal fails.
- Machine-readable proposal output exposes dependency, completion and derived
  blocked state. Existing clients continue to see only pending actionable rows
  until their later cards are updated.
- Missing or malformed dependencies and superseded prerequisites fail closed.
  Do not silently release a dependent, infer a replacement, or delete the
  prerequisite history.

## Ordered plan

1. Add the optional dependency field, proposal-time validation and approval
   gate to `route_card`.
2. Add the verified-completion stamp to `completed_card_report --remove`,
   scoped to proposals whose linked task id is among that report's verified and
   still-matching done rows.
3. Make machine output distinguish dependency state without breaking current
   pending-list output.
4. Test pending, approved/in-progress and completed prerequisites; missing and
   superseded prerequisites; rejection before verified cleanup; release after
   exact cleanup; idempotent retries; shell and machine-readable paths; and
   backward compatibility for proposals without dependencies.
5. Run the full layer tests and clean gate. Update tool usage and the layer
   documentation with the lifecycle and the distinction between approved and
   completed.

## Follow-on slices

- `forgelet-host`: expose pending and approved/in-progress backlog proposals
  with dependency/completion state and keep SSE/reconciliation consistent.
- `forgelet-app`: render the deterministic dependency forest, grey in-progress
  prerequisites, accessible blocked reasons, no approval action while blocked,
  and re-root children after cleanup.
- Lieutenant/operator migration: reconcile current pending proposals against
  the forge backlog and introduce the tracked durable ledger decision before
  treating the proposal list as the sole backlog source.

## Boundaries

Work in `projects/forgelet-swarmforge` on the `forgelet` branch. Do not change
the host or app in this card. Do not delete completed proposal records or use
the clarification channel. Do not weaken the rule that only the operator can
approve a card.

## Verification

Automated route-card, completed-card report, dependency lifecycle and layer
gate fixtures verify this card.

## Operator acceptance

None.
