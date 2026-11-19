Feature: Validate cross-forge issue drafts before filing

  Scenario: Render the canonical request template
    Given six structured request sections
    When the request renderer writes the issue body
    Then exactly one level-three heading appears for each required section
    And the six headings are in canonical order
    And section content is preserved

  Scenario: Refuse malformed request structure
    Given a request body has wrong heading depth, missing, duplicate, empty or out-of-order sections
    When pre-filing validation runs
    Then the body is refused with a bounded diagnostic

  Scenario: Ignore fenced heading examples
    Given a section contains fenced Markdown with heading-like lines
    When the request body is validated
    Then those fenced lines do not count as request headings
    And ordinary Markdown inside the section remains valid

  Scenario: Share the receiver contract
    Given a rendered request body
    When the receiving intake validator reads it
    Then intake accepts the same six-heading structure
    And a changed heading depth is refused by both directions

  Scenario: Validate a fetched issue
    Given an approved request was filed with a known title and body
    When the same helper fetches and validates the issue
    Then the fetched title and body are accepted when unchanged
    And a later heading-depth change is detected and refused

  Scenario: Keep requests free of authority
    Given any rendered or validated issue text
    When the validation succeeds
    Then no card, approval, composition or execution action is created
    And receiving intake still requires the local operator's approval
