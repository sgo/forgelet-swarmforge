Feature: Recognize say-quoted instructions as quick-reply invitations

  Scenario: Challenge a bounded say instruction
    Given an answer contains say followed by one bounded double-quoted phrase
    When the answer command supplies neither quick-reply metadata nor the literal override
    Then the pending answer is refused
    And the diagnostic names --reply-with and --without-quick-reply
    And the quoted phrase is not inferred or echoed

  Scenario: Record the explicitly chosen reply
    Given an answer contains a say-quoted route-like phrase
    When the answer command supplies the exact reply text with --reply-with
    Then one reply action is recorded
    And no route-card approval action is attached

  Scenario: Preserve literal prose
    Given a say-quoted phrase is literal prose
    When the answer command supplies --without-quick-reply
    Then the answer completes without an action

  Scenario: Treat malformed say forms as bounded text
    Given say text is unquoted, multiline or oversized
    When the answer is otherwise ordinary
    Then no quick-reply text or action is inferred
    And the response remains bounded

  Scenario: Preserve existing action safeguards
    Given say wording and unsafe or conflicting metadata
    When the answer is attempted
    Then existing reply validation and route-card exclusivity remain enforced
    And a quoted phrase never becomes execution authority
