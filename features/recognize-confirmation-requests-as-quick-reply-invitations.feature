Feature: Recognize confirmation requests as quick-reply invitations

  Scenario: Challenge a natural confirmation request
    Given an answer contains the phrase tell me, including tell me when
    When the answer command supplies neither quick-reply metadata nor the literal override
    Then the pending answer is refused
    And the diagnostic names --reply-with and --without-quick-reply
    And no reply text is inferred from the prose

  Scenario: Detect the phrase without interpreting arbitrary conversation
    Given an answer contains tell me in different letter cases
    When the answer command has no quick-reply choice
    Then the missing-metadata challenge is raised
    But an unrelated sentence containing tell without me is not challenged

  Scenario: Record an exact confirmation action
    Given an answer contains tell me
    When the answer command supplies a chosen exact phrase with --reply-with
    Then the done record stores that exact reply action
    And dashboard state exposes its pending availability

  Scenario: Preserve literal prose
    Given an answer contains tell me for prose rather than an action
    When the answer command supplies --without-quick-reply
    Then the answer completes without an action

  Scenario: Preserve existing action safeguards
    Given tell-me wording and unsafe or ambiguous quick-reply metadata
    When the answer is attempted
    Then validation and route-card mutual exclusion remain enforced
    And the pending request is not consumed
