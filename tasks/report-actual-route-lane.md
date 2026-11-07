# report-actual-route-lane

# Report the lane actually assigned when routing a card

`route_card.sh commit` currently prints the proposal's pre-routing lane. In a
single-agent project with an active card, a second approved card is correctly
created in `queued`, but the command prints `CREATED ... in lane spec`. The board
and dashboard request state are correct; only the reported result and audit
metadata are misleading.

After `pack-web/create-task!` creates the task, read that task's row by task id
from the project's board and use its actual lane in the success message. Save
that value as `created-lane` in the consumed proposal record. Preserve the
existing `lane` field as the lane selected when the proposal was made, because
it records the decision context before queue placement.

Do not change card creation, queue ordering, active/queued selection, request
creation, promotion behavior or the operator approval gate. This is a reporting
correction in `swarmforge/scripts/route_card.bb` plus its tests and operator
documentation.

Cover:

- an idle single-agent project reports `spec`;
- routing behind an active card reports `queued`;
- the board row matches the reported lane;
- only the active card gets a dashboard request;
- recovery/promotion paths retain the genuinely assigned lane;
- pack-project routing remains unchanged;
- a malformed or missing board row fails the report precisely without creating
  a second card;
- consumed proposal metadata contains both the proposed lane and
  `created-lane`.

Run the shared layer's normal unit, dashboard, real isolated lifecycle,
architecture/documentation checks where available, cleanup and mutation gates.
Preserve unrelated uncommitted shared-script refresh changes. Publish the commit
to `origin/forgelet`; rollout to project-local copies happens through the normal
compose/refresh sequence.
