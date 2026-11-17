Feature: Add a bounded clarification excerpt to automation state

  Scenario: Expose short typed questions
    Given a typed short clarification question
    When the automation projection is read
    Then the row carries a bounded question excerpt field
    And the excerpt preserves the short question
    And identities and lifecycle fields remain unchanged
    And the legacy compatibility section remains empty

  Scenario: Bound and sanitize a long question
    Given a clarification question containing controls, paths, credentials and long text
    When the automation projection is read
    Then the excerpt is flattened and redacted
    And it is limited to 500 code points including a truncation marker
    And the original full question remains in the project dashboard state

  Scenario: Fail closed on empty or malformed questions
    Given a blank or malformed clarification body
    When the automation projection is read
    Then its question excerpt is null
    And no text is invented

  Scenario: Keep automation clarifications bounded
    Given more typed clarifications than the projection row limit
    When the automation projection is read
    Then the typed clarification section remains within its row limit
    And the legacy compatibility section remains empty
    And every excerpt remains within its configured limit
    And the response remains within its response-size bound

  Scenario: Preserve automation and full-state compatibility
    Given a valid automation clarification excerpt
    When automation and full dashboard state are read
    Then answers and references remain absent from automation state
    And cache, ETag and full state behaviour remain unchanged
