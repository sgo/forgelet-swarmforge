Feature: Adopt the Gherkin mutation Maven plugin in the Java template

  Scenario: Compose the explicit mutation gate
    Given the standard and library Java project templates
    When a single-agent Java project is composed
    Then its POM pins the Gherkin mutation Maven adapter
    And harden invokes PIT, acceptance preparation and gherkin-mutation:check explicitly
    And the structured mutation report is recorded as evidence
    And no ordinary lifecycle phase invokes Gherkin mutation

  Scenario: Remove the legacy global mutator
    Given a newly composed Java project
    When its build and agent guidance are inspected
    Then no generated instruction requires a global gherkin-mutator
    And no generated instruction requires run-acceptance-mutation.sh
    And the managed Java engineering rules name the Maven adapter

  Scenario: Run harden without the global mutator
    Given a generated project whose PATH has no gherkin-mutator
    And its acceptance runner is unprepared
    When harden runs
    Then PIT evidence is produced first
    And the acceptance runner is prepared second
    And gherkin-mutation:check runs last through the Maven plugin
    And a deliberate survivor fails the gate
    When the survivor is corrected
    Then a clean rerun produces the structured report
    And the approved feature bytes are unchanged

  Scenario: Preserve project-owned acceptance configuration
    Given an existing project has a project-specific acceptance runner
    And it has project-authored agent instructions
    When the project opts into the managed adapter
    Then its POM and runner override remain unchanged
    And its project-authored instructions remain intact

  Scenario: Distinguish migrated and unmigrated projects
    Given an existing Java project has not opted into the adapter
    When the shared project upgrade runs
    Then its legacy mutation stage and script remain available
    When the managed adapter marker and artifact are both present
    Then upgrade replaces only the harden mutation stage
    And removes the legacy acceptance mutation script
