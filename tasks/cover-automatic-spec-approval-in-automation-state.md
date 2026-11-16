# Cover automatic approval endings in automation state

## Dependency

Corrects the verified
`expose-resolved-approval-resolution-in-automation-state` contract after its
first live rollout. The bridge consumer remains blocked until this correction is
composed.

## Operator-observed problem

The authoritative Forgelet state contains 712 resolved approvals whose
resolution is `automatic_spec_approval`. The bounded projection currently
accepts only `approved` and `sent_back`, so it fails closed with a bounded 502
and stops serving the entire automation projection. `stage.bb` can also emit
`auto_approved`.

These automatic endings are not manual Matrix actions, but they are
authoritative records and cannot be discarded or rewritten into a guessed
manual approval.

## Desired behaviour

- Accept every authoritative resolved-approval value emitted by the stage:
  `approved`, `sent_back`, `automatic_spec_approval` and `auto_approved`.
- Continue to fail closed for missing, non-string or unknown values.
- Preserve the exact value in the bounded projection.
- Keep pending approvals and review/approval bodies out of the projection.
- Leave bridge policy explicit: only `approved` and `sent_back` select the
  manual ➡/⬅ marks; automatic endings must not be presented as those manual
  actions.
- Preserve all projection bounds, ordering, ETag/cache behavior and full
  `/api/state` compatibility.

## Testing

- A live-shaped Family containing `automatic_spec_approval` and `auto_approved`
  builds successfully and preserves those values.
- Manual `approved` and `sent_back` behavior remains unchanged.
- Missing, non-string and unknown endings still fail closed.
- Pending approvals do not acquire a resolution.
- The 200-row resolved-approval bound, response size, ETag, cache, invalidation
  and Family isolation remain correct.
- Bridge fixtures continue to choose ➡ only for `approved`, ⬅ only for
  `sent_back`, and no manual mark for automatic endings.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not rewrite historical resolutions,
remove automatic spec records, change the bridge in this card or broaden the
projection to arbitrary approval text.

## Verification

- `bb test`: 480 tests, 3,564 assertions, 0 failures, all 21 browser tests, and
  the required bounded-projection E2E passed.
- `bb crap`: 22 coverage tests and 123 assertions passed; the complete coverage
  and CRAP gate passed.
- Focused projection tests cover all four authoritative endings, unchanged
  pending rows and manual mark policy, missing/non-string/unknown fail-closed
  values, automation ETag behavior, full-state parity, and a live-shaped Family
  with 713 resolved approvals returning the 200-row bound below 500,000 bytes.

## Operator acceptance

- owner: operator | environment: composed dashboard and deployed bridge | checks: confirm the live 713-row resolved-approval projection builds, preserves every authoritative ending, and shows Matrix manual marks only for `approved` and `sent_back` | status: pending
