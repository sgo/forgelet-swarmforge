# add-structured-quick-reply-actions

# add-structured-quick-reply-actions

# Add structured quick-reply actions

## Operator-observed need

Lieutenant answers sometimes ask the operator to reply with a literal phrase,
such as `connected and unlocked`. On the phone the operator must type that
phrase, even though chat answers already render proposal actions from
structured metadata.

The phrase is chosen dynamically by the agent, so app-side keyword parsing
would be brittle. The answer tool can instead scan for an apparent reply
instruction, challenge the agent to attach metadata, and preserve the exact
phrase as a bounded action.

## Desired behaviour

- Add one optional quick-reply action to
  `pack_dashboard_request.sh answer`.
- `--reply-with <exact text>` records a bounded action with type `reply`, the
  exact reply text and a display label.
- `--without-quick-reply` explicitly records that reply-like wording is literal
  prose and must not render an action.
- Before answering, scan for explicit patterns such as:
  - `reply with \`phrase\``;
  - `reply \`phrase\``;
  - `respond with \`phrase\``.
  This scan is advisory enforcement, not transport authority: it may challenge
  missing metadata, but no prose is ever executed or sent automatically.
- If apparent reply instructions are detected and neither explicit flag is
  present, refuse the answer with a bounded message naming `--reply-with` and
  `--without-quick-reply`.
- Permit at most one structured action per answer in this slice. A proposal
  approval action and quick-reply action cannot be emitted together.
- Validate text length, controls and unsafe framing. Reject blank text and do
  not infer consent, approval or command execution from the phrase.
- Store action metadata atomically in the request record and include it in the
  dashboard request fact projection consumed later by the host.
- Preserve current route-card action behavior when quick replies are absent.

## Layering

This card owns only the recording and dashboard-fact contract in the shared
layer. The host action projection and app button/send behavior are subsequent
cards. The apps must consume validated metadata; they must not regex-scan answer
Markdown for reply phrases.

## Testing

- Explicit quick reply records type, exact text and label.
- Literal prose override records no action even when a phrase looks actionable.
- Missing metadata on apparent reply instructions is refused with a precise
  repair command.
- Malformed, blank, oversized, multiline/control-bearing and duplicate flags
  fail closed.
- Mixing route-card and quick-reply actions is refused.
- Proposal-only, ordinary-answer and withdrawn/done request retry paths remain
  unchanged and idempotent.
- Dashboard state exposes the validated action without exposing paths,
  commands, credentials or unrelated request content.
- Run the complete layer suite, browser tests and coverage/CRAP gate.

## Boundary

Work only in `projects/forgelet-swarmforge`. Do not scan or modify the answer
body after storage, infer app behavior from prose, send a reply automatically,
change host chat semantics, or operate an active forge.

## Verification

`pack_dashboard_request.sh answer` now supports one mutually exclusive
`--reply-with <exact text>` action or the explicit `--without-quick-reply`
override. Apparent backticked reply instructions are advisory only: when no
choice is supplied, the pending request remains intact and the bounded
diagnostic names both repair flags.

Reply text is one line, control-free, at most 200 characters, and rejects
paths, credentials, shell framing and command-like text. The done record stores
the exact validated text, deterministic label and status. `/api/state` exposes
only that reviewed action descriptor; route approval and quick reply metadata
cannot coexist, including on malformed records.

Tests cover exact capture, literal override, missing metadata, malformed and
duplicate flags, unsafe text, route/quick conflicts, done-request backfill,
idempotent retry, bounded dashboard facts and all existing route-card paths.
The Gherkin contract is in
`features/add-structured-quick-reply-actions.feature`.

`bb test` passed 468 tests with 3,376 assertions plus all 21 dashboard browser
tests. `bb crap` passed the complete coverage/CRAP gate.

## Operator acceptance

- owner: operator | environment: controlled live Lieutenant answer flow | checks: confirm `reply with \`connected and unlocked\`` records the exact structured phrase and a literal-prose override records no action | status: pending
