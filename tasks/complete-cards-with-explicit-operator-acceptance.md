# complete-cards-with-explicit-operator-acceptance

# complete-cards-with-explicit-operator-acceptance

# Complete cards with explicit operator acceptance

## Operator-observed problem

A single-agent app card completed every executable gate and pushed its reviewed
source, but remained in `deploy` because the machine had no Apple signing
identity and no connected iPhone. Earlier phone cards completed after automated
and QA evidence while the signed-device confirmation remained explicitly owed
by the operator.

`deploy` for this app means a clean pushed source tree. It is not an App Store,
signing-identity or connected-device gate. Treating an unavailable operator
environment as an unfinished card creates false active work and can stall the
queue indefinitely.

## Desired process contract

- Every single-agent task report has two distinct sections:
  - `## Verification`: automated gates, test counts and source-delivery evidence.
  - `## Operator acceptance`: `None.` or bounded entries with owner,
    environment/device, checks and pending/confirmed status.
- A card with every automated stage complete and source pushed is implementation
  complete. If operator acceptance is environment-bound and unavailable, record
  it as pending and run `stage complete`; do not remain in a gate lane.
- Agents never claim operator acceptance. Only the operator can mark pending
  checks confirmed.
- Actual approvals for destructive, external, irreversible or policy-sensitive
  actions remain hard gates. They are not moved into pending operator
  acceptance.
- `stage complete` output and its persisted completion record retain safe
  operator-acceptance entries so residuals are visible after the card closes.
- `stage list`/`status` clearly distinguish:
  - automation incomplete;
  - automation complete with pending operator acceptance;
  - fully complete with no residual.

## Testing

- A fully gated fixture card with an unavailable physical-device check completes,
  moves to done and retains the pending operator-acceptance record.
- The task note validation accepts `None.` and structured pending entries but
  rejects malformed/unsafe residual records.
- A required release approval or destructive confirmation still blocks
  completion and cannot be supplied as an agent-authored pending entry.
- Existing stages and cards without the new section remain readable; migration
  guidance tells agents to add it on the next touched card rather than
  invalidating history.
- The embedded engineering prompt, generated `AGENTS.md`, generated `STAGES.md`,
  status output and completion record agree.
- Run all layer gates and the update exercise, compose from pushed `forgelet`,
  and verify a new single-agent project receives the instructions.

## Documentation

Update the shared engineering prompt, single-agent template, stage usage and
operator documentation with the automated-versus-operator acceptance boundary
and examples.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not weaken executable gates, release
approval, destructive confirmation or the rule that agents cannot approve their
own work.

## Verification

Automated layer gates, task-report parser fixtures, generated instruction
checks, and the update exercise verify this card.

## Operator acceptance

None.
