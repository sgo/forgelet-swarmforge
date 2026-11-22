Feature: Release the runtime-upgrade status lock on exit

  Scenario: Keep lock ownership aligned with status output
    Given a runtime status command acquires the forge operation lock
    When it emits its JSON result and exits
    Then the lock is released before process exit completes
    And the same lock can be acquired immediately afterward

  Scenario: Report overlap without retaining the lock
    Given one process holds the runtime operation lock
    When status runs concurrently
    Then it reports checking until the holder exits
    And it does not retain a lock after its own exit

  Scenario: Release the lock on error
    Given runtime status encounters an unavailable or malformed source
    When it emits the bounded error status and exits
    Then the operation lock remains available

  Scenario: Release the lock when status times out
    Given a status process is holding the lock while a bounded helper hangs
    When the status process is terminated
    Then the operating system releases the lock
    And a later status read does not report a stale lock

  Scenario: Preserve status JSON contracts
    Given any current, behind, checking, unavailable or unknown status path
    When the command emits JSON
    Then the existing status fields and meanings remain unchanged
    And no project or forge runtime is restarted by the test
