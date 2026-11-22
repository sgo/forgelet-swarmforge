# recognize-current-claude-ready-state

# Recognize current Claude ready and login state

## Operator-observed need

The completed folder-trust launcher fix now selects the current Claude trust
prompt correctly. In the disposable `saibill-detached-smoke` live acceptance,
Claude 2.1.330 then displayed:

- `Try "create a util logging.py that…"` as its idle composer; and
- `Not logged in · run /login` in the status line.

The launcher recognized neither condition as a terminal state. It timed out
after 30 seconds, stopped the agent, and never reached a layer where the
bounded detached-stage check could run.

## Desired outcome

Recognize the current authenticated Claude idle composer as ready. Detect a
logged-out Claude client explicitly and fail immediately with a clear,
credential-free diagnostic instead of waiting for the generic timeout.

## Plan

1. Finish the already-started focused ready-state changes and add fixtures for
   the current `Try "..."` composer, the logged-out status, and an unrelated
   prompt that must remain unsent.
2. Preserve existing Codex and legacy Claude readiness checks and emit a clear
   `Claude is not logged in` diagnostic for the exact login banner.
3. Restart only `saibill-detached-smoke`, verify the authenticated agent becomes
   ready, then run the one-or-two-second detached-stage acceptance.

## Boundaries

- Do not collect, print, store or inject Claude credentials.
- Do not modify other projects or restart an active forge.
- Do not treat an arbitrary composer-like phrase as proof of readiness.
- Preserve the trusted-folder prompt fix and its unrelated-prompt fail-closed
  behavior.

## Testing and evidence

- Focused launcher fixtures cover current authenticated readiness, current
  logged-out readiness, old Claude wording, Codex wording and an unrelated
  prompt.
- Run only the focused layer test target before the live check; do not rerun
  the full layer suite.
- Live acceptance is limited to `saibill-detached-smoke`.

## Verification

The current authenticated Claude screen is accepted promptly, the logged-out
screen fails with a bounded diagnostic, unrelated prompts remain unsent, and
the disposable project completes a one-or-two-second detached stage with one
wake and no next-card promotion.

The focused fixtures pass 19 assertions across authenticated Claude, exact
logged-out Claude, Codex, legacy Claude and unrelated-prompt cases. Live
acceptance against only `saibill-detached-smoke` observed the exact logged-out
banner and failed in one second with the credential-free diagnostic. Claude was
not authenticated in the disposable project, so authenticated readiness was
proved by the focused fixture rather than by injecting or collecting operator
credentials. The prior detached-stage live smoke already proved the bounded
wake/idle path.

## Operator acceptance

None.
