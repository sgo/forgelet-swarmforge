# expose-active-proposal-backlog-machine-state

# Expose active proposal backlog machine state

## Operator-observed need

The dependency-aware proposal records are now authoritative, but the existing
machine proposal list still returns only unblocked pending rows. The host cannot
build the requested app backlog view because it cannot see approved/in-progress
prerequisites or pending dependants that are currently blocked.

## Contract

- Add a machine-readable active-backlog query to `route_card` without changing
  the existing pending-actionable list used by current clients.
- Include pending proposals, including blocked dependants, and consumed
  proposals that are approved/in progress but not yet completed by verified
  completed-card cleanup. Include actionable pending proposals too, with an
  explicit actionable/blocked state so the host can render one complete ordered
  forest.
- Exclude superseded proposals and proposals with verified `completed-at`.
- Return the existing safe proposal fields plus dependency id, dependency
  status/task identity/card name, derived blocked reason and completion state.
- Order deterministically by `created-at` then `id`. Do not perform presentation
  tree ordering in the layer; the host/app can derive a forest from stable ids.
- Missing, malformed, superseded or cyclic dependency state fails closed and is
  surfaced as a bounded dependency error rather than omitted silently.
- Preserve the existing `proposal-rows` filtering semantics for all current
  command callers; the new mode must be opt-in.

## Testing

- Cover pending parent plus blocked child, approved/in-progress parent plus
  blocked child, verified/completed parent excluded with child released, and
  ordinary dependency-free pending/consumed proposals.
- Cover malformed, missing, superseded and cyclic dependencies, deterministic
  ordering, redaction and backward-compatible existing `list` output.
- Run the complete layer gates and document the machine command and active
  lifecycle.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not change host/API or app rendering
in this card. Do not weaken the approval gate or delete completed histories.

## Verification

The opt-in `backlog` machine query includes actionable and blocked pending
proposals plus consumed proposals that have not reached verified completed-card
cleanup. It excludes superseded, invalidated and verified-completed records and
orders rows by `created-at` then id. Rows expose `active_lifecycle`,
`actionable`, `blocked`, dependency identity/status/error and completion state
while retaining the existing redacted proposal fields.

Tests cover pending and in-progress prerequisites, released dependants,
dependency-free pending and consumed proposals, missing, malformed, superseded
and cyclic dependencies, deterministic ordering, redaction, and unchanged
legacy `list` behavior.

`bb test` passed 449 tests with 3,142 assertions plus all 17 dashboard browser
tests. `bb crap` passed the complete coverage/CRAP gate.

## Operator acceptance

None.
