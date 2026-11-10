# paginate-general-chat-history-in-dashboard-state

# paginate-general-chat-history-in-dashboard-state

# Paginate general chat history in dashboard state

## Operator-observed need

Lieutenant conversations currently reload every matching dashboard chat record.
Nothing is skipped by age, but `/api/state` and the host conversation response
grow without bound. The operator wants the newest bounded page, an explicit
`Load older messages` capability, optional near-top loading, and equivalent
pagination support in `/api/state`.

## Desired contract

- Add an opt-in cursor-paged view for `source = app.lieutenant-chat` records in
  `/api/state`.
- The initial view returns a bounded number of the newest turns for a requested
  conversation and metadata containing `has_more` and an opaque next cursor.
- The cursor is stable against insertion: ordering remains `(created_at, id)`,
  and another turn arriving must not duplicate or omit older turns.
- Requested older pages return the preceding turns in chronological order.
- Conversation summaries, latest status and pending turns remain discoverable
  without returning every historical turn.
- Report threads, proposal discussions and unrelated chat retain their current
  behavior.
- Do not silently skip records by age. No pagination parameter means unchanged
  legacy behavior until host/app callers have migrated.
- Reject malformed and unsupported pagination parameters with the project’s
  bounded HTTP error contract.

## Testing

- Cover first page, consecutive older pages, empty history, exact-boundary
  sizes, insertion between reads, deletion/corruption, malformed and foreign
  cursors.
- Prove ordering and no duplicated or skipped turns.
- Prove pending general turns and non-general chat remain available.
- Measure payload size and processing time with a large synthetic history.
- Preserve dashboard rendering and existing bridge/host endpoints.
- Run the complete layer suite.

## Boundary

Work in `projects/forgelet-swarmforge` on the `forgelet` branch. Do not add app
controls or change host conversation identity in this card.
