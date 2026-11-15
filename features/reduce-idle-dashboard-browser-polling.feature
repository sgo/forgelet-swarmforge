Feature: Reduce idle dashboard browser polling

  Scenario: Suspend polling while hidden
    Given the dashboard document is hidden
    When reconciliation timer opportunities occur
    Then no state request is issued
    And the rendered state is retained

  Scenario: Reconcile once when visible again
    Given a hidden dashboard with retained state
    When visibility and focus events arrive together
    Then exactly one refresh starts
    And later events in the burst coalesce into it

  Scenario: Revalidate an unchanged visible dashboard
    Given a rendered dashboard with an ETag
    When the visible reconciliation read is unchanged
    Then the server returns 304
    And the current rendered state remains in place

  Scenario: Refresh changed state and mutations immediately
    Given the dashboard is visible
    When authoritative state changes or a dashboard mutation succeeds
    Then the dashboard refreshes without waiting for the fallback
    And the new state renders normally

  Scenario: Recover without discarding rendered state
    Given a rendered dashboard
    When a conditional read fails or returns malformed data
    Then the failure is explicit
    And the last rendered state remains visible
    When the connection returns
    Then one immediate refresh clears the failure
