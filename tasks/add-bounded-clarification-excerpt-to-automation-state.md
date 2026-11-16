# Add a bounded clarification excerpt to automation state

## Dependency

Extends the verified `serve-bounded-dashboard-state-projections` contract.
The bridge cards `consume-bounded-automation-state-in-bridge` and
`notify-typed-project-clarifications-through-bridge` depend on this capability.

## Operator-observed problem

The `automation-v1` projection deliberately omits clarification bodies, but the
Matrix bridge's clarification notification must include the question itself.
Without a bounded field, moving the bridge to `/api/automation-state` either
breaks the existing notification behaviour or forces the operator to leave the
notification and find the question in the dashboard.

## Desired behaviour

- Add a bounded clarification-question excerpt to both legacy and typed
  clarification rows in `GET /api/automation-state`.
- Keep every existing identity, lifecycle and attention field unchanged.
- Apply one deterministic maximum length with an explicit truncation marker.
- Reject or normalize control characters and redact credential-looking text and
  filesystem paths using the same safety rules as project-chat clarifications.
- Expose only the question excerpt. Do not expose answers, references, the full
  clarification body, surrounding project chat, transcripts, paths, commands or
  credentials.
- Preserve the projection's collection limits, response-size bound, cache and
  ETag behaviour, freshness bound and full `/api/state` compatibility.
- Keep the full authoritative question in project chat/dashboard state. The
  projection is not a second clarification store.

## Testing

- Legacy and typed clarification rows each expose the same bounded excerpt
  shape and retain stable ids.
- Short questions pass unchanged after safety normalization.
- Oversized questions truncate deterministically at the configured bound and
  include the documented marker.
- Control characters, credential-shaped text and paths are removed or redacted.
- Answers, references, full bodies and unrelated transcript content are absent.
- Empty or malformed question records fail closed without inventing text.
- Large projections remain within the defined response-size and row bounds.
- Existing approvals, resolutions, cards, activity, ETag, cache invalidation,
  failure and Family-isolation behaviour remains unchanged.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not change the bridge, add a Matrix
specific endpoint, expose the full clarification body, weaken the bounded
projection, or move clarification ownership out of project chat/dashboard state.

## Verification

- `bb test`: 477 tests, 3,529 assertions, 0 failures, all 21 browser tests, and
  the required bounded-projection E2E passed.
- `bb crap`: 22 coverage tests and 123 assertions passed; the complete coverage
  and CRAP gate passed.
- Focused projection tests cover legacy/typed parity, 500-code-point Unicode
  truncation, control and whitespace normalization, path/token redaction,
  blank and malformed questions, omitted answers/references, full-state
  retention, collection bounds and a response below 500,000 bytes.

## Operator acceptance

- owner: operator | environment: deployed bridge and Matrix client | checks: confirm an unseen legacy or typed clarification notification includes its project, blocked role and bounded question excerpt while the full question remains in the project dashboard and the automation response stays bounded | status: pending
