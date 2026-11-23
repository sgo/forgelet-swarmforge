# deliver-stage-completion-wakes-with-open-request

# Deliver stage completion wakes despite an open request

## Operator-observed need

A project agent launched detached code job
`stage-job-code-1795448586119-dd9b3ee3` from a delivered project-chat request
and ended its turn without running the request's answer command. The stage
completed successfully and queued exactly one completion request.

The completion request never reached the idle agent because the older
project-chat request remained `pending`. The agent-runner selects the oldest
pending request and continued monitoring it. Requiring the agent to remember an
administrative answer before yielding is not a reliable handoff protocol.

## Desired outcome

A healthy detached stage parks its initiating request without closing or
discarding its operator conversation. Parking releases the runner's delivery
slot so the one stage-completion wake can reach the idle agent. The awakened
agent reconciles the stage, finishes the remaining work and answers the
original request once with the final result.

No model-generated acknowledgement is required to make the completion wake
deliverable. Delivery and reconciliation remain idempotent.

## Plan

1. Define the request lifecycle distinction between delivered, parked awaiting
   a stage, active work and answered.
2. Use the canonical stage-job identity/process/heartbeat predicate already
   used by stage status and, after its sibling fix, continuation-nudge
   suppression. Do not create a second job-state parser.
3. When a delivered request has launched or is associated with a matching
   healthy detached stage job, move it to the parked state while retaining its
   request id, conversation and eventual final-answer path.
4. Permit the stage completion event to become deliverable as soon as the
   agent is idle, even while the initiating request is parked.
5. Keep the initiating request associated with the stage wake so the final
   answer updates the correct operator conversation rather than creating a
   disconnected response.
6. On stage success, failure, timeout or cancellation, deliver the terminal
   state through the existing one-wake mechanism.
7. If the wake is consumed by another bounded status reader, keep the separate
   wake request pending until the intended agent has received the terminal
   state; a status inspection must not silently substitute for agent delivery.
8. Use a bounded reminder or recovery path only as a safety net, not as the
   primary acknowledgement mechanism.
9. Preserve normal FIFO ordering for ordinary operator messages and existing
   clarification holds.

## Boundaries

- Do not close or delete the initiating project-chat request merely because the
  stage wake is delivered.
- Do not create a second completion wake or allow duplicate stage execution.
- Do not rely on the agent remembering a separate acknowledgement command.
- Do not let a stale or missing stage job park a request indefinitely; fail
  closed into blocked/unknown recovery.
- Do not use pane output alone as proof that a stage wake was delivered.
- Do not bypass the typed request or project-chat channels.
- Do not restart the active forge or unrelated projects during tests.

## Testing and evidence

- A request launches a bounded detached fake job and ends its turn without
  answering; the job completion reaches the idle agent exactly once.
- The initiating request remains associated and can be answered with the final
  result after reconciliation.
- Repeated runner ticks, status reads and completion events do not duplicate
  the wake or the final answer.
- A lost/disconnected agent queues the completion and delivers it after the
  session returns.
- Stale heartbeat, missing process, invalid job identity, failure, timeout and
  cancellation do not park indefinitely and produce the correct terminal wake.
- Two ordinary operator requests retain FIFO behavior when no stage is active.
- An open clarification still suppresses unrelated continuation nudges.
- No generic continuation nudge is sent while the stage is healthy.

## Verification

The runner demonstrably accepts the normal “launch detached stage and yield”
workflow without requiring an acknowledgement answer. Exactly one completion
wake reaches the intended idle agent, the final result is posted to the
original operator request, and no duplicate wake, duplicate answer or
indefinite parked state remains.

## Operator acceptance

None.
