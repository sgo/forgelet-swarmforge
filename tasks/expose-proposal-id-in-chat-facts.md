# expose-proposal-id-in-chat-facts

# Expose proposal IDs in dashboard chat facts

## Operator-observed problem

Proposal discussion works for one message and one Lieutenant answer, but the
app cannot reconstruct or continue a proposal conversation because dashboard
chat facts omit the proposal identity. The discussion becomes a dead end after
the first answer.

## Root cause

The host's proposal discussion request file already contains:

```text
proposal_id: proposal-...
```

The shared dashboard reader emits chat `id`, `status`, `body`, `response` and
`created_at`, but discards `proposal_id`. The app can only see the currently
selected one-request state and cannot group all durable turns for a proposal.

## Desired behaviour

- Dashboard chat entries include their existing optional `proposal_id` header.
- Ordinary chat requests without a proposal id remain valid and unchanged.
- The shared app/phone reader exposes `proposalId` as an optional field on
  `Chat`.
- `forgelet-host` passes the field through with the rest of `/api/state`.
- The field is data only; it does not change chat ordering, approval, card
  creation, response handling or bridge/Matrix behavior.

## Testing

- Dashboard unit/acceptance coverage includes a proposal-linked request and an
  ordinary chat request, proving only the former has `proposal_id`.
- Host proxy coverage proves the field survives `/api/state` unchanged.
- The shared reader accepts valid proposal ids and treats a missing field as an
  ordinary chat.
- Run the layer and host gates, publish the layer change and compose it into the
  Forgelet forge.

## Out of scope

Changing proposal approval, creating a second chat store, adding general chat,
or changing Matrix transport.
