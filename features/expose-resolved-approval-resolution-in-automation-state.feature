Feature: Expose resolved approval endings in automation state

  Scenario: Preserve approved and sent-back marks
    Given resolved approvals ending as approved and sent_back
    When the automation projection is read
    Then each resolved row carries its exact authoritative resolution
    And a fixture bridge can select the matching Matrix mark without prose inference

  Scenario: Keep pending approvals unresolved
    Given a pending approval and resolved approvals
    When the automation projection is read
    Then the pending row has no resolution field
    And resolved identity, project tagging and ordering are preserved

  Scenario: Fail closed on an invalid resolved ending
    Given a resolved approval with a missing, non-string or unknown resolution
    When the automation projection is read
    Then the response fails closed with the ordinary bounded projection error
    And no misleading resolution reaches the bridge

  Scenario: Preserve bounded automation behaviour
    Given more resolved approvals than the automation collection limit
    When the automation projection is read
    Then its count and truncation facts remain accurate
    And only the bounded ordered rows are returned
    And cache, ETag, invalidation and full state behaviour remain compatible
