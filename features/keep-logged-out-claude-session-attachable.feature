Feature: Keep logged-out Claude session attachable

  Scenario: Preserve the login terminal
    Given Claude shows the exact Not logged in · run /login banner
    When readiness settlement observes it
    Then it returns a distinct login-required state
    And the tmux session remains running and attachable
    And it sends no login keystrokes or credentials

  Scenario: Hold project work until authentication
    Given a logged-out Claude session has a pending work request
    When the relay runs
    Then the request is not delivered
    And a credential-free login diagnostic remains visible

  Scenario: Resume after authentication
    Given the same pane later shows an authenticated Claude composer
    When readiness settlement runs again
    Then the session becomes ready
    And the login-required marker is cleared

  Scenario: Retain generic failure behavior
    Given an unrelated prompt or generic readiness timeout
    When settlement fails
    Then the existing failed-session behavior remains
    And arbitrary sessions are not kept alive

  Scenario: Accept through the disposable project
    Given only saibill-detached-smoke is attached
    When the operator authenticates in the same Claude terminal
    Then one one-or-two-second detached stage completes
    And its wake is delivered exactly once
    And the project returns to idle without promoting another card
