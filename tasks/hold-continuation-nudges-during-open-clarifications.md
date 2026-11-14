# hold-continuation-nudges-during-open-clarifications

# Hold agent continuation nudges during open clarifications

## Operator-observed problem

The `forgelet-host` agent used the legacy clarification mechanism to ask about
a missing layer contract. While that clarification was unresolved, the
agent-runner treated the already-delivered card as idle and sent a continuation
nudge. Receiving that nudge encouraged the agent to seek another way forward,
including filing an unnecessary GitHub issue.

An unresolved clarification is an intentional wait for operator input. It must
not be interpreted as a stalled card, and a continuation nudge must not prompt
the agent to bypass the question or start a parallel workaround.

## Desired behaviour

- Before sending a continuation nudge for a delivered routed request, inspect
  the same project's authoritative pending request/chat records for at least
  one valid clarification whose lifecycle is `open`.
- If an open clarification exists, suppress every continuation nudge for that
  project, including later cooldown expirations.
- Marking a clarification seen does not release the hold. Only `answered` or
  `withdrawn` removes it from the unresolved set.
- Correlations and reads stay scoped to the agent's project; an open
  clarification in another project must not suppress nudges.
- Creating a typed project-chat clarification still performs no wake. The
  operator's eventual project-chat reply remains the delivery that resumes the
  agent.
- After the clarification is answered or withdrawn, normal nudge scheduling
  resumes from fresh activity rather than immediately firing for elapsed idle
  time.
- Update continuation text so a blocked agent is directed to the new typed
  project-chat clarification command, not the legacy clarification channel.
- Malformed or ambiguous clarification state must fail closed for nudge
  suppression and be observable in the runner log, rather than risking a
  bypass-inducing nudge.

## Implementation shape

Add a read-only clarification-state resolver to `agent-runner` over the project
request store used by typed project chat. Reuse its parsed `kind`, `status`,
attention and clarification identity fields rather than scanning message prose.

Pause `handle-delivered-request!` before its idle/cooldown condition when an
open clarification exists. Record the hold in delivery state/log with bounded
detail so operators can distinguish "waiting for clarification" from "agent
idle". Clear or refresh that marker when the clarification resolves.

Do not wake an agent merely because the clarification becomes seen. Do not send
a hidden synthetic answer to unblock the runner.

## Testing

- A delivered request with one open unseen clarification sends no nudge after
  both the idle threshold and cooldown, repeated across runner passes.
- Mark-seen leaves the hold active.
- Answered and withdrawn states release the hold without an immediate stale
  nudge.
- Mixed open/answered clarifications remain blocked while any open one exists.
- A clarification in another project does not affect this project.
- No clarification preserves existing delivery/nudge behavior.
- Malformed records fail closed and produce one bounded diagnostic.
- The clarification creation path sends no wake, and the operator's answer
  remains the first wake.
- The updated nudge text names the typed project-chat clarification command and
  does not mention the legacy clarification store.
- Exercise the real runner state machine with a fake tmux session and temporary
  project root; use no live agent, real Matrix service or operator data.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not remove the legacy clarification
store or Matrix bridge in this card, change clarification lifecycle semantics,
change route-card delivery, or weaken busy/idle detection. Full retirement of
the old clarification system remains a later card after host attention, app
rendering/answer flow and migration parity are verified.

## Verification

The agent-runner never sends a continuation nudge while the selected project
has an open typed clarification, resumes normally after resolution, and directs
blocked agents to the new project-chat clarification path.

Three fake-tmux state-machine fixtures cover repeated idle and cooldown passes,
seen-but-open, mixed open/answered, answered and withdrawn release with fresh
activity, first-wake operator chat delivery, project scoping, malformed and
duplicate clarification state, and the typed clarification command in the
continuation text.

`bb test` passed 453 tests with 3,225 assertions plus all 17 dashboard browser
tests. `bb crap` passed the complete coverage/CRAP gate.

## Operator acceptance

None.
