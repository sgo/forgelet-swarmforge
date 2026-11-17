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
