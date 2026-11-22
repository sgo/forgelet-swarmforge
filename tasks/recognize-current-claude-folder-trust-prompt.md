# recognize-current-claude-folder-trust-prompt

# Recognize the current Claude folder-trust prompt

## Operator-observed need

The operator created the disposable `saibill-detached-smoke` project for the
live detached-stage check. Opening it failed with:

`the relay is running but no swarmforge-agent session appeared`

The project's `.swarmforge/start.log` shows Claude 2.1.328 presenting:

`Quick safety check: Is this a project you created or one you trust?`

with the visible action `Yes, I trust this folder`. `agent-runner.bb` only
recognizes the older text `Trust this folder?`, waits for the full readiness
timeout, and stops the agent session.

## Desired outcome

Teach the project launcher to recognize the current Claude folder-trust prompt
and select its visible affirmative choice. Unrelated prompts must remain
visible and fail rather than receiving guessed keystrokes.

## Plan

1. Add a focused fixture with the current two-line Claude prompt and assert the
   launcher selects `Yes, I trust this folder`.
2. Retain the existing prompt handling and readiness behavior; make the match
   specific to the trust prompt rather than a generic keyboard sender.
3. Restart only the disposable project's runner, verify the project agent
   reaches its normal prompt, then run the one-or-two-second detached-stage
   acceptance check.

## Boundaries

- Do not edit Claude's global configuration directly.
- Do not bypass folder trust or weaken permission policy.
- Do not restart other projects or an active forge.
- Preserve prompt text in diagnostics without exposing credentials.

## Testing and evidence

- A focused launcher fixture covers the old wording, the current wording, and
  an unrelated prompt that must remain actionable rather than auto-confirmed.
- Live acceptance uses only the disposable `saibill-detached-smoke` project.
- The detached job completes in one or two seconds and wakes exactly once.

## Verification

The fixture proves only the known trust prompt is accepted, the disposable
project reaches `Ask Claude to do anything` or its equivalent ready marker,
and the detached stage reports `waiting-for-job`, completes, wakes once, and
returns the project to idle without promoting another card.

## Operator acceptance

None.
