# recognize-current-claude-activity

# Recognize current Claude idle activity

## Operator-observed problem

When the operator uses the completed-card report for the Saibill Family, the
app shows `Activity unknown`. Saibill's Lieutenant runs Claude rather than
Codex.

Live evidence:

- `/api/state` reports `alive: true`;
- it also exposes a valid current status line;
- the state is `unknown`;
- the Claude pane is visibly idle at:

```text
❯
Sonnet … | Context … | …
⏵⏵ bypass permissions on …
```

## Confirmed cause

`pack_web.bb` recognizes idle through `activity-idle-pattern`. That pattern
handles older/Codex-style clues including `Ask Codex to do anything`,
`? for shortcuts`, `enter:send esc:cancel`, and explicit ready/idle text. The
current Claude Code UI no longer ends with those clues; it shows a blank `❯`
composer prompt and the bypass-permissions footer. Neither is recognized, so a
live idle Claude session reports `unknown`.

The activity parser already recognizes Claude's working output through
`esc to interrupt`. The missing case is specifically the current idle footer.

## Desired behaviour

- A live Claude pane showing its current blank composer prompt and
  bypass-permissions footer reports `idle`.
- A live Claude turn still reports `working` when Claude shows its current
  working marker, even though the interactive footer remains present.
- Existing Codex, older Claude, Grok and generic ready/idle detection remains
  unchanged.
- `unavailable` remains reserved for a missing session or fallback shell.
- Genuinely unrecognized live output remains `unknown`; do not default every
  alive backend to idle.
- Keep the bounded status-line redaction and heat behavior unchanged.

## Testing

- Add an activity fixture containing the current Claude idle footer and prove
  state `idle` with heat zero.
- Add a current Claude working fixture containing both its
  `esc to interrupt` marker and the persistent footer; prove `working` wins.
- Cover a blank modern Claude prompt with and without surrounding transcript.
- Retain tests for older Claude `? for shortcuts`, Codex idle prompts, working
  output, unavailable sessions, stale status lines and malformed output.
- Run the complete source-layer tests and the dashboard browser tests.

## Rollout

After the source-layer fix is pushed, run the normal `get-swarm-forge` rollout
and recompose project copies. Verify the live Saibill Family reports `idle` and
then `working` during a report turn.

## Boundary

Work in `forgelet-swarmforge` and the smallest necessary shared dashboard
surface. Do not special-case Saibill, classify live unknown output as idle by
default, add transcript-file access to the dashboard or change chat delivery.

## Verification

The activity classifier now recognizes Claude's current
`⏵⏵ bypass permissions on` footer as idle when it is the current marker. A
simultaneous `esc to interrupt` marker still wins as working, while the footer
alone remains the fallback idle evidence after a turn completes. A blank modern
Claude composer without that footer remains unknown.

The fixture covers the footer with and without surrounding transcript, the
working marker plus persistent footer, and the unrecognized blank composer.
Existing Codex, older Claude, Copilot, Grok, unavailable and malformed-output
tests remain in the suite.

`bb test` passed 446 tests with 3,047 assertions plus all 17 dashboard browser
tests. `bb crap` passed the complete coverage/CRAP gate.

The normal `get-swarm-forge` rollout was composed into the Saibill forge, and
all 13 open project copies were refreshed and committed through managed-runtime
sync. The forge root and every project copy carry the same `pack_web.bb` hash as
the source.

After restarting only the Saibill dashboard, its live `/api/state` reported
`alive: true`, `state: idle`, `heat: 0`, and the current permissions footer as
the bounded status line. The Claude Lieutenant session was left running.

## Operator acceptance

- owner: operator | environment: Forgelet app with live Saibill Family | checks: confirm Idle while the Claude Lieutenant is idle and Working during a report turn | status: pending
