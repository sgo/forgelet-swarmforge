# keep-logged-out-claude-session-attachable

# Keep logged-out Claude session attachable

## Operator-observed need

The operator cannot sign in to the disposable Claude project:

`forge-attach saibill-forge saibill-detached-smoke agent`

starts the relay, then reports that no `swarmforge-agent` session appeared.
The launcher has correctly detected `Not logged in · run /login`, but it then
stops that agent session. This removes the only terminal where the operator
can run `/login`.

## Desired outcome

When the exact Claude login-required banner is visible, leave the agent session
running and attachable. Do not send login keystrokes, collect credentials or
deliver work requests. Resume normal readiness handling after the operator
authenticates.

## Plan

1. Return a distinct login-required state from readiness settlement instead of
   treating it as a generic failure that kills the session.
2. Keep the session alive, suppress request delivery while logged out, and
   preserve the credential-free diagnostic and guidance.
3. Restart only `saibill-detached-smoke`, verify `forge-attach` reaches the
   logged-out Claude terminal, then verify normal readiness and one bounded
   detached-stage completion after operator login.

## Boundaries

- Never read, print, store or inject Claude credentials.
- Do not automate the login flow or enter `/login` on the operator's behalf.
- Do not alter permission policy.
- Keep unrelated prompts fail-closed and do not keep arbitrary failed sessions
  alive indefinitely.
- Do not restart other projects or an active forge.

## Testing and evidence

- Focused tests prove the exact login-required banner does not kill the agent
  session, does not send keystrokes, does not deliver pending work and becomes
  ready after the same pane shows an authenticated composer.
- A generic timeout and unrelated prompt retain the existing failure behavior.
- Live acceptance is limited to `saibill-detached-smoke` and requires the
  operator to complete login in the attached terminal.

## Verification

`forge-attach` reaches a live logged-out Claude terminal, `/login` remains
available to the operator, no request is delivered prematurely, and after
authenticated readiness the disposable project completes one one-or-two-second
detached-stage check with one wake and no next-card promotion.

Focused layer fixtures pass 26 assertions: login-required is distinct from
generic failure, no keystrokes are sent, pending work is held, the marker is
cleared after authenticated readiness, and unrelated/timeout paths still fail.
Live acceptance against only `saibill-detached-smoke` kept the Claude session
alive and attachable, resumed readiness after operator authentication, and ran
one detached smoke command for one second. Its wake was delivered and answered
exactly once, evidence was recorded, and the project returned to `done`/idle
without promoting another card. No credentials were read, printed, stored or
injected.

## Operator acceptance

None.
