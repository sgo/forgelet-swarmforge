# reject-no-action-approval-invitations

# reject-no-action-approval-invitations

# Reject no-action approval invitations

## Operator-observed problem

A Saibill completed-card report named pending proposal
`proposal-20261115T135240538463Z` and explicitly said:

```text
Send "approve proposal-20261115T135240538463Z" if you want it.
```

The operator saw no approval button. The proposal and answer tooling versions
were current, but the completed request contains no `action_*` metadata.
The app correctly rendered no mechanical approval because none was stored.

The route-action guard already challenges a response that names a pending
proposal, but `--without-route-card-action` currently bypasses that challenge
even when the response explicitly invites approval.

## Desired behaviour

- Detect bounded, explicit approval invitations in an answer when that answer
  names a pending local route proposal. Initial patterns include:
  - `send "approve proposal-<id>"`;
  - `reply "approve proposal-<id>"`;
  - `approve proposal-<id> if you want`;
  - `reply approve` in the same paragraph as the named proposal.
- When such an invitation is present, refuse completion unless exactly one
  matching `--approve-route-card <proposal-id>` structured action is supplied.
- Reject `--without-route-card-action` for an explicit approval invitation.
  The agent must either attach the action or rewrite the answer so it genuinely
  discusses the proposal without inviting approval.
- Keep `--without-route-card-action` available for proposal discussion that
  merely references an ID and contains no approval invitation.
- Preserve the requirement that the exact proposal ID appears in the response
  before attaching an action.
- Reject ambiguous/multiple proposal invitations rather than guessing which
  action to attach.
- Diagnostics name the candidate proposal and the repair command but do not
  echo proposal notes, paths, commands, credentials or unrelated request data.

## Testing

- Explicit invitation plus no-action override is refused and leaves the request
  pending unchanged.
- Explicit invitation plus the matching structured action succeeds and emits
  the action fields.
- Discussion-only prose naming an ID plus no-action override still succeeds.
- Invitations naming consumed, superseded, malformed or foreign proposals do not
  create a challenging candidate.
- Multiple live proposal invitations are refused; one exact action is required.
- Normal non-proposal answers, current quick-reply candidates and all existing
  route-action idempotency/backfill tests remain unchanged.
- Cover case, punctuation, quoted commands, multiline paragraphs and bounded
  size without scanning unrelated proposal notes.
- Run the complete layer suite, dashboard browser tests and coverage/CRAP gate.

## Boundary

Work only in `projects/forgelet-swarmforge`. Do not infer approval from prose,
auto-attach an action, send approval automatically, change app rendering or
operate an active forge.

## Verification

Approval-invitation detection now runs before the discussion-only override.
Quoted `send`/`reply` instructions, `approve <id> if you want`, and
`reply approve` in a paragraph containing a proposal are matched
case-insensitively. Only pending proposals owned by the current Family remain
candidates; sentence punctuation is trimmed without altering the proposal ID.

One live invitation requires exactly its matching `--approve-route-card`
action. The no-action override is refused, multiple invitations are rejected,
and diagnostics contain only bounded IDs, task names and repair instructions.
Discussion-only references still accept the override, while consumed,
superseded, malformed and foreign proposal invitations do not challenge.

Tests cover quoted/case/punctuation variants, multiline paragraphs, matching
action success, no-action refusal, discussion-only delivery, non-live and
foreign IDs, multiple live invitations, quick replies, and existing route-card
idempotency/backfill. The Gherkin contract is in
`features/reject-no-action-approval-invitations.feature`.

`bb test` passed 472 tests with 3,427 assertions plus all 21 dashboard browser
tests. `bb crap` passed the complete coverage/CRAP gate.

## Operator acceptance

- owner: operator | environment: controlled live Lieutenant answer flow | checks: confirm a `Send "approve proposal-<id>"` invitation requires the exact action and a discussion-only reference remains deliverable with the explicit no-action choice | status: pending
