Feature: Replace live TUI agents safely on role restart

  Scenario: Recognize a live child behind a shell-like pane
    Given a role pane reports a shell while a live child process exists
    When the role's process tree is observed
    Then the role is treated as live rather than a dead agent

  Scenario: Replace one stable live role
    Given one idle role has a stable live process tree
    When that role is explicitly restarted
    Then only its pane process tree is replaced
    And the replacement is launched through the credential loader
    And the old target process is gone
    And the replacement has a new process identity in the same pane

  Scenario: Recover a dead shell
    Given a role session has a shell with no live child
    When dead-role recovery or an explicit restart runs
    Then the role is launched through the credential loader
    And a live role is not replaced by dead-role recovery

  Scenario: Fail closed on ambiguous ownership
    Given a role session contains more than one pane
    When restart is requested
    Then the restart is refused before modifying the session

  Scenario: Preserve unrelated runtimes
    Given another role, a project runtime and an unrelated tmux session
    When one role is restarted
    Then every unrelated session and process identity remains unchanged

  Scenario: Preserve credential boundaries
    Given a non-package forge with a valid credential hook
    When a role is restarted
    Then the replacement has GH_TOKEN present
    And GITHUB_PACKAGES_TOKEN is absent
    And no credential value is printed

  Scenario: Retain bounded failures
    Given a missing session, unknown role, malformed prompt or invalid credential hook
    When restart is requested
    Then the command fails visibly without starting a second agent
