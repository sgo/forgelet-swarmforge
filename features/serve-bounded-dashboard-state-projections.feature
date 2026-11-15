Feature: Serve bounded reusable dashboard state projections

  Scenario: Reuse a fresh automation projection
    Given a prepared dashboard root with a card
    When the automation projection is read twice inside the freshness window
    Then the second read is a cache hit
    And both reads contain the same bounded automation fields
    And no chat body, pane status or workspace path is exposed

  Scenario: Coalesce concurrent snapshot builds
    Given one authoritative snapshot build is slow
    When two callers miss in the same freshness window
    Then only one authoritative build starts
    And the waiting caller receives the coalesced result

  Scenario: Invalidate before returning a dashboard write
    Given a warm automation projection
    When a dashboard task write succeeds
    Then that root is invalidated before success is returned
    And the next projection contains the new card

  Scenario: Refresh direct workspace changes within the bound
    Given a warm automation projection
    When a workspace file changes without a dashboard request
    Then the old value may be reused only inside the 750 millisecond bound
    And the next read after the bound includes the external change

  Scenario: Fail closed when an authoritative refresh fails
    Given a warm automation projection
    When authoritative files become malformed after expiry
    Then the dashboard returns a bounded failure instead of stale success
    And a repaired root builds a fresh successful projection

  Scenario: Keep Family roots isolated
    Given two warm Family roots
    When one Family project changes through the dashboard
    Then that Family rebuilds
    And the other Family remains a cache hit

  Scenario: Bound the Saibill-scale projection
    Given the large synthetic Family fixture
    When the automation projection is built and reused repeatedly
    Then the projection remains size and time bounded
    And repeated reads perform no additional authoritative builds
    And the full state response remains compatible
