# bound-active-proposal-backlog-to-dependency-work

# Bound active proposal backlog to dependency work

## Operator-observed problem

After the dependent-proposal rendering fix, the live Forgelet Proposals panel
showed an extremely long in-progress section. The host was returning 136
backlog rows: 130 consumed historical proposals, six pending proposals, four
actionable roots and two blocked dependants.

## Root cause

`route_card backlog` treats every consumed proposal without a valid completion
marker as an active in-progress row. Most historical root proposals predate the
completed-card completion marker or never had one recorded, even though their
cards are finished. They have no dependency relationship and are not useful
active backlog work.

The desktop and phone render the host response correctly; the problem is the
projection scope.

## Desired behaviour

- Keep every genuinely pending proposal visible.
- Keep consumed proposals visible only when they participate in an active
  dependency chain: they are a prerequisite of a visible pending/in-progress
  proposal, or they are themselves a dependent awaiting prerequisite
  completion.
- Hide consumed historical roots that neither depend on nor release another
  visible proposal.
- Preserve internal dependency validation across the complete proposal store;
  the display projection must not make an invalid or orphaned dependency look
  valid.
- Preserve the bounded row count, deterministic ordering, depth, lineage,
  accessibility and completed-marker rules.
- Do not delete proposal history or rewrite completion records.

## Testing

- A fixture with many consumed history roots and only a small pending/dependency
  graph contains only the pending roots and genuine in-progress dependencies.
- A consumed prerequisite with a pending dependent remains visible.
- A consumed dependent with a completed prerequisite remains visible.
- A consumed isolated root is hidden.
- Completed proposals remain hidden.
- Missing, malformed, superseded, invalidated and cyclic dependency chains still
  fail closed.
- Row ordering and maximum-depth behaviour remain deterministic.
- Existing route-card approval, completion recording and dependency release
  tests remain green.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not delete proposal records, infer
completion from card names, change the host/app response schema, or change
desktop/mobile presentation in this card.

## Verification

- `bb test`: 486 tests, 3,595 assertions, 0 failures, 0 errors; all 20 browser
  tests and the required bounded-projection E2E passed.
- `bb crap`: 24 coverage tests and 132 assertions passed; the complete coverage
  and CRAP gate passed.
- The route-card lifecycle namespace passed 27 tests and 422 assertions.
- A 130-row consumed-history fixture returned only seven pending/in-progress
  dependency rows, including a consumed prerequisite of a pending dependent and
  a transitive consumed prerequisite chain.
- A consumed dependent whose prerequisite had verified completion remained
  visible while the completed prerequisite stayed hidden.
- Isolated consumed roots and verified-completed proposals were absent.
- Missing, malformed, superseded, invalidated and cyclic dependency evidence
  remained fail-closed; ordering stayed deterministic by created timestamp and
  proposal id.
- Existing approval, completion-marker, dependency-release and proposal-history
  tests remained unchanged and green.

## Operator acceptance

- owner: operator | environment: installed desktop and iPhone | checks: open Proposals and see only the small pending/dependency-related backlog rather than the historical consumed-proposal set | status: pending
