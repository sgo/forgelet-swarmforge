Feature: Recognize the current Claude folder-trust prompt

  Scenario: Accept the current Claude trust prompt
    Given Claude shows the quick safety-check question
    And it shows the exact action Yes, I trust this folder
    When the project launcher settles the agent session
    Then it selects the visible affirmative choice
    And it continues waiting for the normal Claude ready marker

  Scenario: Retain the legacy trust prompt
    Given Claude shows the older Trust this folder? prompt
    When the project launcher settles the session
    Then it uses the same known affirmative selection

  Scenario: Leave unrelated prompts actionable
    Given Claude shows an unrelated safety or selection prompt
    When the project launcher observes it
    Then it sends no guessed keystrokes
    And the session fails with the visible prompt retained in diagnostics

  Scenario: Accept the disposable live project
    Given only the disposable saibill-detached-smoke project is restarted
    When Claude reaches its normal prompt
    Then a one-or-two-second detached stage reports waiting-for-job
    And it completes and wakes exactly once
    And the project returns to idle without promoting another card
