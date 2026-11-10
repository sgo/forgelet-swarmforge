# guard-route-card-actions-when-answering

# guard-route-card-actions-when-answering

# Guard route-card actions when answering chat

## Operator-observed problem

The app can render a structured `approve-route-card` action, but a Lieutenant
can still announce a pending proposal and forget
`--approve-route-card <proposal-id>` when answering. The result is a text-only
turn with `Reply **approve**` prose and no button.

This occurred with `proposal-20261110T202026151360Z`. The done request had no
`action_*` headers. The runtime record was repaired manually after the fact.

## Desired guard

- Before a pending request is moved to done, sparsely scan the exact response
  for syntactically valid standalone `proposal-…` tokens.
- Intersect those tokens with pending route-card proposals in the current forge.
  Ignore unknown, malformed, consumed, superseded and foreign proposal ids.
- If any pending local proposal appears and the caller supplied no structured
  action, refuse the answer, leave the request pending and print:
  - the candidate proposal ids and card names;
  - the exact rerun command with
    `--approve-route-card <proposal-id>`;
  - an explicit `--without-route-card-action` escape for a response that merely
    discusses the proposal.
- Validate `--approve-route-card` through the existing ownership, pending-state
  and bounded metadata checks. Require the selected id to appear in the exact
  response.
- Permit multiple pending proposal ids in the response only when the action
  explicitly selects one.
- If retrying a request already in done with an identical response and no
  stored action, allow `--approve-route-card` to backfill that action
  atomically. Preserve the response, original timestamps, thread/source
  metadata, idempotence and one-delivery behavior.
- Do not silently auto-attach an action from prose. A later deterministic
  request-to-proposal association may auto-attach, but text scanning alone is
  not authority for choosing which proposal a button represents.

## Testing

- Response with no proposal id answers normally.
- One pending local proposal and no action is challenged before delivery; the
  pending request is unchanged.
- The corrected action command succeeds and records the full safe action.
- `--without-route-card-action` permits an intentional text-only response.
- Multiple pending ids require an explicit selection.
- Consumed, superseded, unknown, malformed and another-forge ids do not trigger
  a false challenge.
- An action id absent from the response is refused.
- A failed answer leaves the pending file untouched and no action metadata.
- Repeating a completed identical response is idempotent.
- Adding an action to a legacy completed text-only response succeeds once,
  preserves all existing content and metadata, and subsequent repeats are
  idempotent.
- Diagnostics are bounded and contain no paths, tokens, raw proposal notes or
  unrelated dashboard bodies.
- Existing general-chat, report and plain-request answers remain green.
- Run all layer gates and compose the layer.

## Documentation

Update `pack_dashboard_request.sh` usage, the Lieutenant's tool-facing guidance
and the card-routing design record. Explain the challenge, the explicit
text-only override, action selection and the reason prose is not auto-approved.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not change route-card approval
semantics, the host or app action schemas, or infer approval from Markdown
prose.
