# prefer-active-lieutenant-output-over-static-prompts

# prefer-active-lieutenant-output-over-static-prompts

# Prefer active Lieutenant output over static prompts

## Operator-observed problem

In the desktop app, a message sent to the Saibill and Ledgerhall lieutenants
shows `Waiting · Lieutenant idle` while the lieutenant is working. Forgelet's
Lieutenant shows the working indicator correctly. Saibill uses Claude and
Ledgerhall uses Codex, so the failure is not backend-specific. The phone uses a
different rendering and appears to show Saibill activity.

## Confirmed classifier gaps

Live Saibill output while busy includes:

```text
✢ Honking… (6s · ↓ 549 tokens · thinking)
```

The current working pattern accepts `✻`, `✽`, `✶` or `✳` with
`esc to interrupt`, plus other older markers, but not the current `✢` thinking
line. The persistent Claude permissions footer can then be selected as idle.

The Codex UI can show both:

```text
• Working (3s • esc to interrupt)
...
› Ask Codex to do anything
```

Marker selection takes the later of the working and idle matches. The static
input prompt is later, so it overrides the current working evidence. Claude's
persistent permissions footer has a special precedence rule; the equivalent
Codex prompt does not.

## Desired behaviour

- Recognise the current Claude `✢ ... thinking` working marker with the same
  bounded semantics as the existing Claude spinner markers.
- When a recognised working marker and a known persistent idle prompt/footer
  both occur in the captured tail, working wins while the active marker is
  current.
- Static Claude and Codex composer/footer lines do not override current work.
- When the pane contains only the current idle prompt/footer, report `idle`.
- If no recognised current marker exists, remain `unknown`; do not classify
  every alive backend as idle or working.
- Preserve status-line redaction, heat calculation, unavailable-session
  handling and bounded output.

## Testing

- Add fixtures for the current Claude thinking line with and without the
  persistent permissions footer.
- Add Codex fixtures with active output plus the static input prompt, and with
  only the idle prompt.
- Cover transitions working -> idle and idle -> working across turns.
- Cover older Claude, Copilot, Grok, malformed output, unavailable sessions and
  unknown output.
- Prove `/api/state` reports `working` for the complete active interval and
  `idle` after completion.
- Run the complete layer, browser and coverage/CRAP checks.

## Boundary

Work only in `projects/forgelet-swarmforge`. Do not special-case a forge or
backend, inspect transcripts outside the bounded pane tail, default unknown
output to working, or restart an active forge. Deployment to Saibill,
Ledgerhall and other forges is a separate operator/Lieutenant rollout after the
source is committed and verified.

## Verification

The bounded classifier now recognizes current Claude `✢ … thinking` output and
prefers current work over persistent Claude, Codex, Copilot and Grok composer
or footer chrome. Non-static completion markers still return the Lieutenant to
idle, and unrecognized output remains unknown.

Fixtures cover Claude thinking with and without the permissions footer, Codex
working with a static prompt, prompt-only idle, idle-to-working and
working-to-idle transitions, `/api/state` across a complete Claude active
interval, older agent markers, malformed/unknown output, unavailable sessions,
heat and status redaction.

`bb test` passed 454 tests with 3,239 assertions plus all 17 dashboard browser
tests. `bb crap` passed the complete coverage/CRAP gate.

## Operator acceptance

- owner: operator | environment: composed Saibill and Ledgerhall dashboards on desktop and phone | checks: send one Lieutenant message and confirm working during the turn followed by idle after the response | status: pending
