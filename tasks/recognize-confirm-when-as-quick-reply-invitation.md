# recognize-confirm-when-as-quick-reply-invitation

# Recognize confirm when as a quick-reply invitation

## Operator-observed problem

An app-chat answer asked the operator to connect and unlock the iPhone and
“confirm when it is ready.” The answer helper did not recognize that direct
confirmation request, so the answer was accepted without quick-reply metadata.

The current detector recognizes backticked reply instructions, `tell me`, and
quoted `say` text, but not `confirm when`.

## Decision

The operator agreed on 2026-11-25 to use **`confirm when`** as the recognized
quick-reply invitation. This decision is intentionally narrow; it does not add
all imperative uses of the word “confirm.”

## Desired outcome

- A case-insensitive `confirm when` request triggers the existing quick-reply
  challenge.
- Existing `tell me`, quoted `say`, and backticked reply-instruction behavior is
  unchanged.
- The helper still requires explicit `--reply-with <text>` or
  `--without-quick-reply`; it never infers the answer text from prose.
- Bare `confirm` and `confirm that` are not newly recognized by this card.
- Descriptive technical uses without the exact `confirm when` phrase are not
  affected.

## Plan

1. Extend the apparent-confirmation-request detector with a bounded,
   case-insensitive `\bconfirm\s+when\b` match.
2. Keep the existing quick-reply challenge and CLI diagnostics unchanged.
3. Add positive cases for exact, mixed-case, sentence and multiline uses.
4. Add negative cases for bare `confirm`, `confirm that`, `confirms`, and
   descriptive prose that does not contain `confirm when`.
5. Verify a device-readiness answer using the exact failed wording now requires
   reply metadata.

## Testing and evidence

- `confirm when it is ready` requires `--reply-with` or `--without-quick-reply`.
- `CONFIRM WHEN READY` behaves the same.
- `Please connect it and confirm when ready.` is recognized.
- `confirm` and `confirm that it is ready` remain unflagged by this card.
- Existing tell-me, say, reply-with, approval and action exclusivity tests stay
  green.
- The full layer test and dashboard suites remain green.

## Boundary

- Do not infer a reply value.
- Do not broaden the detector to every use of “confirm.”
- Do not weaken answer-file redaction, safe quick-reply framing, or action
  exclusivity.
- Do not change route-card approval behavior.

## Verification

The detector now recognizes case-insensitive `confirm when` while preserving
the existing reply-instruction, `tell me`, quoted `say`, approval and action
exclusivity behavior. Exact, mixed-case, sentence and multiline invitation
tests require either `--reply-with` or `--without-quick-reply`; the helper does
not infer reply text. Bare `confirm`, `confirm that`, `confirms` and
descriptive confirmation prose remain unflagged.

The focused quick-reply suite passed 4 tests with 102 assertions. The full
`bb test` gate passed 541 tests with 4,367 assertions, all 20 dashboard browser
tests, and the bounded-projection E2E; the detached lifecycle E2E completed in
1,533 ms. The `bb crap` clean gate passed 24 tests with 132 assertions and wrote
`target/coverage/lcov.info`. The documentation contract passed 3 documents and
5 sections.

## Operator acceptance

None.
