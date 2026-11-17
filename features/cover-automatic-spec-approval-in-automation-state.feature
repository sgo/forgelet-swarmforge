Feature: Cover automatic approval endings in automation state

  Scenario: Accept every authoritative ending
    Given resolved approvals ending as approved and sent_back
    And resolved approvals ending as automatic_spec_approval and auto_approved
    And resolved approvals ending as superseded
    When the automation projection is read
    Then every exact resolution is preserved
    And only approved and sent_back select manual Matrix marks

  Scenario: Keep invalid endings closed
    Given a resolved approval with a missing, non-string or unknown resolution
    When the automation projection is read
    Then the response fails closed with the ordinary bounded projection error

  Scenario: Build a live-shaped automatic approval backlog
    Given a Family with 713 resolved approvals spanning all five authoritative endings
    When the automation projection is read
    Then the projection succeeds within its response-size budget
    And it reports 713 rows and returns only the 200-row collection bound
    And pending approvals still carry no resolution

  Scenario: Preserve bounded automation compatibility
    Given valid manual and automatic resolved approvals
    When automation and full dashboard states are read
    Then their resolution values and Family project identity agree
    And cache, ETag, invalidation and ordering behaviour remain unchanged
