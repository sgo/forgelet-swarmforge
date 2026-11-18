# recognize-confirmation-requests-as-quick-reply-invitations

# Recognize “tell me” as a quick-reply invitation

## Operator-observed problem

Lieutenant answers twice asked the operator to perform a device action and
“tell me” when it was complete, but omitted the structured quick-reply metadata
that renders a button in the app.

The current request helper challenges apparent backticked instructions using
`reply with`, `reply`, or `respond with`. Natural confirmation requests using
`tell me` bypass that reminder.

## Decision

Decision (operator, 2026-11-18): use the shorter literal phrase `tell me` as the
reminder trigger rather than adding a separate family of longer confirmation
phrases.

The helper must continue to require explicit `--reply-with` metadata or the
`--without-quick-reply` override. It must never infer, send, or approve an action
from prose alone.

## Desired behavior

- Detect case-insensitive `tell me` in an answer as an apparent request for an
  operator response, including wording such as `tell me when`.
- When neither structured metadata nor the literal-prose override is supplied,
  refuse the answer with the existing bounded quick-reply diagnostic.
- Preserve the existing explicit `reply with`, `reply`, and `respond with`
  detection.
- Preserve explicit quick-reply text, validation, exact-text round-tripping,
  action availability, and route-card/quick-reply mutual exclusion.
- Do not extract or guess reply text from `tell me` prose; the agent must choose
  and attach the exact text.

## Testing

- `tell me` and `tell me when` produce the missing-metadata challenge.
- Case variants are detected without broadening to arbitrary conversation.
- `--reply-with` records one exact validated action and satisfies the challenge.
- `--without-quick-reply` records literal prose as currently specified.
- Unsafe, multiline, path-containing, credential-like, command-like, and
  oversized reply metadata remains refused.
- Existing route-card approval behavior and action exclusivity remain green.
- The help text names the broader `tell me` reminder clearly.

## Documentation and operator guidance

Document `tell me` as a reminder trigger, not an action authority. Actionable
confirmations should be phrased as `Reply with "Done"` and submitted with
`--reply-with "Done"`.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not infer quick replies from prose,
auto-send a reply, relax route-card approval, or modify app presentation.

## Verification

Focused tests prove that natural `tell me` requests trigger the challenge and
that explicit metadata remains the only way to render a quick-reply action.

## Operator acceptance

None.
