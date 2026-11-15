Feature: Add structured quick-reply actions

  Scenario: Require explicit quick-reply metadata
    Given an answer asks the operator to reply with an exact phrase
    When the answer command has no quick-reply choice
    Then the pending answer is refused
    And the diagnostic names --reply-with and --without-quick-reply

  Scenario: Record one exact quick reply
    Given an answer asks the operator to reply with an exact phrase
    When the answer command supplies that phrase with --reply-with
    Then the done record stores one reply action
    And dashboard state exposes the exact text, label and pending availability

  Scenario: Preserve literal prose
    Given reply-like wording is present as literal prose
    When the answer command supplies --without-quick-reply
    Then the answer completes without an action

  Scenario: Reject unsafe or ambiguous action metadata
    Given quick-reply metadata is blank, malformed, oversized or unsafe
    Or route-card approval and quick reply are both requested
    When the answer is attempted
    Then the answer is refused without consuming its pending request

  Scenario: Backfill and retry one completed quick reply
    Given an identical completed text-only answer
    When one quick reply is backfilled and retried
    Then timestamps and response text stay fixed
    And conflicting actions remain refused
