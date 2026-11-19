Feature: Teach app-chat agents safe Markdown

  Scenario: Discover one shared chat skill
    Given a forge composed from the shared layer
    When Lieutenants and project agents load available skills
    Then both discover the same operator-chat skill
    And the forge exposes its discoverable .agents/skills link

  Scenario: Use supported fenced examples
    Given an agent needs to show a bounded command, code, configuration, JSON or exact text
    When it writes an operator-facing chat reply
    Then it may use the supported safe fenced block for that payload
    And ordinary explanation remains outside the fence
    And raw HTML and file attachments are not alternate rendering paths

  Scenario: Keep replies bounded
    Given a complete file, log or transcript is available locally
    When the agent replies in app chat
    Then it summarizes the relevant evidence
    And it does not dump the complete material into chat
    And credentials and device-local secret values remain unexposed

  Scenario: Distinguish chat channels
    Given ordinary project chat, typed clarification, quick reply, approval and completed-card report surfaces
    When an agent chooses how to communicate
    Then it uses the structured surface that owns the decision
    And prose is never approval or execution authority

  Scenario: Add pointer-only project guidance
    Given a new or upgraded single-agent project
    When managed guidance is installed
    Then AGENTS.md contains one short pointer to the shared operator-chat skill
    And project-authored instructions remain intact
    And the full skill body is not copied into project instructions

  Scenario: Preserve existing rendering and ownership
    Given desktop and phone SafeMarkdown fixtures
    And a forge with its own skills
    When composition and existing rendering tests run
    Then fenced-code rendering remains supported
    And forge-owned skills remain in place
