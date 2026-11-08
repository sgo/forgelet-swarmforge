# expose-chat-thread-id-in-dashboard-facts

# Expose chat thread IDs in dashboard facts

## Operator-observed need

Completed-card reports must open a durable Lieutenant conversation on the chat
panel and allow follow-up messages. Dashboard chat facts currently identify
each request only by its own id, so the app cannot reconstruct all turns in one
conversation.

## Desired behaviour

- Dashboard request files support an optional `thread_id` header.
- `/api/state` chat entries expose that value as `thread_id`.
- Proposal discussions may remain proposal-linked while also belonging to a
  thread if the host supplies one.
- Ordinary chat requests without a thread id remain valid.
- The shared app/phone reader exposes the optional thread id on `Chat`.
- Thread ids are opaque safe identifiers; malformed or missing values cannot
  merge unrelated conversations.

## Testing

- State reading includes requests with and without `thread_id`.
- Multiple request files sharing one thread id are returned individually with
  their original status, body, response and creation time.
- Malformed thread ids are omitted or rejected without dropping the chat turn.
- Host proxy and both shared readers preserve the value unchanged.
- Run the layer and host gates, publish the layer, and compose it into Forgelet.

## Out of scope

Creating chats, ordering them into threads, UI rendering or host report APIs.
