# supervise-detached-stage-processes

# Supervise detached stage processes to completion

## Operator-observed problem

`forgelet-host` spent more than eight hours in `harden` while adopting the
Gherkin mutation Maven plugin. The mutation report completed successfully at
03:27:38 with 44 features, 2,307/2,307 mutations killed and zero survivors or
errors. No build child process remained afterward, but the agent continued
checking a detached background operation with repeated sleeps for more than
five hours.

The stage state has no durable record that lets a later turn reconcile a
detached process exit, validate its evidence and complete the stage once.

## Desired behaviour

- Keep short stages in the existing foreground path.
- For a long-running stage command, assign a stable stage-attempt/job id and
  persist the process PID plus process-start identity, log path, command
  fingerprint, heartbeat timestamp, timeout and requested lane.
- Have the supervisor write a bounded exit record atomically when the process
  exits, even if the invoking agent turn or terminal stream disconnects.
- Launch the job in a durable supervisor that is independent of the current
  model turn and any local stdio MCP process. Return the job id immediately and
  instruct the agent to end its turn without polling.
- On process exit, validate the exit record and required evidence, then post one
  idempotent Forgelet project-chat/request wake: `Work complete for stage job
  <id>`. The existing request system queues the wake if the agent is unavailable.
- Add idempotent `stage check`/status reconciliation when the awakened agent
  returns. Validate the exit code, evidence and source stamps; record the stage
  result exactly once.
- A completed successful process with valid evidence must become visibly
  complete on the next status/check call without rerunning the command.
- A failed, timed-out or killed process remains failed and requires an explicit
  retry; a stale or reused PID must never be treated as the original process.
- Provide bounded heartbeat/progress and cancellation operations. Do not require
  agents to implement their own `sleep` polling loop.
- Preserve lane movement, stage freshness, operator acceptance, evidence and
  all existing foreground semantics.
- Add a first-class `waiting-for-job` work state. Ending the model turn must not
  make the agent/project appear idle-ready while the supervisor has active work.
- Dashboard/activity must report `Waiting for <job>` with elapsed time, latest
  progress/heartbeat and the affected card. The project and card remain in
  progress, no next card may be promoted, and Family upgrade idle checks must
  remain false.
- Keep `running`, `waiting-for-job`, `completed-unconsumed`, failed and idle
  distinct. The stage is not fresh until the completion wake is consumed and
  evidence recorded.
- A valid heartbeat suppresses stall nudges. A stale heartbeat or missing
  process becomes blocked/unknown, never idle.

## Testing

- Fixtures cover short foreground commands and detached successful, failed,
  timed-out and cancelled commands.
- Disconnect the invoking client before process exit; a later status/check
  reconciles the exit exactly once and records valid evidence without rerun.
- An immediate wake requests a follow-up turn exactly once and remains queued
  if the agent is offline.
- While the job runs, activity/project state reports waiting, next-card
  promotion is suppressed and upgrade idleness remains false. After the
  completion request is consumed and evidence recorded, the project can return
  to its normal idle/readiness behavior.
- Missing/malformed logs, missing evidence, changed source stamps and stale or
  reused PIDs fail closed.
- Repeated reconciliation and retries are idempotent; cancellation never
  targets an unrelated process.
- Existing stage, board, handoff, dashboard and lifecycle suites remain green.

## E2E

Run a disposable command that outlives a simulated client connection. Confirm
the stage exposes waiting activity, heartbeat/progress, writes an exit record,
posts one completion request, wakes a new turn, reconciles and completes once
while the client is disconnected.

The E2E uses an injected clock/timer and a one- or two-second disposable child.
It simulates disconnect, heartbeat aging, timeout and delivery delay without
waiting for production-scale durations. The complete added E2E procedure must
finish within two minutes and the normal automated suite must remain
materially below that. No fixed sleep may scale with the configured production
timeout. If a real detached process is used, it runs for only a few seconds and
is cancelled and cleaned up in every path.

## Boundary

Work in `projects/forgelet-swarmforge`. Do not auto-retry failed gates, weaken
evidence checks, treat a live PID alone as success, or restart active project
or forge runtimes during tests.

## Verification

The short disposable lifecycle E2E proves waiting activity, heartbeat/progress,
atomic exit, one queued completion wake, disconnected-client reconciliation and
source-stamp/evidence validation without a production-scale sleep. The
verification records the measured E2E duration and confirms that child
processes are cleaned up on success, failure, timeout and cancellation. The
measured disconnected-client E2E duration was 1.637 seconds; the added detached
fixtures completed together in under eight seconds.

## Operator acceptance

None.
