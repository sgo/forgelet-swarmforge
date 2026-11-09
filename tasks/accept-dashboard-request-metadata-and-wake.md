# accept-dashboard-request-metadata-and-wake

# Accept dashboard request metadata and wake atomically

## Operator-observed problem

The app's `Report completed cards` action creates a pending request under the
forge root, but it does not reach the Lieutenant promptly. The host currently
writes `.swarmforge/dashboard/requests/pending/<id>.request` directly so it can
preserve a deterministic report request id and `thread_id`. That bypasses the
dashboard's `POST /api/chat` path, which is what writes and wakes the master
role as one operation. Delivery falls back to a later doorbell pass.

## Desired contract

Extend authenticated dashboard request creation, without breaking plain chat:

- accept an optional validated `request_id`;
- accept an optional validated `thread_id`;
- accept optional bounded `source` metadata such as
  `app.completed-card-report`;
- write the pending request and perform the normal master-role wake atomically;
- return the request id so callers do not have to search the queue;
- preserve existing behavior when the optional fields are absent.

Retries with the same request id and identical content must return the existing
request without writing or waking twice. Reusing an id with different content
must be refused. Malformed identifiers, unsafe source values, duplicate
conflicts and wake failure must be reported without exposing filesystem paths.

The `/api/state` chat facts must expose the safe source value and existing
`thread_id` so the bridge can label app-originated requests distinctly.

## Testing

Cover plain chat compatibility, supplied id/thread/source persistence,
atomic wake, idempotent retry, conflicting duplicate refusal, malformed values,
wake failure, state serialization and the existing omission path.

## Out of scope

Changing the host's completed-card report contents or the bridge's operator
allowlist behavior.
