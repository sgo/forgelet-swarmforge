# reconcile-route-card-proposal-presentations

# Reconcile legacy route-card proposal presentations

## Operator-observed failure

Approving a proposal from the desktop app and from the mobile app both fail:

- desktop: `route-card operation failed`;
- mobile: `the proposal approval failed (502)`.

The shared route-card gate returns:

```json
{"error":{"code":"proposal_not_presented",
          "message":"proposal has no valid presentation record"}}
```

The approval is not consumed and no card is created.

## Confirmed cause

The presentation-requiring gate was introduced in commit `39386d9`. It requires
pending proposal records to contain a valid `:presented-at`.

The forge still has an older deployed `route_card` tool while Forgelet Host
invokes the newer source-layer tool. Proposals made through the older deployed
tool therefore contain `:created-at` and the old `:approval-id` /
`:operator-said` fields, but not `:presented-at` or `:presentation`.

There are currently ten pending proposals in this condition. The old tool wrote
and printed the proposal before returning, so `:created-at` is the presentation
timestamp for records with this exact legacy schema. The approval action names
the proposal explicitly, but the new gate rejects the record before accepting
the action.

The host also maps every unrecognised route-card error code to 502
`proposal_unavailable`, which obscures the specific presentation failure.

## Desired behaviour

- Provide a one-time, idempotent migration for genuine pre-upgrade proposals.
- Migrate only records that have the exact known legacy schema, are still
  pending, have no presentation fields, and contain a valid `:created-at`.
- Stamp `:presented-at` from the legacy `:created-at`, because the old tool
  printed the proposal immediately after writing that timestamp.
- Record explicit migration provenance in the proposal file without treating
  the migration as approval.
- Preserve the original approval and creation fields and never consume a
  proposal or create a card during migration.
- Leave consumed, invalidated, superseded, malformed or unrecognised records
  unchanged and fail closed.
- Make migration retries no-ops after a record has been migrated.
- Ensure every agent invocation path receives the current route-card tool so
  new proposals do not recreate the incompatible schema.

## Implementation

Extend the current source-layer `route_card` tool with:

```text
route_card.sh migrate-presentations --forge-root <root> [--dry-run] [--json]
```

The migration:

1. Reads proposal files without following unexpected paths.
2. Selects only pending records lacking both `:presented-at` and
   `:presentation`, with valid `:created-at`, and with the legacy
   `:approval-id` and `:operator-said` keys present.
3. Sets `:presented-at` to the existing `:created-at`.
4. Sets `:presentation` to a bounded legacy-migration description and records
   `:presentation-migrated-at` plus the migration source.
5. Writes atomically and leaves every other record byte-for-byte unchanged.
6. Reports migrated, already-current, skipped-terminal and refused-invalid
   counts in machine-readable form.

Update proposal detail output to expose the presentation and migration
provenance. Keep approval rules unchanged: migration supplies presentation
evidence, never consent.

Repair the forge composition/deployment path so the `route_card` invoked by
agents at the forge root is the same compatible implementation used by the
host. Do not solve this by pointing the host back at the old gate.

The host's specific 502 mapping is a separate `forgelet-host` follow-up. This
card should not modify the host.

### Host machine-approve follow-up

Forgelet Host approves by running
`route_card.sh approve <exact-proposal-id> --operator-said approve --json`.
The machine `approve` operation is a structured tie to the proposal id in its
argument, so its bare `approve` consent is valid after presentation. Human
`commit` keeps the stricter exact-name or tied-record rule. A regression must
exercise the real tool through list, detail, approve, consumed detail and a
refused duplicate approval.

## Testing

- A known legacy pending record migrates once and is then approvable only with
  an explicit approval tied to that proposal.
- The migrated timestamp equals the original `:created-at`.
- A second migration makes no changes.
- New-format pending proposals are untouched and reported already current.
- Consumed, invalidated, superseded, malformed, missing-timestamp and
  unrecognised-schema records are never stamped and remain unapprovable.
- Stored legacy approval fields do not become approval evidence.
- Dry-run reports the same selection while writing nothing.
- A failed atomic write leaves the original record intact.
- Proposal id, project, note, dependencies and audit fields survive unchanged.
- A fresh proposal through the deployed forge-root command contains
  `:presented-at` and can round-trip through host machine list/detail/approve.

## Deployment and acceptance

- Run the complete route-card unit, property, dashboard, architecture and
  quality gates.
- Deploy the compatible tool and run the migration against the Forgelet forge.
- Verify all ten known legacy pending proposals gain valid presentation
  provenance and no other proposal changes.
- Retry approval of
  `proposal-20261114T105356319556Z` through the host path and verify it consumes
  exactly that proposal and creates exactly one card.
- Verify a newly proposed item also has `:presented-at` and remains compatible
  with both desktop and mobile approval.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not infer approval, bulk-approve
proposals, rewrite proposal notes, delete proposal history, or create cards
during migration. Do not weaken the requirement for new proposals to carry
presentation evidence. Do not change Forgelet Host in this card.

## Additional confirmed regression during acceptance

After the migration proposal itself was presented, its app approval still
failed. This is a second, independent defect in the new consent matcher.

Forgelet Host invokes:

```text
route_card.sh approve proposal-20261114T110640493817Z \
  --operator-said approve --json
```

The proposal has valid `:presented-at`, but the matcher treats
`--operator-said` as untied pane text. Because the text is only `approve`, it
does not name the card/proposal and the invocation returns `proposal_invalid`.
The exact proposal id in the machine operation is currently ignored when
deciding whether the approval is tied to that proposal.

Repair the machine approval contract in the same card:

- `approve <proposal-id>` is a structured action naming exactly that proposal.
- Pass that structured context explicitly into the approval decision; do not
  infer it from generic text or ambient process state.
- Validate that the machine operation and its id agree before treating the
  action as tied.
- Keep human `commit --operator-said` behavior unchanged: generic pane text
  must still name the exact proposal/card unless backed by a tied dashboard
  record.
- A valid machine `approve` with host's existing
  `--operator-said approve` must consume exactly that proposal and create one
  card.
- An approval naming a different proposal, a malformed id, a missing
  presentation, or a non-pending proposal must still fail closed.
- Return a specific machine error when consent genuinely is not tied rather
  than overloading `proposal_invalid`, which currently means malformed data.

Add regression tests that execute the real route-card tool with the same
argument shape as `RouteCardGateway`, not only a fake host command. Include
list -> detail -> machine approve -> consumed detail, duplicate approval, and
approval of a newly proposed valid record.

## Operator acceptance

None.
