# reduce-idle-dashboard-browser-polling

# reduce-idle-dashboard-browser-polling

# Reduce idle dashboard browser polling

## Dependency

Depends on verified proposal `proposal-20261115T072142048531Z`
(`serve-bounded-dashboard-state-projections`). The cached snapshot and
projection foundation must exist before changing browser refresh behavior.

## Operator-observed problem

The browser dashboard fetches the complete `/api/state` every two seconds with
`cache: no-store`, even when the page is hidden and nothing has changed. This
keeps request volume, state builds and transfer cost high while idle.

## Desired behaviour

- Stop polling while the document is hidden and refresh immediately on visible
  focus/re-entry.
- Coalesce overlapping refreshes and prevent duplicate requests from focus,
  visibility, timers and user actions.
- Add a conditional state read using the dashboard's authoritative snapshot
  fingerprint/ETag so unchanged state returns 304 without transferring the full
  body.
- Keep a low-frequency visible-page reconciliation fallback for missed changes;
  do not rely exclusively on browser visibility events.
- Refresh immediately after known dashboard mutations and on reconnect.
- Preserve current rendering, ordering, selection, panels and error/stale
  behavior. Malformed, unauthorized or failed refreshes remain explicit.
- Record bounded browser refresh metrics: visible/hidden polls, 304s, full
  reads, coalesced requests, bytes and refresh latency.

## Testing

- Hidden pages issue no periodic reads; visible focus and visibility changes
  issue one coalesced read.
- Unchanged conditional reads return 304 and retain the current rendered state.
- State changes return a new body and render normally.
- Burst focus/visibility/action events produce at most one in-flight read.
- Reconnect and mutation paths refresh immediately.
- Cover malformed ETag, 304 without prior state, authentication failure,
  network failure, stale retention and recovery.
- Extend browser tests and the fixed synthetic measurement; report bytes,
  request count and CPU before/after.

## Boundary

Work only in `projects/forgelet-swarmforge`. Do not change the full state
contract, remove the visible reconciliation fallback, operate active forges, or
treat hidden-page state as current without reconciling on return.

## Verification

Full and bounded state responses now carry an ETag derived from the exact
rendered body. Matching weak or exact `If-None-Match` values return 304 without
a body; malformed and non-matching tags return the current full response.

The browser dashboard uses one in-flight refresh, suspends periodic reads while
hidden, and reconciles immediately on visible focus/re-entry, reconnect and
successful mutations. A four-second visible fallback preserves direct-file
change detection. Rendered state and explicit errors are retained across
malformed, unauthorized and network failures, with recovery on the next
successful read.

The fixed browser measurement ran 20 visible polls with 20 conditional 304
responses, zero full reads, zero response-body bytes and under 30 ms total. The
same number of reads under the prior full-read design would have transferred
60,140 bytes for the fixture. Twenty hidden polls issued zero requests.

`bb test` passed 464 tests with 3,324 assertions plus all 21 dashboard browser
tests. `bb crap` passed the complete coverage/CRAP gate. The Gherkin contract is
in `features/reduce-idle-dashboard-browser-polling.feature`.

## Operator acceptance

- owner: operator | environment: controlled live dashboard rollout on desktop and phone | checks: confirm visible updates normally, background polling stops, return refreshes once, and idle requests, bytes and dashboard CPU fall without stale UI | status: pending
