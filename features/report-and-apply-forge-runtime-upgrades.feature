Feature: Report and apply forge runtime upgrades

  Scenario: Report current and behind runtime identities
    Given a forge has a generated composition manifest
    When its runtime status is checked against the published layer
    Then the installed and available digests and source commits are reported
    And the status distinguishes current from behind

  Scenario: Exclude accepted local differences
    Given a forge changes its local Lieutenant additions, config or forge-owned skill
    When runtime status is checked
    Then those local files do not change the runtime digest

  Scenario: Fail closed on unknown identity
    Given the installed manifest is missing or malformed
    When runtime status is checked
    Then the status is unknown
    And the command exits nonzero with a bounded reason

  Scenario: Report idle state and planned upgrades
    Given open projects with active and inactive board cards
    When a behind runtime is checked
    Then idle is false with bounded project and card blockers
    And changes and required project-template upgrades are bounded

  Scenario: Refuse busy or changed-idle-state applies
    Given a forge is busy
    Or it becomes busy after a previous idle status
    When apply rechecks under the forge lock
    Then it exits with preflight failure before stopping or composing

  Scenario: Apply an idle family upgrade
    Given a behind disposable forge with two open idle projects and local state
    When apply composes the published layer
    Then only that forge runtime is replaced
    And supported project upgrades run
    And the original open projects are reopened
    And health and the expected installed digest are verified
    And boards, requests, worktrees, credentials and untracked files survive
    And Tailscale Serve is untouched

  Scenario: Recover from a partial failure
    Given an apply fails during composition or a later mutation phase
    When failure is reported
    Then the failed phase is named
    And normal open commands recover the original project set where possible
    And a later retry can complete the upgrade
