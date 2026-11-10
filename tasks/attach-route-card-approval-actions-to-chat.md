# attach-route-card-approval-actions-to-chat

# attach-route-card-approval-actions-to-chat

# Attach route-card approval actions to chat

## Operator-observed need

Lieutenant answers currently ask the operator to type `approve`. Parsing that
prose in the app would rely on every agent formatting a phrase correctly. The
operator wants a mechanical Approve button that invokes the same approval as the
Proposals panel without sending an `approve` chat message or requiring the
Lieutenant to execute the approval.

## Desired behaviour

- Produce an authoritative route-card approval descriptor from the proposal
  record: proposal id, Family/project ownership, task name and a bounded display
  label.
- Allow `pack_dashboard_request.sh answer` to attach a validated
  `approve-route-card` action to the completed request.
- Reject unknown, consumed, malformed or cross-Family proposals.
- Preserve `thread_id`, `source`, `proposal_id` and the action when moving a
  request from pending to done.
- Expose the action in dashboard chat facts as structured data, never as a
  command, filesystem path, credential or instruction for an agent to execute.
- Keep the human-readable response usable without the action.
- Repeated identical answer/action processing must be idempotent.

## Testing

- Create a route proposal, answer a metadata-bearing chat request with its
  approval action, and prove `/api/state` returns the completed turn and action.
- Reopen and reconnect to reconstruct the same action.
- Reject wrong Family, missing, consumed and malformed proposal ids.
- Prove an already-approved proposal reports its authoritative consumed state
  rather than offering another approval.
- Preserve ordinary chat, report actions and metadata-free requests.
- Run the complete layer suite.

## Dependency

This card depends on `preserve-chat-metadata-when-answering`
(`proposal-20261110T073405479857Z`) so the done request keeps the thread and
source needed to project the turn.

## Boundary

Work in `projects/forgelet-swarmforge` on the `forgelet` branch. Do not approve
proposals from the dashboard answer tool and do not execute route-card actions;
only record validated action metadata for the host/app to invoke later.
