Feature: Recognize current Claude ready and login state

  Scenario: Accept the authenticated Claude composer
    Given Claude shows its idle composer beginning with the prompt and Try
    And it does not show the logged-out banner
    When the launcher checks readiness
    Then the session is ready without sending keystrokes

  Scenario: Fail immediately when Claude is logged out
    Given Claude shows Not logged in · run /login
    When the launcher checks readiness
    Then it reports Claude is not logged in
    And it sends no credentials or login keystrokes
    And it does not wait for the generic readiness timeout

  Scenario: Preserve existing readiness forms
    Given Codex shows Ask Codex to do anything
    Or Claude shows Ask Claude to do anything
    When readiness is checked
    Then the existing ready behavior remains

  Scenario: Keep arbitrary composer-like text unsafe
    Given an unrelated prompt contains Try text without the Claude composer shape
    Or a menu or safety prompt is not the known trust prompt
    When the launcher observes it
    Then it sends no guessed keystrokes

  Scenario: Validate the disposable project login state
    Given only saibill-detached-smoke is launched
    When Claude is logged out
    Then the live check fails promptly with the bounded login diagnostic
    And no other project or active forge runtime is restarted
