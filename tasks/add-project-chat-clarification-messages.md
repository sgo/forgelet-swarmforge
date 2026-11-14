# add-project-chat-clarification-messages

# Add project-chat clarification messages

## Operator-observed need

Project chat now lets the operator and a project agent communicate directly.
Clarifications should use that path instead of requiring the separate
clarification store and Matrix room.

A project agent needs to create a typed chat message that asks the operator for
attention. The app will later use unresolved messages to show a dot on the
project button in the Family overview.

## Lifecycle decision

- A clarification is `open` when the project agent creates it.
- Merely viewing it may change `unseen` to `seen`, but not the clarification
  lifecycle.
- A clarification becomes `answered` when the host accepts the operator's
  project-chat response.
- The project agent does not decide whether the answer is sufficient.
- If the answer is insufficient, the agent creates a new clarification with a
  new id. It may reference the previous message/thread for context, but the
  old request remains answered.
- A failed operator send does not answer it.
- A project may explicitly withdraw an unanswered clarification.

## Required shared-layer behaviour

- Define bounded metadata for a project-authored chat message:
  - author/direction (`project-agent`);
  - `kind: clarification`;
  - stable clarification/message id;
  - lifecycle `open | answered | withdrawn`;
  - unseen/seen attention state;
  - created/answered/withdrawn timestamps;
  - optional reference to an earlier clarification for follow-up context.
- Provide a project-side tool/command that atomically writes the clarification
  into the project's own durable project-chat/request store and wakes no one
  until the operator responds through the normal project-chat path.
- Extend project chat state/read projections to include the author, kind and
  clarification metadata without changing ordinary operator/project turns.
- When the existing project-chat operator reply is accepted, atomically mark
  the addressed open clarification answered and link the operator turn
  identity. The reply still reaches/wakes the project agent normally.
- Unknown/withdrawn/already-answered clarification ids refuse safely.
- Repeated create calls with the same clarification id and content are
  idempotent; conflicting reuse is refused.
- Bounded text, safe ids, control-character rejection and no paths/commands in
  public state.
- Expose a machine-readable unresolved/latest-change fact so the host can later
  derive per-project attention without scanning arbitrary text.

## Compatibility

- Existing operator-to-project chat and project-agent answers stay readable.
- Existing old clarification records remain available during migration.
- Do not remove the old clarification tool or Matrix room in this card.
- Project restart and dashboard restart preserve clarification lifecycle.

## Testing

- Create an open clarification, read it through project state, and prove it
  carries author/kind/lifecycle and can be visibly seen.
- Reply through ordinary project chat and prove the clarification becomes
  answered on acknowledgement, even when the agent later disagrees.
- Failed reply delivery leaves it open and retryable.
- A second clarification gets a distinct id and leaves the first answered.
- Withdrawal, duplicate create, conflicting create, malformed ids/text and stale
  reply refusals.
- Restart persistence, multiline Markdown, redaction and bounded state size.
- Preserve existing general/project chat and card-activity records.

## Boundary

Work in the shared SwarmForge/project-dashboard layer. Do not implement app dots
or host aggregation in this card, remove the existing clarification system yet,
add a second unrestricted message store or infer attention from message text.

## Verification

Project chat now stores typed clarification records in the existing durable
request store. The project-side command creates them atomically without waking
an agent; project and Family state expose their lifecycle, attention, linkage
and compact attention facts; and an ordinary operator reply wakes the agent and
marks the addressed open record answered. Failed delivery leaves the record
open, while stale, unknown, conflicting and unsafe inputs are refused. Legacy
chat, legacy clarifications and the Matrix path remain available.

`bb test` passed 439 tests with 2,906 assertions plus all 17 dashboard browser
tests. The browser scenario also proves the answered clarification survives a
dashboard restart. `bb crap` passed the full Cloverage/CRAP gate and regenerated
`target/coverage/lcov.info`. No active Forgelet forge was stopped, restarted,
composed or modified.

## Operator acceptance

None.

## Recovery context

An earlier, unauthorized creation of this task began implementation before the
approval mistake was caught. Its agent was stopped before commit. Recoverable
uncommitted changes remain in the `forgelet-swarmforge` worktree:

- `swarmforge/scripts/pack_web.bb`;
- `test/swarmforge/pack_web_test.bb`;
- the untracked task note.

If this proposal is approved, review that draft against the specification,
retain only correct work, and rerun the full gates. Do not assume the draft is
complete or create a second overlapping message store.
