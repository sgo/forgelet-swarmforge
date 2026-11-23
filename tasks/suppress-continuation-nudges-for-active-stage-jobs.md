# suppress-continuation-nudges-for-active-stage-jobs

# Suppress continuation nudges for active stage jobs

## Operator-observed need

During the real `release-runtime-upgrade-status-lock` code gate, the detached
stage job was running and its heartbeat was advancing. At the runner's
five-minute idle threshold it still sent a continuation nudge because the
continuation path checks pane activity and clarification state but does not
consult the active stage job.

This contradicts the completed detached-stage contract: a valid heartbeat must
suppress stall nudges, while a stale heartbeat or missing process must become
blocked or unknown rather than idle.

## Desired outcome

The actual agent-runner continuation path consults canonical stage-job state
before sending a nudge. While the current project has a matching active job
with a valid process identity and fresh heartbeat, it sends no continuation
nudge. The stage completion wake remains the only normal wake.

A stale heartbeat or missing process must not be misreported as idle and must
not trigger the generic "continue the card" request. It must follow the
existing bounded failed, blocked or unknown recovery path.

## Plan

1. Identify the canonical stage-job status/process-identity/heartbeat predicate
   already used by stage status, dashboard activity and Family idleness.
2. Make `agent-runner.bb` consume that shared predicate instead of duplicating
   job parsing or using pane activity as a proxy.
3. Before a continuation nudge, require the delivered request to have no
   matching active job for the same project and card.
4. Preserve clarification holds and existing cooldown behavior.
5. When a matching job's process identity is valid and its heartbeat is fresh,
   record/print the hold and leave the completion wake responsible for the next
   turn.
6. When the heartbeat is stale, the process is missing, or the job state is
   invalid, fail closed into the existing blocked/recovery reporting and do not
   send an ordinary continuation nudge.
7. Add a bounded integration test through the real runner/nudge path rather
   than testing only the stage-status projection.

## Boundaries

- Do not make agent-runner own a second supervisor or heartbeat producer.
- Do not treat a live PID without matching process-start identity as active.
- Do not suppress indefinitely after a stale or missing process.
- Do not replace or duplicate the one official stage completion wake.
- Do not modify running projects or restart the active forge during tests.
- Do not regress clarification holds, delivery retries or ordinary idle
  continuation behavior when no stage job is active.
- Keep diagnostics free of credentials and unrelated project details.

## Testing and evidence

- A running fake stage job with a fresh heartbeat suppresses the continuation
  nudge at and after the old idle threshold.
- The same project becomes eligible for normal behavior after the completion
  wake is consumed and no active job remains.
- A stale heartbeat does not suppress as healthy: it reports blocked/unknown
  and does not send the generic continuation text.
- A missing or reused process identity follows the same fail-closed path.
- A valid active job for a different project or card does not suppress the
  selected request.
- An open clarification still suppresses regardless of stage state.
- Existing agent-runner, stage, dashboard and board lifecycle tests remain
  green; the integration fixture uses injected milliseconds or seconds rather
  than production-duration waits.

## Verification

The real nudge scheduler demonstrably sends no continuation during a healthy
detached stage, sends one completion wake when that stage terminates, and
reports stale/missing job state as blocked or unknown instead of idle. No
active project or forge runtime is restarted to prove the behavior.

The focused agent-runner suite passed 3 tests with 53 assertions, including
handler-level cases for a fresh matching job, elapsed idle thresholds, stale
heartbeat, missing and reused process identity, another card, completion
consumption, and an open clarification. These fixtures use injected
millisecond/second thresholds and make no production-duration waits.

The full `bb test` gate passed 521 tests with 4,181 assertions, zero failures
and zero errors, plus all 20 dashboard browser tests. The detached lifecycle
E2E completed in 1,595 ms. The `bb crap` clean gate passed 24 tests with 132
assertions, zero failures and zero errors.

## Operator acceptance

None.
