# fix-project-scoped-resolved-approvals

# Fix project-scoped resolved approvals

## Dependency

Corrects the verified `automation-v1` projection after the all-Family layer
rollout exposed two live contract failures.

## Operator-observed problem

Saibill, Innersource and Ledgerhall root dashboards return 502 from
`/api/automation-state` because their historical resolved approvals include the
valid stage ending `superseded`. The projection accepts the four other known
endings and fails closed on this one.

Project dashboards that do return automation state omit `project` from each
resolved approval. The bridge requires that field and therefore rejects every
project-level projection. The bridge's compatibility fallback does not cover
either failure, leaving the bounded bridge path unable to serve the full
Family.

## Desired behaviour

- Treat `superseded` as an authoritative resolved-approval ending and preserve
  the exact value in `resolved_approvals`.
- Continue to reject missing, non-string and unknown resolution values.
- Make every resolved approval in both Family-level and project-level
  `automation-v1` responses carry its owning project name.
- Keep pending approvals without a resolution.
- Preserve bounds, ordering, ETag/cache behavior, response-size limits and
  Family/project identity behavior.
- Keep this card in `projects/forgelet-swarmforge`; do not change the bridge.

## E2E

Build a live-shaped Family containing `superseded` and verify its Family
projection returns 200 while preserving the ending. Build a project root with
resolved approvals and verify every project-level row carries that root's
project name. Verify an intentionally unknown resolution still returns 502.

## Verification

- `bb test`: 481 tests, 3,574 assertions, 0 failures, all 21 browser tests, and
  the required bounded-projection E2E passed.
- `bb crap`: 22 coverage tests and 123 assertions passed; the complete coverage
  and CRAP gate passed.
- Focused projection tests cover a live-shaped Family and project root
  containing `superseded`, exact resolution preservation, project ownership on
  every project-level row, pending rows without resolution, missing/non-string/
  unknown fail-closed endings, the 200-row response bound, cache, ETag,
  invalidation, ordering, response size and Family/project identity.
- Source delivery: implementation commit `219bb8e` was pushed to
  `origin/forgelet`; the approved task/Gherkin bundle is in `f6299b1`.

## Operator acceptance

- owner: operator | environment: composed dashboard and deployed bridge | checks: confirm Saibill, Innersource and Ledgerhall Family projections return 200 with `superseded` preserved; confirm project-level resolved rows carry their project and the bridge accepts them; confirm an unknown ending still fails closed | status: pending
