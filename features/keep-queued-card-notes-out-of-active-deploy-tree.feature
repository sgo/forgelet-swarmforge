Feature: Keep queued card notes out of the active deploy tree

  Scenario: Route queued cards without tracked notes
    Given one active card and multiple queued cards
    When the queued cards are routed
    Then only the active card has a task document
    And every queued board body remains preserved
    And the active card's committed tree can complete cleanly

  Scenario: Materialize exactly one promoted note
    Given a queued card whose note exists only in board state
    When the preceding card completes
    Then the queued note is atomically materialized
    And a repeated promotion validates it without rewriting

  Scenario: Fail closed on unsafe queued state
    Given a queued note is missing, malformed or conflicts with its document
    When promotion is attempted
    Then the card remains queued
    And the source board note remains recoverable

  Scenario: Preserve note content on queue removal
    Given a queued card has only a board body or an exact legacy document
    When the queued row is removed
    Then the board body remains recoverable
    And no tracked orphan remains

  Scenario: Keep genuine dirty state deploy-blocking
    Given a prepared card with arbitrary untracked, modified, staged or missing state
    When completion is attempted
    Then completion is refused
