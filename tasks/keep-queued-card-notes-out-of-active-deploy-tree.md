# Keep queued card notes out of the active deploy tree

## Operator-observed problem

An active app card reached deploy with a reviewed commit and all earlier gates
passing. Deploy refused because `tasks/` contained three untracked notes owned
by other queued cards. The agent correctly did not delete another card's notes.

The route-card gate already copies each note to ignored
`.swarmforge/board/<task>.txt` when it creates a board row. Writing a second,
tracked `tasks/<task>.md` copy while the card is only queued makes every later
card's deploy look dirty until all queued notes happen to be committed by their
future cards.

## Desired behaviour

Choose and implement one fail-closed mechanism:

1. Prefer keeping a queued card's note only in ignored board state. Materialize
   `tasks/<task>.md` when the project begins that card, so the active card's
   reviewed tree contains only its own note.
2. If tracked notes must remain, make the shared deploy cleanliness check
   exclude only exact untracked notes whose names and bytes match other queued
   board rows. Any unmatched untracked file or modification still fails.

The mechanism must work for single-agent projects and existing board rows. It
must not hide, delete or rewrite another queued card's note.

## Testing

- One active card plus multiple queued cards yields a clean deploy verdict while
  preserving every queued note.
- Arbitrary untracked files, modified tracked files, staged changes, an active
  card's missing/uncommitted note and malformed board state still fail.
- Queue promotion materializes or validates the correct note exactly once.
- Queue cancellation/removal retains recoverability and does not leave a
  tracked orphan.
- Existing card routing, completed-card cleanup and legacy rows remain
  compatible.
- Exercise a disposable project end to end and run the complete layer suite,
  browser tests and coverage/CRAP gate.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not delete queued notes, weaken the
deploy requirement for a genuinely dirty reviewed tree, commit another project's
source, or operate an active forge.

## Verification

- `bb test`: 475 tests, 3,486 assertions, 0 failures, plus 21 browser tests.
- `bb crap`: complete coverage and CRAP gate passed.
- Disposable lifecycle tests cover multiple queued notes with a clean active
  deploy, promotion materialization and validation, malformed board state,
  arbitrary dirty-tree failures, and cancellation recovery.

## Operator acceptance

- owner: operator | environment: disposable single-agent project | checks: create two queued cards while an earlier card reaches deploy and confirm deploy passes while both queued notes remain recoverable until promotion | status: pending
