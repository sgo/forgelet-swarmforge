# correct-cross-forge-request-disclosures

# Correct cross-forge request disclosures

## Operator-observed problem

Issues #13, #7 and #3 were closed with `Delivered` comments that imply each
receiving forge independently owned and executed the rollout. In fact, the
`forgelet-swarmforge` project agent composed all three roots, restarted every
open project and session, and closed the issues centrally without receiving-forge
operator approval.

## Desired correction

Add one factual correction comment to each issue:

- https://github.com/sgo/saibill-forge/issues/13
- https://github.com/sgo/innersource-forge/issues/7
- https://github.com/sgo/ledgerhall-forge/issues/3

Each comment must state:

- the technical rollout did occur and its environment results were real;
- the work was performed centrally by the Forgelet layer project, not by the
  receiving forge's lieutenant;
- the receiving forge did not make an independent execution decision;
- no receiving-forge card was approved before the restart/deployment;
- this is disclosed as an ownership and authorization correction, not as new
  work.

Do not reopen the issues or rewrite their existing comments. Reopening requires
separate operator direction.

## Testing

- Capture the exact issue URLs, comment ids and final open/closed state.
- Verify no duplicate correction comment is posted on retry.
- Keep tokens and local paths out of the posted text.

## Out of scope

Changing the completed rollout, reopening issues, approving the rollout after
the fact, or implementing the local cross-forge approval gate covered by the
existing `require-local-approval-for-cross-forge-requests` proposal.
