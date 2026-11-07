# queue-and-promote-single-agent-cards

# Queue approved cards and promote the next one on completion

Single-agent projects currently put every routed card directly in `spec`.
Creating another card while one is active therefore creates two apparent active
cards, and `stage` cannot choose safely. The agent-runner can deliver a pending
dashboard request, but card completion does not explicitly promote queued work,
so a waiting card can be overlooked.

Add a durable, ordered queued-card lifecycle:

- `queued` before `spec` is a board state, not a gate. A queued card carries its
  created time and task id but is not the active card and does not drive the
  project-detail header or gate rail.
- Define **project idle** as having no active card: every non-done card is
  `queued` and no card occupies a gate lane or `spec`. This is independent of
  whether the agent process happens to be sitting at its prompt.
- Define **agent ready** as the agent-runner's prompt-level condition: the
  current turn is not busy and the pane can accept a new prompt. An agent can be
  ready while a card remains active; that project is not idle.
- A single-agent `route_card` creation checks for one active card. With no
  project-active card, create the approved card and activate it immediately as
  today.
  With an active card, persist the task/task note in `queued` order and create
  no dashboard request yet.
- The same lock/transaction path must prevent two concurrent routings from both
  becoming active. Stable order is card creation time then task id.

After all required evidence is current, `stage complete` is the authoritative
promotion hook:

1. Move the completed card to `done` and persist completion as today.
2. Select the oldest queued card.
3. Move it atomically to `spec`.
4. Create exactly one existing-format dashboard request for that card and its
   task id. A new request must not be created for any other queued card.
5. Persist an idempotent promotion record naming the completed card, promoted
   card, request id and timestamp.
6. If nothing is queued, record an idle transition and create no synthetic
   request.

Do not promote from a Git hook. One card has many commits and no pre-commit,
commit-msg or post-commit hook proves that all final gates passed. Keep
`stage complete` as the semantic boundary. It may run while the current agent
turn is still finishing. Promotion records the next active card immediately,
but the agent-runner remains responsible for delivering the new request only
when the agent is ready. An agent becoming ready never promotes a card and does
not make a project idle. Existing pending-request ordering and continuation
nudges remain the retry path if the agent forgets the delivered request.

Make the transition crash-safe and idempotent:

- Repeating `stage complete` creates no second promotion/request.
- A restart between board completion and request creation detects the recorded
  promotion and creates the missing request.
- A request already present is reused.
- A failed promotion leaves the next card durably queued, never partially in
  `spec` without a request.
- Concurrent queued cards cannot both become active.
- Existing pending requests and older single-active-card projects remain
  compatible.

The dashboard must expose `queued` ahead of the stage lanes for single-agent
projects and continue to identify the one active card. `stage` must ignore
queued cards when selecting the active card. `route_card` must not need a
second operator approval when a card is later promoted; routing already
recorded the operator's affirmative decision.

Test first-card immediate activation, queueing while busy, FIFO/task-id order,
concurrent routing, completion promotion, empty queue, duplicate completion,
restart repair, request reuse, no request for queued cards, runner delivery only
after readiness, active runner nudge/recovery, dashboard lane exposure and
active-card selection. Add a real isolated single-agent acceptance scenario
that queues two cards, completes one, and proves the next is prompted and
processed. Preserve unrelated shared-script refresh changes in this checkout.
Run all layer unit, property, acceptance, dashboard, architecture, CRAP/DRY,
mutation, documentation and upgrade-exercise gates before committing and
pushing.

## Verification boundary

This layer checkout has no property suite, architecture module, Gherkin
acceptance tree, documentation contract, mutation manifest, or application
mutation profile. Its supported gates are `bb test` (including the real
isolated lifecycle scenario, dashboard, and upgrade exercise) and `bb crap`.
The family DRY tool and mutation scan are used on the changed Clojure test and
Babashka board module; they do not replace a configured project mutation gate.
