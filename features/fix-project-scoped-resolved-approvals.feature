Feature: Fix project-scoped resolved approvals

  Scenario: Serve historical superseded endings
    Given a live-shaped Family with a resolved approval ending as superseded
    When its automation projection is read
    Then the response succeeds with 200
    And the exact superseded ending is preserved

  Scenario: Tag project-level resolved approvals with their owner
    Given a project root with resolved approvals
    When its automation projection is read
    Then every resolved approval carries that root's project name

  Scenario: Keep unknown endings closed
    Given a resolved approval with an unknown resolution
    When the automation projection is read
    Then the response fails closed with 502

  Scenario: Preserve the existing automation contract
    Given pending approvals and bounded resolved approval collections
    When the automation projection is read
    Then pending approvals carry no resolution
    And ordering, bounds, cache, ETag, identity and response-size behavior remain unchanged
