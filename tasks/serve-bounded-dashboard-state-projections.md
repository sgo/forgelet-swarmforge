# serve-bounded-dashboard-state-projections

# serve-bounded-dashboard-state-projections

# Serve bounded reusable dashboard state projections

## Dependency

Depends on `proposal-20261114T220513157455Z`
(`fetch-one-dashboard-snapshot-per-bridge-tick`). The bridge must first prove
that each of its consumers can share one tick-scoped snapshot.

## Operator-observed problem

The verified bridge card removed duplicate bridge reads, but the dashboard
still reconstructs and serializes the complete Family state for each remaining
request. Measured Family responses are 0.18-1.0 MB and take roughly 0.12-0.30
seconds to build. Browser polling and other consumers repeat that work while
most of the response is not needed by a particular caller.

## Desired behaviour

- Keep the existing `GET /api/state` response compatible.
- Add a bounded automation projection containing only the authoritative fields
  required by the bridge: pending and resolved approvals, clarifications,
  boards/cards, activity facts and the minimum Family/project identity needed
  to route them.
- Add short-lived reuse of the authoritative state snapshot and projection
  results between requests. Coalesce concurrent builds rather than recomputing
  the same snapshot in parallel.
- Define explicit freshness and invalidation. Dashboard writes invalidate
  synchronously before returning success. Changes made directly to workspace
  files become visible within the documented short freshness bound and never
  later than the existing one-second bridge semantics.
- A failed or malformed build must not serve invented or stale success data.
  Existing stale/offline behavior remains authoritative.
- Keep response shapes deterministic and bounded. Never copy full transcripts,
  arbitrary pane text, paths, commands, credentials or unrelated project state
  into the automation projection.
- Record projection name, cache hit/miss/coalesced count, build duration,
  response bytes and invalidation reason without high-cardinality sensitive
  data.

## Measurement and testing

- Establish request count, response bytes, build latency and idle CPU for the
  current bridge projection.
- Prove repeated reads within the freshness window reuse one build and
  concurrent misses coalesce.
- Prove every dashboard mutation invalidates before its success response and
  the next read reflects it.
- Cover external file updates, malformed state, failed refresh, a slow build,
  multiple Family roots, unrelated project changes and Family isolation.
- Bound response size and time under a large synthetic Family.
- Prove the existing full `/api/state` response remains compatible.
- Add executable Gherkin examples and a required E2E procedure.

## Boundary

Work only in `projects/forgelet-swarmforge`. Do not operate an active forge,
change bridge behavior in this card, expose a network daemon, weaken stale or
authentication semantics, or promise cross-process cache sharing beyond the
dashboard process.

## Rollout

Commit and verify the source on the `forgelet` branch. Composition and
deployment to active dashboards remain an operator/Lieutenant release action.

## Consumers waiting

`forgelet-bridge` and the Forgelet dashboard browser client will consume the
verified projection in separate cards. The next browser polling slice remains
blocked until this contract is delivered.

## Verification

`GET /api/automation-state` now serves the bounded `automation-v1` projection
from a process-local, root-isolated cache with a 750 ms freshness bound.
Snapshot and projection builds are promise-coalesced; successful dashboard
writes invalidate synchronously, and failed refreshes return a bounded 502
without serving the previous success.

Fixtures prove cache reuse, concurrent coalescing, write invalidation, direct
workspace expiry, failed refresh and recovery, multiple roots, Family
isolation, fixed collection bounds, redaction of bodies/paths/pane text, and
the unchanged full `/api/state` contract. The Gherkin examples are in
`features/serve-bounded-dashboard-state-projections.feature`; the required
procedure and script are in `qa/serve-bounded-dashboard-state-projections.md`
and `qa/serve-bounded-dashboard-state-projections.sh`.

On the 13-project, 420-card, 450-chat synthetic Family, the first
`automation-v1` response was 59,160 bytes and built in 23 ms. Twenty immediate
warm reads completed in under 1 ms total with no additional builds. The
snapshot/projection build count stayed at two, with 20 cache hits and two
first-path misses.

`bb test` passed 463 tests with 3,314 assertions plus all 17 dashboard browser
tests. `bb crap` passed the complete coverage/CRAP gate, and the E2E procedure
passed.

## Operator acceptance

- owner: operator | environment: controlled dashboard rollout for a live Family | checks: report repeated-build, response-byte and idle-CPU reduction while approvals, clarifications, card activity and Family state remain current | status: pending
