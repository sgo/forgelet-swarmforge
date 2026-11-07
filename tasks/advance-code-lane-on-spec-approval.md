# advance-code-lane-on-spec-approval

# Advance the board to code when the spec is approved

Single-agent projects approve their own first spec mechanically. That is still a
recorded spec approval, so it should advance the visible card in the same way an
operator approval does.

In `swarmforge/scripts/stage.bb`, make every successful spec approval use one
shared lifecycle transition that moves the active card's board lane from `spec`
to `code`. Cover both paths:

- `stage approve <proposal> --words ...` for operator approval;
- automatic approval inside `stage propose spec` for a single-agent project.

This is a visibility convenience only. Moving the card to `code` must not record
code evidence, mark the code stage complete, run the code gate, fabricate stage
status, alter the agent-runner prompt sequence, or block implementation edits.
The code stage remains current/todo until the agent actually runs
`stage check code`; the gates remain useful checklist steps rather than rigid
workflow permissions.

Do not add file watchers, editor hooks, pre-edit restrictions, extra commit
restrictions, or a multi-turn spec/implementation prompt protocol. If an agent
has already edited implementation files before recording spec approval, leave
the board in `spec`; once the approval event occurs, advance it to `code`.

Add regressions proving:

- operator approval moves a single-agent card from `spec` to `code`;
- automatic single-agent approval moves it from `spec` to `code` through the
  same shared transition;
- approved spec evidence remains recorded;
- code evidence and completion state remain absent;
- no stage command is executed merely by moving the lane;
- pack-project approval behavior is unchanged;
- a correction approval follows the same rule without inventing code evidence;
- malformed or absent active-card state reports a precise error rather than
  moving an unrelated card.

Update the layer documentation to describe spec approval as the start of the
visible code phase. Preserve unrelated uncommitted shared-script refresh changes
in this checkout. Run all layer unit, property, acceptance, dashboard,
architecture, CRAP/DRY, mutation, documentation and upgrade-exercise gates
before committing and pushing. Deployment waits for the higher-priority
`queue-and-promote-single-agent-cards` card to land and be composed.

## Verification boundary

This layer checkout has no property suite, architecture module, Gherkin
acceptance tree, documentation contract, mutation manifest, or application
mutation profile. Its supported gates are `bb test` (including dashboard and
upgrade exercise) and `bb crap`; the family DRY tool and mutation scan can cover
changed Clojure tests and Babashka modules but are not substitutes for missing
project gates.
