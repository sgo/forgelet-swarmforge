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

`qa/run-runtime-identity-acceptance.sh` composes from one explicit source commit
using a local Git clone, a fixture product pack and a failing fake curl. It
verified all 102 runtime manifest files against source bytes, the manifest
digest, source commit and `preserve-installed-bridge-v1` policy. Bridge
installation remained absent, no Host API was called, no live Host token or
bridge configuration was read, and no Matrix endpoint was contacted.

Tests poisoned the Host token, Host URL, bridge configuration, homeserver data
directory and operator room membership with sentinel files. Success, invalid
source failure and SIGTERM interruption all returned without changing any
sentinel and without leaving an acceptance work directory. The interruption
test proved the cleanup trap runs while fixture work is active.

The full `bb test` gate passed 540 tests with 4,329 assertions, all 20 dashboard
browser tests, and the bounded-projection E2E; the detached lifecycle E2E
completed in 1,567 ms. The `bb crap` clean gate passed 24 tests with 132
assertions and wrote `target/coverage/lcov.info`. The documentation contract
passed 3 documents and 5 sections.

No live Host or Matrix operation was performed by these automated gates.
Released app/Host creation remains a separately invoked operator acceptance
check.

## Operator acceptance

- owner: operator | environment: released Host and live Matrix | checks: explicitly requested live app/Host creation | status: pending
