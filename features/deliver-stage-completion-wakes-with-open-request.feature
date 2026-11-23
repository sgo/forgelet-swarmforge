Feature: Deliver stage completion wakes despite an open request

  Scenario: Park the request that launched a detached stage
    Given a delivered operator request is associated with a healthy stage job
    When the runner selects the next request
    Then the originating request is parked without being answered or deleted
    And the runner can select the stage completion wake
    And no generic continuation nudge is sent

  Scenario: Deliver one administrative completion wake
    Given a terminal stage queued one completion request
    And the originating request remains parked
    When an idle agent becomes available
    Then the completion request is submitted exactly once
    And it directs the final answer to the originating request
    And the administrative wake is retired only after submission

  Scenario: Reconcile status without substituting for delivery
    Given a stage job is completed but its wake is still pending
    When bounded stage status readers inspect the job repeatedly
    Then the completion request remains pending
    And the originating request remains parked

  Scenario: Complete the original conversation once
    Given the completion wake has reached the agent
    When the stage result is reconciled and the original request is answered
    Then the original conversation receives exactly one final response
    And repeated runner ticks do not create another wake or answer

  Scenario: Fail closed instead of parking indefinitely
    Given a parked request references an unknown stage process
    When the runner reconciles its lifecycle
    Then the request leaves the parked state
    And stale or missing work remains visibly blocked

  Scenario: Preserve ordinary request ordering
    Given two ordinary operator requests are pending
    When no request is parked for a stage
    Then the older request remains selected first
