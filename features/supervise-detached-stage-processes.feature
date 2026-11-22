Feature: Supervise detached stage processes to completion

  Scenario: Keep short stage commands in the foreground
    Given a stage command completes within the foreground path
    When the stage is checked
    Then the command runs in the invoking process
    And no detached job is created

  Scenario: Launch one durable long-running job
    Given a stage whose command outlives the invoking client
    When the stage is checked in detached mode
    Then check returns one stable stage job id immediately
    And the job records its process identity, log, command fingerprint, heartbeat, timeout and requested lane
    And the project reports waiting-for-job rather than idle

  Scenario: Reconcile after client disconnect
    Given the invoking client disconnects before the detached process exits
    When the supervisor writes its bounded exit record
    Then it posts one completion request for the bounded stage job id
    And a later status or check call validates evidence and source stamps
    And the stage completes exactly once without rerunning the command
    And the completion request remains queued when the agent is offline

  Scenario: Join terminal completion with turn yield
    Given the supervisor appends one durable terminal event after exit and evidence state
    And the launching client appends one durable yield-ready event as its final action
    When the coordinator joins those events in either order
    Then it revalidates job, process identity and project/card scope
    And it queues one wake without fixed polling
    When yield readiness is missing
    Then a bounded injected fallback still queues the terminal result

  Scenario: Report waiting through all lifecycle projections
    Given a detached stage job is running or awaiting reconciliation
    When project activity, board readiness and Family upgrade idleness are inspected
    Then activity reports Waiting for the job with elapsed time and progress
    And next-card promotion is suppressed
    And Family upgrade reports the project as non-idle
    And a fresh heartbeat suppresses a stall verdict

  Scenario: Fail closed on invalid detached state
    Given a job exits unsuccessfully, times out or is cancelled
    Or its log, exit record, evidence, source stamps or process identity are invalid
    When status or check reconciles it
    Then it remains failed or unknown without automatic retry
    And an explicit retry starts a new job
    And cancellation never targets an unrelated process

  Scenario: Keep detached acceptance short
    Given the lifecycle E2E uses injectable timing and a one- or two-second child
    When disconnect, heartbeat aging, timeout and delivery delay are simulated
    Then no fixed sleep scales with the production timeout
    And the complete added E2E finishes within two minutes
    And every child process is cleaned up on success, failure, timeout and cancellation
