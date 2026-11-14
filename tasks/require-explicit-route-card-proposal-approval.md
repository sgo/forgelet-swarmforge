# require-explicit-route-card-proposal-approval

# Require explicit approval of a specific route-card proposal

## Operator-observed failure

The operator said “sounds good let's start with that” while agreeing to a
design direction. The Lieutenant used those words with
`route_card.sh propose --operator-said`, which treated `start` as affirmative
and created the card without first recording, presenting or obtaining approval
for that specific proposal.

The operator had agreed to the scope discussion, not to a particular project
slice, task note or card. No approval button was shown.

## Current gate gap

`route_card.bb` permits both `--approval` and `--operator-said` during
`propose`, before the proposal exists. Its affirmative matcher accepts broad
words such as `start` and `go`. Supplying contextual discussion text therefore
marks the not-yet-shown proposal pre-approved, and `commit` creates the card
without another operator turn.

This reverses the intended sequence:

`discuss -> record exact proposal -> show it -> operator approves that proposal -> create card`.

## Desired behaviour

- `propose` always records a pending proposal and never creates a card.
- Reject approval flags during `propose`, or require them only after an explicit
  separate confirmation mode that cannot be inferred from discussion text.
- Approval must identify the specific proposal:
  - a structured proposal approval action; or
  - an exact proposal id/card name in the operator's affirmative response.
- Generic design agreement such as “start”, “go ahead with that”, “sounds good”
  or “let's do that” is discussion, not card approval.
- A bare `approve` is valid only when the dashboard request is unambiguously
  tied to that one recorded proposal, as the existing clarification/chat flow
  intends.
- The proposal record should carry a `presented-at`/approval-source state so a
  commit can prove the proposal was shown before approval was accepted.
- Rejected or invalidated approval cannot consume the proposal or create a
  card.

## Compatibility and recovery

- Preserve explicitly approved queued proposals and their audit records.
- Mark invalidated proposals with reason/timestamps and remove any unauthorized
  active card without discarding its recoverable worktree work.
- Direct-pane approval remains possible only after the exact proposal is
  presented and the operator names/approves it.

## Testing

- “sounds good let's start with that” creates no card and does not approve a
  proposal.
- A recorded, presented proposal plus “approve” tied to that proposal creates
  one card.
- Approval aimed at a different proposal id cannot consume it.
- Generic `start`, `go`, `sounds good` and discussion-only replies decline.
- Negations remain dominant.
- Approval records and invalidations survive restart and appear machine-readably
  in the proposal/audit state.

## Boundary

Work in the shared SwarmForge route-card tooling and its dashboard projection.
Do not remove operator approval, weaken proposal validation or mutate existing
approved project cards.

## Verification

Tooling tests prove no proposal can be pre-approved from a discussion phrase,
and every created card has a recorded approval of that exact proposal after it
was presented.

`propose` now rejects both approval flags and records `presented-at` with the
pending proposal. A later commit requires valid presentation and either an
exact proposal id/card name plus an approval word, or a dashboard answer tied
to the same proposal id. Bare consent from an untied record is refused, as are
generic design phrases and any approval naming a different proposal.

Proposal records retain the exact approved text, approval source and timestamp.
Invalidated proposals remain on disk with a timestamp and bounded reason and are
reported as `invalidated` in machine detail and dashboard actions. The new
invalidation path removes only a linked active or queued board row; it preserves
the task note and recoverable worktree, and retains completed rows and audit
history.

`bb test` passed 442 tests with 2,980 assertions plus all 17 dashboard browser
tests. `bb crap` passed the full Cloverage/CRAP gate and regenerated
`target/coverage/lcov.info`. No active Forgelet forge was stopped, restarted,
composed or modified.

## Operator acceptance

None.
