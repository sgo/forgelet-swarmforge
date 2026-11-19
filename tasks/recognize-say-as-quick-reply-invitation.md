# recognize-say-as-quick-reply-invitation

# Recognize say-quoted instructions as quick-reply invitations

## Operator-observed problem

An Innersource/Saibill Lieutenant response used:

```text
say "route fix-github-packages-token-helper, remove the calls"
```

That is an apparent instruction for the operator to send an exact phrase, but
the request helper does not recognize `say "..."` as an invitation to attach a
quick-reply action.

## Decision

Decision (operator, 2026-11-19): recognize bounded double-quoted `say "..."`
instructions as apparent quick-reply invitations.

This recognition is advisory only. It must never infer, send or approve
anything from prose. A quoted route-like phrase is not route-card approval and
must not select a proposal.

## Desired behavior

- Detect a bounded `say "exact text"` instruction and require either
  `--reply-with` or `--without-quick-reply`.
- Preserve existing backticked `reply with`, `reply`, `respond with` and
  `tell me` reminder behavior.
- Do not infer the quick-reply text from the `say` instruction; the agent must
  explicitly choose and submit it through `--reply-with`.
- Keep route-card approval and quick-reply metadata mutually exclusive.
- Do not treat quoted third-party text as approval or execution authority.

## Testing

- `say "route fix-github-packages-token-helper, remove the calls"` triggers the
  missing-metadata challenge.
- Explicit `--reply-with` with that exact text records one quick reply and does
  not invoke route-card approval.
- `--without-quick-reply` records literal prose with no action.
- Missing quotes, multiline text, oversized text, paths, credentials, commands
  and unsafe framing fail bounded.
- Existing reply/tell-me tests and route-card/quick-reply exclusivity remain
  green.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not automatically execute or route
the quoted phrase, infer operator consent, or weaken the card gate.

## Verification

Quoted `say` instructions produce the same explicit metadata challenge as other
apparent reply invitations, with no inferred action or route approval.

## Operator acceptance

None.
