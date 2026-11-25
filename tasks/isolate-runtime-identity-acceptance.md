# isolate-runtime-identity-acceptance

# Isolate runtime identity acceptance

## Operator-observed problem

While verifying `compose-new-forges-from-one-runtime-commit`, the project agent
created a real Family through the live Host:

`Runtime Identity Check 20261125 2158`

The live bridge provisioned a Matrix space and four rooms and invited the real
operator. The Host removed the Family successfully, but the Matrix rooms and
pending invites survived, so the operator received test-room invitations.

## Desired outcome

- Source and agent gates never create forges, rooms or invitations on the live
  Host or live Matrix homeserver.
- Runtime identity acceptance uses only disposable roots and either:
  - a direct hermetic composer test with no Host/Matrix boundary; or
  - a disposable Host provisioning fixture with a fake bridge/homeserver and a
    fixture operator.
- “Disposable Family” means a disposable end-to-end fixture, not a throwaway
  name on the operator’s live Host.
- Pass, failure and interrupted cleanup leave the live bridge/control
  configuration and operator room set unchanged.
- Real app/Host creation remains an explicit operator acceptance action after
  release.

## Plan

1. Add a hermetic runtime-composition acceptance script under
   `forgelet-swarmforge`.
2. Create a fresh forge root under the project’s temporary directory, invoke
   the composer with an explicit source commit, skip bridge installation and
   verify manifest identity, affected file hashes and bridge-policy evidence.
3. Do not call the Host API, do not read the live Host token and do not contact
   the live Matrix homeserver from that script.
4. If real Host creation needs automated coverage, add a runner that uses the
   Host fixture with a temporary homeserver, temporary bridge config/root and a
   fixture operator. It must be impossible for that runner to fall back to live
   `matrix-bridge.json` or the operator home.
5. Add sentinel fixtures that snapshot the live bridge configuration, control
   registry, homeserver data directory and operator room membership for test
   isolation checks.
6. Update the runtime-card guidance so post-release live app creation is named
   operator acceptance and is not attempted automatically by the project agent.
7. Ensure cleanup traps remove temporary Host, bridge and homeserver state and
   do not rely on forge removal alone to delete Matrix history.

## Testing and evidence

- Direct composition verifies exact commit/digest/file identity without a Host
  or Matrix network call.
- A poisoned live Host token, Host URL or bridge configuration cannot be
  selected by the hermetic test.
- Host fixture acceptance creates no room in the live homeserver and sends no
  invite to the live operator.
- Live bridge/control/home-server sentinels are unchanged after pass, failure,
  timeout and interrupted cleanup.
- The full layer gate and dashboard tests remain green.
- The explicit operator acceptance step still proves a released app/Host
  creation when the operator chooses to run it.

## Boundaries

- Do not delete unrelated live Matrix rooms as part of this fix.
- Do not migrate or rewrite bridge state.
- Do not weaken runtime identity, digest or bridge-policy validation.
- Do not treat a successful Family removal as proof that Matrix rooms or
  invitations were cleaned.
- Do not run live app/Host acceptance without an explicit operator request.

## Verification

Automated runtime identity acceptance completes without modifying the operator’s
live Host, homeserver, bridge registry, control registry or room invites.

## Operator acceptance

None in source. Live app/Host creation remains a separately invoked operator
check.
