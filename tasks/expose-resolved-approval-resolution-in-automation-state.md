# Expose resolved approval endings in automation state

## Dependency

Extends the verified bounded `automation-v1` projection. The bridge card
`consume-bounded-automation-state-in-bridge` depends on this capability to
preserve the Matrix approval marks.

## Operator-observed problem

The authoritative dashboard state records how each resolved approval ended as
`approved` or `sent_back`. The bounded `/api/automation-state` projection keeps
the resolved approval identity but strips that resolution, so the bridge cannot
know whether to mark the original Matrix message with ➡ or ⬅. The bridge cannot
infer the ending safely from prose, ordering or timing.

## Desired behaviour

- Add `resolution` only to rows in `resolved_approvals`.
- Permit exactly the authoritative values `approved` and `sent_back`.
- Preserve every existing resolved-approval identity field and row ordering.
- Do not add resolution to pending approvals and do not add review bodies,
  operator words or arbitrary approval text.
- A resolved row with a missing, unsupported or non-string resolution must fail
  closed in the bounded projection rather than becoming an unknown Matrix mark.
- Preserve the approval collection limit, deterministic ordering, response-size
  bound, cache and ETag behaviour, invalidation and full `/api/state`
  compatibility.

## Testing

- Approved and sent-back resolved approvals expose their exact resolution.
- Pending approvals do not acquire a fabricated resolution field.
- Missing, malformed and unknown resolved resolutions do not produce a
  misleading success projection.
- Resolution identity survives Family project tagging and bounded projection.
- Existing approvals, resolved-approval count, projection size, cache, ETag,
  invalidation and failure behavior remain compatible.
- A real or fixture bridge can choose ➡ for `approved`, ⬅ for `sent_back` and
  never infers the mark from other fields.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not change the bridge, add approval
bodies, invent a Matrix-specific endpoint, make pending approvals resolved, or
move resolution ownership out of the authoritative dashboard state.

## Verification

- `bb test`: 480 tests, 3,559 assertions, 0 failures, all 21 browser tests, and
  the required bounded-projection E2E passed.
- `bb crap`: 22 coverage tests and 123 assertions passed; the complete coverage
  and CRAP gate passed.
- Focused projection tests cover exact `approved`/`sent_back` values, absence on
  pending rows, Family project tagging, stable ordering, fixture Matrix mark
  selection, bounded automation ETags, missing/non-string/unknown fail-closed
  endings, full-state parity, 200-row truncation from 250 records, and a
  response below 500,000 bytes.

## Operator acceptance

- owner: operator | environment: composed dashboard and deployed bridge | checks: confirm a resolved approval read through `/api/automation-state` carries the same `approved` or `sent_back` value as full state and the bridge preserves the corresponding Matrix mark without prose inference | status: pending
