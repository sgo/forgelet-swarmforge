Feature: Emit structured stage guidance for MCP

  Scenario: Emit a stable success outcome
    Given a stage command succeeds
    When the shared helper completes it
    Then it emits one versioned prefixed JSON outcome
    And the outcome has status success and code STAGE_OK
    And existing human and JSON stdout remains usable

  Scenario: Emit an actionable refusal
    Given a stage request is invalid, blocked, stale or not approved
    When the helper refuses it
    Then it preserves the human diagnostic and exit code
    And it emits a specific stable code
    And the outcome includes a bounded reason, remediation and current scope

  Scenario: Distinguish detached job outcomes
    Given a stage job is active, orphaned, failed, timed out, cancelled, unknown or retry-restricted
    When the helper reports it
    Then each condition has its own stable code and corrective action
    And no failed or unknown job is treated as success

  Scenario: Keep the outcome safe
    Given a diagnostic contains private paths, token-like text or process output
    When the machine outcome is emitted
    Then paths and credentials are redacted
    And reason and remediation are bounded
    And malformed envelope data is rejected by the parser fixture

  Scenario: Consume outcomes without prose matching
    Given a transport-neutral parser reads the prefixed outcome
    When it validates the version and required fields
    Then it can decide the next corrective action from the code
    And it does not need to match arbitrary stderr prose

  Scenario: Preserve gate authority
    Given any structured refusal
    When an agent reads its remediation
    Then no gate, route approval, spec approval, evidence or retry requirement is weakened
