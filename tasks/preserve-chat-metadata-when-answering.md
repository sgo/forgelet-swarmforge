# preserve-chat-metadata-when-answering

# preserve-chat-metadata-when-answering

# Preserve conversation metadata when answering requests

## Operator-observed problem

The app sent its first general Lieutenant chat turn with:

```text
thread_id: conversation-0c9bf287692a6a6a82c3b3d251bbbd5c
source: app.lieutenant-chat
```

The Lieutenant answered through `pack_dashboard_request.sh`, and Element X
received the response. The app instead showed `conversation unavailable` and
`conversation was not found`.

The answer helper rewrites the pending request when moving it to done. Its
parser and renderer carry only `id`, `status`, `body`, `response`,
`created_at`, `updated_at` and `role`; they drop `thread_id`, `source` and
`proposal_id`. The host correctly excludes the resulting unlabelled record
from general and report conversations.

## Desired behaviour

- Preserve every accepted safe request metadata field when answering:
  `thread_id`, `source` and `proposal_id`.
- Reject or omit unsafe metadata exactly as the dashboard request reader does.
- Keep pending-to-done movement atomic and preserve the body and response
  escaping behavior.
- A completed general conversation remains listable and readable by its
  original conversation id.
- Completed-card reports retain their report thread after answers, and proposal
  discussions retain their proposal linkage.
- Existing metadata-free requests continue to work unchanged.

## Testing

- Submit a metadata-bearing request through the public `/api/chat` endpoint,
  answer it through the public request helper, then prove `/api/state`,
  conversation list and conversation read all retain the completed turn.
- Cover all three accepted metadata fields, multiline response/body escaping,
  idempotent re-answer behavior and metadata-free requests.
- Prove malformed or unsafe metadata never reaches the done record.
- Cover report-thread and proposal-discussion projection after answer.
- Run the complete layer suite.

## Boundary

Work in `projects/forgelet-swarmforge` on the `forgelet` branch. Do not change
host conversation filtering or add a second message store.
