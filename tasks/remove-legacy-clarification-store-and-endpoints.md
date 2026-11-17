# remove-legacy-clarification-store-and-endpoints

# Remove legacy clarification store and endpoints

## Dependency

Follows verified `retire-legacy-clarification-path-from-bridge`. Every configured
Family now emits typed project clarifications through `automation-v1`, and the
bridge no longer consumes legacy clarification notices or answer routing.

## Operator-observed problem

Typed project chat is now the clarification authority, but the shared layer
still exposes the old `.swarmforge/dashboard/clarifications` store,
`pack_dashboard_request.sh clarify`, and
`/api/clarifications/<id>/{answer,seen,withdraw}` endpoints. Keeping those APIs
allows a second, obsolete path to be used and presented even though no
configured Family needs it.

## Desired behaviour

- Remove creation, scanning and answer/review routing for the legacy
  pending/done clarification request store.
- Remove the legacy `clarify` command from `pack_dashboard_request.sh` and the
  dashboard answer endpoint for legacy requests.
- Retain typed project clarification commands and lifecycle operations:
  `clarify-chat`, typed `withdraw-clarification`, chat metadata, attention,
  project-chat answering and app-marker clearing.
- Leave existing on-disk legacy records untouched as ignored historical data;
  do not delete evidence files or Matrix history.
- Keep `/api/state` readable with typed clarifications in chat and no active
  legacy rows.
- Preserve the `automation-v1` wire shape long enough for the deployed bridge:
  `clarifications.legacy` remains a bounded empty compatibility section while
  the legacy store authority is removed. Typed rows remain the only source.
- Keep continuation-nudge suppression based on open typed clarifications.
- Do not change host/app presentation in this card.

## Testing

- Legacy creation and answer endpoints/commands are absent and return bounded
  not-found or usage errors.
- Typed create, seen, withdraw, answer-through-project-chat, lifecycle and
  attention behavior remains intact.
- `/api/state` and `/api/automation-state` remain compatible; typed rows and
  empty legacy compatibility are correct.
- Existing pending/done legacy files remain on disk but are not scanned or
  surfaced.
- Open typed clarifications continue to suppress continuation nudges.
- Dashboard start, bridge reads, approvals, route cards and project chat retain
  their existing behavior.

## E2E

Create and answer one typed clarification through project chat; confirm one
Matrix notice, the owning project wake, lifecycle completion and app attention
clear. Confirm the legacy command and endpoint are unavailable while
`automation-v1` still validates and the bridge reaches all four Families.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not delete historical clarification
files or Matrix events, remove typed clarification support, change host/app
presentation, or alter unrelated approval and chat routes.

## Verification

- `bb test`: 485 tests, 3,577 assertions, 0 failures, 0 errors; all 20 browser
  tests and the required bounded-projection E2E passed.
- `bb crap`: 24 coverage tests and 132 assertions passed; the complete coverage
  and CRAP gate passed.
- Legacy `clarify` now returns usage, and `/api/clarifications/<id>/answer`
  returns bounded 404 Not found. Historical pending and done files remained
  byte-for-byte unchanged and were absent from `/api/state`.
- Typed creation, project-chat answering, seen, withdraw, attention, lifecycle
  and continuation-nudge suppression tests remained green.
- `automation-v1` keeps `clarifications.legacy` as a bounded empty section
  while typed clarification rows remain the only projected source.
- Route-card approval lookup, role health, dashboard attention and the shared
  dashboard no longer scan or route through the legacy clarification store.
- No dashboard or active forge was stopped, restarted or composed.

## Operator acceptance

- owner: operator | environment: composed layer, bridge and Forgelet app | checks: complete one typed clarification end to end after the legacy command and endpoints are gone, with no bridge or app regression | status: pending
