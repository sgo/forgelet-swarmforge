Feature: Suppress continuation nudges for active stage jobs

  Scenario: Hold continuation while a matching stage job is healthy
    Given a delivered request for the active card
    And that card has a live stage job with a fresh heartbeat
    When the request reaches the old idle-nudge threshold
    Then the runner records a stage-job hold
    And it sends no generic continuation nudge

  Scenario: Use the official completion wake
    Given a healthy stage job is holding the delivered request
    When the job completion wake is consumed
    And no active job remains
    Then normal idle continuation behavior becomes eligible again

  Scenario: Fail closed for stale or missing process identity
    Given a delivered request matches a stage job
    But the job heartbeat is stale or its process identity is not valid
    When the runner evaluates continuation
    Then it reports a bounded blocked stage-job diagnostic
    And it sends no generic continuation nudge

  Scenario: Keep unrelated requests independent
    Given a healthy stage job belongs to another card
    When a delivered request for a different card becomes idle
    Then normal continuation behavior remains eligible
    And an open clarification still suppresses continuation
