Feature: Keep Lieutenant activity available through the launch wrapper

  Scenario: Classify a live Codex child behind a wrapper
    Given the Lieutenant pane reports its credential-wrapper shell
    And the pane process tree contains a live Codex descendant
    When Lieutenant activity is classified
    Then the pane text is read
    And Ready, Working and Unknown activity remain available

  Scenario: Classify a live Claude child behind a wrapper
    Given the Lieutenant pane reports its credential-wrapper shell
    And the pane process tree contains a live Claude descendant
    When Lieutenant activity is classified
    Then Claude markers classify normally

  Scenario: Keep a bare shell unavailable
    Given the Lieutenant pane is a shell with no live expected-agent descendant
    When Lieutenant activity is classified
    Then the state is unavailable
    And no pane contents are exposed

  Scenario: Fail bounded on malformed or exited children
    Given the expected process is exited or the process tree cannot be read
    When Lieutenant activity is classified
    Then the state is unavailable
    And the existing bounded activity contract remains unchanged

  Scenario: Share the process-tree rule with restart
    Given the same exact pane and process-tree observation
    When restart or Lieutenant activity evaluates it
    Then both use one shared shell-and-descendant decision rule

  Scenario: Preserve project activity
    Given project-agent activity, heat and status-line fixtures
    When Lieutenant classification changes
    Then project activity and existing redaction and bounds remain unchanged
