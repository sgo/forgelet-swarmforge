Feature: Add a bounded clarification excerpt to automation state

  Scenario: Expose short legacy and typed questions
    Given legacy and typed short clarification questions
    When the automation projection is read
    Then both rows carry the same bounded question excerpt field
    And each excerpt preserves the short question
    And identities and lifecycle fields remain unchanged

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
    Given more legacy and typed clarifications than the projection row limits
    When the automation projection is read
    Then both clarification sections remain within their row limits
    And every excerpt remains within its configured limit
    And the response remains within its response-size bound

  Scenario: Preserve automation and full-state compatibility
    Given a valid automation clarification excerpt
    When automation and full dashboard state are read
    Then answers and references remain absent from automation state
    And cache, ETag and full state behaviour remain unchanged
